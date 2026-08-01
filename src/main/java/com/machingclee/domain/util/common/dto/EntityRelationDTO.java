package com.machingclee.domain.util.common.dto;

/**
 * A JPA association declared on an entity, for the entity-graph visualizer.
 *
 * @param fieldName       Java field name of the association
 * @param targetEntity    simple/readable name of the related entity
 * @param type            {@code ONE_TO_ONE}, {@code ONE_TO_MANY}, {@code MANY_TO_ONE},
 *                        or {@code MANY_TO_MANY}
 * @param mappedBy        non-empty when this side is inverse ({@code mappedBy = "..."})
 * @param owningSide      {@code true} when this side owns the FK / join table
 * @param insertable      {@code @JoinColumn.insertable} when present; otherwise {@code null}
 * @param updatable       {@code @JoinColumn.updatable} when present; otherwise {@code null}
 * @param extensionChild  {@code true} when this side is a 1–1 polymorphic / secondary-table
 *                        extension child of {@code targetEntity} (child owns FK toward parent)
 */
public record EntityRelationDTO(
        String fieldName,
        String targetEntity,
        String type,
        String mappedBy,
        boolean owningSide,
        Boolean insertable,
        Boolean updatable,
        boolean extensionChild
) {
    public EntityRelationDTO {
        fieldName = fieldName != null ? fieldName : "";
        targetEntity = targetEntity != null ? targetEntity : "";
        type = type != null ? type : "";
        mappedBy = mappedBy != null ? mappedBy : "";
    }

    /**
     * Backward-compatible constructor for callers that do not yet supply JoinColumn meta.
     */
    public EntityRelationDTO(
            String fieldName,
            String targetEntity,
            String type,
            String mappedBy,
            boolean owningSide
    ) {
        this(fieldName, targetEntity, type, mappedBy, owningSide, null, null, false);
    }
}
