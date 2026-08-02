package com.machingclee.domain.util.common.dto;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One JPA entity in the event-storming entity graph: factories, domain behaviour,
 * association edges to other entities, and persisted column schema.
 * <p>
 * Grouped by {@link #context()} (from {@code @BoundedContext} on the entity, or
 * {@code "default"} when absent).
 *
 * @param entityName     readable entity class name
 * @param context        bounded context label
 * @param factories      public static methods that return this entity type
 * @param domainMethods  public instance behaviour methods (non-accessor)
 * @param relations      JPA associations declared on this entity
 * @param columns        persisted column schema ({@code @Column}, {@code @JoinColumn},
 *                       {@code @Id}) as physical DB column name → TypeScript-style type
 *                       descriptor (keys from {@code name=...}, not Java property names).
 *                       Insertion order is preserved ({@link LinkedHashMap}); {@code @Id}
 *                       columns are emitted first by the scanner.
 */
public record EntityNodeDTO(
        String entityName,
        String context,
        List<EntityMethodDTO> factories,
        List<EntityMethodDTO> domainMethods,
        List<EntityRelationDTO> relations,
        Map<String, Object> columns
) {
    public EntityNodeDTO {
        entityName = entityName != null ? entityName : "";
        context = (context != null && !context.isBlank()) ? context : "default";
        factories = factories != null ? List.copyOf(factories) : List.of();
        domainMethods = domainMethods != null ? List.copyOf(domainMethods) : List.of();
        relations = relations != null ? List.copyOf(relations) : List.of();
        // LinkedHashMap keeps scanner order (id first). Map.copyOf() does not preserve order.
        columns = columns != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(columns))
                : Map.of();
    }

    /**
     * Backward-compatible constructor for callers that do not yet supply columns.
     */
    public EntityNodeDTO(
            String entityName,
            String context,
            List<EntityMethodDTO> factories,
            List<EntityMethodDTO> domainMethods,
            List<EntityRelationDTO> relations
    ) {
        this(entityName, context, factories, domainMethods, relations, Map.of());
    }
}
