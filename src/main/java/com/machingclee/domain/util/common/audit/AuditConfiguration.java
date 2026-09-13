package com.machingclee.domain.util.common.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Shared pipeline: {@code REQUIRES_NEW} pres, then persist TX ({@code JOIN}
 * pres + override-or-original + {@code JOIN} posts), then {@code REQUIRES_NEW}
 * posts after that TX commits.
 *
 * @param <T> record type passed to each handler
 * @param <C> concrete configuration type, for fluent chaining
 */
public abstract class AuditConfiguration<T, C extends AuditConfiguration<T, C>> {

    private static final Logger logger = LoggerFactory.getLogger(AuditConfiguration.class);

    private final List<RegisteredHandler<T>> preHandlers = new ArrayList<>();
    private final List<RegisteredHandler<T>> postHandlers = new ArrayList<>();
    private AuditHandler<T> overrideHandler;
    private AuditHandler<T> originalDelegate = record -> {
    };
    /**
     * Stable view of the original (repository-save) handler. Capturing this
     * reference from a {@code @Bean} method is safe — it always forwards to
     * the current delegate, which the auditor/logger binds at construction.
     */
    private final AuditHandler<T> originalHandler = record -> originalDelegate.handle(record);

    private TransactionTemplate persistTemplate;
    private TransactionTemplate requiresNewTemplate;

    @SuppressWarnings("unchecked")
    private C self() {
        return (C) this;
    }

    public C addPreAuditHandler(AuditHandler<T> handler) {
        return addPreAuditHandler(handler, AuditTx.JOIN);
    }

    public C addPreAuditHandler(AuditHandler<T> handler, AuditTx tx) {
        preHandlers.add(new RegisteredHandler<>(
                Objects.requireNonNull(handler, "pre audit handler"),
                tx != null ? tx : AuditTx.JOIN));
        return self();
    }

    public C addPostAuditHandler(AuditHandler<T> handler) {
        return addPostAuditHandler(handler, AuditTx.REQUIRES_NEW);
    }

    public C addPostAuditHandler(AuditHandler<T> handler, AuditTx tx) {
        postHandlers.add(new RegisteredHandler<>(
                Objects.requireNonNull(handler, "post audit handler"),
                tx != null ? tx : AuditTx.REQUIRES_NEW));
        return self();
    }

    /**
     * Replaces the original repository-save handler. Call
     * {@link #getOriginalAuditHandler()} from inside {@code handler} to still
     * persist (or omit that call to skip DB).
     */
    public C overrideAuditHandler(AuditHandler<T> handler) {
        this.overrideHandler = Objects.requireNonNull(handler, "override audit handler");
        return self();
    }

    /**
     * The framework handler that writes the audit row with
     * {@code AuditEventRepository.save}. Does not re-run pre / post.
     */
    public AuditHandler<T> getOriginalAuditHandler() {
        return originalHandler;
    }

    /**
     * Framework use only. Rebinds the repository-save handler.
     */
    public void bindOriginalAuditHandler(AuditHandler<T> handler) {
        this.originalDelegate = Objects.requireNonNull(handler, "original audit handler");
    }

    /**
     * Framework use only. Supplies templates so {@code REQUIRES_NEW} handlers
     * and the persist TX are real Spring transactions. Without a manager
     * (unit tests), handlers still run in order with no TX.
     */
    public void bindTransactionManager(PlatformTransactionManager transactionManager) {
        if (transactionManager == null) {
            this.persistTemplate = null;
            this.requiresNewTemplate = null;
            return;
        }
        this.persistTemplate = new TransactionTemplate(transactionManager);
        this.persistTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Run the pipeline. Persist ({@code JOIN} pres + original/override +
     * {@code JOIN} posts) happens in {@code REQUIRES_NEW};
     * {@code REQUIRES_NEW} posts run after that TX commits.
     */
    public void execute(T record) throws Exception {
        runIsolatedPres(record);
        try {
            persist(record);
        } catch (AuditPersistException e) {
            throw unwrap(e);
        }
        runIsolatedPosts(record);
    }

    private static Exception unwrap(AuditPersistException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception ex) {
            return ex;
        }
        return e;
    }

    private void runIsolatedPres(T record) {
        for (RegisteredHandler<T> registered : preHandlers) {
            if (registered.tx != AuditTx.REQUIRES_NEW) {
                continue;
            }
            try {
                runRequiresNew(registered.handler, record);
            } catch (Exception e) {
                logger.warn("REQUIRES_NEW pre-audit handler failed: {}", e.getMessage(), e);
            }
        }
    }

    private void persist(T record) throws Exception {
        if (persistTemplate != null) {
            persistTemplate.execute(status -> {
                try {
                    persistInCurrentTx(record);
                    return null;
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new AuditPersistException(e);
                }
            });
            return;
        }
        persistInCurrentTx(record);
    }

    private void persistInCurrentTx(T record) throws Exception {
        for (RegisteredHandler<T> registered : preHandlers) {
            if (registered.tx == AuditTx.JOIN) {
                registered.handler.handle(record);
            }
        }
        AuditHandler<T> main = overrideHandler != null ? overrideHandler : originalHandler;
        main.handle(record);
        for (RegisteredHandler<T> registered : postHandlers) {
            if (registered.tx == AuditTx.JOIN) {
                registered.handler.handle(record);
            }
        }
    }

    private void runIsolatedPosts(T record) {
        for (RegisteredHandler<T> registered : postHandlers) {
            if (registered.tx != AuditTx.REQUIRES_NEW) {
                continue;
            }
            try {
                runRequiresNew(registered.handler, record);
            } catch (Exception e) {
                logger.warn("REQUIRES_NEW post-audit handler failed: {}", e.getMessage(), e);
            }
        }
    }

    private void runRequiresNew(AuditHandler<T> handler, T record) throws Exception {
        if (requiresNewTemplate != null) {
            requiresNewTemplate.execute(status -> {
                try {
                    handler.handle(record);
                    return null;
                } catch (RuntimeException e) {
                    throw e;
                } catch (Exception e) {
                    throw new AuditPersistException(e);
                }
            });
            return;
        }
        handler.handle(record);
    }

    /**
     * Unwraps checked exceptions thrown from a {@link TransactionTemplate}.
     */
    static final class AuditPersistException extends RuntimeException {
        AuditPersistException(Exception cause) {
            super(cause);
        }
    }

    private static final class RegisteredHandler<T> {
        private final AuditHandler<T> handler;
        private final AuditTx tx;

        private RegisteredHandler(AuditHandler<T> handler, AuditTx tx) {
            this.handler = handler;
            this.tx = tx;
        }
    }
}
