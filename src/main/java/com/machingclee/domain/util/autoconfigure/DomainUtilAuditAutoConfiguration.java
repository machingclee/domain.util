package com.machingclee.domain.util.autoconfigure;

import com.machingclee.domain.util.common.command.AbstractCommandInvoker;
import com.machingclee.domain.util.common.command.CustomCommandAuditor;
import com.machingclee.domain.util.common.command.CustomCommandInvoker;
import com.machingclee.domain.util.common.event.DomainEventLogger;
import com.machingclee.domain.util.common.interfaces.AuditEvent;
import com.machingclee.domain.util.common.interfaces.AuditEventRepository;
import com.machingclee.domain.util.common.interfaces.CommandAuditorPort;
import com.machingclee.domain.util.common.interfaces.CommandInvoker;
import com.machingclee.domain.util.common.interfaces.DomainEventDispatcher;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.function.Supplier;

/**
 * Write-path glue: auditor, command invoker, and domain-event logger.
 * <p>
 * Created only when there is <strong>exactly one</strong>
 * {@link AuditEventRepository} bean. Spring Data creates that bean only after
 * the matching {@link AuditEvent} entity exists (compile-time generic + JPA
 * metamodel), so there is no separate "wait for entity bean" condition —
 * entities are not Spring beans.
 * <p>
 * Multiple audit repositories / persistence units: skip this auto-config
 * (or declare your own beans) and keep the manual subclass API.
 */
@AutoConfiguration(after = DomainUtilAutoConfiguration.class, afterName = {
        "org.springframework.boot.data.jpa.autoconfigure.DataJpaRepositoriesAutoConfiguration",
        "org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration",
        "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration"
})
@ConditionalOnClass(name = {
        "org.springframework.data.jpa.repository.JpaRepository",
        "org.springframework.transaction.PlatformTransactionManager"
})
@ConditionalOnBean(PlatformTransactionManager.class)
@ConditionalOnSingleCandidate(AuditEventRepository.class)
public class DomainUtilAuditAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(CommandAuditorPort.class)
    @SuppressWarnings({"rawtypes", "unchecked"})
    public CommandAuditorPort<?> commandAuditorPort(AuditEventRepository<?> eventRepository) {
        return new CustomCommandAuditor(eventRepository, eventFactory(eventRepository));
    }

    @Bean
    @ConditionalOnMissingBean({CommandInvoker.class, AbstractCommandInvoker.class})
    public CustomCommandInvoker commandInvoker(
            ApplicationContext context,
            DomainEventDispatcher domainEventDispatcher,
            PlatformTransactionManager transactionManager,
            CommandAuditorPort<?> auditor,
            AuditEventRepository<?> eventRepository
    ) {
        return new CustomCommandInvoker(
                context,
                domainEventDispatcher,
                transactionManager,
                auditor,
                eventRepository
        );
    }

    @Bean
    @ConditionalOnMissingBean(DomainEventLogger.class)
    public DomainEventLogger domainEventLogger(
            AuditEventRepository<?> eventRepository,
            ApplicationEventPublisher publisher
    ) {
        return new DomainEventLogger(eventRepository, eventFactory(eventRepository), publisher);
    }

    private static Supplier<AuditEvent> eventFactory(AuditEventRepository<?> eventRepository) {
        return AuditEventTypeResolver.factory(AuditEventTypeResolver.resolve(eventRepository));
    }
}
