package com.machingclee.domain.util.common.query.interfaces;


public interface QueryHandler<Q extends Query<R>, R> {
    /**
     * Handles the query and returns the result.
     *
     * @param query The query to handle
     * @return The query result
     */
    R handle(Q query) throws Exception;
}
