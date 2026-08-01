package com.machingclee.domain.util.common.command;


import com.machingclee.domain.util.annotation.Actor;
import com.machingclee.domain.util.annotation.BoundedContext;
import com.machingclee.domain.util.common.MdcContextKeys;
import com.machingclee.domain.util.common.RequestSequence;
import com.machingclee.domain.util.common.bytecodescanner.ControllerCommandScanner;
import com.machingclee.domain.util.common.bytecodescanner.EntityTypeScanner;
import com.machingclee.domain.util.common.bytecodescanner.EventTypeScanner;
import com.machingclee.domain.util.common.bytecodescanner.PolicyCommandScanner;
import com.machingclee.domain.util.common.dto.CommandEventFlowDTO;
import com.machingclee.domain.util.common.dto.CommandPayloadDTO;
import com.machingclee.domain.util.common.dto.EventPayloadDTO;
import com.machingclee.domain.util.common.dto.FlowResponseDTO;
import com.machingclee.domain.util.common.dto.InvolvedEntityDTO;
import com.machingclee.domain.util.common.dto.PolicyDetailDTO;
import com.machingclee.domain.util.common.dto.PolicyFlowEntryDTO;
import com.machingclee.domain.util.common.event.SmartEventQueue;
import com.machingclee.domain.util.common.event.specialevent.ToBeArrangedEvent;
import com.machingclee.domain.util.common.interfaces.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.ApplicationContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Abstract base class for CommandInvokers.
 * <p>
 * All shared invocation logic lives here. Subclasses only provide:
 * - the auditor
 * - the event repository (entity {@code @Table} decides physical storage)
 * - the transaction manager (injected by the subclass constructor)
 * <p>
 * Generic type E is the audit event entity persisted by this invoker.
 * Designed for a single pipeline per application: the invoker registers all
 * {@link CommandHandler} beans in the context.
 */
public abstract class AbstractCommandInvoker<E extends AuditEvent> implements CommandInvoker {

    private static final Logger logger = LoggerFactory.getLogger(AbstractCommandInvoker.class);


    private final DomainEventDispatcher domainEventDispatcher;
    private final ApplicationContext context;

    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate requiresNewTemplate;

    private final List<CommandEventFlowDTO> commandEventFlowList = new ArrayList<>();
    private final Map<String, PolicyDetailDTO> policyDetails = new HashMap<>();
    /** Nested DTO field schemas keyed by readable type name (e.g. BookingScheduledCar.DTO). */
    private final Map<String, Map<String, Object>> dtoRegistry = new LinkedHashMap<>();

    private volatile Map<Class<?>, CommandHandler<?, ?>> handlerMap;

