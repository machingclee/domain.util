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
import java.util.concurrent.ConcurrentHashMap;

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
 *     config.addPreCommandAuditHandler(record -> { }, AuditTx.JOIN);
 *     config.addPostCommandAuditHandler(record -> { }, AuditTx.REQUIRES_NEW);
 *     config.addPreEventAuditHandler(record -> { }, AuditTx.JOIN);
 *     config.addPostEventAuditHandler(record -> { }, AuditTx.REQUIRES_NEW);
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
 * <p>
 * Every command and domain-event row passed through {@link #executeCommand}
 * or {@link #executeEvent} is kept in memory for its {@code requestId},
 * including rows whose repository save failed. {@link #executeAfterTransaction}
 * hands the handler that list, so a swallowed persist failure still reaches
 * it. {@code logSuccess} / {@code logFailure} and {@code markEventsFailed}
 * reload managed copies, so they mirror the stamp back with
 * {@link #markCommandResult} and {@link #markEventsFailed}. The invoker
 * drops the request's buffer once the handler has run.
 */
public class AuditConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(AuditConfiguration.class);

    private final Pipeline<CommandAuditRecord> commands = new Pipeline<>("command");
    private final Pipeline<EventAuditRecord> events = new Pipeline<>("event");
    private final List<AuditHandler<AfterTransactionRecord>> afterTransactionHandlers = new ArrayList<>();
    /**
     * Audit rows built for a requestId, in the order they were executed.
     * A nested invoke shares the top-level requestId, so one list holds the
     * whole command chain. Append-only on the command thread.
     */
    private final ConcurrentHashMap<String, List<AuditEvent>> pendingEvents = new ConcurrentHashMap<>();

    private TransactionTemplate persistTemplate;
    private TransactionTemplate requiresNewTemplate;

    // -------------------------------------------------------------------------
    // Command row handlers
    // -------------------------------------------------------------------------

    public AuditConfiguration addPreCommandAuditHandler(AuditHandler<CommandAuditRecord> handler, AuditTx tx) {
        commands.addPre(handler, Objects.requireNonNull(tx, "command pre audit tx"));
        return this;
    }

    public AuditConfiguration addPostCommandAuditHandler(AuditHandler<CommandAuditRecord> handler, AuditTx tx) {
        commands.addPost(handler, Objects.requireNonNull(tx, "command post audit tx"));
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
        try {
            commands.execute(record);
        } finally {
            // Remember even when the save throws — the caller swallows that
            // and the row would otherwise never reach the after-transaction
            // handler. Stamp the reason here too: there is no id to look up
            // later, so logFailure cannot write it. Leave a successful row
            // untouched — consumers default failureReason to "".
            String persistFailure = commands.takePersistFailure();
            if (persistFailure != null && (record.getAuditEvent().getFailureReason() == null
                    || record.getAuditEvent().getFailureReason().isBlank())) {
                record.getAuditEvent().setFailureReason(persistFailure);
            }
            remember(record.getRequestId(), record.getAuditEvent());
        }
    }

    // -------------------------------------------------------------------------
    // Event row handlers
    // -------------------------------------------------------------------------

    public AuditConfiguration addPreEventAuditHandler(AuditHandler<EventAuditRecord> handler, AuditTx tx) {
        events.addPre(handler, Objects.requireNonNull(tx, "event pre audit tx"));
        return this;
    }

    public AuditConfiguration addPostEventAuditHandler(AuditHandler<EventAuditRecord> handler, AuditTx tx) {
        events.addPost(handler, Objects.requireNonNull(tx, "event post audit tx"));
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
        try {
            events.execute(record);
        } finally {
            String persistFailure = events.takePersistFailure();
            if (persistFailure != null && (record.getAuditEvent().getFailureReason() == null
                    || record.getAuditEvent().getFailureReason().isBlank())) {
                record.getAuditEvent().setFailureReason(persistFailure);
            }
            remember(record.getRequestId(), record.getAuditEvent());
        }
    }

    // -------------------------------------------------------------------------
    // After command TX + failure stamps
    // -------------------------------------------------------------------------

    /**
     * Runs once per top-level command invoke, after the command transaction
     * has completed and after {@code logFailure} / {@code markEventsFailed}
     * on the failure path. {@link AfterTransactionRecord#getEvents()} is the
     * in-memory rows for this request. Failures are logged and swallowed.
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
        List<AuditEvent> snapshot = drainPending(requestId);
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

    /**
     * Copies a domain-event failure stamp onto the in-memory rows.
     * {@code markEventsFailed} loads fresh managed copies by requestId, so
     * the rows remembered at {@code executeEvent} would otherwise keep
     * {@code success=true} and an empty {@code failure_reason}. Command rows
     * are already stamped by {@link #markCommandResult}.
     */
    public void markEventsFailed(String requestId, String failureReason) {
        if (requestId == null || requestId.isBlank()) {
            return;
        }
        List<AuditEvent> pending = pendingEvents.get(requestId);
        if (pending == null) {
            return;
        }
        for (AuditEvent event : pending) {
            if (Boolean.FALSE.equals(event.getSuccess())) {
                continue;
            }
            event.setSuccess(false);
            event.setFailureReason(failureReason);
        }
    }

    /**
     * Copies a command-row stamp onto the in-memory row. {@code logSuccess}
     * and {@code logFailure} load a new managed instance by id, so the row
     * remembered at {@code executeCommand} would otherwise keep
     * {@code success=false} and an empty {@code failure_reason}.
     */
    public void markCommandResult(String requestId, Integer eventId, boolean success, String failureReason) {
        if (requestId == null || requestId.isBlank() || eventId == null) {
            return;
        }
        List<AuditEvent> pending = pendingEvents.get(requestId);
        if (pending == null) {
            return;
        }
        for (AuditEvent event : pending) {
            if (eventId.equals(event.getId())) {
                event.setSuccess(success);
                event.setFailureReason(failureReason);
                return;
            }
        }
    }

    /**
     * Keeps the audit row that {@code executeCommand} / {@code executeEvent}
     * just built, so a failed save is still visible to the after-transaction
     * handler. A blank requestId cannot be correlated back, so it is dropped.
     */
    private void remember(String requestId, AuditEvent event) {
        if (requestId == null || requestId.isBlank() || event == null) {
            return;
        }
        pendingEvents.computeIfAbsent(requestId, id -> new ArrayList<>()).add(event);
    }

    private List<AuditEvent> drainPending(String requestId) {
        List<AuditEvent> pending = pendingEvents.remove(requestId);
        if (pending == null || pending.isEmpty()) {
            return List.of();
        }
        return List.copyOf(pending);
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

    private static String failureText(Throwable error) {
        if (error == null) {
            return "audit persist failed";
        }
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            return error.getClass().getSimpleName();
        }
        return error.getClass().getSimpleName() + ": " + message;
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
        /** Set while {@link #execute} is throwing out of {@link #persist}. */
        private String persistFailure;

        private Pipeline(String name) {
            this.name = name;
        }

        private void addPre(AuditHandler<T> handler, AuditTx tx) {
            preHandlers.add(new RegisteredHandler<>(
                    Objects.requireNonNull(handler, name + " pre audit handler"),
                    tx));
        }

        private void addPost(AuditHandler<T> handler, AuditTx tx) {
            postHandlers.add(new RegisteredHandler<>(
                    Objects.requireNonNull(handler, name + " post audit handler"),
                    tx));
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
                Exception cause = unwrap(e);
                persistFailure = failureText(cause);
                throw cause;
            } catch (Exception e) {
                persistFailure = failureText(e);
                throw e;
            }
            runIsolatedPosts(record);
        }

        /** Reason from the persist that just failed, or null. Read once. */
        private String takePersistFailure() {
            String failure = persistFailure;
            persistFailure = null;
            return failure;
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
