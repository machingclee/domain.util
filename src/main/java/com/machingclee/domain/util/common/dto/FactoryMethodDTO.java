package com.machingclee.domain.util.common.dto;

import java.util.List;
import java.util.Map;

/**
 * Describes a single entity factory method for the event-storming graph.
 * <p>
 * Entity classes annotated with {@code @NoArgsConstructor(access = AccessLevel.PROTECTED)}
 * expose public static factory methods. This DTO captures the method name, its
 * parameter schema, and the entity it creates.
 *
 * @param entityName simple name of the entity class (e.g. "BookingScheduleLink")
 * @param methodName the factory method name (e.g. "createComplete")
 * @param parameters parameter-name → TypeScript-style type descriptor (e.g.
 *                   {@code {"param": "CreateCompleteForRegisteredParam"}});
 *                   nested types are registered in {@code factoryDtos}
 */
public record FactoryMethodDTO(
        String entityName,
        String methodName,
        Map<String, Object> parameters
) {
    public FactoryMethodDTO {
        entityName = entityName != null ? entityName : "";
        methodName = methodName != null ? methodName : "";
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
    }
}
