package com.machingclee.domain.util.common.audit;

/**
 * Command-audit pipeline. Register as a {@code @Bean} to replace the
 * auto-configured default:
 *
 * <pre>
 * {@code
 * @Bean
 * CommandAuditConfiguration commandAuditConfiguration() {
 *     CommandAuditConfiguration config = new CommandAuditConfiguration();
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
public class CommandAuditConfiguration
        extends AuditConfiguration<CommandAuditRecord, CommandAuditConfiguration> {
}
