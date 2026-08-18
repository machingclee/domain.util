package com.machingclee.domain.util.autoconfigure;

import com.machingclee.domain.util.common.interfaces.AuditEvent;
import com.machingclee.domain.util.common.interfaces.AuditEventRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuditEventTypeResolverTest {

    @Test
    void resolvesEntityTypeFromRepositoryInterfaceProxy() {
        AuditEventRepository<?> repo = proxy(SampleEventRepository.class);
        assertEquals(SampleEvent.class, AuditEventTypeResolver.resolve(repo));
    }

    @Test
    void factoryUsesNoArgConstructor() {
        Supplier<AuditEvent> factory = AuditEventTypeResolver.factory(SampleEvent.class);
        assertInstanceOf(SampleEvent.class, factory.get());
    }

    @Test
    void factoryAllowsProtectedNoArgConstructor() {
        Supplier<AuditEvent> factory = AuditEventTypeResolver.factory(ProtectedCtorEvent.class);
        assertInstanceOf(ProtectedCtorEvent.class, factory.get());
    }

    @Test
    void factoryRejectsMissingNoArgConstructor() {
        assertThrows(IllegalStateException.class,
                () -> AuditEventTypeResolver.factory(NoDefaultCtorEvent.class));
    }

    @Test
    void resolveFailsWhenGenericIsNotAConcreteEntity() {
        AuditEventRepository<?> repo = proxy(RawishEventRepository.class);
        assertThrows(IllegalStateException.class, () -> AuditEventTypeResolver.resolve(repo));
    }

    @SuppressWarnings("unchecked")
    private static AuditEventRepository<?> proxy(Class<? extends AuditEventRepository<?>> type) {
        return (AuditEventRepository<?>) Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[]{type},
                (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "proxy:" + type.getSimpleName();
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> null;
                        };
                    }
                    return null;
                });
    }

    public static class SampleEvent implements AuditEvent {
        private Integer id;
        private Boolean success;

        @Override
        public Integer getId() {
            return id;
        }

        @Override
        public Boolean getSuccess() {
            return success;
        }

        @Override
        public void setCreatedAt(Double createdAt) {
        }

        @Override
        public void setEventType(String eventType) {
        }

        @Override
        public void setPayload(String payload) {
        }

        @Override
        public void setRequestUserEmail(String requestUserEmail) {
        }

        @Override
        public void setRequestId(String requestId) {
        }

        @Override
        public void setSuccess(Boolean success) {
            this.success = success;
        }

        @Override
        public void setFailureReason(String failureReason) {
        }

        @Override
        public void setEventOrder(Integer order) {
        }
    }

    public static class ProtectedCtorEvent extends SampleEvent {
        protected ProtectedCtorEvent() {
        }
    }

    public static class NoDefaultCtorEvent extends SampleEvent {
        public NoDefaultCtorEvent(String unused) {
        }
    }

    public interface SampleEventRepository extends AuditEventRepository<SampleEvent> {
    }

    public interface RawishEventRepository extends AuditEventRepository<AuditEvent> {
    }
}
