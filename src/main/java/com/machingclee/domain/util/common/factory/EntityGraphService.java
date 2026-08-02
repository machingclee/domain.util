package com.machingclee.domain.util.common.factory;

import com.machingclee.domain.util.annotation.BoundedContext;
import com.machingclee.domain.util.common.bytecodescanner.EventTypeScanner;
import com.machingclee.domain.util.common.dto.EntityMethodDTO;
import com.machingclee.domain.util.common.dto.EntityNodeDTO;
import com.machingclee.domain.util.common.dto.EntityRelationDTO;
import com.machingclee.domain.util.common.interfaces.AuditEvent;
import jakarta.persistence.EntityManager;
import jakarta.persistence.metamodel.EntityType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds a complete entity graph for the event-storming visualizer: every JPA
 * entity with its factory methods, domain behaviour methods, and association
 * edges ({@code OneToOne}, {@code OneToMany}, {@code ManyToOne}, {@code ManyToMany}).
 * <p>
 * Replaces the factories-only scan with a fuller {@link EntityNodeDTO} list so
 * the frontend can render an {@code Entities} tab (left-to-right relation edges,
 * grouped by {@link BoundedContext} or {@code "default"}).
 * <p>
 * Audit event entities ({@link AuditEvent} implementors and {@code *Event} names)
 * are excluded — they are not domain model nodes for the Entities tab.
 * <p>
 * Requires JPA ({@link EntityManager} / {@code EntityManagerFactory}). Resolution
 * is handled by {@link EntityManagerAccess} — Spring Boot does not register a
 * bean named {@code "entityManager"} by default. When JPA is unavailable the
 * scanner returns empty results.
 */
public class EntityGraphService {

    private static final Logger logger = LoggerFactory.getLogger(EntityGraphService.class);

    private static final Set<String> RELATION_ANNOTATIONS = Set.of(
            "OneToOne", "OneToMany", "ManyToOne", "ManyToMany");

    private final ApplicationContext context;

    private final Map<String, Map<String, Object>> entityDtos = new LinkedHashMap<>();

    public EntityGraphService(ApplicationContext context) {
        this.context = context;
    }

    private EntityManager entityManager() {
        return EntityManagerAccess.resolve(context, logger);
    }

    /**
     * Scan every registered JPA entity into an {@link EntityNodeDTO}.
     * Event / audit-event tables are omitted from the graph.
     */
    public List<EntityNodeDTO> getEntityNodes() {
        EntityManager em = entityManager();
        if (em == null) {
            logger.info("EntityManager not available — skipping entity-graph scan");
            return List.of();
        }

        entityDtos.clear();
        List<EntityNodeDTO> result = new ArrayList<>();
        Set<EntityType<?>> entities = em.getMetamodel().getEntities();
        logger.info("Scanning {} JPA entity type(s) for entity graph", entities.size());

        Map<String, Class<?>> entityByName = new LinkedHashMap<>();
        int skippedEvents = 0;
        for (EntityType<?> entityType : entities) {
            Class<?> cls = entityType.getJavaType();
            if (isEventEntity(cls)) {
                skippedEvents++;
                continue;
            }
            entityByName.put(cls.getName(), cls);
        }

        for (Class<?> entityClass : entityByName.values()) {
            String entityName = EventTypeScanner.getReadableClassName(entityClass);
            String contextName = resolveContext(entityClass);

            List<EntityMethodDTO> factories = scanFactoryMethods(entityClass);
            List<EntityMethodDTO> domainMethods = scanDomainMethods(entityClass);
            List<EntityRelationDTO> relations = scanRelations(entityClass, entityByName.keySet());

            result.add(new EntityNodeDTO(
                    entityName, contextName, factories, domainMethods, relations));

            logger.debug("Entity node {}: factories={}, domainMethods={}, relations={}",
                    entityName, factories.size(), domainMethods.size(), relations.size());
        }

        // Stable order: context then name
        result.sort((a, b) -> {
            int c = a.context().compareToIgnoreCase(b.context());
            if (c != 0) return c;
            return a.entityName().compareToIgnoreCase(b.entityName());
        });

        logger.info("Entity graph scan complete: {} entity node(s) (skipped {} event entit{})",
                result.size(), skippedEvents, skippedEvents == 1 ? "y" : "ies");
        return result;
    }

