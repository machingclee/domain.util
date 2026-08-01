package com.machingclee.domain.util.common.event;

import com.machingclee.domain.util.common.ExecutionContext;
import com.machingclee.domain.util.common.MdcContextKeys;
import com.machingclee.domain.util.common.RequestSequence;
import com.machingclee.domain.util.common.event.enums.DispatchTiming;
import com.machingclee.domain.util.common.interfaces.AuditEvent;
import com.machingclee.domain.util.common.interfaces.AuditEventRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.function.Supplier;

/**
 * Persists domain events using the injected audit repository.
 * <p>
 * Physical storage (table name, Postgres schema, datasource) is decided entirely
 * by the consumer's {@link AuditEvent} entity (e.g. {@code @Table}) and
 * {@link AuditEventRepository} — not by this library.
 * <p>
 * Register one logger bean per application (or per persistence unit you want
 * events written to):
 *
 * <pre>
 * {@code
 * @Component
 * public class SomeDomainDomainEventLogger extends DomainEventLogger {
 *     public SomeDomainDomainEventLogger(SomeDomainEventRepository repo,
 *                                        ApplicationEventPublisher publisher) {
 *         super(repo, SomeDomainEvent::new, publisher);
 *     }
 * }
 * }
 * </pre>
 * <p>
 * Prefer a single logger bean. Multiple logger beans would each receive every
 * {@link EventWrapper} and may double-write.
 */
public class DomainEventLogger {

    private static final Logger logger = LoggerFactory.getLogger(DomainEventLogger.class);

    private final ObjectMapper objectMapper = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private final AuditEventRepository<AuditEvent> eventRepository;
    private final Supplier<AuditEvent> eventFactory;
    private final ApplicationEventPublisher applicationEventPublisher;

    @SuppressWarnings("unchecked")
    public DomainEventLogger(AuditEventRepository<? extends AuditEvent> eventRepository,
                             Supplier<? extends AuditEvent> eventFactory,
                             ApplicationEventPublisher applicationEventPublisher) {
        this.eventRepository = (AuditEventRepository<AuditEvent>) eventRepository;
        this.eventFactory = (Supplier<AuditEvent>) eventFactory;
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordSynchronousEvent(EventWrapper<Object> wrapperEvent) {
        if (wrapperEvent.getTiming() != DispatchTiming.IMMEDIATE) return;
        try {
            persistEventWithPreciseTiming(wrapperEvent);
        } catch (Exception e) {
            logger.warn("Failed to persist synchronous event: {}", e.getMessage(), e);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordTransactionalEvent(EventWrapper<Object> wrapperEvent) {
        if (wrapperEvent.getTiming() != DispatchTiming.POST_COMMIT) return;
        try {
            persistEventWithPreciseTiming(wrapperEvent);
            applicationEventPublisher.publishEvent(wrapperEvent.getEvent());
        } catch (Exception e) {
            logger.warn("Failed to persist or publish transactional event: {}", e.getMessage(), e);
        }
    }

    private void persistEventWithPreciseTiming(EventWrapper<Object> wrappedEvent) {
        Object event = wrappedEvent.getEvent();
        ExecutionContext ctx = wrappedEvent.getContext();

        String rawId = MDC.get(MdcContextKeys.REQUEST_ID);
        if (rawId == null && ctx != null) rawId = ctx.requestId();
        String requestId = rawId != null ? rawId : "";

        String userId = (ctx != null && ctx.userEmail() != null) ? ctx.userEmail() : "";
        String eventTypeName = event.getClass().getSimpleName();
        String commandAwareEventType = (ctx != null && ctx.commandName() != null)
                ? ctx.commandName() + " > " + eventTypeName
                : eventTypeName;

        long uniqueTimestamp = System.currentTimeMillis() + (System.nanoTime() % 1000);

        AuditEvent eventToSave = eventFactory.get();
        eventToSave.setCreatedAt((double) uniqueTimestamp);
        eventToSave.setEventType(commandAwareEventType);
        eventToSave.setPayload(writePayload(event));
        eventToSave.setRequestUserEmail(userId);
        eventToSave.setRequestId(requestId);
        eventToSave.setEventOrder(RequestSequence.next());
        eventToSave.setSuccess(true);

        eventRepository.save(eventToSave);
        logger.info("AUDIT: Event [{}] saved with createdAt={}", commandAwareEventType, uniqueTimestamp);
    }

    private String writePayload(Object event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize domain event", e);
        }
    }
}
