package com.machingclee.domain.util.common.event;

import com.machingclee.domain.util.common.MdcContextKeys;
import com.machingclee.domain.util.common.RequestSequence;
import com.machingclee.domain.util.common.audit.AuditConfiguration;
import com.machingclee.domain.util.common.interfaces.AuditEvent;
import com.machingclee.domain.util.common.interfaces.AuditEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Correlation between an externally published event and the command pipeline:
 * a publish inside an open request must keep that requestId and continue its
 * event_order, while a publish that starts a chain opens one request that the
 * listeners (and any command they dispatch) share.
 */
class ExternalEventPublisherTest {

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void continuesTheOpenRequestInsteadOfStartingANewOne() {
        RecordingRepo repo = new RecordingRepo();
        DomainEventLogger logger = logger(repo);
        ExternalEventPublisher publisher = new ExternalEventPublisher(publishingTo(logger));

        MDC.put(MdcContextKeys.REQUEST_ID, "req-from-command");
        MDC.put(MdcContextKeys.USER_ID, "ada@example.com");
        // The command that is already in flight consumed order 1.
        RequestSequence.next("req-from-command");

        publisher.publish(new IncomingEvent("boot"));

        assertEquals(1, repo.saved.size());
        assertEquals("req-from-command", repo.saved.get(0).requestId);
        assertEquals(2, repo.saved.get(0).eventOrder);
        assertEquals("ada@example.com", repo.saved.get(0).requestUserEmail);
        // The command's request id must still be in place once publish returns.
        assertEquals("req-from-command", MDC.get(MdcContextKeys.REQUEST_ID));
    }

    @Test
    void opensOneRequestThatFollowOnCommandsShare() {
        RecordingRepo repo = new RecordingRepo();
        List<String> seenByListener = new ArrayList<>();
        DomainEventLogger logger = logger(repo);
        ExternalEventPublisher publisher = new ExternalEventPublisher(event -> {
            if (event instanceof EventWrapper wrapper) {
                logger.recordSynchronousEvent(wrapper);
            } else {
                // A policy reacting to the raw event and dispatching a command:
                // the invoker reads the request id from MDC, so it must already
                // be the one this publish opened.
                seenByListener.add(MDC.get(MdcContextKeys.REQUEST_ID));
                RequestSequence.next(MDC.get(MdcContextKeys.REQUEST_ID));
            }
        });

        publisher.publish(new IncomingEvent("boot"));

        assertEquals(1, seenByListener.size());
        String requestId = repo.saved.get(0).requestId;
        assertEquals(requestId, seenByListener.get(0));
        // Event is order 1, the command the listener dispatched is order 2.
        assertEquals(1, repo.saved.get(0).eventOrder);
        assertEquals(2, RequestSequence.next(requestId) - 1);
        // Nothing was in flight, so the request id must not leak past publish.
        assertNull(MDC.get(MdcContextKeys.REQUEST_ID));
    }

    private static DomainEventLogger logger(RecordingRepo repo) {
        return new DomainEventLogger(repo.proxy(), SampleEvent::new, event -> {
        }, new AuditConfiguration(), null);
    }

    private static ApplicationEventPublisher publishingTo(DomainEventLogger logger) {
        return event -> {
            if (event instanceof EventWrapper wrapper) {
                logger.recordSynchronousEvent(wrapper);
            }
        };
    }

    record IncomingEvent(String value) {
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
        Integer eventOrder;
        String requestId;
        String requestUserEmail;

        @Override
        public Integer getId() {
            return id;
        }

        @Override
        public Boolean getSuccess() {
            return null;
        }

        @Override
        public String getRequestId() {
            return requestId;
        }

        @Override
        public String getEventType() {
            return null;
        }

        @Override
        public String getFailureReason() {
            return null;
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
            this.requestUserEmail = requestUserEmail;
        }

        @Override
        public void setRequestId(String requestId) {
            this.requestId = requestId;
        }

        @Override
        public void setSuccess(Boolean success) {
        }

        @Override
        public void setFailureReason(String failureReason) {
        }

        @Override
        public void setEventOrder(Integer order) {
            this.eventOrder = order;
        }
    }
}
