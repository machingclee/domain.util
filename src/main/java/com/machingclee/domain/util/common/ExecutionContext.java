package com.machingclee.domain.util.common;

import com.machingclee.domain.util.schema.SchemaIdentifier;

/**
 * Immutable snapshot of execution context at the time an event was enqueued.
 */
public record ExecutionContext(
        String userEmail,
        String requestId,
        java.util.Map<String, String> originalMDC,
        String commandName,
        SchemaIdentifier schemaIdentifier) {

    public ExecutionContext(String userEmail, String requestId, java.util.Map<String, String> originalMDC) {
        this(userEmail, requestId, originalMDC, null, null);
    }

    public ExecutionContext(String userEmail, String requestId, java.util.Map<String, String> originalMDC, String commandName) {
        this(userEmail, requestId, originalMDC, commandName, null);
    }
}
