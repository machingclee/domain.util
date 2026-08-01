package com.machingclee.domain.util.common.dto;

/**
 * Describes a query and the schema of the DTO payload it carries.
 * Mirrors {@link CommandPayloadDTO} — same representation, with {@code query}
 * instead of {@code command}.
 *
 * @param query   simple name of the query class
 * @param payload schema describing the query's data fields; TypeScript-style
 *                type descriptors
 */
public record QueryPayloadDTO(
        String query,
        Object payload
) {
    public QueryPayloadDTO {
        query = query != null ? query : "";
    }
}
