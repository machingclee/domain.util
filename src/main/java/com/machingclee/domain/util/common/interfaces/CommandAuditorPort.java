package com.machingclee.domain.util.common.interfaces;

/**
 * Common interface for schema-specific CommandAuditors.
 * Generic type E is the audit event entity (EChargeEvent or EcapiEvent).
 */
public interface CommandAuditorPort<E extends AuditEvent> {
    <T> E logCommandInTransaction(T command, String requestId) throws Exception;

    /**
     * Logs a domain event in the current (mandatory) transaction.
     */
    <T> E logEventInTransaction(T event, String requestId) throws Exception;

    void logSuccess(int eventId);

    void logFailure(int eventId, String error);
}
