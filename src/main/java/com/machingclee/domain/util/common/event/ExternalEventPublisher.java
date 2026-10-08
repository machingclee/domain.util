package com.machingclee.domain.util.common.event;

import com.machingclee.domain.util.common.ExecutionContext;
import com.machingclee.domain.util.common.MdcContextKeys;
import com.machingclee.domain.util.common.event.enums.DispatchTiming;
import org.slf4j.MDC;
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
 * <p>
 * Correlation follows the request already in flight. When a policy (or any
 * other code running inside a command) publishes here, the event keeps that
 * command's {@code requestId} and continues its {@code event_order}. Only a
 * publish with no request open — the usual case for a queue poller or webhook
 * that <em>starts</em> a chain — opens a new request id. That id stays in MDC
 * for the synchronous listeners, so a policy's follow-on
 * {@code commandInvoker.invoke} joins the same request instead of starting
 * another one. The key is removed again once the publish returns, unless this
 * call found it already set.
 * <p>
 * Where events are stored is determined by the consumer's
 * {@link DomainEventLogger}
 * bean (its repository + entity {@code @Table}), not by this publisher.
 */
public class ExternalEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public ExternalEventPublisher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
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
        inRequest(() -> {
            var wrappedEvent = wrapEvent(event, DispatchTiming.IMMEDIATE);
            applicationEventPublisher.publishEvent(wrappedEvent);
            applicationEventPublisher.publishEvent(event);
        });
    }

    /**
     * Publish an event for after-commit delivery.
     * The wrapped copy is published first so that {@link DomainEventLogger}
     * picks it up via
     * {@code @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)}
     * and audits it with the correct event_order, then the raw event is
     * published so that {@code @EventListener} policy handlers receive it.
     * When called outside a transaction the events are delivered immediately.
     *
     * @param event the raw domain event
     */
    public void publishTransactional(Object event) {
        inRequest(() -> {
            var wrappedEvent = wrapEvent(event, DispatchTiming.POST_COMMIT);
            applicationEventPublisher.publishEvent(wrappedEvent);
            applicationEventPublisher.publishEvent(event);
        });
    }

    /**
     * Runs {@code action} with a request id in MDC. Reuses one that is already
     * there (nested inside a command, or an earlier publish on this thread)
     * and restores MDC exactly as it was found. Opens a fresh id only when
     * none is present, and removes it again afterwards.
     */
    private void inRequest(Runnable action) {
        String previousRequestId = MDC.get(MdcContextKeys.REQUEST_ID);
        boolean openedRequest = previousRequestId == null || previousRequestId.isBlank();
        if (openedRequest) {
            MDC.put(MdcContextKeys.REQUEST_ID, UUID.randomUUID().toString());
        }
        try {
            action.run();
        } finally {
            if (openedRequest) {
                MDC.remove(MdcContextKeys.REQUEST_ID);
            }
        }
    }

    private EventWrapper<Object> wrapEvent(Object event, DispatchTiming timing) {
        String userId = MDC.get(MdcContextKeys.USER_ID);
        Map<String, String> mdc = MDC.getCopyOfContextMap();
        var ctx = new ExecutionContext(
                userId != null ? userId : "",
                MDC.get(MdcContextKeys.REQUEST_ID),
                mdc != null ? mdc : Map.of(),
                null);
        return new EventWrapper<>(event, timing, ctx);
    }
}
