package com.machingclee.domain.util.common.event;

import com.machingclee.domain.util.common.event.enums.DispatchTiming;
import com.machingclee.domain.util.common.ExecutionContext;
import com.machingclee.domain.util.common.MdcContextKeys;
import com.machingclee.domain.util.common.interfaces.EventQueue;
import com.machingclee.domain.util.schema.SchemaIdentifier;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class SmartEventQueue implements EventQueue {

    private final List<EventWrapper<Object>> events = new ArrayList<>();
    private final SchemaIdentifier schemaIdentifier;

    public SmartEventQueue(SchemaIdentifier schemaIdentifier) {
        this.schemaIdentifier = schemaIdentifier;
    }

    @Override
    public void add(Object event) {
        ExecutionContext context = captureCurrentCommandContext();
        events.add(new EventWrapper<>(event, DispatchTiming.IMMEDIATE, context));
    }

    @Override
    public void addTransactional(Object event) {
        ExecutionContext context = captureCurrentCommandContext();
        events.add(new EventWrapper<>(event, DispatchTiming.POST_COMMIT, context));
    }

    @Override
    public void addAll(List<?> eventList) {
        ExecutionContext context = captureCurrentCommandContext();
        for (Object event : eventList) {
            events.add(new EventWrapper<>(event, DispatchTiming.IMMEDIATE, context));
        }
    }

    @Override
    public void addAllTransactional(List<?> eventList) {
        ExecutionContext context = captureCurrentCommandContext();
        for (Object event : eventList) {
            events.add(new EventWrapper<>(event, DispatchTiming.POST_COMMIT, context));
        }
    }

    private ExecutionContext captureCurrentCommandContext() {
        return new ExecutionContext(
                MDC.get(MdcContextKeys.USER_ID) != null ? MDC.get(MdcContextKeys.USER_ID) : "",
                MDC.get(MdcContextKeys.REQUEST_ID),
                MDC.getCopyOfContextMap(),
                null,
                schemaIdentifier);
    }

    @Override
    public List<EventWrapper<Object>> getImmediateEvents() {
        return events.stream()
                .filter(e -> e.getTiming() == DispatchTiming.IMMEDIATE)
                .collect(Collectors.toList());
    }

    @Override
    public List<EventWrapper<Object>> getPostCommitEvents() {
        return events.stream()
                .filter(e -> e.getTiming() == DispatchTiming.POST_COMMIT)
                .collect(Collectors.toList());
    }

    @Override
    public List<EventWrapper<Object>> getAllEvents() {
        return new ArrayList<>(events);
    }
}
