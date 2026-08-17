package com.machingclee.domain.util.common.bytecodescanner;

import io.swagger.v3.oas.annotations.Operation;
import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * Uses reflection + ASM bytecode scanning to detect which HTTP endpoints
 * invoke which Queries via {@code queryInvoker.invoke(new XxxQuery())}.
 * <p>
 * Mirrors {@link ControllerCommandScanner}, adapted for the Query side.
 */
public final class ControllerQueryScanner {

    private static final Logger logger = LoggerFactory.getLogger(ControllerQueryScanner.class);

    private ControllerQueryScanner() {
    }

    public record EndpointInfo(
            String httpMethod,
            String path,
            String summary,
            String description,
            List<String> roles,
            String requestBodyClassName
    ) {
        public EndpointInfo {
            roles = roles != null ? List.copyOf(roles) : List.of();
            requestBodyClassName = requestBodyClassName != null && !requestBodyClassName.isEmpty()
                    ? requestBodyClassName : null;
        }
    }

    /**
     * Scan all {@code @RestController} / {@code @Controller} beans and return
     * query simple name → endpoint info.
     */
    public static Map<String, EndpointInfo> scanEndpoints(ApplicationContext context) {
        Map<String, EndpointInfo> result = new LinkedHashMap<>();

        Map<String, Object> controllers = new LinkedHashMap<>();
        controllers.putAll(context.getBeansWithAnnotation(RestController.class));
        controllers.putAll(context.getBeansWithAnnotation(Controller.class));

        AuthRoleAnnotationConfig authConfig = AuthRoleAnnotationConfig.from(context);
        for (Object controller : controllers.values()) {
            Class<?> targetClass = AopUtils.getTargetClass(controller);
            scanController(targetClass, result, authConfig);
        }
        return result;
    }

    private static void scanController(Class<?> targetClass,
                                        Map<String, EndpointInfo> result,
                                        AuthRoleAnnotationConfig authConfig) {
        String classPath = extractClassMappingPath(targetClass);

        Map<String, MethodEndpointMeta> methodMappings = new LinkedHashMap<>();
        for (Method method : targetClass.getDeclaredMethods()) {
            MethodEndpointMeta meta = extractMethodMappingInfo(method, targetClass, authConfig);
            if (meta != null) {
                methodMappings.put(method.getName(), meta);
            }
        }

        if (methodMappings.isEmpty()) return;

        // ASM scan for queryInvoker.invoke(new XxxQuery()) calls
        Map<String, Set<String>> methodQueries = scanQueryInvokeCalls(
                targetClass, methodMappings.keySet());

        for (Map.Entry<String, MethodEndpointMeta> entry : methodMappings.entrySet()) {
            String methodName = entry.getKey();
            MethodEndpointMeta meta = entry.getValue();
            Set<String> queries = methodQueries.getOrDefault(methodName, Set.of());

            for (String qInternalName : queries) {
                String qSimpleName = simpleName(qInternalName);
                String fullPath = joinPaths(classPath, meta.path);
                if (!result.containsKey(qSimpleName)) {
                    result.put(qSimpleName, new EndpointInfo(
                            meta.httpMethod, fullPath,
                            meta.summary, meta.description, meta.roles,
                            meta.requestBodyClassName));
                }
            }
        }
    }

    // ── Reflection helpers (same as ControllerCommandScanner) ──

    static String extractClassMappingPath(Class<?> clazz) {
        RequestMapping rm = clazz.getAnnotation(RequestMapping.class);
        if (rm != null && rm.value().length > 0) {
            String path = rm.value()[0];
            return path.isEmpty() ? "" : (path.startsWith("/") ? path : "/" + path);
        }
        return "";
    }

    static MethodEndpointMeta extractMethodMappingInfo(Method method, Class<?> controllerClass,
            AuthRoleAnnotationConfig authConfig) {
        String httpMethod = null;
        String path = null;

        GetMapping gm = method.getAnnotation(GetMapping.class);
        if (gm != null) { httpMethod = "GET"; path = firstPath(gm.value(), gm.path()); }
        if (httpMethod == null) { PostMapping pm = method.getAnnotation(PostMapping.class);
            if (pm != null) { httpMethod = "POST"; path = firstPath(pm.value(), pm.path()); } }
        if (httpMethod == null) { PutMapping pu = method.getAnnotation(PutMapping.class);
            if (pu != null) { httpMethod = "PUT"; path = firstPath(pu.value(), pu.path()); } }
        if (httpMethod == null) { DeleteMapping dm = method.getAnnotation(DeleteMapping.class);
            if (dm != null) { httpMethod = "DELETE"; path = firstPath(dm.value(), dm.path()); } }
        if (httpMethod == null) { PatchMapping patch = method.getAnnotation(PatchMapping.class);
            if (patch != null) { httpMethod = "PATCH"; path = firstPath(patch.value(), patch.path()); } }
        if (httpMethod == null) { RequestMapping rm = method.getAnnotation(RequestMapping.class);
            if (rm != null) {
                var methods = rm.method();
                httpMethod = methods.length > 0 ? methods[0].name() : "REQUEST";
                path = firstPath(rm.value(), rm.path());
            } }

        if (httpMethod == null) return null;

        Operation operation = method.getAnnotation(Operation.class);
        String summary = operation != null ? nullToEmpty(operation.summary()) : "";
        String description = operation != null ? nullToEmpty(operation.description()) : "";
        List<String> roles = AuthRoleExtractor.extract(method, controllerClass, authConfig);

        // Extract @RequestBody parameter class name (if any)
        String requestBodyClassName = null;
        for (Parameter param : method.getParameters()) {
            if (param.isAnnotationPresent(RequestBody.class)) {
                Class<?> paramType = param.getType();
                // Store fully-qualified class name for resolving later
                requestBodyClassName = paramType.getName();
                break;
            }
        }

        return new MethodEndpointMeta(httpMethod, path, summary, description, roles, requestBodyClassName);
    }

