package com.machingclee.domain.util.common.event;

import com.machingclee.domain.util.common.ExecutionContext;
import com.machingclee.domain.util.common.MdcContextKeys;
import com.machingclee.domain.util.common.interfaces.DomainEventDispatcher;

import com.machingclee.domain.util.common.interfaces.EventQueue;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;

@Component
public class SpringDomainEventDispatcher implements DomainEventDispatcher {

    private final ApplicationEventPublisher applicationEventPublisher;

    public SpringDomainEventDispatcher(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void dispatchNow(EventQueue eventQueue, String requestId) {
        // Backward-compatible: dispatch all events immediately
        dispatchEvents(eventQueue.getEvents(), requestId);
    }

    @Override
    public void dispatch(EventQueue eventQueue, String requestId) {
        // Dispatch immediate events right away
        dispatchEvents(eventQueue.getImmediateEvents(), requestId);

        // Register post-commit events for after-commit dispatch
        List<EventWrapper<Object>> postCommitEvents = eventQueue.getPostCommitEvents();
        if (!postCommitEvents.isEmpty()) {
            registerPostCommitEvents(postCommitEvents, requestId);
        }
    }

    private void dispatchEvents(List<EventWrapper<Object>> wrappedEvents, String requestId) {
        for (EventWrapper<Object> wrappedEvent : wrappedEvents) {
            // Patch requestId into the existing context if it's missing
            ExecutionContext ctx = wrappedEvent.getContext();
            if (ctx != null && ctx.requestId() == null && requestId != null) {
                wrappedEvent.setContext(new ExecutionContext(
                        ctx.userEmail(), requestId, ctx.originalMDC(),
                        ctx.commandName(), ctx.schemaIdentifier()));
            }
            // Publish the wrapper first — this logs the event (via DomainEventLogger)
            // before any policies run, so event_order in the DB reflects the true
            // causal sequence: command → event → policy → nested command/events.
            // If a policy listener throws, markEventsFailed() will mark this event
            // as failed in a subsequent REQUIRES_NEW transaction.
            applicationEventPublisher.publishEvent(wrappedEvent);
            // Publish the actual business event — triggers @EventListener policies
            applicationEventPublisher.publishEvent(wrappedEvent.getEvent());
        }
    }

    private void registerPostCommitEvents(List<EventWrapper<Object>> wrappedEvents, String requestId) {
        ExecutionContext capturedContext = captureCurrentContext(requestId);

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    withContext(capturedContext, ctx -> {
                        for (EventWrapper<Object> wrappedEvent : wrappedEvents) {
                            // Patch requestId into existing context, preserving schemaIdentifier
                            ExecutionContext existing = wrappedEvent.getContext();
                            if (existing != null && existing.requestId() == null && ctx.requestId() != null) {
                                wrappedEvent.setContext(new ExecutionContext(
                                        existing.userEmail(), ctx.requestId(), existing.originalMDC(),
                                        existing.commandName(), existing.schemaIdentifier()));
                            }
                            applicationEventPublisher.publishEvent(wrappedEvent);
                            applicationEventPublisher.publishEvent(wrappedEvent.getEvent());
                        }
                    });
                }
            });
        } else {
            // No active transaction — dispatch immediately
            dispatchEvents(wrappedEvents, requestId);
        }
    }

    private ExecutionContext captureCurrentContext(String requestId) {
        return new ExecutionContext(
                "me",
                requestId,
                MDC.getCopyOfContextMap());
    }

    private void withContext(ExecutionContext context, java.util.function.Consumer<ExecutionContext> block) {
        if (context.userEmail() != null)
            MDC.put(MdcContextKeys.USER_ID, context.userEmail());
        if (context.requestId() != null)
            MDC.put(MdcContextKeys.REQUEST_ID, context.requestId());
        Map<String, String> originalMDC = context.originalMDC();
        if (originalMDC != null)
            originalMDC.forEach(MDC::put);
        try {
            block.accept(context);
        } finally {
            MDC.clear();
        }
    }
}
