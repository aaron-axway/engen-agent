package com.engen.webhookservice.entity;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "event_records")
public class EventRecord {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "event_id", nullable = false)
    private String eventId;
    
    @Column(name = "event_type", nullable = false)
    private String eventType;
    
    @Column(name = "source", nullable = false)
    private String source;
    
    @Column(name = "correlation_id")
    private String correlationId;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "TEXT")
    private Map<String, Object> payload;
    
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", columnDefinition = "TEXT")
    private Map<String, String> headers;
    
    @Column(name = "status", nullable = false)
    private String status;
    
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    
    @Column(name = "processed_at")
    private Instant processedAt;
    
    @Column(name = "error_message")
    private String errorMessage;
    
    @Column(name = "retry_count")
    private Integer retryCount = 0;
    
    @Column(name = "related_event_id")
    private String relatedEventId;
    
    @Column(name = "approval_state")
    private String approvalState;
    
    @Column(name = "callback_status")
    private String callbackStatus;
    
    @Column(name = "callback_attempted_at")
    private Instant callbackAttemptedAt;
    
    @Version
    private Long version;

    // Getters and Setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    
    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }
    
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    
    public Instant getReceivedAt() { return receivedAt; }
    public void setReceivedAt(Instant receivedAt) { this.receivedAt = receivedAt; }
    
    public Instant getProcessedAt() { return processedAt; }
    public void setProcessedAt(Instant processedAt) { this.processedAt = processedAt; }
    
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    
    public Integer getRetryCount() { return retryCount; }
    public void setRetryCount(Integer retryCount) { this.retryCount = retryCount; }
    
    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }
    
    public String getRelatedEventId() { return relatedEventId; }
    public void setRelatedEventId(String relatedEventId) { this.relatedEventId = relatedEventId; }
    
    public String getApprovalState() { return approvalState; }
    public void setApprovalState(String approvalState) { this.approvalState = approvalState; }
    
    public String getCallbackStatus() { return callbackStatus; }
    public void setCallbackStatus(String callbackStatus) { this.callbackStatus = callbackStatus; }
    
    public Instant getCallbackAttemptedAt() { return callbackAttemptedAt; }
    public void setCallbackAttemptedAt(Instant callbackAttemptedAt) { this.callbackAttemptedAt = callbackAttemptedAt; }
}