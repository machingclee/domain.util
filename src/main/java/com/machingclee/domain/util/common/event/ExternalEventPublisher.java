package com.machingclee.domain.util.common.event;

import com.machingclee.domain.util.common.ExecutionContext;
import com.machingclee.domain.util.common.event.enums.DispatchTiming;
import com.machingclee.domain.util.schema.SchemaIdentifier;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;
import java.util.UUID;

/**
 * Publishes domain events from external entry points (message-queue pollers,
 * webhook handlers, scheduled tasks, etc.) that sit outside the command/event
 * pipeline.
 * <p>
 * Each call wraps the raw event in an {@link EventWrapper} so that
 * {@link DomainEventLogger} and other {@code @EventListener} /
 * {@code @TransactionalEventListener} consumers can pick it up.
 * <p>
 * Two dispatch modes are provided:
 * <ul>
 * <li>{@link #publish(Object)} — {@link DispatchTiming#IMMEDIATE}, fires
 * synchronously</li>
 * <li>{@link #publishTransactional(Object)} —
 * {@link DispatchTiming#POST_COMMIT},
 * delivered after the current transaction commits (via Spring's
 * {@code @TransactionalEventListener})</li>
 * </ul>
 */
public class ExternalEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final SchemaIdentifier schemaIdentifier;

    /**
     * Create a publisher that does not target any specific schema.
     * Events published this way are still visible to generic listeners, but
     * {@link DomainEventLogger} instances will skip them because they match on
     * {@link SchemaIdentifier}.
     */
    public ExternalEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this(applicationEventPublisher, null);
    }

    /**
     * Create a publisher that tags every event with the given
     * {@link SchemaIdentifier} so that the matching {@link DomainEventLogger}
     * persists it.
     */
    public ExternalEventPublisher(ApplicationEventPublisher applicationEventPublisher,
            SchemaIdentifier schemaIdentifier) {
        this.applicationEventPublisher = applicationEventPublisher;
        this.schemaIdentifier = schemaIdentifier;
    }

    /**
     * Publish an event immediately (non-transactional).
     * The wrapped copy is published first so that {@link DomainEventLogger}
     * audits it with the correct event_order, then the raw event is published
     * so that {@code @EventListener} policy handlers receive it.
     *
     * @param event the raw domain event
     */
    public void publish(Object event) {
        var wrappedEvent = wrapEvent(event, DispatchTiming.IMMEDIATE);
        applicationEventPublisher.publishEvent(wrappedEvent);
        applicationEventPublisher.publishEvent(event);
    }

    /**
     * Publish an event for after-commit delivery.
     * The wrapped copy is published first so that {@link DomainEventLogger}
     * picks it up via {@code @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)}
     * and audits it with the correct event_order, then the raw event is
     * published so that {@code @EventListener} policy handlers receive it.
     * When called outside a transaction the events are delivered immediately.
     *
     * @param event the raw domain event
     */
    public void publishTransactional(Object event) {
        var wrappedEvent = wrapEvent(event, DispatchTiming.POST_COMMIT);
        applicationEventPublisher.publishEvent(wrappedEvent);
        applicationEventPublisher.publishEvent(event);
    }

    private EventWrapper<Object> wrapEvent(Object event, DispatchTiming timing) {
        var requestId = UUID.randomUUID().toString();
        Map<String, String> dummyMDC = Map.of();
        var ctx = new ExecutionContext(
                "ExternalEventPublisher",
                requestId,
                dummyMDC,
                "ExternalEvent",
                schemaIdentifier);
        return new EventWrapper<>(event, timing, ctx);
    }
}
