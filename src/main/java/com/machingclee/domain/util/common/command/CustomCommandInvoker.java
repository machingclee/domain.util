package com.machingclee.domain.util.common.command;

import com.machingclee.domain.util.common.interfaces.AuditEvent;
import com.machingclee.domain.util.common.interfaces.AuditEventRepository;
import com.machingclee.domain.util.common.interfaces.CommandAuditorPort;
import com.machingclee.domain.util.common.interfaces.DomainEventDispatcher;
import com.machingclee.domain.util.schema.SchemaIdentifier;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Concrete, ready-to-use CommandInvoker for consumers that do not need
 * to subclass AbstractCommandInvoker themselves.
 * <p>
 * Consumers only need to provide:
 * - An AuditEventPort bean        → auto-configures DefaultCommandAuditor
 * - An AuditEventRepository bean  → passed in by the consumer
 * - A SchemaIdentifier            → e.g. TargetSchema.Schema.SALES
 * <p>
 * Example registration in web.sales:
 *
 * @Bean public CustomCommandInvoker salesCommandInvoker(
 * PlatformTransactionManager tm,
 * DefaultCommandAuditor auditor,
 * SalesEventRepository repo) {
 * return new CustomCommandInvoker(tm,
 * TargetSchema.Schema.SALES, auditor, repo);
 * }
 */
public class CustomCommandInvoker extends AbstractCommandInvoker<AuditEvent> {

    public CustomCommandInvoker(
            ApplicationContext context,
            DomainEventDispatcher domainEventDispatcher,
            PlatformTransactionManager transactionManager,
            SchemaIdentifier schemaIdentifier,
            CommandAuditorPort<? extends AuditEvent> auditor,
            AuditEventRepository<? extends AuditEvent> eventRepository
    ) {
        super(context, domainEventDispatcher, transactionManager, schemaIdentifier,
                (CommandAuditorPort<AuditEvent>) auditor,
                (AuditEventRepository<AuditEvent>) eventRepository);
    }
}
