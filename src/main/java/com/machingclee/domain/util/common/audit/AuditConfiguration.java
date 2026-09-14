package com.machingclee.domain.util.common.audit;

import com.machingclee.domain.util.common.interfaces.AuditEvent;
import com.machingclee.domain.util.common.interfaces.AuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Combined command + domain-event audit pipeline, plus a callback that
 * runs after the command invoker finishes the command transaction and
 * any failure stamps.
 * <p>
 * Register as a {@code @Bean} (or {@code @Configuration} subclass) to
 * replace the auto-configured default:
 *
 * <pre>
 * {@code
 * @Bean
 * AuditConfiguration auditConfiguration() {
 *     AuditConfiguration config = new AuditConfiguration();
 *     config.addPreCommandAuditHandler(record -> { });
 *     config.addPostCommandAuditHandler(record -> { });
 *     config.addPreEventAuditHandler(record -> { });
 *     config.addPostEventAuditHandler(record -> { });
 *     config.addAfterTransactionHandler(record -> {
 *         List<AuditEvent> rows = record.getEvents();
 *     });
 *     config.overrideCommandAuditHandler(record -> {
 *         config.getOriginalCommandAuditHandler().handle(record);
 *     });
 *     return config;
 * }
 * }
 * </pre>
 * <p>
 * Row pipeline ({@link #executeCommand} / {@link #executeEvent}), same
 * thread: {@code REQUIRES_NEW} pres, then persist TX ({@code JOIN} pres
 * + override-or-original + {@code JOIN} posts), then {@code REQUIRES_NEW}
 * posts after that TX commits.
 * <p>
 * {@link #addAfterTransactionHandler} is not a row handler. The command
 * invoker runs it once for a top-level invoke, after commit (so
 * POST_COMMIT event rows exist) or after rollback + failure stamps (so
 * {@code failure_reason} is already on the rows).
 */
public class AuditConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AuditConfiguration.class);

    private final Pipeline<CommandAuditRecord> commands = new Pipeline<>("command");
    private final Pipeline<EventAuditRecord> events = new Pipeline<>("event");
    private final List<AuditHandler<AfterTransactionRecord>> afterTransactionHandlers = new ArrayList<>();

    private TransactionTemplate persistTemplate;
    private TransactionTemplate requiresNewTemplate;

    // -------------------------------------------------------------------------
    // Command row handlers
    // -------------------------------------------------------------------------

    public AuditConfiguration addPreCommandAuditHandler(AuditHandler<CommandAuditRecord> handler) {
        return addPreCommandAuditHandler(handler, AuditTx.JOIN);
    }

    public AuditConfiguration addPreCommandAuditHandler(AuditHandler<CommandAuditRecord> handler, AuditTx tx) {
        commands.addPre(handler, tx);
        return this;
    }

    public AuditConfiguration addPostCommandAuditHandler(AuditHandler<CommandAuditRecord> handler) {
        return addPostCommandAuditHandler(handler, AuditTx.REQUIRES_NEW);
    }

    public AuditConfiguration addPostCommandAuditHandler(AuditHandler<CommandAuditRecord> handler, AuditTx tx) {
        commands.addPost(handler, tx);
        return this;
    }

    /**
     * Replaces the original command repository-save handler. Call
     * {@link #getOriginalCommandAuditHandler()} from inside {@code handler} to
     * still persist (or omit that call to skip DB).
     */
    public AuditConfiguration overrideCommandAuditHandler(AuditHandler<CommandAuditRecord> handler) {
        commands.override(handler);
        return this;
    }

    /**
     * The framework handler that writes the command audit row with
     * {@code AuditEventRepository.save}. Does not re-run pre / post.
     */
    public AuditHandler<CommandAuditRecord> getOriginalCommandAuditHandler() {
        return commands.originalHandler();
    }

    /**
     * Framework use only. Rebinds the command repository-save handler.
     */
    public void bindOriginalCommandAuditHandler(AuditHandler<CommandAuditRecord> handler) {
        commands.bindOriginal(handler);
    }

    public void executeCommand(CommandAuditRecord record) throws Exception {
        commands.execute(record);
    }

    // -------------------------------------------------------------------------
    // Event row handlers
    // -------------------------------------------------------------------------

    public AuditConfiguration addPreEventAuditHandler(AuditHandler<EventAuditRecord> handler) {
        return addPreEventAuditHandler(handler, AuditTx.JOIN);
    }

    public AuditConfiguration addPreEventAuditHandler(AuditHandler<EventAuditRecord> handler, AuditTx tx) {
        events.addPre(handler, tx);
        return this;
    }

    public AuditConfiguration addPostEventAuditHandler(AuditHandler<EventAuditRecord> handler) {
        return addPostEventAuditHandler(handler, AuditTx.REQUIRES_NEW);
    }

    public AuditConfiguration addPostEventAuditHandler(AuditHandler<EventAuditRecord> handler, AuditTx tx) {
        events.addPost(handler, tx);
        return this;
    }

    /**
     * Replaces the original domain-event repository-save handler. Call
     * {@link #getOriginalEventAuditHandler()} from inside {@code handler} to
     * still persist (or omit that call to skip DB).
     */
    public AuditConfiguration overrideEventAuditHandler(AuditHandler<EventAuditRecord> handler) {
        events.override(handler);
        return this;
    }

    /**
     * The framework handler that writes the domain-event audit row with
     * {@code AuditEventRepository.save}. Does not re-run pre / post.
     */
    public AuditHandler<EventAuditRecord> getOriginalEventAuditHandler() {
        return events.originalHandler();
    }

    /**
     * Framework use only. Rebinds the domain-event repository-save handler.
     */
    public void bindOriginalEventAuditHandler(AuditHandler<EventAuditRecord> handler) {
        events.bindOriginal(handler);
    }

    public void executeEvent(EventAuditRecord record) throws Exception {
        events.execute(record);
    }

    // -------------------------------------------------------------------------
    // After command TX + failure stamps
    // -------------------------------------------------------------------------

    /**
     * Runs once per top-level command invoke, after the command transaction
     * has completed and after {@code logFailure} / {@code markEventsFailed}
     * on the failure path. {@link AfterTransactionRecord#getEvents()} is
     * {@code findAllByRequestId}. Failures are logged and swallowed.
     */
    public AuditConfiguration addAfterTransactionHandler(AuditHandler<AfterTransactionRecord> handler) {
        afterTransactionHandlers.add(Objects.requireNonNull(handler, "after-transaction audit handler"));
        return this;
    }

    /**
     * Framework use only. Invoked by the command invoker after a top-level
     * command transaction (and failure stamps) have finished.
     */
    public void executeAfterTransaction(String requestId, boolean committed,
            AuditEventRepository<? extends AuditEvent> repository) {
        if (afterTransactionHandlers.isEmpty() || requestId == null || requestId.isBlank()) {
            return;
        }
        List<AuditEvent> snapshot;
        try {
            snapshot = loadEvents(requestId, repository);
        } catch (Exception e) {
            logger.warn("Failed to load audit events for requestId={}: {}", requestId, e.getMessage(), e);
            snapshot = List.of();
        }
        AfterTransactionRecord record = new AfterTransactionRecord(requestId, committed, snapshot);
        for (AuditHandler<AfterTransactionRecord> handler : afterTransactionHandlers) {
            try {
                runRequiresNew(handler, record);
            } catch (Exception e) {
                logger.warn("after-transaction audit handler failed: {}", e.getMessage(), e);
            }
        }
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

    private List<AuditEvent> loadEvents(String requestId,
            AuditEventRepository<? extends AuditEvent> repository) {
        if (repository == null) {
            return List.of();
        }
        if (requiresNewTemplate != null) {
            return requiresNewTemplate.execute(tx -> copyEvents(repository.findAllByRequestId(requestId)));
        }
        return copyEvents(repository.findAllByRequestId(requestId));
    }

    private static List<AuditEvent> copyEvents(List<? extends AuditEvent> found) {
        if (found == null || found.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(found);
    }

    private <T> void runRequiresNew(AuditHandler<T> handler, T record) throws Exception {
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

    private static Exception unwrap(AuditPersistException e) {
        Throwable cause = e.getCause();
        if (cause instanceof Exception ex) {
            return ex;
        }
        return e;
    }

    /**
     * Unwraps checked exceptions thrown from a {@link TransactionTemplate}.
     */
    static final class AuditPersistException extends RuntimeException {
        AuditPersistException(Exception cause) {
            super(cause);
        }
    }

    private final class Pipeline<T> {

        private final String name;
        private final List<RegisteredHandler<T>> preHandlers = new ArrayList<>();
        private final List<RegisteredHandler<T>> postHandlers = new ArrayList<>();
        private AuditHandler<T> overrideHandler;
        private AuditHandler<T> originalDelegate = record -> {
        };
        private final AuditHandler<T> originalHandler = record -> originalDelegate.handle(record);

        private Pipeline(String name) {
            this.name = name;
        }

        private void addPre(AuditHandler<T> handler, AuditTx tx) {
            preHandlers.add(new RegisteredHandler<>(
                    Objects.requireNonNull(handler, name + " pre audit handler"),
                    tx != null ? tx : AuditTx.JOIN));
        }

        private void addPost(AuditHandler<T> handler, AuditTx tx) {
            postHandlers.add(new RegisteredHandler<>(
                    Objects.requireNonNull(handler, name + " post audit handler"),
                    tx != null ? tx : AuditTx.REQUIRES_NEW));
        }

        private void override(AuditHandler<T> handler) {
            this.overrideHandler = Objects.requireNonNull(handler, name + " override audit handler");
        }

        private AuditHandler<T> originalHandler() {
            return originalHandler;
        }

        private void bindOriginal(AuditHandler<T> handler) {
            this.originalDelegate = Objects.requireNonNull(handler, name + " original audit handler");
        }

        private void execute(T record) throws Exception {
            runIsolatedPres(record);
            try {
                persist(record);
            } catch (AuditPersistException e) {
                throw unwrap(e);
            }
            runIsolatedPosts(record);
        }

        private void runIsolatedPres(T record) {
            for (RegisteredHandler<T> registered : preHandlers) {
                if (registered.tx != AuditTx.REQUIRES_NEW) {
                    continue;
                }
                try {
                    runRequiresNew(registered.handler, record);
                } catch (Exception e) {
                    logger.warn("REQUIRES_NEW {} pre-audit handler failed: {}", name, e.getMessage(), e);
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
                    logger.warn("REQUIRES_NEW {} post-audit handler failed: {}", name, e.getMessage(), e);
                }
            }
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
