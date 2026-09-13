package com.machingclee.domain.util.common.command;

import com.machingclee.domain.util.common.MdcContextKeys;
import com.machingclee.domain.util.common.RequestSequence;
import com.machingclee.domain.util.common.audit.CommandAuditConfiguration;
import com.machingclee.domain.util.common.audit.CommandAuditRecord;
import com.machingclee.domain.util.common.audit.EventAuditConfiguration;
import com.machingclee.domain.util.common.audit.EventAuditRecord;
import com.machingclee.domain.util.common.interfaces.AuditEvent;
import com.machingclee.domain.util.common.interfaces.AuditEventRepository;
import com.machingclee.domain.util.common.interfaces.CommandAuditorPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.function.Supplier;

/**
 * Generic CommandAuditor for use by any consumer module.
 * Uses AuditEventPort to remain decoupled from any concrete entity or
 * repository.
 * Consumers register an AuditEventPort bean to activate this auditor.
 */
public class CustomCommandAuditor<E extends AuditEvent> implements CommandAuditorPort<E> {

    private static final Logger logger = LoggerFactory.getLogger(CustomCommandAuditor.class);

    private final AuditEventRepository<E> eventRepository;
    private final Supplier<E> eventFactory;
    private final CommandAuditConfiguration commandAudit;
    private final EventAuditConfiguration eventAudit;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);

    public CustomCommandAuditor(AuditEventRepository<E> eventRepository, Supplier<E> eventFactory) {
        this(eventRepository, eventFactory, new CommandAuditConfiguration(), new EventAuditConfiguration(), null);
    }

    public CustomCommandAuditor(AuditEventRepository<E> eventRepository, Supplier<E> eventFactory,
            CommandAuditConfiguration commandAudit, EventAuditConfiguration eventAudit,
            PlatformTransactionManager transactionManager) {
        this.eventRepository = eventRepository;
        this.eventFactory = eventFactory;
        this.commandAudit = commandAudit;
        this.eventAudit = eventAudit;
        commandAudit.bindOriginalAuditHandler(record -> saveAuditEvent(record.getAuditEvent()));
        eventAudit.bindOriginalAuditHandler(record -> saveAuditEvent(record.getAuditEvent()));
        commandAudit.bindTransactionManager(transactionManager);
        eventAudit.bindTransactionManager(transactionManager);
    }

    @Override
    public <T> E logCommandInTransaction(T command, String requestId) {
        E event = eventFactory.get();
        try {
            String payload = objectMapper.writeValueAsString(command);
            String commandEventType = detectPolicyOrigin(command.getClass().getSimpleName());
            String userId = MDC.get(MdcContextKeys.USER_ID) != null ? MDC.get(MdcContextKeys.USER_ID) : "";
            long uniqueTimestamp = uniqueTimestamp();

            event.setCreatedAt((double) uniqueTimestamp);
            event.setRequestId(requestId);
            event.setEventOrder(RequestSequence.next(requestId));
            event.setEventType(commandEventType);
            event.setPayload(payload);
            event.setRequestUserEmail(userId);
            event.setSuccess(false);

            commandAudit.execute(new CommandAuditRecord(command, requestId, event));
            logger.info("AUDIT: Command logged in transaction with createdAt = {}", uniqueTimestamp);
            return event;
        } catch (Exception e) {
            // Side effect on this thread (REQUIRES_NEW TX, not a new thread).
            // Persist TX already rolled back — never abort CommandHandler.
            logger.error("AUDIT ERROR: Failed to save command: {}", e.getMessage(), e);
            return event;
        }
    }

    @Override
    public <T> E logEventInTransaction(T domainEvent, String requestId) {
        E event = eventFactory.get();
        try {
            String payload = objectMapper.writeValueAsString(domainEvent);
            String eventType = domainEvent.getClass().getSimpleName();
            String userId = MDC.get(MdcContextKeys.USER_ID) != null ? MDC.get(MdcContextKeys.USER_ID) : "anonymous";
            long uniqueTimestamp = uniqueTimestamp();

            event.setCreatedAt((double) uniqueTimestamp);
            event.setRequestId(requestId);
            event.setEventType(eventType);
            event.setPayload(payload);
            event.setRequestUserEmail(userId);
            event.setSuccess(true);

            eventAudit.execute(new EventAuditRecord(domainEvent, requestId, event));
            logger.info("AUDIT: Event [{}] logged for requestId={}", eventType, requestId);
            return event;
        } catch (Exception e) {
            logger.error("AUDIT ERROR: Failed to save event: {}", e.getMessage(), e);
            return event;
        }
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logSuccess(int eventId) {
        E event = eventRepository.findById(eventId).orElse(null);
        if (event == null)
            return;
        event.setSuccess(true);
        eventRepository.save(event);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void logFailure(int eventId, String error) {
        E event = eventRepository.findById(eventId).orElse(null);
        if (event == null)
            return;
        event.setSuccess(false);
        event.setFailureReason(error);
        eventRepository.save(event);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void saveAuditEvent(AuditEvent auditEvent) {
        eventRepository.save((E) auditEvent);
    }

    private long uniqueTimestamp() {
        return System.currentTimeMillis() + (System.nanoTime() % 1000);
    }

    private String detectPolicyOrigin(String commandName) {
        try {
            for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
                String className = element.getClassName();
                if (className.contains(".policy.") && className.endsWith("Policy")) {
                    String policyName = className.substring(className.lastIndexOf('.') + 1);
                    String eventName = deriveEventNameFromMethod(element.getMethodName());
                    return eventName != null
                            ? eventName + " > " + policyName + " > " + commandName
                            : policyName + " > " + commandName;
                }
            }
            return commandName;
        } catch (Exception e) {
            logger.warn("Failed to detect policy origin: {}", e.getMessage());
            return commandName;
        }
    }

    private String deriveEventNameFromMethod(String methodName) {
        try {
            if (methodName == null)
                return null;
            int idx = methodName.lastIndexOf("On");
            if (idx >= 0) {
                String eventPart = methodName.substring(idx + 2);
                if (!eventPart.isEmpty()) {
                    String eventName = Character.toUpperCase(eventPart.charAt(0)) + eventPart.substring(1);
                    return eventName.endsWith("Event") ? eventName : eventName + "Event";
                }
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
