package com.machingclee.domain.util.autoconfigure;

import com.machingclee.domain.util.common.command.AbstractCommandInvoker;
import com.machingclee.domain.util.common.command.CustomCommandInvoker;
import com.machingclee.domain.util.common.event.DomainEventLogger;
import com.machingclee.domain.util.common.interfaces.AuditEvent;
import com.machingclee.domain.util.common.interfaces.AuditEventRepository;
import com.machingclee.domain.util.common.interfaces.CommandAuditorPort;
import com.machingclee.domain.util.common.interfaces.CommandInvoker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;

class DomainUtilAuditAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DomainUtilAutoConfiguration.class,
                    DomainUtilAuditAutoConfiguration.class));

    @Test
    void doesNotCreateWriteGlueWithoutRepository() {
        runner.withUserConfiguration(TransactionManagerConfig.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CommandAuditorPort.class);
                    assertThat(context).doesNotHaveBean(CommandInvoker.class);
                    assertThat(context).doesNotHaveBean(DomainEventLogger.class);
                });
    }

    @Test
    void doesNotCreateWriteGlueWithoutTransactionManager() {
        runner.withUserConfiguration(SingleRepoConfig.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CommandAuditorPort.class);
                    assertThat(context).doesNotHaveBean(CommandInvoker.class);
                    assertThat(context).doesNotHaveBean(DomainEventLogger.class);
                });
    }

    @Test
    void createsWriteGlueWhenSingleRepositoryExists() {
        runner.withUserConfiguration(TransactionManagerConfig.class, SingleRepoConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(CommandAuditorPort.class);
                    assertThat(context).hasSingleBean(CustomCommandInvoker.class);
                    assertThat(context).hasSingleBean(CommandInvoker.class);
                    assertThat(context).hasSingleBean(AbstractCommandInvoker.class);
                    assertThat(context).hasSingleBean(DomainEventLogger.class);
                });
    }

    @Test
    void skipsAutoGlueWhenMultipleRepositoriesExist() {
        runner.withUserConfiguration(TransactionManagerConfig.class, TwoRepoConfig.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CommandAuditorPort.class);
                    assertThat(context).doesNotHaveBean(CommandInvoker.class);
                    assertThat(context).doesNotHaveBean(DomainEventLogger.class);
                });
    }

    @Test
    void respectsExistingInvokerBean() {
        runner.withUserConfiguration(
                        TransactionManagerConfig.class,
                        SingleRepoConfig.class,
                        ExistingInvokerConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(CommandInvoker.class);
                    assertThat(context).doesNotHaveBean(CustomCommandInvoker.class);
                    assertThat(context.getBean(CommandInvoker.class))
                            .isSameAs(ExistingInvokerConfig.MARKER);
                    assertThat(context).hasSingleBean(CommandAuditorPort.class);
                    assertThat(context).hasSingleBean(DomainEventLogger.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class TransactionManagerConfig {
        @Bean
        PlatformTransactionManager transactionManager() {
            return new AbstractPlatformTransactionManager() {
                @Override
                protected Object doGetTransaction() throws TransactionException {
                    return new Object();
                }

                @Override
                protected void doBegin(Object transaction, TransactionDefinition definition)
                        throws TransactionException {
                }

                @Override
                protected void doCommit(DefaultTransactionStatus status) throws TransactionException {
                }

                @Override
                protected void doRollback(DefaultTransactionStatus status) throws TransactionException {
                }
            };
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class SingleRepoConfig {
        @Bean
        SampleEventRepository sampleEventRepository() {
            return proxy(SampleEventRepository.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoRepoConfig {
        @Bean
        SampleEventRepository sampleEventRepository() {
            return proxy(SampleEventRepository.class);
        }

        @Bean
        OtherEventRepository otherEventRepository() {
            return proxy(OtherEventRepository.class);
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class ExistingInvokerConfig {
        static final CommandInvoker MARKER = new CommandInvoker() {
            @Override
            public <T extends com.machingclee.domain.util.common.interfaces.Command<R>, R> R invoke(
                    com.machingclee.domain.util.common.interfaces.CommandHandler<T, R> handler, T command) {
                return null;
            }

            @Override
            public <R> R invoke(com.machingclee.domain.util.common.interfaces.Command<R> command) {
                return null;
            }

            @Override
            public com.machingclee.domain.util.common.dto.FlowResponseDTO getFlow() {
                return null;
            }
        };

        @Bean
        CommandInvoker commandInvoker() {
            return MARKER;
        }
    }

    public static class SampleEvent implements AuditEvent {
        @Override
        public Integer getId() {
            return 1;
        }

        @Override
        public Boolean getSuccess() {
            return true;
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
        }

        @Override
        public void setFailureReason(String failureReason) {
        }

        @Override
        public void setEventOrder(Integer order) {
        }
    }

    public static class OtherEvent extends SampleEvent {
    }

    public interface SampleEventRepository extends AuditEventRepository<SampleEvent> {
    }

    public interface OtherEventRepository extends AuditEventRepository<OtherEvent> {
    }

    @SuppressWarnings("unchecked")
    private static <T extends AuditEventRepository<?>> T proxy(Class<T> type) {
        return (T) Proxy.newProxyInstance(
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
}
