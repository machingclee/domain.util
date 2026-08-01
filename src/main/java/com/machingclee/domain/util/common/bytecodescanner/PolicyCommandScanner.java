package com.machingclee.domain.util.common.bytecodescanner;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.event.EventListener;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Uses ASM bytecode scanning to automatically detect which Command types
 * a Policy's @EventListener method dispatches via commandInvoker.invoke(new
 * XxxCommand()).
 * <p>
 * This removes the need to manually annotate with @NextCommand.
 */
public class PolicyCommandScanner {

    private static final Logger logger = LoggerFactory.getLogger(PolicyCommandScanner.class);

    /**
     * Scans all @EventListener methods in the given policy and returns a map of
     * method name -> list of command classes that are instantiated and passed to
     * invoke().
     */
    public static Map<String, List<Class<?>>> scanNextCommands(Object policy) {
        Class<?> targetClass = AopUtils.getTargetClass(policy);

        // Collect @EventListener method names to scan
        Set<String> listenerMethodNames = new HashSet<>();
        for (Method method : targetClass.getDeclaredMethods()) {
            if (method.isAnnotationPresent(EventListener.class)) {
                listenerMethodNames.add(method.getName());
            }
        }

        if (listenerMethodNames.isEmpty()) {
            return Map.of();
        }

        Map<String, Set<String>> methodToNewTypes = new LinkedHashMap<>();

        try {
            String classResourcePath = targetClass.getName().replace('.', '/') + ".class";
            InputStream is = targetClass.getClassLoader().getResourceAsStream(classResourcePath);
            if (is == null) {
                logger.warn("Cannot find class resource for {}", targetClass.getName());
                return Map.of();
            }

            ClassReader classReader = new ClassReader(is);
            classReader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    if (listenerMethodNames.contains(name)) {
                        Set<String> collected = new LinkedHashSet<>();
                        methodToNewTypes.put(name, collected);
                        return new InvokeCommandCollectingMethodVisitor(collected);
                    }
                    return null;
                }
            }, ClassReader.SKIP_FRAMES);

        } catch (Exception e) {
            logger.warn("Failed to scan policy commands for {}: {}", targetClass.getSimpleName(), e.getMessage());
            return Map.of();
        }

        // Resolve internal names to Class<?>
        Map<String, List<Class<?>>> result = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> entry : methodToNewTypes.entrySet()) {
            List<Class<?>> classes = new ArrayList<>();
            for (String internalName : entry.getValue()) {
                String className = internalName.replace('/', '.');
                try {
                    classes.add(Class.forName(className, false, targetClass.getClassLoader()));
                } catch (ClassNotFoundException e) {
                    logger.warn("Cannot load command class: {}", className);
                }
            }
            result.put(entry.getKey(), classes);
        }
        return result;
    }

    /**
     * Collects command types passed to invoke().
     * Handles both direct instantiation (new XxxCommand()) and
     * builder pattern (XxxCommand.builder().build()).
     * <p>
     * Tracks the "most recently produced type" on the conceptual stack:
     * - NEW opcode sets the pending type
     * - Method calls with a non-void return type update the pending type
     * - When invoke() is called, the pending type is captured as the command
     */
    private static class InvokeCommandCollectingMethodVisitor extends MethodVisitor {

        private final Set<String> collectedTypes;
        private String pendingType = null;

        InvokeCommandCollectingMethodVisitor(Set<String> collectedTypes) {
            super(Opcodes.ASM9);
            this.collectedTypes = collectedTypes;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            if (opcode == Opcodes.NEW) {
                pendingType = type;
            }
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            if ("invoke".equals(name)) {
                if (pendingType != null) {
                    collectedTypes.add(pendingType);
                    pendingType = null;
                }
            } else {
                // Track return type of factory/builder methods (e.g. build(), builder())
                String returnType = extractObjectReturnType(descriptor);
                if (returnType != null) {
                    pendingType = returnType;
                }
                // void return (e.g. <init>) leaves pendingType unchanged
            }
        }

        /**
         * Extracts the internal class name from a method descriptor's return type.
         * Returns null for primitives and void.
         * e.g. "()Lcom/example/XxxCommand;" -> "com/example/XxxCommand"
         */
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
}
