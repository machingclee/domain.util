package com.machingclee.domain.util.common.interfaces;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;

/**
 * Base repository interface for audit event entities.
 * Consumers provide a concrete repository + entity; physical table/schema
 * is controlled by the entity's {@code @Table} (and datasource), not this library.
 */
@NoRepositoryBean
public interface AuditEventRepository<E extends AuditEvent> extends JpaRepository<E, Integer> {
    List<E> findAllByRequestId(String requestId);
}

