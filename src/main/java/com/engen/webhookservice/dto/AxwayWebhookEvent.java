package com.engen.webhookservice.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Axway Amplify Central specific webhook event structure
 * Based on official Axway documentation
 */
public class AxwayWebhookEvent {
    
    @NotBlank(message = "Event ID is required")
    @JsonProperty("id")
    private String id;
    
    @JsonProperty("time")
    private String time;
    
    @JsonProperty("version")
    private String version;
    
    @JsonProperty("product")
    private String product;
    
    @NotBlank(message = "Event type is required")
    @JsonProperty("type")
    private String type;
    
    @JsonProperty("correlationId")
    private String correlationId;
    
    @JsonProperty("organization")
    private Organization organization;
    
    @JsonProperty("payload")
    private Map<String, Object> payload = new HashMap<>();
    
    // Capture any additional fields not explicitly mapped
    private Map<String, Object> additionalProperties = new HashMap<>();
    
    @JsonAnySetter
    public void setAdditionalProperty(String key, Object value) {
        additionalProperties.put(key, value);
    }
    
    // Inner class for Organization
    public static class Organization {
        @JsonProperty("id")
        private String id;
        
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
    }
    
    // Convert to generic WebhookEvent for processing
    public WebhookEvent toWebhookEvent() {
        WebhookEvent event = new WebhookEvent();
        event.setEventId(this.id);
        event.setEventType(this.type);
        event.setCorrelationId(this.correlationId);
        event.setSource("axway");


        
        // Parse time string to Instant if needed
        if (this.time != null) {
            try {
                event.setTimestamp(java.time.Instant.parse(this.time.replace("+0000", "Z")));
            } catch (Exception e) {
                // Use current time if parsing fails
                event.setTimestamp(java.time.Instant.now());
            }
        }

        // Map event resource kind
        String kind = this.payload.get("kind").toString();
        event.setKind(kind);

        // Map event resource selfLink
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) this.payload.get("metadata");
        String selfLink = metadata != null ? String.valueOf(metadata.get("selfLink")) : null;
        event.setSelfLink(selfLink);

        // Map event resource references
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> references = metadata != null ? (List<Map<String, Object>>) metadata.get("references") : null;
        event.setReferences(references);

        // Include organization info in payload
        Map<String, Object> enrichedPayload = new HashMap<>(this.payload);
        if (this.organization != null) {
            enrichedPayload.put("organizationId", this.organization.getId());
        }
        enrichedPayload.put("version", this.version);
        enrichedPayload.put("product", this.product);
        
        event.setPayload(enrichedPayload);
        event.setAdditionalProperties(this.additionalProperties);
        
        return event;
    }

    // Getters and Setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    
    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }
    
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    
    public String getProduct() { return product; }
    public void setProduct(String product) { this.product = product; }
    
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    
    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) { this.correlationId = correlationId; }
    
    public Organization getOrganization() { return organization; }
    public void setOrganization(Organization organization) { this.organization = organization; }
    
    public Map<String, Object> getPayload() { return payload; }
    public void setPayload(Map<String, Object> payload) { this.payload = payload; }
    
    public Map<String, Object> getAdditionalProperties() { return additionalProperties; }
    public void setAdditionalProperties(Map<String, Object> additionalProperties) { this.additionalProperties = additionalProperties; }

    @Override
    public String toString() {
        return "AxwayWebhookEvent{" +
                "id='" + id + '\'' +
                ", time='" + time + '\'' +
                ", version='" + version + '\'' +
                ", product='" + product + '\'' +
                ", type='" + type + '\'' +
                ", correlationId='" + correlationId + '\'' +
                ", organization=" + organization +
                ", payload=" + payload +
                ", additionalProperties=" + additionalProperties +
                '}';
    }
}