package com.machingclee.domain.util.common;

import com.machingclee.domain.util.common.command.AbstractCommandInvoker;

/**
 * Standard MDC (Mapped Diagnostic Context) keys used across the
 * command-invocation and event-dispatch pipeline.
 * <p>
 * Consumers of the {@code domain.util} package should use these constants
 * when putting values into MDC so that logging and audit trails are
 * populated correctly.
 */
public final class MdcContextKeys {

    private MdcContextKeys() {
        // utility class — not meant to be instantiated
    }

    /**
     * MDC key for the current user's identity.
     * <p>
     * Set by HTTP filters (e.g. {@code UserContextFilter}) before each request.
     * The value is captured by {@link SmartEventQueue} into
     * {@link ExecutionContext#userEmail()} and ultimately persisted as the
     * {@code request_user_email} column in audit event tables.
     */
    public static final String USER_ID = "userId";

    /**
     * MDC key for the unique request identifier.
     * <p>
     * Set by {@link AbstractCommandInvoker} at the start of every
     * top-level command invocation. Used to correlate command records,
     * domain-event records, and log lines belonging to the same request.
     */
    public static final String REQUEST_ID = "requestId";
}
