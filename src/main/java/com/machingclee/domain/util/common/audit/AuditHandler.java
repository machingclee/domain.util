package com.machingclee.domain.util.common.audit;

/**
 * A step in the command or domain-event audit pipeline.
 *
 * @param <T> {@link CommandAuditRecord} or {@link EventAuditRecord}
 */
@FunctionalInterface
public interface AuditHandler<T> {

    void handle(T record) throws Exception;
}
