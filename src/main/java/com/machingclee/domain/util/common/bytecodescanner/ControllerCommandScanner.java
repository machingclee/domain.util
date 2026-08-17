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
import java.util.*;

/**
 * Uses a combination of reflection and ASM bytecode scanning to automatically
 * detect which HTTP endpoints invoke which Commands.
 * <p>
 * <b>Reflection</b> reads {@code @RequestMapping}, {@code @GetMapping},
 * {@code @PostMapping}, {@code @PutMapping}, {@code @DeleteMapping}, and
 * {@code @PatchMapping} annotations to extract HTTP method + path, and
 * {@code @Operation} for OpenAPI summary/description.
 * <p>
 * Also resolves the host app's auth annotation (configured via
 * {@code domain-util.docs.auth-annotation}) to surface authorized
 * roles. Method-level annotation wins over class-level. Empty
 * {@code auth-annotation} disables role scanning.
 * <p>
 * <b>ASM bytecode scanning</b> traces the method body to find
 * {@code commandInvoker.invoke(new XxxCommand())} calls, using the same
 * technique as {@link EventTypeScanner} and {@link PolicyCommandScanner}.
 * <p>
 * Results are returned as a map of command simple name → endpoint metadata,
 * intended to be merged into {@link com.machingclee.domain.util.common.dto.CommandEventFlowDTO} records by the caller.
 *
 * @see EventTypeScanner
 * @see PolicyCommandScanner
 */
public final class ControllerCommandScanner {

    private static final Logger logger = LoggerFactory.getLogger(ControllerCommandScanner.class);

    private ControllerCommandScanner() {
        // utility class
    }

    /**
     * Holds HTTP endpoint metadata extracted from mapping + OpenAPI annotations.
     * Returned by {@link #scanEndpoints(ApplicationContext)}.
     *
     * @param roles authorized role names from the configured auth annotation,
     *              empty when {@code auth-annotation} is {@code ""}, the
     *              annotation is absent / not on the classpath, or no roles
     *              are restricted
     */
    public record EndpointInfo(
            String httpMethod,
            String path,
            String summary,
            String description,
            List<String> roles
    ) {
        public EndpointInfo {
            roles = roles != null ? List.copyOf(roles) : List.of();
        }
    }

