package com.machingclee.domain.util.common.audit;

import com.machingclee.domain.util.common.event.EventWrapper;
import com.machingclee.domain.util.common.interfaces.AuditEvent;

/**
 * In-flight domain-event audit: the domain event plus the {@link AuditEvent}
 * row about to be persisted. {@link #getWrapper()} is set when the record
 * comes from {@code DomainEventLogger}.
 * <p>
 * Handlers may mutate {@link #getAuditEvent()} but must not replace the
 * instance.
 */
public final class EventAuditRecord {

    private final Object domainEvent;
    private final String requestId;
    private final AuditEvent auditEvent;
    private final EventWrapper<?> wrapper;

    public EventAuditRecord(Object domainEvent, String requestId, AuditEvent auditEvent) {
        this(domainEvent, requestId, auditEvent, null);
    }

    public EventAuditRecord(Object domainEvent, String requestId, AuditEvent auditEvent,
            EventWrapper<?> wrapper) {
        this.domainEvent = domainEvent;
        this.requestId = requestId;
        this.auditEvent = auditEvent;
        this.wrapper = wrapper;
    }

    public Object getDomainEvent() {
        return domainEvent;
    }

    public String getRequestId() {
        return requestId;
    }

    public AuditEvent getAuditEvent() {
        return auditEvent;
    }

    public EventWrapper<?> getWrapper() {
        return wrapper;
    }
}
