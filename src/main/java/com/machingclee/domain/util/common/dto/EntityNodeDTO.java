package com.machingclee.domain.util.common.dto;

import java.util.List;

/**
 * One JPA entity in the event-storming entity graph: factories, domain behaviour,
 * and association edges to other entities.
 * <p>
 * Grouped by {@link #context()} (from {@code @BoundedContext} on the entity, or
 * {@code "default"} when absent).
 *
 * @param entityName     readable entity class name
 * @param context        bounded context label
 * @param factories      public static methods that return this entity type
 * @param domainMethods  public instance behaviour methods (non-accessor)
 * @param relations      JPA associations declared on this entity
 */
public record EntityNodeDTO(
        String entityName,
        String context,
        List<EntityMethodDTO> factories,
        List<EntityMethodDTO> domainMethods,
        List<EntityRelationDTO> relations
) {
    public EntityNodeDTO {
        entityName = entityName != null ? entityName : "";
        context = (context != null && !context.isBlank()) ? context : "default";
        factories = factories != null ? List.copyOf(factories) : List.of();
        domainMethods = domainMethods != null ? List.copyOf(domainMethods) : List.of();
        relations = relations != null ? List.copyOf(relations) : List.of();
    }
}
