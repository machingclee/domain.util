package com.machingclee.domain.util.common.dto;

import java.util.Map;

/**
 * A public method on a Spring {@code @Service} bean for the Services tab.
 *
 * @param methodName  method name (e.g. {@code selectTimeslotForLink})
 * @param parameters  parameter-name → TypeScript-style type descriptor (insertion order)
 * @param returnType  readable return type (e.g. {@code SelectTimeslotResult}, {@code void})
 * @param signature   full display signature, e.g.
 *                    {@code SelectTimeslotResult selectTimeslotForLink(String token, Integer timeslotOptionId)}
 */
public record ServiceMethodDTO(
        String methodName,
        Map<String, Object> parameters,
        String returnType,
        String signature
) {
    public ServiceMethodDTO {
        methodName = methodName != null ? methodName : "";
        parameters = parameters != null ? Map.copyOf(parameters) : Map.of();
        returnType = returnType != null ? returnType : "void";
        signature = signature != null ? signature : "";
    }
}
