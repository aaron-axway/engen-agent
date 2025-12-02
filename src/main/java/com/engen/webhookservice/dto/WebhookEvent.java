package com.engen.webhookservice.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WebhookEvent {
    
    @NotBlank(message = "Event ID is required")
    @JsonProperty("id")
    private String eventId;
    
    @NotBlank(message = "Event type is required")
    @JsonProperty("eventType")
    private String eventType;

    @NotBlank(message = "Event kind is required")
    @JsonProperty("kind")
    private String kind;

    @NotBlank(message = "Event selflink is required")
    @JsonProperty("selfLink")
    private String selfLink;

    @NotBlank(message = "Event references is required")
    @JsonProperty("references")
    private List<Map<String, Object>> references;

    @JsonProperty("timestamp")
    private Instant timestamp = Instant.now();
    
    @JsonProperty("source")
    private String source;
    
    @JsonProperty("correlationId")
    private String correlationId;
    
    @JsonProperty("payload")
    private Map<String, Object> payload = new HashMap<>();
    
    // Capture any additional fields not explicitly mapped
    private Map<String, Object> additionalProperties = new HashMap<>();
    
    @JsonAnySetter
    public void setAdditionalProperty(String key, Object value) {
        additionalProperties.put(key, value);
    }

    // Getters and Setters
    public String getEventId() { return eventId; }
    public void setEventId(String eventId) { this.eventId = eventId; }
    
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getKind() { return kind; }
    public void setKind(String kind) { this.kind = kind; }

    public String getSelfLink() { return selfLink; }
    public void setSelfLink(String selfLink) { this.selfLink = selfLink; }

    public List<Map<String, Object>> getReferences() { return references; }
    public void setReferences(List<Map<String, Object>> references) { this.references = references; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    
    public Map<String, Object> getAdditionalProperties() { return additionalProperties; }
    public void setAdditionalProperties(Map<String, Object> additionalProperties) { this.additionalProperties = additionalProperties; }

    @Override
    public String toString() {
        return "WebhookEvent{" +
                "eventId='" + eventId + '\'' +
                ", eventType='" + eventType + '\'' +
                ", timestamp=" + timestamp +
                ", source='" + source + '\'' +
                ", correlationId='" + correlationId + '\'' +
                ", payload=" + payload +
                ", additionalProperties=" + additionalProperties +
                '}';
    }
}