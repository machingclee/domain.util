package com.machingclee.domain.util.common.bytecodescanner;

import com.machingclee.domain.util.common.dto.EventPayloadDTO;
import com.machingclee.domain.util.common.interfaces.EventQueue;
import org.objectweb.asm.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.support.AopUtils;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Uses ASM bytecode scanning to automatically detect which event types a
 * CommandHandler adds to the EventQueue inside its handle() method.
 * <p>
 * This removes the need to manually override declareEvents().
 */
public class EventTypeScanner {

    private static final Logger logger = LoggerFactory.getLogger(EventTypeScanner.class);

    private static final String EVENT_QUEUE_INTERNAL = org.objectweb.asm.Type.getInternalName(EventQueue.class);
    private static final String ADD_METHOD_NAME = "add";
    private static final String ADD_ALL_METHOD_NAME = "addAll";
    private static final String ADD_TRANSACTIONAL_METHOD_NAME = "addTransactional";
    private static final String ADD_ALL_TRANSACTIONAL_METHOD_NAME = "addAllTransactional";

    /**
     * Scan the handle() method of the given handler and return the event classes
     * instantiated and passed to eventQueue.add().
     */
    public static List<Class<?>> scanEventTypes(Object handler) {
        Class<?> targetClass = AopUtils.getTargetClass(handler);
        Set<String> eventTypeInternalNames = new LinkedHashSet<>();

        try {
            String classResourcePath = targetClass.getName().replace('.', '/') + ".class";
            InputStream is = targetClass.getClassLoader().getResourceAsStream(classResourcePath);
            if (is == null) {
                logger.warn("Cannot find class resource for {}", targetClass.getName());
                return List.of();
            }

            ClassReader classReader = new ClassReader(is);
            classReader.accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    // Match the handle() method (not the bridge method)
                    if ("handle".equals(name) && !descriptor.contains("Object")) {
                        return new EventCollectingMethodVisitor(eventTypeInternalNames);
                    }
                    return null;
                }
            }, ClassReader.SKIP_FRAMES);
        } catch (Exception e) {
            logger.warn("Failed to scan event types for {}: {}", targetClass.getSimpleName(), e.getMessage());
            return List.of();
        }

        List<Class<?>> result = new ArrayList<>();
        for (String internalName : eventTypeInternalNames) {
            String className = internalName.replace('/', '.');
            try {
                result.add(Class.forName(className, false, targetClass.getClassLoader()));
            } catch (ClassNotFoundException e) {
                logger.warn("Cannot load event class: {}", className);
            }
        }
        return result;
    }

    /**
     * Build {@link EventPayloadDTO} instances from scanned event classes,
     * reflecting on each event's declared fields to produce a payload schema.
     */
    public static List<EventPayloadDTO> buildEventPayloads(List<Class<?>> eventClasses) {
        return buildEventPayloads(eventClasses, null);
    }

    /**
     * Same as {@link #buildEventPayloads(List)} but also registers nested DTO
     * field schemas into {@code dtoRegistry} (keyed by readable type name such as
     * {@code BookingScheduledCar.DTO}) so the visualizer can expand nested types.
     */
    public static List<EventPayloadDTO> buildEventPayloads(
            List<Class<?>> eventClasses,
            Map<String, Map<String, Object>> dtoRegistry) {
        return eventClasses.stream()
                .map(cls -> new EventPayloadDTO(
                        cls.getSimpleName(),
                        buildPayloadSchema(cls, dtoRegistry)))
                .toList();
    }

    /**
     * Reflect on a class's declared fields and return a schema map
     * where keys are field names and values are TypeScript-style type descriptors.
     * Used for both domain events and commands.
     */
    public static Map<String, Object> buildPayloadSchema(Class<?> clazz) {
        return buildPayloadSchema(clazz, null);
    }

    /**
     * Reflect on a class's declared fields and return a schema map.
     * When {@code dtoRegistry} is non-null, every custom nested type is also
     * registered under its readable name with its own field schema so the
     * frontend can expand {@code xxx.DTO} beyond a bare type name.
     */
    public static Map<String, Object> buildPayloadSchema(
            Class<?> clazz,
            Map<String, Map<String, Object>> dtoRegistry) {
        Map<String, Object> schema = new LinkedHashMap<>();
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            schema.put(field.getName(), describeType(field.getGenericType(), dtoRegistry));
        }
        return schema;
    }

    // ─────────────────────────────────────────────────────────────────
    // Java → TypeScript type mapping
    // ─────────────────────────────────────────────────────────────────

    private static final Set<String> TS_STRING_TYPES = Set.of(
            "java.lang.String", "java.lang.CharSequence",
            "java.util.UUID",
            "java.time.LocalDateTime", "java.time.LocalDate", "java.time.LocalTime",
            "java.time.Instant", "java.util.Date", "java.sql.Date", "java.sql.Timestamp",
            "java.time.ZonedDateTime", "java.time.OffsetDateTime", "java.time.OffsetTime",
            "java.math.BigInteger"
    );

    private static final Set<String> TS_NUMBER_TYPES = Set.of(
            "int", "long", "double", "float", "short", "byte",
            "java.lang.Integer", "java.lang.Long", "java.lang.Double",
            "java.lang.Float", "java.lang.Short", "java.lang.Byte",
            "java.math.BigDecimal", "java.lang.Number"
    );

    private static final Set<String> TS_BOOLEAN_TYPES = Set.of(
            "boolean", "java.lang.Boolean"
    );

    private static final Set<String> TS_ARRAY_TYPES = Set.of(
            "java.util.List", "java.util.Collection", "java.util.Set",
            "java.util.ArrayList", "java.util.LinkedList", "java.util.HashSet",
            "java.util.LinkedHashSet", "java.util.TreeSet",
            "java.util.SortedSet", "java.util.NavigableSet",
            "java.util.Deque", "java.util.Queue",
            "java.util.Vector", "java.util.Stack"
    );

    private static final Set<String> TS_PRIMITIVE_NAMES = Set.of(
            "string", "number", "boolean", "void", "any"
    );

    /**
     * Produce a TypeScript type descriptor for a Java {@link Type}.
     * <ul>
     *   <li>Built-in Java types → TS primitives ({@code string}, {@code number}, {@code boolean})</li>
     *   <li>{@code List<X>} / {@code Set<X>} → {@code X[]}</li>
     *   <li>{@code Map<K, V>} → {@code Record<K, V>}</li>
     *   <li>{@code Optional<X>} → {@code X | null}</li>
     *   <li>Custom classes keep their simple name</li>
     * </ul>
     * Nested structures (e.g. {@code List<Map<String, SomeDTO>>}) are
     * translated recursively. When a registry is provided, custom types are
     * registered with their field schemas for nested expansion in the UI.
     */
    private static Object describeType(Type type, Map<String, Map<String, Object>> dtoRegistry) {
        if (type instanceof Class<?> cls) {
            return tsTypeForClass(cls, dtoRegistry);
        }
        if (type instanceof ParameterizedType pt) {
            Class<?> raw = (Class<?>) pt.getRawType();
            Type[] args = pt.getActualTypeArguments();

            // List<X>, Set<X>, Collection<X> → X[]
            if (TS_ARRAY_TYPES.contains(raw.getName()) && args.length == 1) {
                return describeType(args[0], dtoRegistry) + "[]";
            }

            // Map<K, V> → Record<K, V>
            if (Map.class.isAssignableFrom(raw) && args.length == 2) {
                return "Record<" + describeType(args[0], dtoRegistry)
                        + ", " + describeType(args[1], dtoRegistry) + ">";
            }

            // Optional<X> → X | null
            if (raw == java.util.Optional.class && args.length == 1) {
                return describeType(args[0], dtoRegistry) + " | null";
            }

            // Fallback: RawName<A, B> with translated args
            String rawName = getReadableClassName(raw);
            registerCustomType(raw, dtoRegistry);
            List<String> argDescs = new ArrayList<>();
            for (Type arg : args) {
                argDescs.add(String.valueOf(describeType(arg, dtoRegistry)));
            }
            return rawName + "<" + String.join(", ", argDescs) + ">";
        }
        return type.getTypeName();
    }

    /**
     * Map a Java {@link Class} to its TypeScript type name, optionally
     * registering custom types into the DTO registry.
     */
    private static String tsTypeForClass(Class<?> cls, Map<String, Map<String, Object>> dtoRegistry) {
        String name = cls.isPrimitive() ? cls.getName() : cls.getName();

        if (TS_STRING_TYPES.contains(name)) return "string";
        if (TS_NUMBER_TYPES.contains(name)) return "number";
        if (TS_BOOLEAN_TYPES.contains(name)) return "boolean";
        if ("void".equals(name) || "java.lang.Void".equals(name)) return "void";
        if ("java.lang.Object".equals(name)) return "any";

        // byte[] → string (base64 in JSON)
        if (cls == byte[].class || cls == Byte[].class) return "string";

        // Enum → string
        if (cls.isEnum()) return "string";

        // Arrays of objects: ElementType[]
        if (cls.isArray()) {
            Class<?> component = cls.getComponentType();
            return tsTypeForClass(component, dtoRegistry) + "[]";
        }

        registerCustomType(cls, dtoRegistry);
        return getReadableClassName(cls);
    }

    /**
     * Register a custom type's field schema under its readable name.
     * Cycle-safe: inserts a placeholder before reflecting nested fields.
     */
    private static void registerCustomType(
            Class<?> cls,
            Map<String, Map<String, Object>> dtoRegistry) {
        if (dtoRegistry == null || cls == null) return;
        if (cls.isPrimitive() || cls.isArray() || cls.isEnum()) return;
        if (cls.getPackageName() != null && (
                cls.getPackageName().startsWith("java.")
                        || cls.getPackageName().startsWith("javax.")
                        || cls.getPackageName().startsWith("jakarta.")
                        || cls.getPackageName().startsWith("kotlin.")
                        || cls.getPackageName().startsWith("scala.")
        )) {
            return;
        }

        String typeName = getReadableClassName(cls);
        if (TS_PRIMITIVE_NAMES.contains(typeName)) return;
        if (dtoRegistry.containsKey(typeName)) return;

        // Placeholder first to break cycles (A.DTO → B.DTO → A.DTO)
        Map<String, Object> nested = new LinkedHashMap<>();
        dtoRegistry.put(typeName, nested);

        for (Field field : cls.getDeclaredFields()) {
            if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
                continue;
            }
            nested.put(field.getName(), describeType(field.getGenericType(), dtoRegistry));
        }
    }

    /**
     * Public wrapper around {@link #describeType(Type, Map)} that returns the
     * TypeScript-style type name as a string while also registering nested
     * custom types into the DTO registry.  Useful for callers that need a
     * single type descriptor rather than a full field schema.
     *
     * @param type         the Java type to describe
     * @param dtoRegistry  optional registry for nested DTO field schemas
     * @return TypeScript-style type name (e.g. {@code "string"}, {@code "number"},
     *         {@code "BookingScheduleLink.CreateCompleteForRegisteredParam"})
     */
    public static String describeTypeName(Type type,
                                          Map<String, Map<String, Object>> dtoRegistry) {
        return String.valueOf(describeType(type, dtoRegistry));
    }

    /**
     * Return a readable class name: for inner classes, prefix with the
     * enclosing class simple name (e.g. {@code SellingCarSalesOffer.DTO}).
     */
    public static String getReadableClassName(Class<?> cls) {
        if (cls.getEnclosingClass() != null) {
            return getReadableClassName(cls.getEnclosingClass()) + "." + cls.getSimpleName();
        }
        return cls.getSimpleName();
    }

    /**
     * Tracks the most recently produced type on the conceptual stack.
     * Handles both direct instantiation (new XxxEvent()) and
     * builder pattern (XxxEvent.builder().field(x).build()).
     * <p>
     * Uses a {@code newPending} flag to distinguish two cases:
     * <ul>
     *   <li>{@code newPending = true}: a NEW opcode was just seen and its
     *       constructor has not been called yet. Method call return types
     *       (e.g. {@code List.of()}) are constructor arguments and must
     *       NOT overwrite the event type.</li>
     *   <li>{@code newPending = false}: we are in a builder or factory
     *       chain — every object-returning method call updates the
     *       tracked type so the chain advances toward the final event.</li>
     * </ul>
     */
    private static class EventCollectingMethodVisitor extends MethodVisitor {

        private final Set<String> collectedTypes;
        private String pendingType = null;
        private boolean newPending = false;

        EventCollectingMethodVisitor(Set<String> collectedTypes) {
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
            boolean isEventQueueCall = isInterface
                    && (EVENT_QUEUE_INTERNAL.equals(owner) || owner.endsWith("EventQueue"));

            if (isEventQueueCall) {
                boolean isSingleAdd = ADD_METHOD_NAME.equals(name)
                        || ADD_TRANSACTIONAL_METHOD_NAME.equals(name);
                boolean isBulkAdd = ADD_ALL_METHOD_NAME.equals(name)
                        || ADD_ALL_TRANSACTIONAL_METHOD_NAME.equals(name);

                if ((isSingleAdd || isBulkAdd) && pendingType != null) {
                    collectedTypes.add(pendingType);
                    pendingType = null;
                    newPending = false;
                }
            } else if ("<init>".equals(name)) {
                // Constructor finished — the NEW expression is complete.
                // pendingType stays as the event type; clear the flag so
                // subsequent builder / factory calls can update it again.
                newPending = false;
            } else if (!newPending) {
                // Only update from builder / factory returns when we are
                // NOT inside a NEW expression (where every method return is
                // just a constructor argument like List.of(...)).
                String returnType = extractObjectReturnType(descriptor);
                if (returnType != null) {
                    pendingType = returnType;
                }
            }
            // If newPending is true, method calls are constructor arguments
            // — ignore their return types to keep the event type from NEW.
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
}
