package com.machingclee.domain.util.common.dto;

/**
 * Describes a domain event and the schema of the DTO payload it carries.
 *
 * @param event   simple name of the domain event class
 * @param payload schema describing the event's data fields; typically a
 *                {@code Map<String, Object>} whose keys are field names and
 *                whose values are type descriptors (strings for simple types,
 *                nested maps for complex objects, or arrays for generics)
 */
public record EventPayloadDTO(
        String event,
        Object payload
) {
    public EventPayloadDTO {
        event = event != null ? event : "";
    }
}
