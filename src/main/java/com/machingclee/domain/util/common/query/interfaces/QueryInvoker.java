package com.machingclee.domain.util.common.query.interfaces;

/**
 * QueryInvoker is responsible for routing queries to their appropriate
 * handlers.
 * Unlike CommandInvoker, queries are read-only operations and:
 * - Do not produce domain events
 * - Use read-only transactions (@Transactional(readOnly = true))
 * - Are typically lighter weight
 * - Can be cached or optimized for read performance
 */
public interface QueryInvoker {
    /**
     * Invokes the appropriate query handler for the given query.
     *
     * @param query The query to execute
     * @return The query result
     */
    <R> R invoke(Query<R> query) throws Exception;
}