    /**
     * Scan all {@code @RestController} and {@code @Controller} beans in the
     * application context and return command→endpoint mappings.
     *
     * @param context the Spring ApplicationContext
     * @return map of command simple name → endpoint info
     */
    public static Map<String, EndpointInfo> scanEndpoints(ApplicationContext context) {
        Map<String, EndpointInfo> result = new LinkedHashMap<>();

        // Collect all controller beans (both @RestController and @Controller)
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

    // -------------------------------------------------------------------------
    // Per-controller scanning
    // -------------------------------------------------------------------------

    private static void scanController(Class<?> targetClass,
                                        Map<String, EndpointInfo> result,
                                        AuthRoleAnnotationConfig authConfig) {

        // 1. Class-level @RequestMapping path (may be empty)
        String classPath = extractClassMappingPath(targetClass);

        // 2. For each method, collect HTTP method + path + @Operation + auth roles
        Map<String, MethodEndpointMeta> methodMappings = new LinkedHashMap<>();
        for (Method method : targetClass.getDeclaredMethods()) {
            MethodEndpointMeta meta = extractMethodMappingInfo(method, targetClass, authConfig);
            if (meta != null) {
                methodMappings.put(method.getName(), meta);
            }
        }

        if (methodMappings.isEmpty()) {
            return;
        }

        // 3. ASM bytecode scan for invoke() calls in the mapped methods
        Map<String, Set<String>> methodCommands = scanInvokeCalls(
                targetClass, methodMappings.keySet());

        // 4. Combine reflection data with bytecode findings
        for (Map.Entry<String, MethodEndpointMeta> entry : methodMappings.entrySet()) {
            String methodName = entry.getKey();
            MethodEndpointMeta meta = entry.getValue();
            Set<String> commands = methodCommands.getOrDefault(methodName, Set.of());

            for (String cmdInternalName : commands) {
                String cmdSimpleName = simpleName(cmdInternalName);
                String fullPath = joinPaths(classPath, meta.path);

                if (result.containsKey(cmdSimpleName)) {
                    logger.debug("Command {} has multiple endpoints; keeping first: {} {}",
                            cmdSimpleName, meta.httpMethod, fullPath);
                } else {
                    result.put(cmdSimpleName, new EndpointInfo(
                            meta.httpMethod,
                            fullPath,
                            meta.summary,
                            meta.description,
                            meta.roles));
                }
            }
        }
    }

    // -------------------------------------------------------------------------
    // Annotation extraction (reflection)
    // -------------------------------------------------------------------------

    /**
     * Reads the {@code @RequestMapping} value (path) from a class.
     * Returns empty string if absent.
     */
    static String extractClassMappingPath(Class<?> clazz) {
        RequestMapping rm = clazz.getAnnotation(RequestMapping.class);
        if (rm != null && rm.value().length > 0) {
            String path = rm.value()[0];
            return path.isEmpty() ? "" : (path.startsWith("/") ? path : "/" + path);
        }
        return "";
    }

    /**
     * Reads the HTTP method, path, OpenAPI {@code @Operation} metadata, and
     * configured auth-annotation roles from a controller method. Returns
     * {@code null} if the method has no mapping.
     */
    static MethodEndpointMeta extractMethodMappingInfo(Method method, Class<?> controllerClass,
            AuthRoleAnnotationConfig authConfig) {
        String httpMethod = null;
        String path = null;

        GetMapping gm = method.getAnnotation(GetMapping.class);
        if (gm != null) {
            httpMethod = "GET";
            path = firstPath(gm.value(), gm.path());
        } else {
            PostMapping pm = method.getAnnotation(PostMapping.class);
            if (pm != null) {
                httpMethod = "POST";
                path = firstPath(pm.value(), pm.path());
            } else {
                PutMapping pu = method.getAnnotation(PutMapping.class);
                if (pu != null) {
                    httpMethod = "PUT";
                    path = firstPath(pu.value(), pu.path());
                } else {
                    DeleteMapping dm = method.getAnnotation(DeleteMapping.class);
                    if (dm != null) {
                        httpMethod = "DELETE";
                        path = firstPath(dm.value(), dm.path());
                    } else {
                        PatchMapping patch = method.getAnnotation(PatchMapping.class);
                        if (patch != null) {
                            httpMethod = "PATCH";
                            path = firstPath(patch.value(), patch.path());
                        } else {
                            RequestMapping rm = method.getAnnotation(RequestMapping.class);
                            if (rm != null) {
                                var methods = rm.method();
                                httpMethod = methods.length > 0 ? methods[0].name() : "REQUEST";
                                path = firstPath(rm.value(), rm.path());
                            }
                        }
                    }
                }
            }
        }

        if (httpMethod == null) {
            return null;
        }

        Operation operation = method.getAnnotation(Operation.class);
        String summary = operation != null ? nullToEmpty(operation.summary()) : "";
        String description = operation != null ? nullToEmpty(operation.description()) : "";
        List<String> roles = AuthRoleExtractor.extract(method, controllerClass, authConfig);
        return new MethodEndpointMeta(httpMethod, path, summary, description, roles);
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    /** Returns the first non-empty path from {@code value} or {@code path} attributes. */
    private static String firstPath(String[] value, String[] path) {
        if (value.length > 0 && !value[0].isEmpty()) return value[0];
        if (path.length > 0 && !path[0].isEmpty()) return path[0];
        return "";
    }

    // -------------------------------------------------------------------------
    // ASM bytecode scanning
    // -------------------------------------------------------------------------

    /**
     * Scans the bytecode of the given class for methods whose names appear in
     * {@code targetMethodNames}, tracking which Command types are passed to
     * {@code CommandInvoker.invoke()}.
     *
     * @return map of method name → set of Command internal class names
     */
    static Map<String, Set<String>> scanInvokeCalls(
            Class<?> targetClass,
            Set<String> targetMethodNames) {

        Map<String, Set<String>> methodToCommands = new LinkedHashMap<>();

        try {
            String classResourcePath = targetClass.getName().replace('.', '/') + ".class";
            ClassLoader classLoader = targetClass.getClassLoader();
            if (classLoader == null) {
                classLoader = ClassLoader.getSystemClassLoader();
            }
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
                        methodToCommands.put(name, collected);
                        return new InvokeCollectingMethodVisitor(collected);
                    }
                    return null;
                }
            }, ClassReader.SKIP_FRAMES);
        } catch (Exception e) {
            logger.warn("Failed to scan invoke calls in {}: {}",
                    targetClass.getSimpleName(), e.getMessage());
            return Map.of();
        }

        return methodToCommands;
    }

    /**
     * ASM MethodVisitor that tracks the most-recently-instantiated type and
     * captures it when {@code CommandInvoker.invoke()} is called.
     * <p>
     * Handles both direct instantiation ({@code new XxxCommand()}) and
     * builder/factory chains ({@code XxxCommand.builder()...build()}).
     * This is the same technique used by {@link PolicyCommandScanner}.
     */
    private static class InvokeCollectingMethodVisitor extends MethodVisitor {

        private final Set<String> collectedTypes;
        private String pendingType = null;
        private boolean newPending = false;

        InvokeCollectingMethodVisitor(Set<String> collectedTypes) {
            super(Opcodes.ASM9);
            this.collectedTypes = collectedTypes;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW) {
                pendingType = type;
                newPending = true;
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {

            // Detect CommandInvoker.invoke() — capture the pending command type
            if ("invoke".equals(name) && owner.contains("CommandInvoker")) {
                if (pendingType != null) {
                    collectedTypes.add(pendingType);
                    pendingType = null;
                    newPending = false;
                }
            } else if ("<init>".equals(name)) {
                newPending = false;
            } else if (!newPending) {
                String returnType = extractObjectReturnType(descriptor);
                if (returnType != null) {
                    pendingType = returnType;
                }
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

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Joins a class-level {@code @RequestMapping} prefix with a method-level
     * mapping path, normalising slashes.
     */
    static String joinPaths(String classPath, String methodPath) {
        String cp = classPath;
        String mp = methodPath;

        if (!cp.isEmpty() && !cp.startsWith("/")) {
            cp = "/" + cp;
        }
        if (!mp.isEmpty() && !mp.startsWith("/")) {
            mp = "/" + mp;
        }

        if (cp.isEmpty()) return mp.isEmpty() ? "/" : mp;
        if (mp.isEmpty()) return cp;

        if (cp.endsWith("/") && mp.startsWith("/")) {
            return cp + mp.substring(1);
        }
        if (!cp.endsWith("/") && !mp.startsWith("/")) {
            return cp + "/" + mp;
        }
        return cp + mp;
    }

    /** Extracts the simple class name from an ASM internal name. */
    private static String simpleName(String internalName) {
        String className = internalName.replace('/', '.');
        int lastDot = className.lastIndexOf('.');
        return lastDot >= 0 ? className.substring(lastDot + 1) : className;
    }

    /** Holds HTTP method, path, OpenAPI, and role metadata extracted from a controller method. */
    static class MethodEndpointMeta {
        final String httpMethod;
        final String path;
        final String summary;
        final String description;
        final List<String> roles;

        MethodEndpointMeta(String httpMethod, String path, String summary, String description,
                           List<String> roles) {
            this.httpMethod = httpMethod;
            this.path = path;
            this.summary = summary;
            this.description = description;
            this.roles = roles != null ? List.copyOf(roles) : List.of();
        }
    }
}
