package com.machingclee.domain.util.common.command;

import com.machingclee.domain.util.common.audit.AuditConfiguration;
import com.machingclee.domain.util.common.audit.AuditTx;
import com.machingclee.domain.util.common.interfaces.AuditEvent;
import com.machingclee.domain.util.common.interfaces.AuditEventRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CustomCommandAuditorTest {

    @Test
    void defaultPipelineSavesCommandRow() throws Exception {
        RecordingRepo repo = new RecordingRepo();
        CustomCommandAuditor<SampleEvent> auditor = new CustomCommandAuditor<>(repo.proxy(), SampleEvent::new);

        SampleEvent saved = auditor.logCommandInTransaction(new SampleCommand("hello"), "req-1");

        assertEquals(1, repo.saved.size());
        assertEquals(saved, repo.saved.get(0));
        assertEquals("req-1", saved.requestId);
        assertEquals(Boolean.FALSE, saved.success);
        assertTrue(saved.payload.contains("hello"));
    }

    @Test
    void preAndPostRunAroundSave() throws Exception {
        List<String> order = new ArrayList<>();
        RecordingRepo repo = new RecordingRepo();
        AuditConfiguration audit = new AuditConfiguration();
        audit.addPreCommandAuditHandler(record -> order.add("pre"), AuditTx.JOIN);
        audit.addPostCommandAuditHandler(record -> order.add("post"), AuditTx.REQUIRES_NEW);
        CustomCommandAuditor<SampleEvent> auditor = new CustomCommandAuditor<>(
                repo.proxy(), SampleEvent::new, audit, null);

        auditor.logCommandInTransaction(new SampleCommand("x"), "req-1");

        assertEquals(List.of("pre", "post"), order);
        assertEquals(1, repo.saved.size());
    }

    @Test
    void overrideCanSkipOriginalSave() throws Exception {
        RecordingRepo repo = new RecordingRepo();
        AtomicBoolean overrideRan = new AtomicBoolean();
        AuditConfiguration audit = new AuditConfiguration();
        audit.overrideCommandAuditHandler(record -> overrideRan.set(true));
        CustomCommandAuditor<SampleEvent> auditor = new CustomCommandAuditor<>(
                repo.proxy(), SampleEvent::new, audit, null);

        SampleEvent event = auditor.logCommandInTransaction(new SampleCommand("x"), "req-1");

        assertTrue(overrideRan.get());
        assertTrue(repo.saved.isEmpty());
        assertNull(event.getId());
    }

    @Test
    void overrideCanCallOriginalSave() throws Exception {
        RecordingRepo repo = new RecordingRepo();
        AuditConfiguration audit = new AuditConfiguration();
        audit.overrideCommandAuditHandler(record -> audit.getOriginalCommandAuditHandler().handle(record));
        CustomCommandAuditor<SampleEvent> auditor = new CustomCommandAuditor<>(
                repo.proxy(), SampleEvent::new, audit, null);

        auditor.logCommandInTransaction(new SampleCommand("x"), "req-1");

        assertEquals(1, repo.saved.size());
    }

    @Test
    void logSuccessNoOpsWhenRowMissing() {
        RecordingRepo repo = new RecordingRepo();
        CustomCommandAuditor<SampleEvent> auditor = new CustomCommandAuditor<>(repo.proxy(), SampleEvent::new);

        auditor.logSuccess(99);

        assertTrue(repo.saved.isEmpty());
    }

    @Test
    void joinPostFailureDoesNotPropagateFromLogCommand() {
        RecordingRepo repo = new RecordingRepo();
        AuditConfiguration audit = new AuditConfiguration();
        audit.addPostCommandAuditHandler(record -> {
            throw new IllegalStateException("audit post failed");
        }, AuditTx.JOIN);
        CustomCommandAuditor<SampleEvent> auditor = new CustomCommandAuditor<>(
                repo.proxy(), SampleEvent::new, audit, null);

        SampleEvent event = auditor.logCommandInTransaction(new SampleCommand("x"), "req-1");

        assertEquals("req-1", event.requestId);
    }

    @Test
    void defaultEventPipelineSavesEventRow() throws Exception {
        RecordingRepo repo = new RecordingRepo();
        CustomCommandAuditor<SampleEvent> auditor = new CustomCommandAuditor<>(repo.proxy(), SampleEvent::new);

        auditor.logEventInTransaction(new SampleDomainEvent("evt"), "req-2");

        assertEquals(1, repo.saved.size());
        assertEquals(Boolean.TRUE, repo.saved.get(0).success);
        assertEquals("SampleDomainEvent", repo.saved.get(0).eventType);
    }

    record SampleCommand(String value) {
    }

    record SampleDomainEvent(String value) {
    }

    static final class RecordingRepo {
        final List<SampleEvent> saved = new ArrayList<>();
        final AtomicInteger nextId = new AtomicInteger(1);

        @SuppressWarnings("unchecked")
        AuditEventRepository<SampleEvent> proxy() {
            return (AuditEventRepository<SampleEvent>) Proxy.newProxyInstance(
                    AuditEventRepository.class.getClassLoader(),
                    new Class<?>[]{AuditEventRepository.class},
                    (p, method, args) -> switch (method.getName()) {
                        case "save" -> {
                            SampleEvent event = (SampleEvent) args[0];
                            if (event.id == null) {
                                event.id = nextId.getAndIncrement();
                            }
                            saved.removeIf(existing -> existing.id.equals(event.id));
                            saved.add(event);
                            yield event;
                        }
                        case "findById" -> {
                            Integer id = (Integer) args[0];
                            yield saved.stream().filter(e -> id.equals(e.id)).findFirst();
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
        String payload;
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
            this.payload = payload;
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
