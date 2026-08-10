package com.machingclee.domain.util.common.factory;

import com.machingclee.domain.util.annotation.BoundedContext;
import com.machingclee.domain.util.common.bytecodescanner.EventTypeScanner;
import com.machingclee.domain.util.common.dto.ServiceMethodDTO;
import com.machingclee.domain.util.common.dto.ServiceNodeDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Scans Spring {@link Service @Service} beans into {@link ServiceNodeDTO} nodes
 * for the event-storming visualizer Services tab.
 * <p>
 * Each node lists public method signatures
 * ({@code ReturnType methodName(ParamType paramName, ...)}). Private methods are
 * omitted. Grouped by {@link BoundedContext} (class or package) or {@code "default"}.
 */
public class ServiceGraphService {

    private static final Logger logger = LoggerFactory.getLogger(ServiceGraphService.class);

    private final ApplicationContext context;

    private final Map<String, Map<String, Object>> serviceDtos = new LinkedHashMap<>();

    public ServiceGraphService(ApplicationContext context) {
        this.context = context;
    }

    /**
     * Nested DTO field schemas collected while scanning method parameters.
     * Keyed by readable type name.
     */
    public Map<String, Map<String, Object>> getServiceDtos() {
        return new LinkedHashMap<>(serviceDtos);
    }

    /**
     * Scan every Spring bean annotated with {@link Service} into a
     * {@link ServiceNodeDTO}. Framework / non-application beans are skipped.
     */
    public List<ServiceNodeDTO> getServiceNodes() {
        serviceDtos.clear();
        List<ServiceNodeDTO> result = new ArrayList<>();
        Set<String> seenClasses = new LinkedHashSet<>();

        Map<String, Object> beans;
        try {
            beans = context.getBeansWithAnnotation(Service.class);
        } catch (Exception e) {
            logger.warn("Failed to list @Service beans: {}", e.getMessage());
            return List.of();
        }

        logger.info("Scanning {} @Service bean(s) for service graph", beans.size());

        for (Map.Entry<String, Object> entry : beans.entrySet()) {
            Object bean = entry.getValue();
            if (bean == null) continue;

            Class<?> serviceClass = AopUtils.getTargetClass(bean);
            if (serviceClass == null) continue;
            if (!seenClasses.add(serviceClass.getName())) continue;
            if (shouldSkip(serviceClass)) continue;

            String serviceName = EventTypeScanner.getReadableClassName(serviceClass);
            String contextName = resolveContext(serviceClass);
            List<ServiceMethodDTO> methods = scanPublicMethods(serviceClass);

            result.add(new ServiceNodeDTO(serviceName, contextName, methods));
            logger.debug("Service node {}: methods={}", serviceName, methods.size());
        }

        result.sort((a, b) -> {
            int c = a.context().compareToIgnoreCase(b.context());
            if (c != 0) return c;
            return a.serviceName().compareToIgnoreCase(b.serviceName());
        });

        logger.info("Service graph scan complete: {} service node(s)", result.size());
        return result;
    }

    /**
     * Skip Spring / infra beans that are not domain application services.
     */
    private static boolean shouldSkip(Class<?> cls) {
        if (cls == null) return true;
        String name = cls.getName();
        if (name.startsWith("org.springframework.")) return true;
        if (name.startsWith("org.hibernate.")) return true;
        if (name.startsWith("com.zaxxer.")) return true;
        // Generated proxies without a useful simple name
        if (name.contains("$$") && cls.getSimpleName().isEmpty()) return true;
        return false;
    }

    /**
     * Public methods declared on the service type hierarchy (excluding Object).
     * Private / package-private / protected methods are not listed.
     */
    private List<ServiceMethodDTO> scanPublicMethods(Class<?> serviceClass) {
        List<ServiceMethodDTO> methods = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        // getMethods() = public including inherited; filter Object + noise
        for (Method method : serviceClass.getMethods()) {
            if (method.getDeclaringClass() == Object.class) continue;
            int mods = method.getModifiers();
            if (!Modifier.isPublic(mods)) continue;
            if (method.isSynthetic() || method.isBridge()) continue;
            if (isObjectOverride(method)) continue;
            // Skip Spring lifecycle / infrastructure hooks if present
            if (isInfrastructureMethod(method)) continue;

            String signatureKey = methodSignatureKey(method);
            if (!seen.add(signatureKey)) continue;

            methods.add(toMethodDto(method));
        }

        methods.sort((a, b) -> a.methodName().compareToIgnoreCase(b.methodName()));
        return methods;
    }

