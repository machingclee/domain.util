package com.machingclee.domain.util.common.interfaces;

import com.machingclee.domain.util.common.event.EventWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Queue that handlers write events into during command execution.
 */
public interface EventQueue {

    void add(Object event);

    void addTransactional(Object event);

    void addAll(List<?> events);

    void addAllTransactional(List<?> events);

    List<EventWrapper<Object>> getImmediateEvents();

    List<EventWrapper<Object>> getPostCommitEvents();

    List<EventWrapper<Object>> getAllEvents();

    /**
     * Backward-compatible: returns immediate + post-commit events.
     */
    default List<EventWrapper<Object>> getEvents() {
        List<EventWrapper<Object>> combined = new ArrayList<>();
        combined.addAll(getImmediateEvents());
        combined.addAll(getPostCommitEvents());
        return combined;
    }
}
