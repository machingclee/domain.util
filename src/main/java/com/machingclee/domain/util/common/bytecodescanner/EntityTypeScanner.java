package com.machingclee.domain.util.common.bytecodescanner;

import com.machingclee.domain.util.common.bytecodescanner.helper.CascadeEntityResolver;
import com.machingclee.domain.util.common.bytecodescanner.helper.CascadeEntityResolver.InvolvedEntity;
import com.machingclee.domain.util.common.dto.InvolvedEntityDTO;
import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Uses ASM bytecode scanning to automatically detect which JPA entity types
 * a CommandHandler <em>potentially</em> modifies.
 * <p>
 * Detection matches this codebase's convention: handlers {@code saveAndFlush}
 * the aggregate before dispatching domain events.
 * <ol>
 *   <li><b>Direct {@code save*} calls</b> — records entity types for any
 *       {@code save} / {@code saveAndFlush} / {@code saveAll} /
 *       {@code saveAllAndFlush} call on a known Spring Data repository field.
 *       javac emits these against the <em>concrete</em> repository type, not
 *       {@code JpaRepository}.</li>
 *   <li><b>Entity factory / domain methods</b> — follows method calls from the
 *       handler into application classes (e.g. {@code BookingScheduleLink.createIncomplete})
 *       so types {@code NEW}'d or referenced only inside those factories are still
 *       available for relation filtering. Without this, cascade children created
 *       in factory methods are invisible to the handler bytecode alone.</li>
 *   <li><b>Related entities filtered by bytecode use</b> — walks
 *       {@code @OneToOne}, {@code @OneToMany}, and {@code @ManyToOne} on each
 *       saved entity, but <em>only keeps</em> a related type if the scanned
 *       bytecode (handler + followed callees) actually references that type.
 *   </li>
 * </ol>
 * <p>
 * Result shape: {@code List&lt;InvolvedEntityDTO&gt;} where each entry is
 * {@code { entity: savedRoot, childEntity: [used related types] }}.
 *
 * @see EventTypeScanner
 * @see PolicyCommandScanner
 * @see CascadeEntityResolver
 */
public final class EntityTypeScanner {

    private static final Logger logger = LoggerFactory.getLogger(EntityTypeScanner.class);

    private static final Set<String> REPOSITORY_INTERFACES = Set.of(
            "org.springframework.data.repository.Repository",
            "org.springframework.data.repository.CrudRepository",
            "org.springframework.data.repository.ListCrudRepository",
            "org.springframework.data.repository.ListPagingAndSortingRepository",
            "org.springframework.data.jpa.repository.JpaRepository"
    );

    private static final Set<String> SAVE_METHODS = Set.of(
            "save", "saveAndFlush", "saveAll", "saveAllAndFlush", "delete", "deleteAll"
    );

    /**
     * How deep to follow method calls from the handler into application code
     * (entity factories, domain methods, helpers). Depth 0 = handler only.
     */
    private static final int MAX_CALLEE_DEPTH = 5;

    private EntityTypeScanner() {
        // utility class
    }

