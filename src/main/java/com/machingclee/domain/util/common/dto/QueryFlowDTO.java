package com.machingclee.domain.util.common.dto;

import java.util.List;

/**
 * Describes a query's place in the event-storming graph, optionally enriched
 * with the HTTP endpoint that invokes it (when one exists).
 *
 * @param from             query name + TypeScript-style payload schema
 * @param result           schema of the return type (same flattened form as
 *                         event payloads)
 * @param context          {@link com.machingclee.domain.util.annotation.BoundedContext}
 *                         value on the query class
 * @param actors           {@link com.machingclee.domain.util.annotation.Actor} values
 * @param httpMethod       GET/POST, or empty if not HTTP-triggered
 * @param path             full request path, or empty if not HTTP-triggered
 * @param summary          OpenAPI {@code @Operation.summary}, or empty
 * @param description      OpenAPI {@code @Operation.description}, or empty
 * @param roles            authorized role names from controller
 */
public record QueryFlowDTO(
        QueryPayloadDTO from,
        QueryPayloadDTO result,
        String context,
        List<String> actors,
        String httpMethod,
        String path,
        String summary,
        String description,
        List<String> roles
) {
    public QueryFlowDTO {
        from = from != null ? from : new QueryPayloadDTO("", java.util.Map.of());
        result = result != null ? result : new QueryPayloadDTO("", java.util.Map.of());
        context = context != null ? context : "";
        actors = actors != null ? List.copyOf(actors) : List.of();
        httpMethod = httpMethod != null ? httpMethod : "";
        path = path != null ? path : "";
        summary = summary != null ? summary : "";
        description = description != null ? description : "";
        roles = roles != null ? List.copyOf(roles) : List.of();
    }
}
