package com.machingclee.domain.util.common.command;

import com.machingclee.domain.util.common.interfaces.AuditEvent;
import com.machingclee.domain.util.common.interfaces.AuditEventRepository;
import com.machingclee.domain.util.common.interfaces.CommandAuditorPort;
import com.machingclee.domain.util.common.interfaces.DomainEventDispatcher;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Concrete, ready-to-use CommandInvoker for consumers that do not need
 * to subclass AbstractCommandInvoker themselves.
 * <p>
 * Consumers only need to provide:
 * - An ApplicationContext
 * - A DomainEventDispatcher
 * - A PlatformTransactionManager
 * - A CommandAuditorPort
 * - An AuditEventRepository (entity {@code @Table} decides physical storage)
 * <p>
 * Example:
 *
 * <pre>
 * {@code
 * @Bean
 * public CustomCommandInvoker commandInvoker(
 *         ApplicationContext context,
 *         DomainEventDispatcher dispatcher,
 *         PlatformTransactionManager tm,
 *         CommandAuditorPort<? extends AuditEvent> auditor,
 *         AuditEventRepository<? extends AuditEvent> repo) {
 *     return new CustomCommandInvoker(context, dispatcher, tm, auditor, repo);
 * }
 * }
 * </pre>
 */
public class CustomCommandInvoker extends AbstractCommandInvoker<AuditEvent> {

    @SuppressWarnings("unchecked")
    public CustomCommandInvoker(
            ApplicationContext context,
            DomainEventDispatcher domainEventDispatcher,
            PlatformTransactionManager transactionManager,
            CommandAuditorPort<? extends AuditEvent> auditor,
            AuditEventRepository<? extends AuditEvent> eventRepository
    ) {
        super(context, domainEventDispatcher, transactionManager,
                (CommandAuditorPort<AuditEvent>) auditor,
                (AuditEventRepository<AuditEvent>) eventRepository);
    }
}