    /**
     * Audit-log / event store tables must not appear on the Entities tab.
     * Matches {@link AuditEvent} implementors and classes whose simple name ends
     * with {@code Event} (e.g. {@code SalesEvent}, {@code EcapiEvent}).
     */
    static boolean isEventEntity(Class<?> entityClass) {
        if (entityClass == null) return false;
        if (AuditEvent.class.isAssignableFrom(entityClass)) return true;
        String simple = entityClass.getSimpleName();
        return simple != null && simple.endsWith("Event");
    }

    /**
     * Nested DTO field schemas collected while scanning method parameters.
     * Keyed by readable type name.
     */
    public Map<String, Map<String, Object>> getEntityDtos() {
        return new LinkedHashMap<>(entityDtos);
    }

    // ── factories ──────────────────────────────────────────────────────────

    /**
     * Public static methods whose return type is the entity (or a subtype).
     * Includes entities with a public no-arg ctor (unlike the old factory-only filter).
     */
    private List<EntityMethodDTO> scanFactoryMethods(Class<?> entityClass) {
        List<EntityMethodDTO> methods = new ArrayList<>();
        for (Method method : entityClass.getDeclaredMethods()) {
            int mods = method.getModifiers();
            if (!Modifier.isPublic(mods) || !Modifier.isStatic(mods)) continue;
            if (method.isSynthetic() || method.isBridge()) continue;
            if (!entityClass.isAssignableFrom(method.getReturnType())) continue;

            methods.add(toMethodDto(method, true));
        }
        methods.sort((a, b) -> a.methodName().compareToIgnoreCase(b.methodName()));
        return methods;
    }

    // ── domain methods ─────────────────────────────────────────────────────

