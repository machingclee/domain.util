package com.machingclee.domain.util.common.dto;

import java.util.Map;

/**
 * A factory or domain method exposed on an entity node in the entity graph.
 *
 * @param methodName  method name (e.g. {@code createIncomplete}, {@code clearUserLinks})
 * @param parameters  parameter-name → TypeScript-style type descriptor
 * @param returnType  readable return type (e.g. {@code BookingScheduleLink}, {@code void})
 * @param factory     {@code true} for public static factories that return the entity itself
 */
public record EntityMethodDTO(
        String methodName,
        Map<String, Object> parameters,
        String returnType,
        boolean factory
) {
    public EntityMethodDTO {
        methodName = methodName != null ? methodName : "";
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        returnType = returnType != null ? returnType : "void";
    }
}
