package com.machingclee.domain.util.common.audit;

/**
 * Domain-event audit pipeline. Register as a {@code @Bean} to replace the
 * auto-configured default:
 *
 * <pre>
 * {@code
 * @Bean
 * EventAuditConfiguration eventAuditConfiguration() {
 *     EventAuditConfiguration config = new EventAuditConfiguration();
 *     config.addPreAuditHandler(record -> { });
 *     config.addPostAuditHandler(record -> { });
 *     config.overrideAuditHandler(record -> {
 *         config.getOriginalAuditHandler().handle(record);
 *     });
 *     return config;
 * }
 * }
 * </pre>
 */
public class EventAuditConfiguration
        extends AuditConfiguration<EventAuditRecord, EventAuditConfiguration> {
}
