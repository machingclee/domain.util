package com.machingclee.domain.util.common.bytecodescanner;

import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.context.ApplicationContext;
import org.springframework.core.env.Environment;

/**
 * Which controller annotation (and which attribute) the docs scanner should
 * treat as the authorized-role list.
 * <p>
 * {@link #annotationClass} is a required string. Empty disables role
 * scanning. Override via {@code domain-util.docs.auth-annotation}
 * / {@code domain-util.docs.auth-roles-attribute}.
 */
public record AuthRoleAnnotationConfig(
        String annotationClass,
        String rolesAttribute) {

    public static final String DEFAULT_ROLES_ATTRIBUTE = "role";

    public static final String ANNOTATION_PROPERTY = "domain-util.docs.auth-annotation";

    public static final String ATTRIBUTE_PROPERTY = "domain-util.docs.auth-roles-attribute";

    public AuthRoleAnnotationConfig {
        annotationClass = annotationClass != null ? annotationClass : "";
        rolesAttribute = (rolesAttribute == null || rolesAttribute.isBlank())
                ? DEFAULT_ROLES_ATTRIBUTE
                : rolesAttribute.trim();
    }

    public static AuthRoleAnnotationConfig disabled() {
        return new AuthRoleAnnotationConfig("", DEFAULT_ROLES_ATTRIBUTE);
    }

    /**
     * Resolve from the Spring context: bound {@code DomainUtilDocsProperties}
     * bean if present, otherwise Environment properties. Missing property
     * is treated as {@code ""}.
     */
    public static AuthRoleAnnotationConfig from(ApplicationContext context) {
        if (context == null) {
            return disabled();
        }
        try {
            Class<?> propsType = Class.forName(
                    "com.machingclee.domain.util.autoconfigure.DomainUtilDocsProperties");
            Object props = context.getBean(propsType);
            Object config = propsType.getMethod("toConfig").invoke(props);
            if (config instanceof AuthRoleAnnotationConfig resolved) {
                return resolved;
            }
        } catch (ClassNotFoundException | NoSuchBeanDefinitionException ignored) {
            // properties type / bean not registered — fall through to Environment
        } catch (Exception ignored) {
            // reflective failure — fall through to Environment
        }
        Environment env = context.getEnvironment();
        if (env == null) {
            return disabled();
        }
        String annotation = env.getProperty(ANNOTATION_PROPERTY);
        String attribute = env.getProperty(ATTRIBUTE_PROPERTY);
        return new AuthRoleAnnotationConfig(
                annotation != null ? annotation : "",
                attribute != null ? attribute : DEFAULT_ROLES_ATTRIBUTE);
    }

    public boolean isEnabled() {
        return !annotationClass.isEmpty();
    }
}
