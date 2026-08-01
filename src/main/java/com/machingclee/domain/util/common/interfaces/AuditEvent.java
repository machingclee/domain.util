package com.machingclee.domain.util.common.interfaces;

/**
 * Common interface for audit event entities (EChargeEvent, EcapiEvent).
 * Allows AbstractCommandInvoker to work with both event types generically.
 */
public interface AuditEvent {
    Integer getId();

    Boolean getSuccess();

    void setCreatedAt(Double createdAt);

    void setEventType(String eventType);

    void setPayload(String payload);

    void setRequestUserEmail(String requestUserEmail);

    void setRequestId(String requestId);

    void setSuccess(Boolean success);

    void setFailureReason(String failureReason);

    void setEventOrder(Integer order);
}
