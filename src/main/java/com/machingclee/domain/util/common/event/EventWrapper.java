package com.machingclee.domain.util.common.event;

import com.machingclee.domain.util.common.ExecutionContext;
import com.machingclee.domain.util.common.event.enums.DispatchTiming;

/**
 * Wraps a domain event together with its dispatch timing and execution context.
 */
public class EventWrapper<T> {

    private final T event;
    private final DispatchTiming timing;
    private ExecutionContext context; // mutable — set after post-commit dispatch

    public EventWrapper(T event, DispatchTiming timing) {
        this.event = event;
        this.timing = timing;
    }

    public EventWrapper(T event, DispatchTiming timing, ExecutionContext context) {
        this.event = event;
        this.timing = timing;
        this.context = context;
    }

    public T getEvent() {
        return event;
    }

    public DispatchTiming getTiming() {
        return timing;
    }

    public ExecutionContext getContext() {
        return context;
    }

    public void setContext(ExecutionContext context) {
        this.context = context;
    }
}
