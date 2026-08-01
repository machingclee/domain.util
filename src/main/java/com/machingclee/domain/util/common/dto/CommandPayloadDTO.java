package com.machingclee.domain.util.common.dto;

/**
 * Describes a command and the schema of the DTO payload it carries.
 * Mirrors {@link EventPayloadDTO} — same representation, with {@code command}
 * instead of {@code event}.
 *
 * @param command simple name of the command class
 * @param payload schema describing the command's data fields; typically a
 *                {@code Map<String, Object>} whose keys are field names and
 *                whose values are type descriptors (strings for simple types,
 *                nested maps for complex objects, or arrays for generics),
 *                expressed in TypeScript-style type names
 */
public record CommandPayloadDTO(
        String command,
        Object payload
) {
    public CommandPayloadDTO {
        command = command != null ? command : "";
    }
}
