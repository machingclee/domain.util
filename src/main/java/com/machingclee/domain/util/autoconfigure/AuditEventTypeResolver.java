package com.machingclee.domain.util.autoconfigure;

import com.machingclee.domain.util.common.interfaces.AuditEvent;
import com.machingclee.domain.util.common.interfaces.AuditEventRepository;
import org.springframework.aop.support.AopUtils;
import org.springframework.core.ResolvableType;
import org.springframework.util.ClassUtils;

import java.lang.reflect.Constructor;
import java.util.function.Supplier;

/**
 * Resolves the concrete {@link AuditEvent} entity type from an
 * {@link AuditEventRepository} bean and builds a no-arg factory for it.
 * <p>
 * Spring Data only exposes a repository bean after the matching entity type
 * exists and is a managed JPA type, so auto-config waits on the repository —
 * not on a separate entity bean (entities are not Spring beans).
 */
public final class AuditEventTypeResolver {

    private AuditEventTypeResolver() {
    }

    @SuppressWarnings("unchecked")
    public static Class<? extends AuditEvent> resolve(Object repository) {
        if (repository == null) {
            throw new IllegalStateException("AuditEventRepository bean is null");
        }

        Class<? extends AuditEvent> fromTarget = resolveFromType(AopUtils.getTargetClass(repository));
        if (fromTarget != null) {
            return fromTarget;
        }

        for (Class<?> iface : ClassUtils.getAllInterfacesForClass(repository.getClass())) {
            Class<? extends AuditEvent> fromIface = resolveFromType(iface);
            if (fromIface != null) {
                return fromIface;
            }
        }

        throw new IllegalStateException(
                "Cannot resolve AuditEvent entity type from repository "
                        + repository.getClass().getName()
                        + ". Declare interface X extends AuditEventRepository<YourEvent> "
                        + "with a concrete entity type.");
    }

    public static Supplier<AuditEvent> factory(Class<? extends AuditEvent> entityType) {
        Constructor<? extends AuditEvent> ctor;
        try {
            ctor = entityType.getDeclaredConstructor();
            ctor.setAccessible(true);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "AuditEvent entity must have a no-arg constructor: " + entityType.getName(), e);
        }
        return () -> {
            try {
                return ctor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Failed to instantiate " + entityType.getName(), e);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Class<? extends AuditEvent> resolveFromType(Class<?> type) {
        if (type == null) {
            return null;
        }
        Class<?> resolved = ResolvableType.forClass(type)
                .as(AuditEventRepository.class)
                .getGeneric(0)
                .resolve();
        if (resolved != null
                && AuditEvent.class.isAssignableFrom(resolved)
                && resolved != AuditEvent.class) {
            return (Class<? extends AuditEvent>) resolved;
        }
        return null;
    }
}
