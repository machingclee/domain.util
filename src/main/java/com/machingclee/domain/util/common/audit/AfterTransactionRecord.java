package com.machingclee.domain.util.common.audit;

import com.machingclee.domain.util.common.interfaces.AuditEvent;

import java.util.List;

/**
 * Snapshot of every audit row for a {@code requestId}, taken after the
 * command invoker has finished the command transaction <em>and</em> any
 * failure stamps ({@code logFailure} / {@code markEventsFailed}).
 */
public final class AfterTransactionRecord {

    private final String requestId;
    private final boolean committed;
    private final List<AuditEvent> events;

    public AfterTransactionRecord(String requestId, boolean committed, List<AuditEvent> events) {
        this.requestId = requestId;
        this.committed = committed;
        this.events = events == null ? List.of() : List.copyOf(events);
    }

    public String getRequestId() {
        return requestId;
    }

    /**
     * {@code true} when the command transaction committed;
     * {@code false} after rollback (failure stamps are already applied).
     */
    public boolean isCommitted() {
        return committed;
    }

    /**
     * All {@code event} rows for {@link #getRequestId()}, including command
     * rows and domain-event rows, in repository order. On failure this is
     * after {@code failure_reason} has been written.
     */
    public List<AuditEvent> getEvents() {
        return events;
    }
}
