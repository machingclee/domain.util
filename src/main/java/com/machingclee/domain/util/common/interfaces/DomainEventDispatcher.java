package com.machingclee.domain.util.common.interfaces;

public interface DomainEventDispatcher {
    /**
     * Dispatch all events immediately (backward-compatible).
     */
    void dispatchNow(EventQueue eventQueue, String requestId);

    /**
     * Dispatch immediate events now; register post-commit events for after-commit.
     */
    void dispatch(EventQueue eventQueue, String requestId);

    default void dispatchNow(EventQueue eventQueue) {
        dispatchNow(eventQueue, null);
    }

    default void dispatch(EventQueue eventQueue) {
        dispatch(eventQueue, null);
    }
}
