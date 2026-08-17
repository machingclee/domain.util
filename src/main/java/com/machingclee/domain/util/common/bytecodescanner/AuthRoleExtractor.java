package com.machingclee.domain.util.common.bytecodescanner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads authorized role names from a configurable controller annotation.
 * <p>
 * Method-level annotation wins over class-level. The configured attribute
 * may be:
 * <ul>
 * <li>{@code Enum[]} or {@code String[]} (typical {@code role = {ADMIN}})</li>
 * <li>a single {@code Enum} or {@code String}</li>
 * <li>a {@link Collection} of either</li>
 * </ul>
 * Enums are surfaced as {@link Enum#name()} so docs stay independent of
 * any display / db value.
 */
public final class AuthRoleExtractor {

    private static final Logger logger = LoggerFactory.getLogger(AuthRoleExtractor.class);

    private static final ConcurrentHashMap<String, Optional<Class<? extends Annotation>>> TYPE_CACHE =
            new ConcurrentHashMap<>();

    private AuthRoleExtractor() {
    }

    public static List<String> extract(Method method, Class<?> controllerClass,
            AuthRoleAnnotationConfig config) {
        if (config == null || !config.isEnabled()) {
            return List.of();
        }
        Class<? extends Annotation> annotationType = resolveAnnotationType(config.annotationClass());
        if (annotationType == null) {
            return List.of();
        }
        try {
            Annotation annotation = AnnotationUtils.findAnnotation(method, annotationType);
            if (annotation == null) {
                annotation = AnnotationUtils.findAnnotation(controllerClass, annotationType);
            }
            if (annotation == null) {
                return List.of();
            }
            Method attribute = annotationType.getMethod(config.rolesAttribute());
            return toRoleNames(attribute.invoke(annotation));
        } catch (NoSuchMethodException e) {
            logger.debug("Auth annotation {} has no attribute '{}': {}",
                    config.annotationClass(), config.rolesAttribute(), e.getMessage());
            return List.of();
        } catch (ReflectiveOperationException e) {
            logger.debug("Could not read auth roles from {}: {}",
                    config.annotationClass(), e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    static Class<? extends Annotation> resolveAnnotationType(String className) {
        if (className == null || className.isEmpty()) {
            return null;
        }
        return TYPE_CACHE.computeIfAbsent(className, name -> {
            try {
                Class<?> loaded = Class.forName(name);
                if (!Annotation.class.isAssignableFrom(loaded)) {
                    logger.warn("Configured auth-annotation {} is not an annotation", name);
                    return Optional.empty();
                }
                return Optional.of((Class<? extends Annotation>) loaded);
            } catch (ClassNotFoundException e) {
                logger.debug("Auth annotation {} not on classpath; docs roles will be empty", name);
                return Optional.empty();
            }
        }).orElse(null);
    }

    static List<String> toRoleNames(Object rolesObj) {
        if (rolesObj == null) {
            return List.of();
        }
        if (rolesObj instanceof Object[] arr) {
            return namesFromArray(arr);
        }
        if (rolesObj instanceof Collection<?> collection) {
            return namesFromArray(collection.toArray());
        }
        String single = nameOf(rolesObj);
        return single == null || single.isEmpty() ? List.of() : List.of(single);
    }

    private static List<String> namesFromArray(Object[] arr) {
        if (arr.length == 0) {
            return List.of();
        }
        List<String> names = new ArrayList<>(arr.length);
        for (Object role : arr) {
            String name = nameOf(role);
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        return List.copyOf(names);
    }

    private static String nameOf(Object role) {
        if (role == null) {
            return null;
        }
        if (role instanceof Enum<?> e) {
            return e.name();
        }
        return role.toString();
    }
}
