package com.engen.webhookservice.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * ServiceNow-specific webhook event structure for approval/rejection events.
 * Matches payload format:
 * {
 *   "event": "change.rejected",
 *   "timestamp": "2025-11-18T14:30:00Z",
 *   "data": {
 *     "request_number": "REQ0012345",
 *     "correlation_id": "corr-123abc",
 *     "approval_status": "rejected",
 *     "approved_by": "user@company.com",
 *     "comments": "..."
 *   }
 * }
 */
public class ServiceNowWebhookEvent {

    @NotBlank(message = "Event is required")
    @Size(max = 256)
    @JsonProperty("event")
    private String event;

    @Size(max = 64)
    @JsonProperty("timestamp")
    private String timestamp;

    @JsonProperty("data")
    private Data data;

    private Map<String, Object> additionalProperties = new HashMap<>();

    @JsonAnySetter
    public void setAdditionalProperty(String key, Object value) {
        additionalProperties.put(key, value);
    }

    public static class Data {
        @Size(max = 256)
        @JsonProperty("request_number")
        private String requestNumber;

        @Size(max = 512)
        @JsonProperty("correlation_id")
        private String correlationId;

        @Size(max = 64)
        @JsonProperty("approval_status")
        private String approvalStatus;

        @Size(max = 256)
        @JsonProperty("approved_by")
        private String approvedBy;

        @Size(max = 2048)
        @JsonProperty("comments")
        private String comments;

        public String getRequestNumber() { return requestNumber; }
        public void setRequestNumber(String requestNumber) { this.requestNumber = requestNumber; }

        public String getCorrelationId() { return correlationId; }
        public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }

        public String getApprovalStatus() { return approvalStatus; }
        public void setApprovalStatus(String approvalStatus) { this.approvalStatus = approvalStatus; }

        public String getApprovedBy() { return approvedBy; }
        public void setApprovedBy(String approvedBy) { this.approvedBy = approvedBy; }

        public String getComments() { return comments; }
        public void setComments(String comments) { this.comments = comments; }
    }

    public WebhookEvent toWebhookEvent() {
        WebhookEvent webhookEvent = new WebhookEvent();
        webhookEvent.setSource("servicenow");
        webhookEvent.setEventType(this.event);

        String requestNumber = data != null ? data.getRequestNumber() : null;
        webhookEvent.setEventId(requestNumber != null && !requestNumber.isBlank()
            ? requestNumber
            : "snow-" + System.currentTimeMillis());

        if (data != null) {
            webhookEvent.setCorrelationId(data.getCorrelationId());
        }

        if (timestamp != null) {
            try {
                webhookEvent.setTimestamp(Instant.parse(timestamp));
            } catch (Exception e) {
                webhookEvent.setTimestamp(Instant.now());
            }
        }

        Map<String, Object> payload = new HashMap<>();
        if (data != null) {
            if (data.getRequestNumber() != null) payload.put("request_number", data.getRequestNumber());
            if (data.getCorrelationId() != null) payload.put("correlation_id", data.getCorrelationId());
            if (data.getApprovalStatus() != null) payload.put("approval_status", data.getApprovalStatus());
            if (data.getApprovedBy() != null) payload.put("approved_by", data.getApprovedBy());
            if (data.getComments() != null) payload.put("comments", data.getComments());
        }
        webhookEvent.setPayload(payload);
        webhookEvent.setAdditionalProperties(this.additionalProperties);

        return webhookEvent;
    }

    public String getEvent() { return event; }
    public void setEvent(String event) { this.event = event; }

    public String getTimestamp() { return timestamp; }
    public void setTimestamp(String timestamp) { this.timestamp = timestamp; }

    public Data getData() { return data; }
    public void setData(Data data) { this.data = data; }

    public Map<String, Object> getAdditionalProperties() { return additionalProperties; }
    public void setAdditionalProperties(Map<String, Object> additionalProperties) { this.additionalProperties = additionalProperties; }
}
