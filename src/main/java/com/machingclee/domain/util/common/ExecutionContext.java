package com.machingclee.domain.util.common;

/**
 * Immutable snapshot of execution context at the time an event was enqueued.
 */
public record ExecutionContext(
        String userEmail,
        String requestId,
        java.util.Map<String, String> originalMDC,
        String commandName) {

    public ExecutionContext(String userEmail, String requestId, java.util.Map<String, String> originalMDC) {
        this(userEmail, requestId, originalMDC, null);
    }
}
