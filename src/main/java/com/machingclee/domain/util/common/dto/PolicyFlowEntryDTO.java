package com.machingclee.domain.util.common.dto;

/**
 * One event → policy → command link in the event-storming graph.
 *
 * @param fromEvent        simple name of the event the policy listens to, or null
 * @param toCommand        simple name of the command the policy dispatches, or null
 * @param invariant        business rule the policy enforces, or null
 * @param fromEventContext {@code @BoundedContext} on the event type (class, else
 *                         package). Lets the visualizer place an event that no
 *                         command emits — e.g. one published through
 *                         {@code ExternalEventPublisher} — inside its bounding box.
 *                         Empty when the event carries no annotation; ignored for
 *                         events a command already emits, which inherit the
 *                         command's box.
 */
public record PolicyFlowEntryDTO(String fromEvent, String toCommand, String invariant,
                                 String fromEventContext) {
    public PolicyFlowEntryDTO {
        fromEventContext = fromEventContext != null ? fromEventContext : "";
    }

    public PolicyFlowEntryDTO(String fromEvent, String toCommand, String invariant) {
        this(fromEvent, toCommand, invariant, "");
    }

    public PolicyFlowEntryDTO() {
        this(null, null, null, "");
    }
}
