package com.machingclee.domain.util.common.event;

import com.machingclee.domain.util.common.audit.AuditConfiguration;
import com.machingclee.domain.util.common.audit.AuditTx;
import com.machingclee.domain.util.common.event.enums.DispatchTiming;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class DomainEventLoggerTest {

    @Test
    void immediateEventGoesThroughPipelineAndSaves() {
        RecordingRepo repo = new RecordingRepo();
        List<String> order = new ArrayList<>();
        AuditConfiguration audit = new AuditConfiguration();
        audit.addPreEventAuditHandler(record -> order.add("pre"), AuditTx.JOIN);
        audit.addPostEventAuditHandler(record -> order.add("post"), AuditTx.REQUIRES_NEW);
        DomainEventLogger logger = new DomainEventLogger(
                repo.proxy(), SampleEvent::new, event -> {
                }, audit, null);

        logger.recordSynchronousEvent(new EventWrapper<>(new SampleDomainEvent("e"), DispatchTiming.IMMEDIATE));

        assertEquals(List.of("pre", "post"), order);
        assertEquals(1, repo.saved.size());
        assertEquals("SampleDomainEvent", repo.saved.get(0).eventType);
        assertEquals(Boolean.TRUE, repo.saved.get(0).success);
    }

    @Test
    void overrideCanSkipSave() {
        RecordingRepo repo = new RecordingRepo();
        AtomicBoolean overrideRan = new AtomicBoolean();
        AuditConfiguration audit = new AuditConfiguration();
        audit.overrideEventAuditHandler(record -> overrideRan.set(true));
        DomainEventLogger logger = new DomainEventLogger(
                repo.proxy(), SampleEvent::new, event -> {
                }, audit, null);

        logger.recordSynchronousEvent(new EventWrapper<>(new SampleDomainEvent("e"), DispatchTiming.IMMEDIATE));

        assertTrue(overrideRan.get());
        assertTrue(repo.saved.isEmpty());
    }

    @Test
    void ignoresPostCommitOnSynchronousListener() {
        RecordingRepo repo = new RecordingRepo();
        DomainEventLogger logger = new DomainEventLogger(repo.proxy(), SampleEvent::new, event -> {
        });

        logger.recordSynchronousEvent(new EventWrapper<>(new SampleDomainEvent("e"), DispatchTiming.POST_COMMIT));

        assertTrue(repo.saved.isEmpty());
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
                            saved.add(event);
                            yield event;
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
        String eventType;
        String requestId;
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