    private final CommandAuditorPort<E> auditor;
    private final AuditEventRepository<E> eventRepository;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    protected AbstractCommandInvoker(
            ApplicationContext context,
            DomainEventDispatcher domainEventDispatcher,
            PlatformTransactionManager transactionManager,
            CommandAuditorPort<E> auditor,
            AuditEventRepository<E> eventRepository
    ) {
        this.domainEventDispatcher = domainEventDispatcher;
        this.context = context;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.auditor = auditor;
        this.eventRepository = eventRepository;
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    @Override
    public FlowResponseDTO getFlow() {
        getHandlerMap(); // trigger lazy init
        Map<String, Map<String, Object>> schema = commandEventFlowList.stream()
                .flatMap(f -> f.to().stream())
                .collect(Collectors.toMap(
                        EventPayloadDTO::event,
                        e -> {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> payload = (Map<String, Object>) e.payload();
                            return payload;
                        },
                        (existing, replacement) -> existing,
                        LinkedHashMap::new));
        return new FlowResponseDTO(
                new ArrayList<>(commandEventFlowList),
                new HashMap<>(policyDetails),
                schema,
                new LinkedHashMap<>(dtoRegistry),
                List.of(),
                Map.of(),
                List.of(),
                Map.of());
    }

    @Override
    public <R> R invoke(Command<R> command) throws Exception {
        return invoke(command, (String) null);
    }

    public <R> R invoke(Command<R> command, String requestId) throws Exception {
        CommandHandler<?, ?> handler = getHandlerMap().get(command.getClass());
        if (handler == null) {
            throw new IllegalArgumentException(
                    "No handler registered for command: " + command.getClass().getSimpleName());
        }
        return invoke((CommandHandler<Command<R>, R>) handler, command, requestId);
    }

    @Override
    public <T extends Command<R>, R> R invoke(CommandHandler<T, R> handler, T command) throws Exception {
        return invoke(handler, command, null);
    }

    public <T extends Command<R>, R> R invoke(CommandHandler<T, R> handler, T command, String overrideRequestId) throws Exception {
        String existingRequestId = overrideRequestId != null ? overrideRequestId : MDC.get(MdcContextKeys.REQUEST_ID);
        String requestId = existingRequestId != null ? existingRequestId : UUID.randomUUID().toString();
        boolean isNestedCommand = overrideRequestId == null && MDC.get(MdcContextKeys.REQUEST_ID) != null;

        // 1. Set requestId in MDC first — all subsequent logging will carry it
        MDC.put(MdcContextKeys.REQUEST_ID, requestId);

        logger.info("Command: {}, isNested: {}, requestId: {}",
                command.getClass().getSimpleName(), isNestedCommand, requestId);

        // 2. Log the command in its own committed transaction BEFORE any business logic.
        //    logCommandInTransaction is @Transactional(REQUIRES_NEW) so it commits independently,
        //    regardless of what happens in the main transaction.
        E commandEvent = auditor.logCommandInTransaction(command, requestId);

        try {
            R result;

            if (isNestedCommand && TransactionSynchronizationManager.isSynchronizationActive()) {
                logger.debug("Executing nested command in existing transaction");
                SmartEventQueue eventQueue = new SmartEventQueue();
                try {
                    // 1. Execute business logic
                    result = handler.handle(eventQueue, command);

                    // 2. Dispatch — DomainEventLogger @EventListener persists each EventWrapper
                    //    in its own REQUIRES_NEW transaction, so logs survive any outer rollback.
                    domainEventDispatcher.dispatch(eventQueue, requestId);

                    // 3. Mark command as successful in REQUIRES_NEW.
                    //    The command audit row was inserted by logCommandInTransaction
                    //    (REQUIRES_NEW) while the parent TX was already open. Under MySQL
                    //    REPEATABLE READ the parent snapshot cannot see that row, so
                    //    findById/setSuccess in the outer PC is a silent no-op. logSuccess
                    //    mirrors logFailure and commits the flag in a fresh transaction.
                    auditor.logSuccess(commandEvent.getId());
                } catch (Exception e) {
                    // Stamp failure on command record, then mark every domain-event record
                    // for this request as failed (they were committed by DomainEventLogger).
                    auditor.logFailure(commandEvent.getId(), stackTraceOf(e));
                    markEventsFailed(requestId, stackTraceOf(e));
                    throw e;
                }
            } else {
                logger.debug("Executing top-level command in new transaction");
                final R[] resultHolder = (R[]) new Object[1];
                final Exception[] exceptionHolder = new Exception[1];

                try {
                    transactionTemplate.execute(status -> {
                        try {
                            SmartEventQueue eventQueue = new SmartEventQueue();
                            try {
                                // 1. Execute business logic
                                resultHolder[0] = handler.handle(eventQueue, command);

                                // 2. Dispatch — DomainEventLogger @EventListener persists each
                                //    EventWrapper in its own REQUIRES_NEW transaction.
                                domainEventDispatcher.dispatch(eventQueue, requestId);

                                // 3. Mark command as successful (inside the main transaction)
                                commandEvent.setSuccess(true);
                                eventRepository.save(commandEvent);
                            } catch (Exception e) {
                                exceptionHolder[0] = e;
                                status.setRollbackOnly();
                            }
                        } catch (Exception e) {
                            exceptionHolder[0] = e;
                            status.setRollbackOnly();
                        }
                        return null;
                    });
                } catch (org.springframework.transaction.UnexpectedRollbackException ignored) {
                    // transaction was rolled back via setRollbackOnly() — real cause is in exceptionHolder
                } catch (RuntimeException e) {
                    // flush-time failures (e.g. ConstraintViolationException during commit)
                    // happen outside the inner try/catch — capture them so the failure
                    // is audited below
                    exceptionHolder[0] = e;
                }

                if (exceptionHolder[0] != null) {
                    // Stamp failure on command record, then mark every domain-event record
                    // for this request as failed (they were committed by DomainEventLogger).
                    auditor.logFailure(commandEvent.getId(), stackTraceOf(exceptionHolder[0]));
                    markEventsFailed(requestId, stackTraceOf(exceptionHolder[0]));
                    throw exceptionHolder[0];
                }


                result = resultHolder[0];
            }

            logger.info("Command completed successfully: {}", command.getClass().getSimpleName());
            return result;
        } catch (RuntimeException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            logger.error("Command failed: {}, error: {}",
                    command.getClass().getSimpleName(), cause.getMessage(), cause);
            throw e;
        } catch (Exception e) {
            logger.error("Command failed: {}, error: {}",
                    command.getClass().getSimpleName(), e.getMessage(), e);
            throw e;
        } finally {
            if (!isNestedCommand) {
                RequestSequence.clear();
                MDC.clear();
            }
        }
    }

    // -------------------------------------------------------------------------
    // Failure helpers

    /**
     * Converts a Throwable's full stack trace to a String for storage in failure_reason.
     */
    private static String stackTraceOf(Throwable t) {
        if (t == null) return "";
        java.io.StringWriter sw = new java.io.StringWriter();
        t.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }

    /**
     * Marks all domain-event records that were committed by DomainEventLogger
     * for this requestId as failed.  Runs in a fresh REQUIRES_NEW transaction
     * so it always commits even when the caller is in a rolled-back context.
     */
    private void markEventsFailed(String requestId, String reason) {
        requiresNewTemplate.execute(status -> {
            eventRepository.findAllByRequestId(requestId).forEach(evt -> {
                if (Boolean.FALSE.equals(evt.getSuccess())) return; // command record — already handled
                evt.setSuccess(false);
                evt.setFailureReason(reason);
                eventRepository.save(evt);
            });
            return null;
        });
    }

    // -------------------------------------------------------------------------
    // Handler map construction — lazy, like Kotlin's `by lazy {}`

    private Map<Class<?>, CommandHandler<?, ?>> getHandlerMap() {
        if (handlerMap == null) {
            synchronized (this) {
                if (handlerMap == null) {
                    handlerMap = buildHandlerMap();
                }
            }
        }
        return handlerMap;
    }

    private Map<Class<?>, CommandHandler<?, ?>> buildHandlerMap() {
        List<Policy> policies = new ArrayList<>(context.getBeansOfType(Policy.class).values());
        logger.info("[{}] Found {} policy bean(s)", getClass().getSimpleName(), policies.size());

        @SuppressWarnings("rawtypes")
        List<CommandHandler<?, ?>> allHandlers = context.getBeansOfType(CommandHandler.class)
                .values()
                .stream()
                .map(h -> (CommandHandler<?, ?>) h)
                .collect(Collectors.toList());
        logger.info("[{}] Found {} total CommandHandler bean(s): {}", getClass().getSimpleName(), allHandlers.size(),
                allHandlers.stream().map(h -> AopUtils.getTargetClass(h).getSimpleName()).collect(Collectors.toList()));

        List<CommandHandler<?, ?>> commandHandlers = allHandlers;

        Map<Class<?>, CommandHandler<?, ?>> map = new HashMap<>();

        // 2. Scan controller endpoints — map HTTP endpoints to Commands
        Map<String, ControllerCommandScanner.EndpointInfo> endpointMap =
                ControllerCommandScanner.scanEndpoints(context);
        logger.info("[{}] Auto-detected {} controller endpoint(s)",
                getClass().getSimpleName(), endpointMap.size());

        for (CommandHandler<?, ?> handler : commandHandlers) {
            Class<?> commandClass = extractCommandClass(handler);
            if (commandClass == null) {
                logger.warn("Could not determine command type for handler: {}",
                        handler.getClass().getSimpleName());
                continue;
            }
            if (map.containsKey(commandClass)) {
                throw new IllegalStateException(
                        "Multiple handlers found for command: " + commandClass.getSimpleName());
            }
            map.put(commandClass, handler);
            logger.info("Registered command handler: {} for {}",
                    handler.getClass().getSimpleName(), commandClass.getSimpleName());

            List<Class<?>> scannedEvents = EventTypeScanner.scanEventTypes(handler);
            List<EventPayloadDTO> eventPayloads =
                    EventTypeScanner.buildEventPayloads(scannedEvents, dtoRegistry);
            CommandPayloadDTO commandPayload = new CommandPayloadDTO(
                    commandClass.getSimpleName(),
                    EventTypeScanner.buildPayloadSchema(commandClass, dtoRegistry));
            List<InvolvedEntityDTO> involvedEntities = EntityTypeScanner.scanEntityTypes(handler);
            BoundedContext contextAnnotation = commandClass.getAnnotation(BoundedContext.class);
            String context = contextAnnotation != null ? contextAnnotation.value() : "";
            Actor actorAnnotation = commandClass.getAnnotation(Actor.class);
            List<String> actors = actorAnnotation != null
                    ? List.of(actorAnnotation.value())
                    : List.of();

            // Merge endpoint info if this command is invoked from an HTTP endpoint
            ControllerCommandScanner.EndpointInfo endpoint = endpointMap.get(commandClass.getSimpleName());
            String httpMethod = endpoint != null ? endpoint.httpMethod() : "";
            String path = endpoint != null ? endpoint.path() : "";
            String summary = endpoint != null ? endpoint.summary() : "";
            String description = endpoint != null ? endpoint.description() : "";
            List<String> roles = endpoint != null ? endpoint.roles() : List.of();

            commandEventFlowList.add(new CommandEventFlowDTO(
                    commandPayload,
                    eventPayloads,
                    context,
                    actors,
                    httpMethod,
                    path,
                    summary,
                    description,
                    roles,
                    involvedEntities));
            logger.info("Auto-detected command payload for {}: {}", commandClass.getSimpleName(), commandPayload);
            logger.info("Auto-detected events for {}: {}", commandClass.getSimpleName(), eventPayloads);
            logger.info("Auto-detected entities for {}: {}", commandClass.getSimpleName(), involvedEntities);
        }

        buildPolicyFlows(policies);
        return map;
    }

    private void buildPolicyFlows(List<Policy> policies) {
        for (Policy policy : policies) {
            String policyName = policy.getClass().getSimpleName();
            List<PolicyFlowEntryDTO> flows = new ArrayList<>();

            Map<String, List<Class<?>>> scannedCommands = PolicyCommandScanner.scanNextCommands(policy);

            for (Method method : AopUtils.getTargetClass(policy).getDeclaredMethods()) {
                boolean isEventListener = method.isAnnotationPresent(
                        org.springframework.context.event.EventListener.class);
                if (!isEventListener) continue;

                Invariant invariantAnnotation = method.getAnnotation(Invariant.class);

                Class<?>[] paramTypes = method.getParameterTypes();
                Class<?> firstParam = paramTypes.length > 0 ? paramTypes[0] : null;
                String fromEvent = (firstParam != null && firstParam != ToBeArrangedEvent.class)
                        ? firstParam.getSimpleName() : null;
                String invariant = (invariantAnnotation != null && invariantAnnotation.value().length > 0)
                        ? stripCommonLeadingWhitespace(invariantAnnotation.value()[0]) : null;

                List<Class<?>> nextCommands = scannedCommands.getOrDefault(method.getName(), List.of());
                if (nextCommands.size() > 1) {
                    throw new IllegalStateException(
                            "Policy " + policyName + "." + method.getName() +
                                    " dispatches " + nextCommands.size() + " commands. " +
                                    "A policy must dispatch exactly one command (DDD rule).");
                }
                if (nextCommands.isEmpty()) {
                    flows.add(new PolicyFlowEntryDTO(fromEvent, null, invariant));
                } else {
                    for (Class<?> cmd : nextCommands) {
                        flows.add(new PolicyFlowEntryDTO(fromEvent, cmd.getSimpleName(), invariant));
                    }
                }
            }

            logger.info("Auto-detected policy flows for {}: {}", policyName, flows);
            policyDetails.put(policyName, new PolicyDetailDTO(flows));
        }
    }


    private Class<?> extractCommandClass(CommandHandler<?, ?> handler) {
        Class<?> targetClass = AopUtils.getTargetClass(handler);
        for (Type genericInterface : targetClass.getGenericInterfaces()) {
            if (genericInterface instanceof ParameterizedType pt
                    && pt.getRawType() == CommandHandler.class) {
                Type[] args = pt.getActualTypeArguments();
                if (args.length > 0 && args[0] instanceof Class<?> clazz) {
                    return clazz;
                }
            }
        }
        return null;
    }

    /**
     * Strip the common leading whitespace from every non-blank line,
     * preserving relative indentation (code blocks, nested lists, etc.).
     * <p>
     * m = min { leading-whitespace-count(line) | line is non-blank }
     * <br>
     * Then subtract m spaces from the start of each non-blank line.
     */
    static String stripCommonLeadingWhitespace(String s) {
        if (s == null || s.isEmpty()) return s;

        String[] lines = s.split("\n", -1);

        // --- find min indent across non-blank lines ---
        int minIndent = Integer.MAX_VALUE;
        for (String line : lines) {
            if (!line.isBlank()) {
                int indent = 0;
                while (indent < line.length() && line.charAt(indent) == ' ') {
                    indent++;
                }
                if (indent < minIndent) minIndent = indent;
            }
        }

        if (minIndent == Integer.MAX_VALUE || minIndent == 0) return s;

        // --- strip exactly minIndent spaces from each non-blank line ---
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) sb.append('\n');
            String line = lines[i];
            if (!line.isBlank()) {
                int skip = Math.min(minIndent, line.length());
                sb.append(line, skip, line.length());
            }
            // blank lines stay empty
        }
        return sb.toString();
    }
}