    private static boolean isObjectOverride(Method method) {
        String name = method.getName();
        int params = method.getParameterCount();
        if ("toString".equals(name) && params == 0) return true;
        if ("hashCode".equals(name) && params == 0) return true;
        if ("equals".equals(name) && params == 1) return true;
        if ("canEqual".equals(name)) return true;
        if ("clone".equals(name) && params == 0) return true;
        if ("finalize".equals(name) && params == 0) return true;
        return false;
    }

    private static boolean isInfrastructureMethod(Method method) {
        String name = method.getName();
        // Common Spring / bean lifecycle
        if ("afterPropertiesSet".equals(name) && method.getParameterCount() == 0) return true;
        if ("destroy".equals(name) && method.getParameterCount() == 0) return true;
        if ("setApplicationContext".equals(name)) return true;
        if ("setBeanFactory".equals(name)) return true;
        if ("setBeanName".equals(name)) return true;
        return false;
    }

    private static String methodSignatureKey(Method method) {
        StringBuilder sb = new StringBuilder(method.getName());
        sb.append('(');
        for (Class<?> p : method.getParameterTypes()) {
            sb.append(p.getName()).append(',');
        }
        sb.append(')');
        return sb.toString();
    }

    private ServiceMethodDTO toMethodDto(Method method) {
        Map<String, Object> paramSchema = buildParamSchema(method);
        String returnType = EventTypeScanner.describeTypeName(
                method.getGenericReturnType(), null);
        if (method.getReturnType() == void.class || method.getReturnType() == Void.class) {
            returnType = "void";
        }
        String signature = formatSignature(returnType, method.getName(), method);
        return new ServiceMethodDTO(method.getName(), paramSchema, returnType, signature);
    }

    /**
     * {@code ReturnType methodName(Type0 name0, Type1 name1)}
     */
    private static String formatSignature(String returnType, String methodName, Method method) {
        StringBuilder sb = new StringBuilder();
        sb.append(returnType).append(' ').append(methodName).append('(');
        Parameter[] parameters = method.getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) sb.append(", ");
            Parameter param = parameters[i];
            String typeName = EventTypeScanner.describeTypeName(
                    param.getParameterizedType(), null);
            String paramName = resolveParamName(param, i);
            sb.append(typeName).append(' ').append(paramName);
        }
        sb.append(')');
        return sb.toString();
    }

    private Map<String, Object> buildParamSchema(Method method) {
        Map<String, Object> schema = new LinkedHashMap<>();
        Parameter[] parameters = method.getParameters();
        for (Parameter param : parameters) {
            String paramName = resolveParamName(param, schema.size());
            String typeName = EventTypeScanner.describeTypeName(
                    param.getParameterizedType(), null);

            Class<?> paramClass = resolveConcreteClass(param.getParameterizedType());
            if (paramClass != null && !isBuiltinOrSimple(paramClass)) {
                String readableName = EventTypeScanner.getReadableClassName(paramClass);
                if (!serviceDtos.containsKey(readableName)) {
                    Map<String, Object> fields = EventTypeScanner.buildPayloadSchema(
                            paramClass, null);
                    serviceDtos.put(readableName, fields);
                }
            }

            schema.put(paramName, typeName);
        }
        return schema;
    }

    private static Class<?> resolveConcreteClass(Type type) {
        if (type instanceof Class<?> cls) return cls;
        if (type instanceof ParameterizedType pt) {
            Type raw = pt.getRawType();
            if (raw instanceof Class<?> rc) return rc;
        }
        return null;
    }

    private static boolean isBuiltinOrSimple(Class<?> cls) {
        if (cls.isPrimitive() || cls.isArray() || cls.isEnum()) return true;
        String name = cls.getName();
        return name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("jakarta.")
                || name.startsWith("kotlin.")
                || name.startsWith("scala.");
    }

    private static String resolveParamName(Parameter param, int fallbackIdx) {
        if (param.isNamePresent()) {
            String name = param.getName();
            if (name != null && !name.isEmpty()) return name;
        }
        return "arg" + fallbackIdx;
    }

    private static String resolveContext(Class<?> serviceClass) {
        BoundedContext onClass = serviceClass.getAnnotation(BoundedContext.class);
        if (onClass != null && !onClass.value().isBlank()) {
            return onClass.value().trim();
        }
        Package pkg = serviceClass.getPackage();
        if (pkg != null) {
            BoundedContext onPkg = pkg.getAnnotation(BoundedContext.class);
            if (onPkg != null && !onPkg.value().isBlank()) {
                return onPkg.value().trim();
            }
        }
        return "default";
    }
}
