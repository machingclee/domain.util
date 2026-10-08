package com.machingclee.domain.util.common.bytecodescanner;

import com.machingclee.domain.util.common.event.ExternalEventPublisher;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Finds the domain event types handed to {@link ExternalEventPublisher}.
 * <p>
 * Command handlers emit through {@code EventQueue}, which {@link EventTypeScanner}
 * already sees. Events that enter the pipeline from outside — a websocket frame,
 * a queue poller — are published instead, so they never appear in a command's
 * {@code to} list. This scanner is what puts them on the diagram anyway.
 * <p>
 * It follows the two shapes real call sites use:
 * <ul>
 * <li>an argument built in place —
 * {@code publisher.publish(new SomeEvent(...))} or
 * {@code publisher.publish(SomeEvent.builder()...build())}</li>
 * <li>a local assigned first and published later —
 * {@code var event = SomeEvent.builder()...build(); publisher.publish(event);}</li>
 * </ul>
 * A value whose type cannot be resolved (a cast to {@code Object}, a field read,
 * a method return) is skipped rather than guessed.
 */
public final class ExternalEventScanner {

    private static final Logger logger = LoggerFactory.getLogger(ExternalEventScanner.class);

    private static final String PUBLISHER_INTERNAL =
            org.objectweb.asm.Type.getInternalName(ExternalEventPublisher.class);
    private static final String PUBLISH = "publish";
    private static final String PUBLISH_TRANSACTIONAL = "publishTransactional";

    private ExternalEventScanner() {
    }

    /**
     * Whether {@code type}'s bytecode mentions {@link ExternalEventPublisher} at
     * all. The reference is a string in the constant pool, so this is a byte
     * search rather than the instruction walk {@link #scan} does — the invoker
     * uses it to skip the beans that never publish.
     */
    public static boolean referencesPublisher(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        ClassLoader loader = type.getClassLoader();
        InputStream is = loader != null
                ? loader.getResourceAsStream(resource)
                : ClassLoader.getSystemResourceAsStream(resource);
        if (is == null) {
            return false;
        }
        try (is) {
            byte[] bytes = is.readAllBytes();
            byte[] needle = "ExternalEventPublisher".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            for (int i = 0; i + needle.length <= bytes.length; i++) {
                if (Arrays.equals(bytes, i, i + needle.length, needle, 0, needle.length)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Scan one class and return the event types it publishes, in the order they
     * are first seen. Empty when the class never calls the publisher, or when its
     * bytecode cannot be read. The caller resolves proxies first — the invoker
     * passes the bean class from {@code ApplicationContext#getType}.
     */
    public static List<Class<?>> scan(Class<?> type) {
        String resource = type.getName().replace('.', '/') + ".class";
        ClassLoader loader = type.getClassLoader();
        InputStream is = loader != null
                ? loader.getResourceAsStream(resource)
                : ClassLoader.getSystemResourceAsStream(resource);
        if (is == null) {
            logger.warn("Cannot find class resource for {}", type.getName());
            return List.of();
        }

        Set<String> internalNames = new LinkedHashSet<>();
        try (is) {
            ClassReader reader = new ClassReader(is);
            reader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                        String signature, String[] exceptions) {
                    if ((access & Opcodes.ACC_BRIDGE) != 0 || (access & Opcodes.ACC_SYNTHETIC) != 0) {
                        return null;
                    }
                    if ("<init>".equals(name) || "<clinit>".equals(name)) {
                        return null;
                    }
                    return new PublishingMethodVisitor(internalNames);
                }
            }, ClassReader.SKIP_FRAMES);
        } catch (IOException e) {
            logger.warn("Failed to scan published events for {}: {}", type.getSimpleName(), e.getMessage());
            return List.of();
        }

        List<Class<?>> result = new ArrayList<>();
        for (String internalName : internalNames) {
            try {
                result.add(Class.forName(internalName.replace('/', '.'), false, loader));
            } catch (ClassNotFoundException e) {
                logger.warn("Cannot load published event class: {}", internalName);
            }
        }
        return result;
    }

    /**
     * Remembers, per local slot, the type most recently stored there, and the
     * type of the value most recently produced — the same idea as
     * {@link EventTypeScanner}'s pending type, extended to locals so an event
     * built on one line and published on the next still resolves.
     */
    private static final class PublishingMethodVisitor extends MethodVisitor {

        private final Set<String> collected;
        /** Local slot → internal name of the type last stored in it. */
        private final Map<Integer, String> locals = new LinkedHashMap<>();
        private String pendingType = null;
        private boolean newPending = false;

        PublishingMethodVisitor(Set<String> collected) {
            super(Opcodes.ASM9);
            this.collected = collected;
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
            if (isPublish(owner, name)) {
                if (pendingType != null) {
                    collected.add(pendingType);
                }
                pendingType = null;
                newPending = false;
                return;
            }
            if ("<init>".equals(name)) {
                // The NEW is complete. pendingType stays so a publish straight
                // after `new SomeEvent(...)` still sees it.
                newPending = false;
                return;
            }
            if (newPending) {
                // Inside a constructor's arguments — their return types are not
                // the event being built.
                return;
            }
            String returnType = objectReturnType(descriptor);
            if (returnType != null) {
                pendingType = returnType;
            }
        }

        @Override
        public void visitVarInsn(int opcode, int slot) {
            if (opcode == Opcodes.ASTORE) {
                if (pendingType != null) {
                    locals.put(slot, pendingType);
                }
                return;
            }
            if (opcode == Opcodes.ALOAD) {
                pendingType = locals.get(slot);
                newPending = false;
            }
        }

        private static boolean isPublish(String owner, String name) {
            if (!PUBLISH.equals(name) && !PUBLISH_TRANSACTIONAL.equals(name)) {
                return false;
            }
            return PUBLISHER_INTERNAL.equals(owner) || owner.endsWith("ExternalEventPublisher");
        }

        private static String objectReturnType(String descriptor) {
            int returnStart = descriptor.lastIndexOf(')') + 1;
            if (returnStart <= 0 || returnStart >= descriptor.length()) {
                return null;
            }
            String returnDesc = descriptor.substring(returnStart);
            if (returnDesc.startsWith("L") && returnDesc.endsWith(";")) {
                return returnDesc.substring(1, returnDesc.length() - 1);
            }
            return null;
        }
    }
}
