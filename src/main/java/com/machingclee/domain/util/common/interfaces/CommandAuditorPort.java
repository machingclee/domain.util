package com.machingclee.domain.util.common.interfaces;

/**
 * Common interface for CommandAuditors.
 * Generic type E is the audit event entity persisted for commands/events.
 */
public interface CommandAuditorPort<E extends AuditEvent> {
    /**
     * Persists the command audit row in its own transaction. Failures are
     * swallowed so they cannot abort {@code CommandHandler} execution.
     */
    <T> E logCommandInTransaction(T command, String requestId);

    /**
     * Logs a domain event in its own persist transaction. Failures are
     * swallowed so they cannot abort command execution.
     */
    <T> E logEventInTransaction(T event, String requestId);

    void logSuccess(int eventId);

    void logFailure(int eventId, String error);
}
