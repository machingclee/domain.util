package com.machingclee.domain.util.common.audit;

import com.machingclee.domain.util.common.interfaces.AuditEvent;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditConfigurationTest {

    @Test
    void runsPreThenOriginalThenPost() throws Exception {
        List<String> order = new ArrayList<>();
        CommandAuditConfiguration config = new CommandAuditConfiguration();
        config.addPreAuditHandler(record -> order.add("pre"));
        config.bindOriginalAuditHandler(record -> order.add("original"));
        config.addPostAuditHandler(record -> order.add("post"));

        config.execute(record());

        assertEquals(List.of("pre", "original", "post"), order);
    }

    @Test
    void overrideSkipsOriginalUnlessCalled() throws Exception {
        AtomicBoolean originalRan = new AtomicBoolean();
        AtomicBoolean overrideRan = new AtomicBoolean();
        CommandAuditConfiguration config = new CommandAuditConfiguration();
        config.bindOriginalAuditHandler(record -> originalRan.set(true));
        config.overrideAuditHandler(record -> overrideRan.set(true));

        config.execute(record());

        assertTrue(overrideRan.get());
        assertFalse(originalRan.get());
    }

    @Test
    void overrideCanCallOriginal() throws Exception {
        List<String> order = new ArrayList<>();
        CommandAuditConfiguration config = new CommandAuditConfiguration();
        config.bindOriginalAuditHandler(record -> order.add("original"));
        config.overrideAuditHandler(record -> {
            order.add("override");
            config.getOriginalAuditHandler().handle(record);
        });

        config.execute(record());

        assertEquals(List.of("override", "original"), order);
    }

    @Test
    void originalHandlerRemainsValidAfterRebind() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CommandAuditConfiguration config = new CommandAuditConfiguration();
        AuditHandler<CommandAuditRecord> captured = config.getOriginalAuditHandler();
        config.bindOriginalAuditHandler(record -> calls.incrementAndGet());

        captured.handle(record());
        config.execute(record());

        assertEquals(2, calls.get());
        assertSame(captured, config.getOriginalAuditHandler());
    }

    @Test
    void throwingRequiresNewPostDoesNotFailExecuteOrPreventSave() throws Exception {
        AtomicBoolean saved = new AtomicBoolean();
        CommandAuditConfiguration config = new CommandAuditConfiguration();
        config.bindOriginalAuditHandler(record -> saved.set(true));
        config.addPostAuditHandler(record -> {
            throw new IllegalStateException("post failed");
        }, AuditTx.REQUIRES_NEW);

        config.execute(record());

        assertTrue(saved.get());
    }

    @Test
    void throwingJoinPrePreventsSave() {
        AtomicBoolean saved = new AtomicBoolean();
        CommandAuditConfiguration config = new CommandAuditConfiguration();
        config.addPreAuditHandler(record -> {
            throw new IllegalStateException("pre failed");
        }, AuditTx.JOIN);
        config.bindOriginalAuditHandler(record -> saved.set(true));

        assertThrows(IllegalStateException.class, () -> config.execute(record()));
        assertFalse(saved.get());
    }

    @Test
    void requiresNewPreFailureDoesNotPreventSave() throws Exception {
        AtomicBoolean saved = new AtomicBoolean();
        CommandAuditConfiguration config = new CommandAuditConfiguration();
        config.addPreAuditHandler(record -> {
            throw new IllegalStateException("isolated pre failed");
        }, AuditTx.REQUIRES_NEW);
        config.bindOriginalAuditHandler(record -> saved.set(true));

        config.execute(record());

        assertTrue(saved.get());
    }

    @Test
    void joinPostRunsAfterOriginalAndPropagates() {
        List<String> order = new ArrayList<>();
        CommandAuditConfiguration config = new CommandAuditConfiguration();
        config.bindOriginalAuditHandler(record -> order.add("original"));
        config.addPostAuditHandler(record -> {
            order.add("post");
            throw new IllegalStateException("join post failed");
        }, AuditTx.JOIN);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> config.execute(record()));
        assertEquals("join post failed", thrown.getMessage());
        assertEquals(List.of("original", "post"), order);
    }

    @Test
    void throwingJoinPostRollsBackPersistTransaction() {
        AtomicBoolean saved = new AtomicBoolean();
        TrackingTransactionManager tm = new TrackingTransactionManager();
        CommandAuditConfiguration config = new CommandAuditConfiguration();
        config.bindTransactionManager(tm);
        config.bindOriginalAuditHandler(record -> saved.set(true));
        config.addPostAuditHandler(record -> {
            throw new IllegalStateException("join post failed");
        }, AuditTx.JOIN);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> config.execute(record()));
        assertEquals("join post failed", thrown.getMessage());
        assertTrue(saved.get());
        assertEquals(1, tm.begins);
        assertEquals(0, tm.commits);
        assertEquals(1, tm.rollbacks);
    }

    @Test
    void joinPostRunsInPersistTxBeforeRequiresNewPost() throws Exception {
        List<String> order = new ArrayList<>();
        TrackingTransactionManager tm = new TrackingTransactionManager();
        CommandAuditConfiguration config = new CommandAuditConfiguration();
        config.bindTransactionManager(tm);
        config.bindOriginalAuditHandler(record -> order.add("original"));
        config.addPostAuditHandler(record -> order.add("join-post"), AuditTx.JOIN);
        config.addPostAuditHandler(record -> order.add("new-post"), AuditTx.REQUIRES_NEW);

        config.execute(record());

        assertEquals(List.of("original", "join-post", "new-post"), order);
        assertEquals(2, tm.begins);
        assertEquals(2, tm.commits);
        assertEquals(0, tm.rollbacks);
    }

    @Test
    void requiresNewPreRunsBeforeJoinPreAndOriginal() throws Exception {
        List<String> order = new ArrayList<>();
        CommandAuditConfiguration config = new CommandAuditConfiguration();
        config.addPreAuditHandler(record -> order.add("join-pre"), AuditTx.JOIN);
        config.addPreAuditHandler(record -> order.add("new-pre"), AuditTx.REQUIRES_NEW);
        config.bindOriginalAuditHandler(record -> order.add("original"));

        config.execute(record());

        assertEquals(List.of("new-pre", "join-pre", "original"), order);
    }

    private static CommandAuditRecord record() {
        return new CommandAuditRecord("cmd", "req-1", new StubEvent());
    }

    static final class TrackingTransactionManager extends AbstractPlatformTransactionManager {
        int begins;
        int commits;
        int rollbacks;

        @Override
        protected Object doGetTransaction() throws TransactionException {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
            begins++;
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
            commits++;
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
            rollbacks++;
        }
    }

    static class StubEvent implements AuditEvent {
        @Override
        public Integer getId() {
            return null;
        }

        @Override
        public Boolean getSuccess() {
            return false;
        }

        @Override
        public void setCreatedAt(Double createdAt) {
        }

        @Override
        public void setEventType(String eventType) {
        }

        @Override
        public void setPayload(String payload) {
        }

        @Override
        public void setRequestUserEmail(String requestUserEmail) {
        }

        @Override
        public void setRequestId(String requestId) {
        }

        @Override
        public void setSuccess(Boolean success) {
        }

        @Override
        public void setFailureReason(String failureReason) {
        }

        @Override
        public void setEventOrder(Integer order) {
        }
    }
}