    /**
     * Scan the given handler and return structured involved entities:
     * each {@code save*} root with its bytecode-referenced related children.
     */
    public static List<InvolvedEntityDTO> scanEntityTypes(Object handler) {
        Class<?> targetClass = AopUtils.getTargetClass(handler);
        ClassLoader classLoader = targetClass.getClassLoader();
        if (classLoader == null) {
            classLoader = ClassLoader.getSystemClassLoader();
        }

        Map<String, String> repoTypeToEntityType = buildRepoTypeMap(targetClass);
        if (repoTypeToEntityType.isEmpty()) {
            logger.debug("No repository fields found in {}", targetClass.getSimpleName());
            return List.of();
        }

        Set<String> savedEntityInternalNames = new LinkedHashSet<>();
        Set<String> referencedTypeInternalNames = new LinkedHashSet<>();
        Set<String> scannedMethodKeys = new LinkedHashSet<>();
        Deque<CalleeRef> pendingCallees = new ArrayDeque<>();

        // 1) Scan all concrete methods of the handler itself
        scanClassMethods(
                targetClass.getName().replace('.', '/'),
                classLoader,
                /* specificMethod */ null,
                repoTypeToEntityType,
                savedEntityInternalNames,
                referencedTypeInternalNames,
                pendingCallees,
                scannedMethodKeys);

        // 2) Follow application callees (entity factories, domain methods, …)
        int depth = 0;
        while (!pendingCallees.isEmpty() && depth < MAX_CALLEE_DEPTH) {
            int levelSize = pendingCallees.size();
            for (int i = 0; i < levelSize; i++) {
                CalleeRef callee = pendingCallees.poll();
                if (callee == null) continue;
                if (shouldSkipOwner(callee.ownerInternalName())) continue;
                String key = callee.key();
                if (!scannedMethodKeys.add(key)) continue;

                scanClassMethods(
                        callee.ownerInternalName(),
                        classLoader,
                        callee,
                        repoTypeToEntityType,
                        savedEntityInternalNames,
                        referencedTypeInternalNames,
                        pendingCallees,
                        scannedMethodKeys);
            }
            depth++;
        }

        List<Class<?>> directEntities = resolveClasses(
                savedEntityInternalNames, classLoader);
        List<InvolvedEntity> structured = CascadeEntityResolver.expand(
                directEntities, referencedTypeInternalNames);

        List<InvolvedEntityDTO> result = structured.stream()
                .map(ie -> new InvolvedEntityDTO(
                        ie.entity().getSimpleName(),
                        ie.childEntity().stream()
                                .map(Class::getSimpleName)
                                .toList()))
                .toList();

        logger.info("Entity scan for {}: {}",
                targetClass.getSimpleName(),
                result.stream()
                        .map(e -> e.entity()
                                + (e.childEntity().isEmpty()
                                ? ""
                                : "→" + e.childEntity()))
                        .toList());

        return result;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Class / method scanning
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Scan methods of a class.
     *
     * @param ownerInternalName ASM internal name of the class to load
     * @param specific          if non-null, only that method is scanned;
     *                          if null, all non-bridge instance/static methods
     *                          (except {@code <clinit>}) are scanned
     */
    private static void scanClassMethods(
            String ownerInternalName,
            ClassLoader classLoader,
            CalleeRef specific,
            Map<String, String> repoTypeToEntityType,
            Set<String> savedEntityInternalNames,
            Set<String> referencedTypeInternalNames,
            Deque<CalleeRef> pendingCallees,
            Set<String> scannedMethodKeys) {

        String classResourcePath = ownerInternalName + ".class";
        try (InputStream is = classLoader.getResourceAsStream(classResourcePath)) {
            if (is == null) {
                logger.debug("Cannot find class resource for {}", ownerInternalName);
                return;
            }

            ClassReader classReader = new ClassReader(is);
            classReader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name,
                                                 String descriptor,
                                                 String signature,
                                                 String[] exceptions) {
                    if ("<clinit>".equals(name)) return null;
                    if ((access & Opcodes.ACC_BRIDGE) != 0) return null;
                    if ((access & Opcodes.ACC_SYNTHETIC) != 0 && !"<init>".equals(name)) {
                        return null;
                    }

                    if (specific != null) {
                        if (!specific.methodName().equals(name)
                                || !specific.descriptor().equals(descriptor)) {
                            return null;
                        }
                    } else {
                        // Full class scan (handler): skip constructors — entities
                        // are not constructed in the handler ctor for our purposes.
                        if ("<init>".equals(name)) return null;
                    }

                    // Mark this method scanned when we are the initial full-class pass
                    if (specific == null) {
                        scannedMethodKeys.add(ownerInternalName + "." + name + descriptor);
                    }

                    collectTypesFromDescriptor(descriptor, referencedTypeInternalNames);
                    if (signature != null) {
                        collectTypesFromSignature(signature, referencedTypeInternalNames);
                    }

                    return new EntityCollectingMethodVisitor(
                            repoTypeToEntityType,
                            savedEntityInternalNames,
                            referencedTypeInternalNames,
                            pendingCallees);
                }
            }, ClassReader.SKIP_FRAMES);
        } catch (Exception e) {
            logger.debug("Failed to scan methods of {}: {}", ownerInternalName, e.getMessage());
        }
    }

    /**
     * Skip JDK / framework packages — only follow into application / domain code.
     */
    static boolean shouldSkipOwner(String internalName) {
        if (internalName == null || internalName.isEmpty()) return true;
        if (internalName.startsWith("[")) return true;
        return internalName.startsWith("java/")
                || internalName.startsWith("javax/")
                || internalName.startsWith("jakarta/")
                || internalName.startsWith("sun/")
                || internalName.startsWith("jdk/")
                || internalName.startsWith("com/sun/")
                || internalName.startsWith("org/springframework/")
                || internalName.startsWith("org/hibernate/")
                || internalName.startsWith("org/slf4j/")
                || internalName.startsWith("org/apache/")
                || internalName.startsWith("com/fasterxml/")
                || internalName.startsWith("kotlin/")
                || internalName.startsWith("scala/")
                || internalName.startsWith("lombok/");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Repository type → entity type resolution (reflection)
    // ─────────────────────────────────────────────────────────────────────

    static Map<String, String> buildRepoTypeMap(Class<?> handlerClass) {
        Map<String, String> map = new LinkedHashMap<>();
        for (Field field : handlerClass.getDeclaredFields()) {
            Class<?> fieldType = field.getType();
            String entityType = resolveEntityInternalName(fieldType);
            if (entityType != null) {
                map.put(fieldType.getName().replace('.', '/'), entityType);
            }
        }
        return map;
    }

    static String resolveEntityInternalName(Class<?> repositoryType) {
        return resolveFromClass(repositoryType, new HashSet<>());
    }

    private static String resolveFromClass(Class<?> clazz, Set<Type> visited) {
        for (Type gi : clazz.getGenericInterfaces()) {
            String result = resolveFromType(gi, visited);
            if (result != null) return result;
        }
        Type superclass = clazz.getGenericSuperclass();
        if (superclass != null) {
            return resolveFromType(superclass, visited);
        }
        return null;
    }

    private static String resolveFromType(Type type, Set<Type> visited) {
        if (!visited.add(type)) return null;

        if (type instanceof ParameterizedType pt) {
            Type rawType = pt.getRawType();
            if (rawType instanceof Class<?> rawClass) {
                if (REPOSITORY_INTERFACES.contains(rawClass.getName())) {
                    Type[] args = pt.getActualTypeArguments();
                    if (args.length > 0 && args[0] instanceof Class<?> entityClass) {
                        return entityClass.getName().replace('.', '/');
                    }
                }
                for (Type parent : rawClass.getGenericInterfaces()) {
                    String result = resolveFromType(parent, visited);
                    if (result != null) return result;
                }
            }
        } else if (type instanceof Class<?> clazz) {
            for (Type parent : clazz.getGenericInterfaces()) {
                String result = resolveFromType(parent, visited);
                if (result != null) return result;
            }
            Type superclass = clazz.getGenericSuperclass();
            if (superclass != null) {
                String result = resolveFromType(superclass, visited);
                if (result != null) return result;
            }
        }

        return null;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Type collection helpers
    // ─────────────────────────────────────────────────────────────────────

    private static List<Class<?>> resolveClasses(Set<String> internalNames,
                                                 ClassLoader classLoader) {
        List<Class<?>> result = new ArrayList<>();
        for (String internalName : internalNames) {
            String className = internalName.replace('/', '.');
            try {
                result.add(Class.forName(className, false, classLoader));
            } catch (ClassNotFoundException e) {
                logger.warn("Cannot load entity class: {}", className);
            }
        }
        return result;
    }

    /**
     * Extracts object type internal names from a method descriptor, e.g.
     * {@code (Ljava/lang/String;Lcom/foo/Bar;)Lcom/foo/Baz;}.
     */
    static void collectTypesFromDescriptor(String descriptor, Set<String> out) {
        if (descriptor == null || descriptor.isEmpty()) return;
        int i = 0;
        while (i < descriptor.length()) {
            char c = descriptor.charAt(i);
            if (c == 'L') {
                int end = descriptor.indexOf(';', i);
                if (end < 0) break;
                out.add(descriptor.substring(i + 1, end));
                i = end + 1;
            } else {
                i++;
            }
        }
    }

    /**
     * Lightweight extraction of {@code L...;} type names from a generic
     * signature string (method / local-variable signatures).
     */
    static void collectTypesFromSignature(String signature, Set<String> out) {
        collectTypesFromDescriptor(signature, out);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Callee tracking
    // ─────────────────────────────────────────────────────────────────────

    /**
     * A method to scan later (entity factory / domain helper).
     */
    record CalleeRef(String ownerInternalName, String methodName, String descriptor) {
        String key() {
            return ownerInternalName + "." + methodName + descriptor;
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // ASM MethodVisitor
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Collects:
     * <ul>
     *   <li>saved entity types from {@code save*} on known repositories</li>
     *   <li>every object type mentioned in instructions (for relation filtering)</li>
     *   <li>application-method callees to follow into entity factories</li>
     * </ul>
     */
    private static class EntityCollectingMethodVisitor extends MethodVisitor {

        private final Map<String, String> repoTypeToEntityType;
        private final Set<String> savedEntityTypes;
        private final Set<String> referencedTypes;
        private final Deque<CalleeRef> pendingCallees;

        EntityCollectingMethodVisitor(Map<String, String> repoTypeToEntityType,
                                      Set<String> savedEntityTypes,
                                      Set<String> referencedTypes,
                                      Deque<CalleeRef> pendingCallees) {
            super(Opcodes.ASM9);
            this.repoTypeToEntityType = repoTypeToEntityType;
            this.savedEntityTypes = savedEntityTypes;
            this.referencedTypes = referencedTypes;
            this.pendingCallees = pendingCallees;
        }

        @Override
        public void visitTypeInsn(int opcode, String type) {
            // NEW, CHECKCAST, INSTANCEOF, ANEWARRAY
            if (type != null && !type.startsWith("[")) {
                referencedTypes.add(type);
            }
        }

        @Override
        public void visitFieldInsn(int opcode, String owner, String name,
                                   String descriptor) {
            if (owner != null) {
                referencedTypes.add(owner);
            }
            collectTypesFromDescriptor(descriptor, referencedTypes);
        }

        @Override
        public void visitMethodInsn(int opcode, String owner, String name,
                                    String descriptor, boolean isInterface) {
            if (owner != null) {
                referencedTypes.add(owner);
            }
            collectTypesFromDescriptor(descriptor, referencedTypes);

            if (SAVE_METHODS.contains(name) && repoTypeToEntityType.containsKey(owner)) {
                savedEntityTypes.add(repoTypeToEntityType.get(owner));
            }

            // Follow into application code (entity factories, domain methods).
            // Skip constructors of non-app types via shouldSkipOwner; still follow
            // app constructors so nested `new ChildEntity()` body types are seen
            // if factories are inlined into <init> (unusual but cheap to include).
            if (owner != null
                    && !shouldSkipOwner(owner)
                    && !repoTypeToEntityType.containsKey(owner)
                    && name != null
                    && descriptor != null) {
                pendingCallees.add(new CalleeRef(owner, name, descriptor));
            }
        }

        @Override
        public void visitInvokeDynamicInsn(String name, String descriptor,
                                           Handle bootstrapMethodHandle,
                                           Object... bootstrapMethodArguments) {
            collectTypesFromDescriptor(descriptor, referencedTypes);
            if (bootstrapMethodArguments != null) {
                for (Object arg : bootstrapMethodArguments) {
                    if (arg instanceof Handle h) {
                        if (h.getOwner() != null) {
                            referencedTypes.add(h.getOwner());
                        }
                        collectTypesFromDescriptor(h.getDesc(), referencedTypes);
                        if (h.getOwner() != null
                                && !shouldSkipOwner(h.getOwner())
                                && !repoTypeToEntityType.containsKey(h.getOwner())) {
                            pendingCallees.add(new CalleeRef(
                                    h.getOwner(), h.getName(), h.getDesc()));
                        }
                    } else if (arg instanceof org.objectweb.asm.Type t
                            && t.getSort() == org.objectweb.asm.Type.OBJECT) {
                        referencedTypes.add(t.getInternalName());
                    }
                }
            }
        }

        @Override
        public void visitLdcInsn(Object value) {
            if (value instanceof org.objectweb.asm.Type t
                    && t.getSort() == org.objectweb.asm.Type.OBJECT) {
                referencedTypes.add(t.getInternalName());
            }
        }

        @Override
        public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
            collectTypesFromDescriptor(descriptor, referencedTypes);
        }

        @Override
        public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
            if (type != null) {
                referencedTypes.add(type);
            }
        }

        @Override
        public void visitLocalVariable(String name, String descriptor, String signature,
                                       Label start, Label end, int index) {
            collectTypesFromDescriptor(descriptor, referencedTypes);
            if (signature != null) {
                collectTypesFromSignature(signature, referencedTypes);
            }
        }
    }
}
