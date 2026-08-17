package com.machingclee.domain.util.autoconfigure;

import com.machingclee.domain.util.common.bytecodescanner.AuthRoleAnnotationConfig;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Docs / event-storming settings for {@code GET /docs/commands}.
 * <p>
 * Point the scanner at the host app's controller auth annotation. The
 * annotation must expose {@link #authRolesAttribute} (default {@code role})
 * as a list of roles — {@code Enum[]}, {@code String[]}, a single
 * {@code Enum}, or a single {@code String}.
 *
 * <pre>
 * domain-util:
 *   docs:
 *     auth-annotation: com.example.security.RequiresRole
 *     auth-roles-attribute: role
 * </pre>
 *
 * {@code auth-annotation} is a required string. Use {@code ""} to disable
 * role scanning.
 */
@ConfigurationProperties(prefix = "domain-util.docs")
public class DomainUtilDocsProperties {

    /**
     * Fully-qualified name of the controller annotation that declares
     * authorized roles. Required string; empty disables role scanning.
     */
    private String authAnnotation = "";

    /**
     * Attribute on {@link #authAnnotation} that holds the role list.
     * Default {@code role}.
     */
    private String authRolesAttribute = AuthRoleAnnotationConfig.DEFAULT_ROLES_ATTRIBUTE;

    public String getAuthAnnotation() {
        return authAnnotation;
    }

    public void setAuthAnnotation(String authAnnotation) {
        this.authAnnotation = authAnnotation != null ? authAnnotation : "";
    }

    public String getAuthRolesAttribute() {
        return authRolesAttribute;
    }

    public void setAuthRolesAttribute(String authRolesAttribute) {
        this.authRolesAttribute = (authRolesAttribute == null || authRolesAttribute.isBlank())
                ? AuthRoleAnnotationConfig.DEFAULT_ROLES_ATTRIBUTE
                : authRolesAttribute;
    }

    public AuthRoleAnnotationConfig toConfig() {
        return new AuthRoleAnnotationConfig(authAnnotation, authRolesAttribute);
    }
}