    private static String nullToEmpty(String v) { return v != null ? v : ""; }

    private static String firstPath(String[] value, String[] path) {
        if (value.length > 0 && !value[0].isEmpty()) return value[0];
        if (path.length > 0 && !path[0].isEmpty()) return path[0];
        return "";
    }

    // ── ASM scanning (QueryInvoker variant) ──

    static Map<String, Set<String>> scanQueryInvokeCalls(
            Class<?> targetClass, Set<String> targetMethodNames) {
        Map<String, Set<String>> methodToQueries = new LinkedHashMap<>();
        try {
            String classResourcePath = targetClass.getName().replace('.', '/') + ".class";
            ClassLoader classLoader = targetClass.getClassLoader();
            if (classLoader == null) classLoader = ClassLoader.getSystemClassLoader();
            InputStream is = classLoader.getResourceAsStream(classResourcePath);
            if (is == null) {
                logger.warn("Cannot find class resource for {}", targetClass.getName());
                return Map.of();
            }
            ClassReader classReader = new ClassReader(is);
            classReader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (targetMethodNames.contains(name)) {
                        Set<String> collected = new LinkedHashSet<>();
                        methodToQueries.put(name, collected);
                        return new QueryInvokeCollectingMethodVisitor(collected);
                    }
                    return null;
                }
            }, ClassReader.SKIP_FRAMES);
        } catch (Exception e) {
            logger.warn("Failed to scan query invoke calls in {}: {}",
                    targetClass.getSimpleName(), e.getMessage());
            return Map.of();
        }
        return methodToQueries;
    }

    /** Same as {@code ControllerCommandScanner.InvokeCollectingMethodVisitor},
     *  but matches {@code QueryInvoker.invoke()}. */
    private static class QueryInvokeCollectingMethodVisitor extends MethodVisitor {
        private final Set<String> collectedTypes;
        private String pendingType = null;
        private boolean newPending = false;

        QueryInvokeCollectingMethodVisitor(Set<String> collectedTypes) {
            super(Opcodes.ASM9);
            this.collectedTypes = collectedTypes;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW) { pendingType = type; newPending = true; }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            // Detect QueryInvoker.invoke() — capture the pending query type
            if ("invoke".equals(name) && owner.contains("QueryInvoker")) {
                if (pendingType != null) {
                    collectedTypes.add(pendingType);
                    pendingType = null;
                    newPending = false;
                }
            } else if ("<init>".equals(name)) {
                newPending = false;
            } else if (!newPending) {
                String returnType = extractObjectReturnType(descriptor);
                if (returnType != null) pendingType = returnType;
            }
        }

        private static String extractObjectReturnType(String descriptor) {
            int returnStart = descriptor.lastIndexOf(')') + 1;
            if (returnStart <= 0 || returnStart >= descriptor.length()) return null;
            String returnDesc = descriptor.substring(returnStart);
            if (returnDesc.startsWith("L") && returnDesc.endsWith(";")) {
                return returnDesc.substring(1, returnDesc.length() - 1);
            }
            return null;
        }
    }

    static String joinPaths(String classPath, String methodPath) {
        String cp = classPath, mp = methodPath;
        if (!cp.isEmpty() && !cp.startsWith("/")) cp = "/" + cp;
        if (!mp.isEmpty() && !mp.startsWith("/")) mp = "/" + mp;
        if (cp.isEmpty()) return mp.isEmpty() ? "/" : mp;
        if (mp.isEmpty()) return cp;
        if (cp.endsWith("/") && mp.startsWith("/")) return cp + mp.substring(1);
        if (!cp.endsWith("/") && !mp.startsWith("/")) return cp + "/" + mp;
        return cp + mp;
    }

    private static String simpleName(String internalName) {
        String className = internalName.replace('/', '.');
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(lastDot + 1) : className;
    }

    static class MethodEndpointMeta {
        final String httpMethod, path, summary, description;
        final List<String> roles;
        final String requestBodyClassName;

        MethodEndpointMeta(String httpMethod, String path, String summary,
                           String description, List<String> roles,
                           String requestBodyClassName) {
            this.httpMethod = httpMethod; this.path = path;
            this.summary = summary; this.description = description;
            this.roles = roles != null ? List.copyOf(roles) : List.of();
            this.requestBodyClassName = requestBodyClassName != null && !requestBodyClassName.isEmpty()
                    ? requestBodyClassName : null;
        }
    }
}
