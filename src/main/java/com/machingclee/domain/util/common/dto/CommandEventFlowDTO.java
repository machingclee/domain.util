package com.machingclee.domain.util.common.dto;

import com.machingclee.domain.util.annotation.Actor;
import com.machingclee.domain.util.annotation.BoundedContext;

import java.util.List;
import java.util.Map;

/**
 * Describes a command's place in the event-storming graph, optionally enriched
 * with the HTTP endpoint that invokes it (when one exists).
 *
 * @param from             command name + TypeScript-style payload schema
 *                         (same shape as {@link EventPayloadDTO}, with
 *                         {@code command} instead of {@code event})
 * @param to               domain events produced by the handler, each with its
 *                         name and the schema of the DTO payload it carries
 * @param context          {@link BoundedContext} value on the command
 * @param actors           {@link Actor} values on the command (who can initiate it)
 * @param httpMethod       GET/POST/PUT/DELETE/PATCH, or empty if not HTTP-triggered
 * @param path             full request path, or empty if not HTTP-triggered
 * @param summary          OpenAPI {@code @Operation.summary}, or empty if absent
 * @param description      OpenAPI {@code @Operation.description}, or empty if absent
 * @param roles            authorized role names from the host auth annotation
 *                         configured by {@code domain-util.docs.auth-annotation}
 *                         (method-level preferred over class-level); empty when none
 * @param involvedEntities entities {@code save*}'d by the handler, each with
 *                         related child types actually referenced in bytecode
 */
public record CommandEventFlowDTO(
        CommandPayloadDTO from,
        List<EventPayloadDTO> to,
        String context,
        List<String> actors,
        String httpMethod,
        String path,
        String summary,
        String description,
        List<String> roles,
        List<InvolvedEntityDTO> involvedEntities
) {

    public CommandEventFlowDTO(
            CommandPayloadDTO from,
            List<EventPayloadDTO> to,
            String context,
            List<String> actors,
            String httpMethod,
            String path,
            String summary,
            String description,
            List<String> roles,
            List<InvolvedEntityDTO> involvedEntities
    ) {
        this.from = from != null ? from : new CommandPayloadDTO("", Map.of());
        this.to = to != null ? List.copyOf(to) : List.of();
        this.context = context != null ? context : "";
        this.actors = actors != null ? List.copyOf(actors) : List.of();
        this.httpMethod = httpMethod != null ? httpMethod : "";
        this.path = path != null ? path : "";
        this.summary = summary != null ? summary : "";
        this.description = description != null ? description : "";
        this.roles = roles != null ? List.copyOf(roles) : List.of();
        this.involvedEntities = involvedEntities != null ? List.copyOf(involvedEntities) : List.of();
    }
}
