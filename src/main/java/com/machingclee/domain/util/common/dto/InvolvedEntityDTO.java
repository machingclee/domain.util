package com.machingclee.domain.util.common.dto;

import java.util.List;

/**
 * A JPA entity potentially modified by a command, with related entity types
 * that the handler bytecode actually references.
 *
 * @param entity      simple name of a directly {@code save*}'d entity
 * @param childEntity simple names of related types ({@code @OneToOne},
 *                    {@code @OneToMany}, {@code @ManyToOne}) referenced in
 *                    the handler bytecode; empty when none
 */
public record InvolvedEntityDTO(
        String entity,
        List<String> childEntity
) {
    public InvolvedEntityDTO {
        entity = entity != null ? entity : "";
        childEntity = childEntity != null ? List.copyOf(childEntity) : List.of();
    }
}
