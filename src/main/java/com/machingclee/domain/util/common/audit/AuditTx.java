package com.machingclee.domain.util.common.audit;

/**
 * Transaction mode for a pre / post audit handler.
 * <p>
 * {@link #JOIN} is always the audit persist transaction around
 * {@code eventRepository.save} — including post-handlers, which run in that
 * TX after save and can roll it back. {@link #REQUIRES_NEW} is always a
 * separate transaction (Spring {@code REQUIRES_NEW}).
 */
public enum AuditTx {

    /** Join the audit persist transaction (same TX as {@code eventRepository.save}). */
    JOIN,

    /** Always open a new transaction (Spring {@code REQUIRES_NEW}). */
    REQUIRES_NEW
}
