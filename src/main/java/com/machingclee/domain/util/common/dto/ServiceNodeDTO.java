package com.machingclee.domain.util.common.dto;

import java.util.List;

/**
 * One Spring {@code @Service} bean in the event-storming Services tab.
 * <p>
 * Grouped by {@link #context()} (from {@code @BoundedContext} on the class or
 * package, or {@code "default"} when absent).
 *
 * @param serviceName readable service class name
 * @param context     bounded context label
 * @param methods     public methods (instance + static); private methods omitted
 */
public record ServiceNodeDTO(
        String serviceName,
        String context,
        List<ServiceMethodDTO> methods
) {
    public ServiceNodeDTO {
        serviceName = serviceName != null ? serviceName : "";
        context = (context != null && !context.isBlank()) ? context : "default";
        methods = methods != null ? List.copyOf(methods) : List.of();
    }
}
