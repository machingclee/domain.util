package com.machingclee.domain.util.common.command;

import com.machingclee.domain.util.common.audit.AfterTransactionRecord;
import com.machingclee.domain.util.common.audit.AuditConfiguration;
import com.machingclee.domain.util.common.interfaces.AuditEvent;
import com.machingclee.domain.util.common.interfaces.AuditEventRepository;
import com.machingclee.domain.util.common.interfaces.Command;
import com.machingclee.domain.util.common.interfaces.CommandHandler;
import com.machingclee.domain.util.common.interfaces.DomainEventDispatcher;
import com.machingclee.domain.util.common.interfaces.EventQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AbstractCommandInvokerTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void afterTransactionHandlerSeesFailureReasonAfterStamps() {
        RecordingRepo repo = new RecordingRepo();
        AuditConfiguration audit = new AuditConfiguration();
        AtomicReference<AfterTransactionRecord> seen = new AtomicReference<>();
        audit.addAfterTransactionHandler(seen::set);

        CustomCommandAuditor<SampleEvent> auditor = new CustomCommandAuditor<>(
                repo.proxy(), SampleEvent::new, audit, null);
        CustomCommandInvoker invoker = new CustomCommandInvoker(
                mock(ApplicationContext.class),
                dispatcherThatSavesEvent(repo, new IllegalStateException("boom")),
                new TrackingTransactionManager(),
                auditor,
                repo.proxy(),
                audit);

        CommandHandler<FailingCommand, Void> handler = (queue, command) -> {
            queue.add(new SampleDomainEvent("e"));
            return null;
        };

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> invoker.invoke(handler, new FailingCommand()));
        assertEquals("boom", thrown.getMessage());

        AfterTransactionRecord record = seen.get();
        assertFalse(record.isCommitted());
        assertEquals(2, record.getEvents().size());
        assertTrue(record.getEvents().stream().allMatch(e -> Boolean.FALSE.equals(e.getSuccess())));
        assertTrue(record.getEvents().stream().allMatch(e -> e.getFailureReason() != null
                && e.getFailureReason().contains("boom")));
    }

    @Test
    void afterTransactionHandlerSeesCommittedSnapshot() throws Exception {
        RecordingRepo repo = new RecordingRepo();
        AuditConfiguration audit = new AuditConfiguration();
        AtomicReference<AfterTransactionRecord> seen = new AtomicReference<>();
        audit.addAfterTransactionHandler(seen::set);

        CustomCommandAuditor<SampleEvent> auditor = new CustomCommandAuditor<>(
                repo.proxy(), SampleEvent::new, audit, null);
        CustomCommandInvoker invoker = new CustomCommandInvoker(
                mock(ApplicationContext.class),
                dispatcherThatSavesEvent(repo),
                new TrackingTransactionManager(),
                auditor,
                repo.proxy(),
                audit);

        CommandHandler<OkCommand, String> handler = (queue, command) -> {
            queue.add(new SampleDomainEvent("e"));
            return "ok";
        };

        assertEquals("ok", invoker.invoke(handler, new OkCommand()));

        AfterTransactionRecord record = seen.get();
        assertTrue(record.isCommitted());
        assertEquals(2, record.getEvents().size());
        assertTrue(record.getEvents().stream().allMatch(e -> Boolean.TRUE.equals(e.getSuccess())));
    }

    @Test
    void nestedInvokeDoesNotRunAfterTransactionHandler() throws Exception {
        RecordingRepo repo = new RecordingRepo();
        AuditConfiguration audit = new AuditConfiguration();
        AtomicInteger afterTx = new AtomicInteger();
        audit.addAfterTransactionHandler(record -> afterTx.incrementAndGet());

        CustomCommandAuditor<SampleEvent> auditor = new CustomCommandAuditor<>(
                repo.proxy(), SampleEvent::new, audit, null);
        CustomCommandInvoker invoker = new CustomCommandInvoker(
                mock(ApplicationContext.class),
                dispatcherThatSavesEvent(repo),
                new TrackingTransactionManager(),
                auditor,
                repo.proxy(),
                audit);

        CommandHandler<OkCommand, String> nested = (queue, command) -> "nested";
        CommandHandler<OkCommand, String> top = (queue, command) -> {
            try {
                return invoker.invoke(nested, new OkCommand());
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        };

        invoker.invoke(top, new OkCommand());

        assertEquals(1, afterTx.get());
    }

    private static DomainEventDispatcher dispatcherThatSavesEvent(RecordingRepo repo) {
        return dispatcherThatSavesEvent(repo, null);
    }

    private static DomainEventDispatcher dispatcherThatSavesEvent(RecordingRepo repo, RuntimeException fail) {
        return new DomainEventDispatcher() {
            @Override
            public void dispatchNow(EventQueue eventQueue, String requestId) {
            }

            @Override
            public void dispatch(EventQueue eventQueue, String requestId) {
                if (eventQueue.getImmediateEvents().isEmpty()) {
                    return;
                }
                SampleEvent event = new SampleEvent();
                event.requestId = requestId;
                event.eventType = "SampleDomainEvent";
                event.success = true;
                repo.save(event);
                if (fail != null) {
                    throw fail;
                }
            }
        };
    }

    static final class FailingCommand implements Command<Void> {
    }

    static final class OkCommand implements Command<String> {
    }

    record SampleDomainEvent(String value) {
    }

    static final class TrackingTransactionManager extends AbstractPlatformTransactionManager {
        @Override
        protected Object doGetTransaction() throws TransactionException {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) throws TransactionException {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
        }
    }

    static final class RecordingRepo {
        final List<SampleEvent> saved = new ArrayList<>();
        final AtomicInteger nextId = new AtomicInteger(1);

        void save(SampleEvent event) {
            if (event.id == null) {
                event.id = nextId.getAndIncrement();
            }
            saved.removeIf(existing -> existing.id.equals(event.id));
            saved.add(event);
        }

        @SuppressWarnings("unchecked")
        AuditEventRepository<SampleEvent> proxy() {
            return (AuditEventRepository<SampleEvent>) Proxy.newProxyInstance(
                    AuditEventRepository.class.getClassLoader(),
                    new Class<?>[]{AuditEventRepository.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "save" -> {
                            SampleEvent event = (SampleEvent) args[0];
                            save(event);
                            yield event;
                        }
                        case "findById" -> {
                            Integer id = (Integer) args[0];
                            yield saved.stream().filter(e -> id.equals(e.id)).findFirst();
                        }
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

    static class SampleEvent implements AuditEvent {
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
