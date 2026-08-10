package com.machingclee.domain.util.common.dto;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Aggregate payload for the command-flow visualizer.
 * <p>
 * {@code factories}/{@code factoryDtos} remain for older UIs; prefer
 * {@code entities}/{@code entityDtos} for the full entity graph (factories,
 * domain methods, relations, and persisted {@code columns}).
 * {@code services}/{@code serviceDtos} power the Services tab
 * ({@code @Service} beans with public method signatures).
 */
@Builder
public record FlowResponseDTO(List<CommandEventFlowDTO> commands,
                              Map<String, PolicyDetailDTO> policies,
                              Map<String, Map<String, Object>> schema,
                              Map<String, Map<String, Object>> dtos,
                              List<QueryFlowDTO> queries,
                              Map<String, Map<String, Object>> queryDtos,
                              List<FactoryMethodDTO> factories,
                              Map<String, Map<String, Object>> factoryDtos,
                              List<EntityNodeDTO> entities,
                              Map<String, Map<String, Object>> entityDtos,
                              List<ServiceNodeDTO> services,
                              Map<String, Map<String, Object>> serviceDtos) {
    public FlowResponseDTO {
        commands = commands != null ? commands : List.of();
        policies = policies != null ? policies : Map.of();
        schema = schema != null ? schema : Map.of();
        dtos = dtos != null ? dtos : Map.of();
        queries = queries != null ? queries : List.of();
        queryDtos = queryDtos != null ? queryDtos : Map.of();
        factories = factories != null ? factories : List.of();
        factoryDtos = factoryDtos != null ? factoryDtos : Map.of();
        entities = entities != null ? entities : List.of();
        entityDtos = entityDtos != null ? entityDtos : Map.of();
        services = services != null ? services : List.of();
        serviceDtos = serviceDtos != null ? serviceDtos : Map.of();
    }

    /**
     * Backward-compatible constructor used by call sites that only supply factories.
     */
    public FlowResponseDTO(List<CommandEventFlowDTO> commands,
                           Map<String, PolicyDetailDTO> policies,
                           Map<String, Map<String, Object>> schema,
                           Map<String, Map<String, Object>> dtos,
                           List<QueryFlowDTO> queries,
                           Map<String, Map<String, Object>> queryDtos,
                           List<FactoryMethodDTO> factories,
                           Map<String, Map<String, Object>> factoryDtos) {
        this(commands, policies, schema, dtos, queries, queryDtos,
                factories, factoryDtos, List.of(), Map.of(), List.of(), Map.of());
    }

    /**
     * Backward-compatible constructor for call sites that supply entities but not services.
     */
    public FlowResponseDTO(List<CommandEventFlowDTO> commands,
                           Map<String, PolicyDetailDTO> policies,
                           Map<String, Map<String, Object>> schema,
                           Map<String, Map<String, Object>> dtos,
                           List<QueryFlowDTO> queries,
                           Map<String, Map<String, Object>> queryDtos,
                           List<FactoryMethodDTO> factories,
                           Map<String, Map<String, Object>> factoryDtos,
                           List<EntityNodeDTO> entities,
                           Map<String, Map<String, Object>> entityDtos) {
        this(commands, policies, schema, dtos, queries, queryDtos,
                factories, factoryDtos, entities, entityDtos, List.of(), Map.of());
    }
}
