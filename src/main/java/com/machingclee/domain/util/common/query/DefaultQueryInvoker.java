package com.machingclee.domain.util.common.query;

import com.machingclee.domain.util.annotation.Actor;
import com.machingclee.domain.util.annotation.BoundedContext;
import com.machingclee.domain.util.common.MdcContextKeys;
import com.machingclee.domain.util.common.bytecodescanner.ControllerQueryScanner;
import com.machingclee.domain.util.common.bytecodescanner.EventTypeScanner;
import com.machingclee.domain.util.common.dto.QueryFlowDTO;
import com.machingclee.domain.util.common.dto.QueryPayloadDTO;
import com.machingclee.domain.util.common.query.interfaces.Query;
import com.machingclee.domain.util.common.query.interfaces.QueryHandler;
import com.machingclee.domain.util.common.query.interfaces.QueryInvoker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

public class DefaultQueryInvoker implements QueryInvoker {

    private static final Logger logger = LoggerFactory.getLogger(DefaultQueryInvoker.class);

    private final Map<Class<?>, QueryHandler<?, ?>> handlerMap;
    private final ApplicationContext context;

    /** Nested DTO field schemas for query side. */
    private final Map<String, Map<String, Object>> queryDtoRegistry = new LinkedHashMap<>();

    public DefaultQueryInvoker(List<QueryHandler<?, ?>> queryHandlers,
                               ApplicationContext context) {
        this.context = context;
        this.handlerMap = buildHandlerMap(queryHandlers);
    }

    private Map<Class<?>, QueryHandler<?, ?>> buildHandlerMap(List<QueryHandler<?, ?>> queryHandlers) {
        Map<Class<?>, QueryHandler<?, ?>> map = new HashMap<>();

        for (QueryHandler<?, ?> handler : queryHandlers) {
            Class<?> queryClass = extractQueryClass(handler);
            if (queryClass != null) {
                if (map.containsKey(queryClass)) {
                    throw new IllegalStateException(
                            "Multiple handlers found for query: " + queryClass.getSimpleName());
                }
                map.put(queryClass, handler);
                logger.info("Registered query handler: {} for {}",
                        handler.getClass().getSimpleName(), queryClass.getSimpleName());
            } else {
                logger.warn("Could not determine query type for handler: {}",
                        handler.getClass().getSimpleName());
            }
        }
        return map;
    }

    /**
     * Build the list of query flow DTOs for the visualizer.
     * Each handler is scanned once: payload schema from query fields,
     * result schema from the return type, endpoint info from controllers.
     */
    public List<QueryFlowDTO> getQueryFlow() {
        if (handlerMap.isEmpty()) return List.of();

        queryDtoRegistry.clear();
        Map<String, ControllerQueryScanner.EndpointInfo> endpointMap =
                ControllerQueryScanner.scanEndpoints(context);

        List<QueryFlowDTO> result = new ArrayList<>();

        for (Map.Entry<Class<?>, QueryHandler<?, ?>> entry : handlerMap.entrySet()) {
            Class<?> queryClass = entry.getKey();
            QueryHandler<?, ?> handler = entry.getValue();

            // Result schema from Query<R> return type
            Class<?> returnType = extractReturnType(handler);
            Map<String, Object> resultSchema = returnType != null
                    ? EventTypeScanner.buildPayloadSchema(returnType, queryDtoRegistry)
                    : Map.of();

            String resultTypeName = returnType != null
                    ? getReadableTypeName(returnType) : "void";
            QueryPayloadDTO res = new QueryPayloadDTO(
                    resultTypeName, resultSchema);

            BoundedContext ctxAnno = queryClass.getAnnotation(BoundedContext.class);
            String context = ctxAnno != null ? ctxAnno.value() : "";

            Actor actorAnno = queryClass.getAnnotation(Actor.class);
            List<String> actors = actorAnno != null
                    ? List.of(actorAnno.value()) : List.of();

            ControllerQueryScanner.EndpointInfo ep =
                    endpointMap.get(queryClass.getSimpleName());
            String httpMethod = ep != null ? ep.httpMethod() : "";
            String path = ep != null ? ep.path() : "";
            String summary = ep != null ? ep.summary() : "";
            String description = ep != null ? ep.description() : "";
            List<String> roles = ep != null ? ep.roles() : List.of();

            // Build the "from" (input) payload.
            // When the controller method declares a @RequestBody parameter,
            // use that DTO's schema — it is what the API consumer actually sends.
            // Otherwise fall back to the query class fields (e.g. GET with query params).
            QueryPayloadDTO from;
            String requestBodyClassName = ep != null ? ep.requestBodyClassName() : null;
            if (requestBodyClassName != null) {
                Class<?> bodyClass = resolveClass(requestBodyClassName);
                if (bodyClass != null) {
                    Map<String, Object> bodySchema =
                            EventTypeScanner.buildPayloadSchema(bodyClass, queryDtoRegistry);
                    String bodyReadableName = EventTypeScanner.getReadableClassName(bodyClass);
                    from = new QueryPayloadDTO(bodyReadableName, bodySchema);
                } else {
                    // Fallback: unresolved body class, use query schema
                    Map<String, Object> payloadSchema =
                            EventTypeScanner.buildPayloadSchema(queryClass, queryDtoRegistry);
                    from = new QueryPayloadDTO(queryClass.getSimpleName(), payloadSchema);
                }
            } else {
                Map<String, Object> payloadSchema =
                        EventTypeScanner.buildPayloadSchema(queryClass, queryDtoRegistry);
                from = new QueryPayloadDTO(queryClass.getSimpleName(), payloadSchema);
            }

            result.add(new QueryFlowDTO(
                    from, res, context, actors,
                    httpMethod, path, summary, description, roles));
        }

        return result;
    }

