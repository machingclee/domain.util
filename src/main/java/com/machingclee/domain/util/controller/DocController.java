package com.machingclee.domain.util.controller;

import com.machingclee.domain.util.common.command.AbstractCommandInvoker;
import com.machingclee.domain.util.common.dto.CommandEventFlowDTO;
import com.machingclee.domain.util.common.dto.EntityMethodDTO;
import com.machingclee.domain.util.common.dto.EntityNodeDTO;
import com.machingclee.domain.util.common.dto.FactoryMethodDTO;
import com.machingclee.domain.util.common.dto.FlowResponseDTO;
import com.machingclee.domain.util.common.dto.PolicyDetailDTO;
import com.machingclee.domain.util.common.dto.QueryFlowDTO;
import com.machingclee.domain.util.common.factory.EntityFactoryService;
import com.machingclee.domain.util.common.factory.EntityGraphService;
import com.machingclee.domain.util.common.query.DefaultQueryInvoker;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/docs")
public class DocController {

    private final List<AbstractCommandInvoker> commandInvokers;
    private final DefaultQueryInvoker queryInvoker;
    private final EntityFactoryService entityFactoryService;
    private final EntityGraphService entityGraphService;

    public DocController(List<AbstractCommandInvoker> commandInvokers,
                         DefaultQueryInvoker queryInvoker,
                         EntityFactoryService entityFactoryService,
                         EntityGraphService entityGraphService) {
        this.commandInvokers = commandInvokers;
        this.queryInvoker = queryInvoker;
        this.entityFactoryService = entityFactoryService;
        this.entityGraphService = entityGraphService;
    }

    private record APIResponseDTO<T>(boolean success, T result) {

        public static <T> APIResponseDTO<T> success(T result) {
            return new APIResponseDTO<>(true, result);
        }
    }

    @GetMapping("/commands")
    APIResponseDTO<Object> getFlows() {
        List<CommandEventFlowDTO> commands = commandInvokers
                .stream()
                .flatMap(f -> f.getFlow().commands().stream())
                .toList();
        Map<String, PolicyDetailDTO> policies = commandInvokers.stream()
                .flatMap(f -> f.getFlow().policies().entrySet().stream())
                .collect(Collectors.toMap(e -> e.getKey(), e -> e.getValue()));
        Map<String, Map<String, Object>> schema = commandInvokers.stream()
                .flatMap(f -> f.getFlow().schema().entrySet().stream())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (existing, replacement) -> existing));
        Map<String, Map<String, Object>> dtos = commandInvokers.stream()
                .flatMap(f -> f.getFlow().dtos().entrySet().stream())
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (existing, replacement) -> existing));

        // Query flows
        List<QueryFlowDTO> queries = queryInvoker != null
                ? queryInvoker.getQueryFlow()
                : List.of();
        Map<String, Map<String, Object>> queryDtos = queryInvoker != null
                ? queryInvoker.getQueryDtos()
                : Map.of();

        // Full entity graph (factories + domain methods + relations)
        List<EntityNodeDTO> entities = entityGraphService != null
                ? entityGraphService.getEntityNodes()
                : List.of();
        Map<String, Map<String, Object>> entityDtos = entityGraphService != null
                ? entityGraphService.getEntityDtos()
                : Map.of();

        // Keep factories/factoryDtos derived from the entity graph for older UIs
        List<FactoryMethodDTO> factories = flattenFactories(entities);
        Map<String, Map<String, Object>> factoryDtos = new LinkedHashMap<>(entityDtos);
        if (factories.isEmpty() && entityFactoryService != null) {
            factories = entityFactoryService.getFactoryMethods();
            factoryDtos.putAll(entityFactoryService.getFactoryDtos());
        }

        var combinedFlows = new FlowResponseDTO(
                commands, policies, schema, dtos, queries, queryDtos,
                factories, factoryDtos, entities, entityDtos);
        return APIResponseDTO.success(combinedFlows);
    }

    private static List<FactoryMethodDTO> flattenFactories(List<EntityNodeDTO> entities) {
        List<FactoryMethodDTO> out = new ArrayList<>();
        for (EntityNodeDTO node : entities) {
            for (EntityMethodDTO m : node.factories()) {
                out.add(new FactoryMethodDTO(node.entityName(), m.methodName(), m.parameters()));
            }
        }
        return out;
    }

    @Operation(summary = "Open command diagram",
            description = "[Open command diagram](/docs/commands/diagram)")
    @GetMapping(value = "/commands/diagram", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<String> getDiagram(HttpServletRequest request) {
        String prefix = request.getHeader("X-Forwarded-Prefix");
        if (prefix == null) {
            prefix = "";
        }
        String visualizationUrl = prefix
                + "/command-visualization/index.html?url=" + prefix + "/docs/commands";
        String html = """
                <!DOCTYPE html>
                <html>
                <body>
                <p><a href="%s" target="_blank">Open command diagram</a></p>
                </body>
                </html>
                """.formatted(visualizationUrl);
        return ResponseEntity.ok(html);
    }
}
