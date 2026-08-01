package com.machingclee.domain.util.common.interfaces;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.repository.NoRepositoryBean;

import java.util.List;

/**
 * Base repository interface for audit event entities.
 * Concrete schema repositories (SalesEventRepository, etc.) extend this.
 */
@NoRepositoryBean
public interface AuditEventRepository<E extends AuditEvent> extends JpaRepository<E, Integer> {
    List<E> findAllByRequestId(String requestId);
}