    /** Return the nested DTO schemas collected during payload scanning. */
    public Map<String, Map<String, Object>> getQueryDtos() {
        return new LinkedHashMap<>(queryDtoRegistry);
    }

    // ── helpers ──

    /**
     * Resolve a class by its fully-qualified name through the Spring application
     * context's class loader. Returns {@code null} if the class cannot be loaded.
     */
    private Class<?> resolveClass(String className) {
        try {
            ClassLoader cl = context.getClassLoader();
            return Class.forName(className, false, cl);
        } catch (ClassNotFoundException e) {
            logger.warn("Cannot resolve request body class: {}", className);
            return null;
        }
    }

    private Class<?> extractQueryClass(QueryHandler<?, ?> handler) {
        Class<?> targetClass = AopUtils.getTargetClass(handler);
        Type[] genericInterfaces = targetClass.getGenericInterfaces();

        for (Type genericInterface : genericInterfaces) {
            if (genericInterface instanceof ParameterizedType pt
                    && pt.getRawType() == QueryHandler.class) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length > 0 && args[0] instanceof Class<?> clazz) {
                    return clazz;
                }
            }
        }
        return null;
    }

    private Class<?> extractReturnType(QueryHandler<?, ?> handler) {
        Class<?> targetClass = AopUtils.getTargetClass(handler);
        Type[] genericInterfaces = targetClass.getGenericInterfaces();

        for (Type genericInterface : genericInterfaces) {
            if (genericInterface instanceof ParameterizedType pt
                    && pt.getRawType() == QueryHandler.class) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length > 1) {
                    return unwrapType(args[1]);
                }
            }
        }
        return null;
    }

    /**
     * Unwrap a type to find the concrete element class. For parameterized types
     * like {@code List<FooDTO>}, this drills into the first type argument to
     * return {@code FooDTO} so that the schema is built from the DTO fields
     * rather than from the wrapper type.
     */
    private Class<?> unwrapType(Type type) {
        if (type instanceof Class<?> cls) return cls;
        if (type instanceof ParameterizedType pt) {
            Type[] typeArgs = pt.getActualTypeArguments();
            if (typeArgs.length > 0) {
                return unwrapType(typeArgs[0]);
            }
            // Fallback: raw type with no type arguments
            Type raw = pt.getRawType();
            if (raw instanceof Class<?> rc) return rc;
        }
        return null;
    }

    /**
     * Human-readable type name, e.g. "BookingScheduleLink.DTO" or "List<Foo>".
     * Mirrors the naming convention used by EventTypeScanner.getReadableClassName.
     */
    static String getReadableTypeName(Class<?> cls) {
        if (cls == null) return "void";
        String name = cls.getSimpleName();
        // For inner classes, prefix with enclosing class
        if (cls.getEnclosingClass() != null) {
            return getReadableTypeName(cls.getEnclosingClass()) + "." + name;
        }
        return name;
    }

    // ── invoke ──

    @Override
    @SuppressWarnings("unchecked")
    public <R> R invoke(Query<R> query) throws Exception {
        String existingRequestId = MDC.get(MdcContextKeys.REQUEST_ID);
        String requestId = existingRequestId != null ? existingRequestId : UUID.randomUUID().toString();

        if (existingRequestId == null) {
            MDC.put(MdcContextKeys.REQUEST_ID, requestId);
        }

        try {
            QueryHandler<Query<R>, R> handler = (QueryHandler<Query<R>, R>) handlerMap.get(query.getClass());
            if (handler == null) {
                String available = handlerMap.keySet().stream()
                        .map(Class::getSimpleName)
                        .collect(Collectors.joining(", "));
                throw new IllegalArgumentException(
                        "No handler found for query: " + query.getClass().getSimpleName() +
                                ". Available handlers: [" + available + "]");
            }

            logger.debug("Executing query: {} with requestId: {}", query.getClass().getSimpleName(), requestId);
            R result = handler.handle(query);
            logger.debug("Query completed: {}", query.getClass().getSimpleName());
            return result;
        } catch (Exception e) {
            logger.error("Query failed: {}, error: {}", query.getClass().getSimpleName(), e.getMessage(), e);
            throw e;
        } finally {
            if (existingRequestId == null) {
                MDC.remove(MdcContextKeys.REQUEST_ID);
            }
        }
    }
}
