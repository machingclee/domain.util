package com.machingclee.domain.util.common.audit;

import com.machingclee.domain.util.common.interfaces.AuditEvent;
import com.machingclee.domain.util.common.interfaces.AuditEventRepository;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuditConfigurationTest {

    @Test
    void runsPreThenOriginalThenPostForCommand() throws Exception {
        List<String> order = new ArrayList<>();
        AuditConfiguration config = new AuditConfiguration();
        config.addPreCommandAuditHandler(record -> order.add("pre"), AuditTx.JOIN);
        config.bindOriginalCommandAuditHandler(record -> order.add("original"));
        config.addPostCommandAuditHandler(record -> order.add("post"), AuditTx.REQUIRES_NEW);

        config.executeCommand(commandRecord());

        assertEquals(List.of("pre", "original", "post"), order);
    }

    @Test
    void runsPreThenOriginalThenPostForEvent() throws Exception {
        List<String> order = new ArrayList<>();
        AuditConfiguration config = new AuditConfiguration();
        config.addPreEventAuditHandler(record -> order.add("pre"), AuditTx.JOIN);
        config.bindOriginalEventAuditHandler(record -> order.add("original"));
        config.addPostEventAuditHandler(record -> order.add("post"), AuditTx.REQUIRES_NEW);

        config.executeEvent(eventRecord());

        assertEquals(List.of("pre", "original", "post"), order);
    }

    @Test
    void overrideSkipsOriginalUnlessCalled() throws Exception {
        AtomicBoolean originalRan = new AtomicBoolean();
        AtomicBoolean overrideRan = new AtomicBoolean();
        AuditConfiguration config = new AuditConfiguration();
        config.bindOriginalCommandAuditHandler(record -> originalRan.set(true));
        config.overrideCommandAuditHandler(record -> overrideRan.set(true));

        config.executeCommand(commandRecord());

        assertTrue(overrideRan.get());
        assertFalse(originalRan.get());
    }

    @Test
    void overrideCanCallOriginal() throws Exception {
        List<String> order = new ArrayList<>();
        AuditConfiguration config = new AuditConfiguration();
        config.bindOriginalCommandAuditHandler(record -> order.add("original"));
        config.overrideCommandAuditHandler(record -> {
            order.add("override");
            config.getOriginalCommandAuditHandler().handle(record);
        });

        config.executeCommand(commandRecord());

        assertEquals(List.of("override", "original"), order);
    }

    @Test
    void originalHandlerRemainsValidAfterRebind() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AuditConfiguration config = new AuditConfiguration();
        AuditHandler<CommandAuditRecord> captured = config.getOriginalCommandAuditHandler();
        config.bindOriginalCommandAuditHandler(record -> calls.incrementAndGet());

        captured.handle(commandRecord());
        config.executeCommand(commandRecord());

        assertEquals(2, calls.get());
        assertSame(captured, config.getOriginalCommandAuditHandler());
    }

    @Test
    void throwingRequiresNewPostDoesNotFailExecuteOrPreventSave() throws Exception {
        AtomicBoolean saved = new AtomicBoolean();
        AuditConfiguration config = new AuditConfiguration();
        config.bindOriginalCommandAuditHandler(record -> saved.set(true));
        config.addPostCommandAuditHandler(record -> {
            throw new IllegalStateException("post failed");
        }, AuditTx.REQUIRES_NEW);

        config.executeCommand(commandRecord());

        assertTrue(saved.get());
    }

    @Test
    void throwingJoinPrePreventsSave() {
        AtomicBoolean saved = new AtomicBoolean();
        AuditConfiguration config = new AuditConfiguration();
        config.addPreCommandAuditHandler(record -> {
            throw new IllegalStateException("pre failed");
        }, AuditTx.JOIN);
        config.bindOriginalCommandAuditHandler(record -> saved.set(true));

        assertThrows(IllegalStateException.class, () -> config.executeCommand(commandRecord()));
        assertFalse(saved.get());
    }

    @Test
    void requiresNewPreFailureDoesNotPreventSave() throws Exception {
        AtomicBoolean saved = new AtomicBoolean();
        AuditConfiguration config = new AuditConfiguration();
        config.addPreCommandAuditHandler(record -> {
            throw new IllegalStateException("isolated pre failed");
        }, AuditTx.REQUIRES_NEW);
        config.bindOriginalCommandAuditHandler(record -> saved.set(true));

        config.executeCommand(commandRecord());

        assertTrue(saved.get());
    }

    @Test
    void joinPostRunsAfterOriginalAndPropagates() {
        List<String> order = new ArrayList<>();
        AuditConfiguration config = new AuditConfiguration();
        config.bindOriginalCommandAuditHandler(record -> order.add("original"));
        config.addPostCommandAuditHandler(record -> {
            order.add("post");
            throw new IllegalStateException("join post failed");
        }, AuditTx.JOIN);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> config.executeCommand(commandRecord()));
        assertEquals("join post failed", thrown.getMessage());
        assertEquals(List.of("original", "post"), order);
    }

    @Test
    void throwingJoinPostRollsBackPersistTransaction() {
        AtomicBoolean saved = new AtomicBoolean();
        TrackingTransactionManager tm = new TrackingTransactionManager();
        AuditConfiguration config = new AuditConfiguration();
        config.bindTransactionManager(tm);
        config.bindOriginalCommandAuditHandler(record -> saved.set(true));
        config.addPostCommandAuditHandler(record -> {
            throw new IllegalStateException("join post failed");
        }, AuditTx.JOIN);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> config.executeCommand(commandRecord()));
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
        AuditConfiguration config = new AuditConfiguration();
        config.bindTransactionManager(tm);
        config.bindOriginalCommandAuditHandler(record -> order.add("original"));
        config.addPostCommandAuditHandler(record -> order.add("join-post"), AuditTx.JOIN);
        config.addPostCommandAuditHandler(record -> order.add("new-post"), AuditTx.REQUIRES_NEW);

        config.executeCommand(commandRecord());

        assertEquals(List.of("original", "join-post", "new-post"), order);
        assertEquals(2, tm.begins);
        assertEquals(2, tm.commits);
        assertEquals(0, tm.rollbacks);
    }

    @Test
    void requiresNewPreRunsBeforeJoinPreAndOriginal() throws Exception {
        List<String> order = new ArrayList<>();
        AuditConfiguration config = new AuditConfiguration();
        config.addPreCommandAuditHandler(record -> order.add("join-pre"), AuditTx.JOIN);
        config.addPreCommandAuditHandler(record -> order.add("new-pre"), AuditTx.REQUIRES_NEW);
        config.bindOriginalCommandAuditHandler(record -> order.add("original"));

        config.executeCommand(commandRecord());

        assertEquals(List.of("new-pre", "join-pre", "original"), order);
    }

    @Test
    void afterTransactionHandlerReceivesRequestRows() {
        RecordingRepo repo = new RecordingRepo();
        StubEvent command = new StubEvent();
        command.id = 1;
        command.requestId = "req-1";
        command.eventType = "CreateCommand";
        command.success = false;
        command.failureReason = "boom";
        repo.saved.add(command);

        AtomicReference<AfterTransactionRecord> seen = new AtomicReference<>();
        AuditConfiguration config = new AuditConfiguration();
        config.addAfterTransactionHandler(seen::set);

        config.executeAfterTransaction("req-1", false, repo.proxy());

        AfterTransactionRecord record = seen.get();
        assertEquals("req-1", record.getRequestId());
        assertFalse(record.isCommitted());
        assertEquals(1, record.getEvents().size());
        assertEquals("boom", record.getEvents().get(0).getFailureReason());
        assertEquals("CreateCommand", record.getEvents().get(0).getEventType());
    }

    @Test
    void throwingAfterTransactionHandlerIsSwallowed() {
        RecordingRepo repo = new RecordingRepo();
        AuditConfiguration config = new AuditConfiguration();
        config.addAfterTransactionHandler(record -> {
            throw new IllegalStateException("callback failed");
        });

        config.executeAfterTransaction("req-1", true, repo.proxy());
    }

    @Test
    void afterTransactionIsNoOpWithoutHandlers() {
        AuditConfiguration config = new AuditConfiguration();
        config.executeAfterTransaction("req-1", true, null);
    }

    private static CommandAuditRecord commandRecord() {
        return new CommandAuditRecord("cmd", "req-1", new StubEvent());
    }

    private static EventAuditRecord eventRecord() {
        return new EventAuditRecord("evt", "req-1", new StubEvent());
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

    static final class RecordingRepo {
        final List<StubEvent> saved = new ArrayList<>();

        @SuppressWarnings("unchecked")
        AuditEventRepository<StubEvent> proxy() {
            return (AuditEventRepository<StubEvent>) Proxy.newProxyInstance(
                    AuditEventRepository.class.getClassLoader(),
                    new Class<?>[]{AuditEventRepository.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "findAllByRequestId" -> {
                            String requestId = (String) args[0];
                            yield saved.stream().filter(e -> requestId.equals(e.requestId)).toList();
                        }
                        case "toString" -> "recording-repo";
                        case "hashCode" -> System.identityHashCode(p);
                        case "equals" -> p == args[0];
                        default -> method.getReturnType() == Optional.class ? Optional.empty() : null;
                    });
        }
    }

    static class StubEvent implements AuditEvent {
        Integer id;
        Boolean success;
        String requestId;
        String eventType;
        String failureReason;

        @Override
        public Integer getId() {
            return id;
        }

        @Override
        public Boolean getSuccess() {
            return success;
        }

        @Override
        public String getRequestId() {
            return requestId;
        }

        @Override
        public String getEventType() {
            return eventType;
        }

        @Override
        public String getFailureReason() {
            return failureReason;
        }

        @Override
        public void setCreatedAt(Double createdAt) {
        }

        @Override
        public void setEventType(String eventType) {
            this.eventType = eventType;
        }

        @Override
        public void setPayload(String payload) {
        }

        @Override
        public void setRequestUserEmail(String requestUserEmail) {
        }

        @Override
        public void setRequestId(String requestId) {
            this.requestId = requestId;
        }

        @Override
        public void setSuccess(Boolean success) {
            this.success = success;
        }

        @Override
        public void setFailureReason(String failureReason) {
            this.failureReason = failureReason;
        }

        @Override
        public void setEventOrder(Integer order) {
        }
    }
}
