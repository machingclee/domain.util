package com.machingclee.domain.util.common.audit;

import com.machingclee.domain.util.common.interfaces.AuditEvent;

/**
 * In-flight command audit: the command being invoked plus the
 * {@link AuditEvent} row about to be persisted.
 * <p>
 * Handlers may mutate {@link #getAuditEvent()} but must not replace the
 * instance — the invoker keeps this same reference.
 */
public final class CommandAuditRecord {

    private final Object command;
    private final String requestId;
    private final AuditEvent auditEvent;

    public CommandAuditRecord(Object command, String requestId, AuditEvent auditEvent) {
        this.command = command;
        this.requestId = requestId;
        this.auditEvent = auditEvent;
    }

    public Object getCommand() {
        return command;
    }

    public String getRequestId() {
        return requestId;
    }

    public AuditEvent getAuditEvent() {
        return auditEvent;
    }
}
