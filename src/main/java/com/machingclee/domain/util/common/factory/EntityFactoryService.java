package com.machingclee.domain.util.common.factory;

import com.machingclee.domain.util.common.bytecodescanner.EventTypeScanner;
import com.machingclee.domain.util.common.dto.FactoryMethodDTO;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.*;

/**
 * Scans JPA entity classes whose no-arg constructor is non-public
 * (the runtime effect of {@code @NoArgsConstructor(access = PROTECTED)},
 * which is a source-level Lombok annotation invisible to reflection)
 * and discovers their public static factory methods.
 * <p>
 * The result mirrors the shape used by
 * {@link com.machingclee.domain.util.common.dto.CommandEventFlowDTO} and
 * {@link com.machingclee.domain.util.common.dto.QueryFlowDTO} so the frontend
 * visualizer can render factory methods in a dedicated "Factories" tab.
 * <p>
 * Requires a JPA {@link EntityManager} bean named {@code "entityManager"} in
 * the application context.  When none is available (e.g. the consuming module
 * does not use JPA) the scanner gracefully returns empty lists.
 */
public class EntityFactoryService {

    private static final Logger logger = LoggerFactory.getLogger(EntityFactoryService.class);

    /** Standard Spring Boot JPA {@code EntityManager} bean name. */
    private static final String ENTITY_MANAGER_BEAN_NAME = "entityManager";

    private final ApplicationContext context;

    private final Map<String, Map<String, Object>> factoryDtos = new LinkedHashMap<>();

    public EntityFactoryService(ApplicationContext context) {
        this.context = context;
    }

    /**
     * Resolve the {@link EntityManager} lazily so the bean can be created before
     * the JPA infrastructure is fully ready.  Uses the well-known bean name
     * {@code "entityManager"} to avoid ambiguity when multiple
     * {@code EntityManager}-assignable beans exist (e.g. with read/write
     * DataSource routing).
     */
    private EntityManager entityManager() {
        try {
            return context.getBean(ENTITY_MANAGER_BEAN_NAME, EntityManager.class);
        } catch (Exception e) {
            logger.debug("EntityManager bean '{}' not available: {}",
                    ENTITY_MANAGER_BEAN_NAME, e.getMessage());
            return null;
        }
    }

    /**
     * Discover all entity factory methods across every JPA entity type whose
     * no-arg constructor is non-public (the effect of
     * {@code @NoArgsConstructor(access = AccessLevel.PROTECTED)}).
     *
     * @return ordered list of factory-method descriptors (grouped by entity name)
     */
    public List<FactoryMethodDTO> getFactoryMethods() {
        EntityManager em = entityManager();
        if (em == null) {
            logger.info("EntityManager not available — skipping factory-method scan");
            return List.of();
        }

        factoryDtos.clear();
        List<FactoryMethodDTO> result = new ArrayList<>();

        Set<EntityType<?>> entities = em.getMetamodel().getEntities();
        logger.info("Scanning {} JPA entity type(s) for factory methods", entities.size());

        for (EntityType<?> entityType : entities) {
            Class<?> entityClass = entityType.getJavaType();

            // @NoArgsConstructor(access = PROTECTED) is a SOURCE-level Lombok
            // annotation — it's gone at runtime.  Detect its effect instead:
            // a no-arg constructor that is NOT public.
            if (hasPublicNoArgConstructor(entityClass)) continue;

            String entityName = EventTypeScanner.getReadableClassName(entityClass);

            for (Method method : entityClass.getDeclaredMethods()) {
                int mods = method.getModifiers();
                if (!Modifier.isPublic(mods) || !Modifier.isStatic(mods)) continue;
                if (method.isSynthetic() || method.isBridge()) continue;

                Map<String, Object> paramSchema = buildParamSchema(method);
                result.add(new FactoryMethodDTO(entityName, method.getName(), paramSchema));

                logger.info("Found factory: {}.{}({})",
                        entityName, method.getName(), paramSchema.keySet());
            }
        }

        logger.info("Factory scan complete: {} method(s) across registered entities",
                result.size());
        return result;
    }

    /**
     * Returns {@code true} if {@code cls} has a no-arg constructor that is
     * {@code public}.  Classes with a non-public (protected/private) no-arg
     * constructor follow the factory-method pattern.
     */
    private static boolean hasPublicNoArgConstructor(Class<?> cls) {
        try {
            Constructor<?> ctor = cls.getDeclaredConstructor();
            return Modifier.isPublic(ctor.getModifiers());
        } catch (NoSuchMethodException e) {
            // No no-arg constructor at all — can't be a JPA entity
            // (JPA requires one), but treat as non-public for safety
            return false;
        }
    }

    /**
     * Return nested DTO field schemas collected during factory-parameter scanning.
     * Keyed by readable type name (e.g. {@code BookingScheduleLink.CreateCompleteForRegisteredParam}).
     */
    public Map<String, Map<String, Object>> getFactoryDtos() {
        return new LinkedHashMap<>(factoryDtos);
    }

    // ── helpers ──

    /**
     * Build a parameter-name → type-name schema from a factory method's parameters.
     * <p>
     * Custom types (DTOs, inner classes) get their <em>first-level</em> fields
     * registered in {@link #factoryDtos} so the frontend expands one level deep.
     * Deeper nested objects are kept as bare class names to keep the display compact.
     */
    private Map<String, Object> buildParamSchema(Method method) {
        Map<String, Object> schema = new LinkedHashMap<>();
        Parameter[] parameters = method.getParameters();
        for (Parameter param : parameters) {
            String paramName = resolveParamName(param, schema.size());
            // Get the type name WITHOUT registering nested DTOs
            String typeName = EventTypeScanner.describeTypeName(
                    param.getParameterizedType(), null);

            // For complex parameter types, register first-level fields only
            // so the frontend expands one level but no deeper
            Class<?> paramClass = resolveConcreteClass(param.getParameterizedType());
            if (paramClass != null && !isBuiltinOrSimple(paramClass)) {
                String readableName = EventTypeScanner.getReadableClassName(paramClass);
                if (!factoryDtos.containsKey(readableName)) {
                    // buildPayloadSchema with null → first-level fields only
                    Map<String, Object> fields = EventTypeScanner.buildPayloadSchema(
                            paramClass, null);
                    factoryDtos.put(readableName, fields);
                }
            }

            schema.put(paramName, typeName);
        }
        return schema;
    }

    /** Resolve the concrete class from a {@link java.lang.reflect.Type},
     *  unwrapping parameterized types (e.g. {@code List<Foo>} → {@code Foo}). */
    private static Class<?> resolveConcreteClass(java.lang.reflect.Type type) {
        if (type instanceof Class<?> cls) return cls;
        if (type instanceof java.lang.reflect.ParameterizedType pt) {
            java.lang.reflect.Type raw = pt.getRawType();
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

    /**
     * Resolve a readable parameter name.  Uses the reflected name when available
     * (requires {@code -parameters} compiler flag, which Spring Boot enables by
     * default); falls back to {@code arg0, arg1, …}.
     */
    private static String resolveParamName(Parameter param, int fallbackIdx) {
        if (param.isNamePresent()) {
            String name = param.getName();
            if (name != null && !name.isEmpty()) return name;
        }
        return "arg" + fallbackIdx;
    }
}