    /**
     * Public instance methods that look like domain behaviour (not accessors,
     * not Object overrides, not static).
     */
    private List<EntityMethodDTO> scanDomainMethods(Class<?> entityClass) {
        List<EntityMethodDTO> methods = new ArrayList<>();
        for (Method method : entityClass.getDeclaredMethods()) {
            int mods = method.getModifiers();
            if (!Modifier.isPublic(mods) || Modifier.isStatic(mods)) continue;
            if (method.isSynthetic() || method.isBridge()) continue;
            if (isObjectOverride(method)) continue;
            if (isAccessor(method, entityClass)) continue;
            // Skip nested-class builders / lombok generated noise on the outer type
            if (method.getDeclaringClass() != entityClass
                    && !entityClass.isAssignableFrom(method.getDeclaringClass())) {
                continue;
            }

            methods.add(toMethodDto(method, false));
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
        if ("canEqual".equals(name)) return true; // lombok
        if ("clone".equals(name) && params == 0) return true;
        if ("finalize".equals(name) && params == 0) return true;
        return false;
    }

    /**
     * Heuristic: getX / isX / setX matching a declared field are accessors.
     */
    private static boolean isAccessor(Method method, Class<?> entityClass) {
        String name = method.getName();
        int params = method.getParameterCount();
        Class<?> ret = method.getReturnType();

        if (name.startsWith("get") && name.length() > 3 && params == 0
                && ret != void.class && ret != Void.class) {
            return hasFieldIgnoringCase(entityClass, name.substring(3));
        }
        if (name.startsWith("is") && name.length() > 2 && params == 0
                && (ret == boolean.class || ret == Boolean.class)) {
            return hasFieldIgnoringCase(entityClass, name.substring(2));
        }
        if (name.startsWith("set") && name.length() > 3 && params == 1
                && (ret == void.class || ret == Void.class)) {
            return hasFieldIgnoringCase(entityClass, name.substring(3));
        }
        return false;
    }

    private static boolean hasFieldIgnoringCase(Class<?> cls, String propertySuffix) {
        if (propertySuffix == null || propertySuffix.isEmpty()) return false;
        String camel = Character.toLowerCase(propertySuffix.charAt(0))
                + propertySuffix.substring(1);
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (f.getName().equals(camel) || f.getName().equalsIgnoreCase(propertySuffix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private EntityMethodDTO toMethodDto(Method method, boolean factory) {
        Map<String, Object> paramSchema = buildParamSchema(method);
        String returnType = EventTypeScanner.describeTypeName(
                method.getGenericReturnType(), null);
        if (method.getReturnType() == void.class || method.getReturnType() == Void.class) {
            returnType = "void";
        }
        return new EntityMethodDTO(method.getName(), paramSchema, returnType, factory);
    }

    // ── relations ──────────────────────────────────────────────────────────

    private List<EntityRelationDTO> scanRelations(Class<?> entityClass,
                                                  Set<String> knownEntityFqcns) {
        List<EntityRelationDTO> relations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (Class<?> c = entityClass; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;

                RelationMeta meta = resolveRelationMeta(field);
                if (meta == null) continue;

                Class<?> target = meta.targetType();
                if (target == null) continue;
                // Prefer metamodel membership; fall back to @Entity on the target type
                if (!knownEntityFqcns.isEmpty() && !knownEntityFqcns.contains(target.getName())
                        && !isJpaEntity(target)) {
                    continue;
                }
                if (knownEntityFqcns.isEmpty() && !isJpaEntity(target)) {
                    continue;
                }

                String targetName = EventTypeScanner.getReadableClassName(target);
                String key = field.getName() + "->" + targetName + ":" + meta.type();
                if (!seen.add(key)) continue;

                relations.add(new EntityRelationDTO(
                        field.getName(),
                        targetName,
                        meta.type(),
                        meta.mappedBy(),
                        meta.owningSide(),
                        meta.insertable(),
                        meta.updatable(),
                        meta.extensionChild()));
            }
        }

        relations.sort((a, b) -> {
            int t = a.type().compareTo(b.type());
            if (t != 0) return t;
            return a.fieldName().compareToIgnoreCase(b.fieldName());
        });
        return relations;
    }

    private record RelationMeta(
            String type,
            Class<?> targetType,
            String mappedBy,
            boolean owningSide,
            Boolean insertable,
            Boolean updatable,
            boolean extensionChild
    ) {}

    private static RelationMeta resolveRelationMeta(Field field) {
        for (String simple : RELATION_ANNOTATIONS) {
            Annotation ann = findAnnotation(field, simple);
            if (ann == null) continue;

            String mappedBy = readMappedBy(ann);
            boolean owning = mappedBy == null || mappedBy.isBlank();
            Class<?> target;
            if ("OneToMany".equals(simple) || "ManyToMany".equals(simple)) {
                target = resolveCollectionElementType(field);
                if (target == null) {
                    target = readTargetEntity(ann);
                }
            } else {
                target = field.getType();
                Class<?> annTarget = readTargetEntity(ann);
                if (annTarget != null && annTarget != void.class) {
                    target = annTarget;
                }
            }

            String type = switch (simple) {
                case "OneToOne" -> "ONE_TO_ONE";
                case "OneToMany" -> "ONE_TO_MANY";
                case "ManyToOne" -> "MANY_TO_ONE";
                case "ManyToMany" -> "MANY_TO_MANY";
                default -> simple.toUpperCase(Locale.ROOT);
            };

            JoinColumnMeta jc = readJoinColumnMeta(field);
            // Polymorphic / secondary-table extension child of target when:
            // 1) owning OneToOne with read-only JoinColumn (shared PK / derived identity), or
            // 2) owning OneToOne and the target declares the inverse with cascade/orphanRemoval
            //    (parent owns the association lifecycle; child holds the FK).
            // Parent-owned associations like CarModel.selectedAvatar are NOT extension
            // children — the inverse on the avatar lacks cascade.
            boolean extensionChild = false;
            if ("ONE_TO_ONE".equals(type) && owning && target != null) {
                boolean readOnlyFk = jc != null && (!jc.insertable() || !jc.updatable());
                boolean parentInverseCascade = hasCascadingInverseOnTarget(target, field.getName());
                extensionChild = readOnlyFk || parentInverseCascade;
            }

            return new RelationMeta(
                    type,
                    target,
                    mappedBy != null ? mappedBy : "",
                    owning,
                    jc != null ? jc.insertable() : null,
                    jc != null ? jc.updatable() : null,
                    extensionChild);
        }
        return null;
    }

    /**
     * True when {@code target} declares {@code @OneToOne(mappedBy = owningFieldName)}
     * with cascade and/or orphanRemoval — the usual parent side of a poly extension.
     */
    private static boolean hasCascadingInverseOnTarget(Class<?> target, String owningFieldName) {
        if (target == null || owningFieldName == null || owningFieldName.isBlank()) return false;
        for (Class<?> c = target; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Annotation oneToOne = findAnnotation(f, "OneToOne");
                if (oneToOne == null) continue;
                String mb = readMappedBy(oneToOne);
                if (!owningFieldName.equals(mb)) continue;
                if (readOrphanRemoval(oneToOne)) return true;
                if (readHasCascade(oneToOne)) return true;
            }
        }
        return false;
    }

    private static boolean readOrphanRemoval(Annotation ann) {
        try {
            Method m = ann.annotationType().getMethod("orphanRemoval");
            Object v = m.invoke(ann);
            return v instanceof Boolean b && b;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean readHasCascade(Annotation ann) {
        try {
            Method m = ann.annotationType().getMethod("cascade");
            Object v = m.invoke(ann);
            if (v instanceof Object[] arr) return arr.length > 0;
            if (v != null && v.getClass().isArray()) {
                return java.lang.reflect.Array.getLength(v) > 0;
            }
        } catch (Exception ignored) {
            // no cascade attribute
        }
        return false;
    }

    private record JoinColumnMeta(boolean insertable, boolean updatable) {}

    /**
     * Read {@code @JoinColumn} insertable/updatable (jakarta then javax).
     * Defaults match JPA when the annotation is present without overrides (both true).
     * Returns {@code null} when no JoinColumn is present.
     */
    private static JoinColumnMeta readJoinColumnMeta(Field field) {
        Annotation joinColumn = findAnnotation(field, "JoinColumn");
        if (joinColumn == null) return null;
        boolean insertable = true;
        boolean updatable = true;
        try {
            Method ins = joinColumn.annotationType().getMethod("insertable");
            Object v = ins.invoke(joinColumn);
            if (v instanceof Boolean b) insertable = b;
        } catch (Exception ignored) {
            // keep default
        }
        try {
            Method upd = joinColumn.annotationType().getMethod("updatable");
            Object v = upd.invoke(joinColumn);
            if (v instanceof Boolean b) updatable = b;
        } catch (Exception ignored) {
            // keep default
        }
        return new JoinColumnMeta(insertable, updatable);
    }

    @SuppressWarnings("unchecked")
    private static Annotation findAnnotation(Field field, String simpleName) {
        try {
            Class<?> jakartaClass = Class.forName("jakarta.persistence." + simpleName);
            Annotation ann = field.getAnnotation((Class<? extends Annotation>) jakartaClass);
            if (ann != null) return ann;
        } catch (ClassNotFoundException ignored) {
            // not on classpath
        }
        try {
            Class<?> javaxClass = Class.forName("javax.persistence." + simpleName);
            return field.getAnnotation((Class<? extends Annotation>) javaxClass);
        } catch (ClassNotFoundException ignored) {
            return null;
        }
    }

    private static String readMappedBy(Annotation ann) {
        try {
            Method m = ann.annotationType().getMethod("mappedBy");
            Object v = m.invoke(ann);
            return v != null ? v.toString() : "";
        } catch (Exception e) {
            return "";
        }
    }

    private static Class<?> readTargetEntity(Annotation ann) {
        try {
            Method m = ann.annotationType().getMethod("targetEntity");
            Object v = m.invoke(ann);
            if (v instanceof Class<?> cls && cls != void.class) return cls;
        } catch (Exception ignored) {
            // no targetEntity
        }
        return null;
    }

    private static Class<?> resolveCollectionElementType(Field field) {
        Type genericType = field.getGenericType();
        if (genericType instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length > 0) {
                Type arg = args[0];
                if (arg instanceof Class<?> elementClass) return elementClass;
                if (arg instanceof ParameterizedType nested
                        && nested.getRawType() instanceof Class<?> raw) {
                    return raw;
                }
            }
        }
        return null;
    }

    private static boolean isJpaEntity(Class<?> cls) {
        for (Annotation a : cls.getAnnotations()) {
            String n = a.annotationType().getSimpleName();
            if ("Entity".equals(n)) return true;
        }
        return false;
    }

    // ── context ────────────────────────────────────────────────────────────

    private static String resolveContext(Class<?> entityClass) {
        BoundedContext onClass = entityClass.getAnnotation(BoundedContext.class);
        if (onClass != null && !onClass.value().isBlank()) {
            return onClass.value().trim();
        }
        // Package-level @BoundedContext if present
        Package pkg = entityClass.getPackage();
        if (pkg != null) {
            BoundedContext onPkg = pkg.getAnnotation(BoundedContext.class);
            if (onPkg != null && !onPkg.value().isBlank()) {
                return onPkg.value().trim();
            }
        }
        return "default";
    }

    // ── param schema (same approach as EntityFactoryService) ───────────────

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
                if (!entityDtos.containsKey(readableName)) {
                    Map<String, Object> fields = EventTypeScanner.buildPayloadSchema(
                            paramClass, null);
                    entityDtos.put(readableName, fields);
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
}
