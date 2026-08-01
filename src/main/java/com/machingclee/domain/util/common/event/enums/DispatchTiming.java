package com.machingclee.domain.util.common.event.enums;

/**
 * Controls when a domain event is dispatched relative to the transaction.
 */
public enum DispatchTiming {
    IMMEDIATE,
    POST_COMMIT
}
