package com.machingclee.domain.util.common.bytecodescanner.helper;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;

/**
 * Builds a parent → children structure for entities a CommandHandler saves.
 * <p>
 * For each directly-saved entity, related types from {@code @OneToOne},
 * {@code @OneToMany}, and {@code @ManyToOne} are included as children
 * <em>only</em> when the handler bytecode references them.
 * <p>
 * Tries {@code jakarta.persistence.*} first, then {@code javax.persistence.*}.
 */
public final class CascadeEntityResolver {

    private static final Logger logger = LoggerFactory.getLogger(CascadeEntityResolver.class);

    private CascadeEntityResolver() {
        // utility class
    }

    /**
     * One saved entity and its bytecode-referenced related types.
     *
     * @param entity       the directly {@code save*}'d entity class
     * @param childEntity  related types used in the handler
     */
    public record InvolvedEntity(Class<?> entity, List<Class<?>> childEntity) {
        public InvolvedEntity {
            childEntity = childEntity != null ? List.copyOf(childEntity) : List.of();
        }
    }

    /**
     * For each saved entity, collect related types that appear in
     * {@code referencedTypes}.
     *
     * @param directEntities  entities detected via repository {@code save*} calls
     * @param referencedTypes ASM internal names of types seen in handler bytecode
     * @return one entry per saved entity, with used related types as children
     */
    public static List<InvolvedEntity> expand(List<Class<?>> directEntities,
                                             Set<String> referencedTypes) {
        if (directEntities.isEmpty()) return List.of();

        Set<String> referenced = referencedTypes != null
                ? referencedTypes
                : Set.of();

        List<InvolvedEntity> result = new ArrayList<>();
        Set<String> seenRoots = new LinkedHashSet<>();

        for (Class<?> root : directEntities) {
            if (!seenRoots.add(root.getName())) continue;

            List<Class<?>> children = new ArrayList<>();
            Set<String> seenChildren = new LinkedHashSet<>();

            for (Class<?> related : resolveRelatedEntities(root)) {
                String internalName = related.getName().replace('.', '/');
                if (!referenced.contains(internalName)) {
                    logger.debug("Skip unused relation {} → {} (not in handler bytecode)",
                            root.getSimpleName(), related.getSimpleName());
                    continue;
                }
                // Do not list the root as its own child
                if (related.getName().equals(root.getName())) continue;
                if (seenChildren.add(related.getName())) {
                    children.add(related);
                    logger.debug("Related (used): {} → {}",
                            root.getSimpleName(), related.getSimpleName());
                }
            }

            result.add(new InvolvedEntity(root, children));
        }

        return result;
    }

    private static List<Class<?>> resolveRelatedEntities(Class<?> entityClass) {
        List<Class<?>> related = new ArrayList<>();

        for (Field field : entityClass.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) continue;

            Annotation oneToMany = findAnnotation(field, "OneToMany");
            Annotation oneToOne = findAnnotation(field, "OneToOne");
            Annotation manyToOne = findAnnotation(field, "ManyToOne");

            if (oneToMany == null && oneToOne == null && manyToOne == null) continue;

            Class<?> targetType;
            if (oneToMany != null) {
                targetType = resolveCollectionElementType(field);
            } else {
                targetType = field.getType();
            }

            if (targetType != null && !targetType.equals(entityClass)) {
                related.add(targetType);
            }
        }

        return related;
    }

    @SuppressWarnings("unchecked")
    private static Annotation findAnnotation(Field field, String simpleName) {
        try {
            Class<?> jakartaClass = Class.forName("jakarta.persistence." + simpleName);
            Annotation ann = field.getAnnotation((Class<? extends Annotation>) jakartaClass);
            if (ann != null) return ann;
        } catch (ClassNotFoundException ignored) {
            // jakarta.persistence not on classpath
        }

        try {
            Class<?> javaxClass = Class.forName("javax.persistence." + simpleName);
            return field.getAnnotation((Class<? extends Annotation>) javaxClass);
        } catch (ClassNotFoundException ignored) {
            // javax.persistence not on classpath
        }

        return null;
    }

    private static Class<?> resolveCollectionElementType(Field field) {
        Type genericType = field.getGenericType();
        if (genericType instanceof ParameterizedType pt) {
            Type[] args = pt.getActualTypeArguments();
            if (args.length > 0 && args[0] instanceof Class<?> elementClass) {
                return elementClass;
            }
        }
        return null;
    }
}
