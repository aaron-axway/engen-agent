package com.engen.webhookservice.controller;

import com.engen.webhookservice.dto.AxwayWebhookEvent;
import com.engen.webhookservice.dto.ServiceNowWebhookEvent;
import com.engen.webhookservice.dto.WebhookEvent;
import com.engen.webhookservice.service.EventProcessingService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/webhooks")
public class WebhookController {

    private static final Logger log = LoggerFactory.getLogger(WebhookController.class);

    private final EventProcessingService eventProcessingService;
    private final ObjectMapper objectMapper;
    private final Validator validator;

    @Value("${webhook.debug.dump-payload:false}")
    private boolean dumpPayload;

    public WebhookController(EventProcessingService eventProcessingService, ObjectMapper objectMapper, Validator validator) {
        this.eventProcessingService = eventProcessingService;
        this.objectMapper = objectMapper;
        this.validator = validator;
    }

    private <T> void validateEvent(T event) {
        Set<ConstraintViolation<T>> violations = validator.validate(event);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .collect(Collectors.joining(", "));
            throw new IllegalArgumentException("Validation failed: " + message);
        }
    }

    @PostMapping(value = "/axway", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> handleAxwayWebhook(
            @RequestBody Map<String, Object> rawPayload,
            @RequestHeader Map<String, String> headers) {
        
        try {
            // Dump raw payload for debugging/capture when enabled
            if (dumpPayload) {
                try {
                    String prettyJson = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(rawPayload);
                    System.out.println("=== WEBHOOK PAYLOAD START ===");
                    System.out.println(prettyJson);
                    System.out.println("=== WEBHOOK PAYLOAD END ===");
                } catch (Exception dumpEx) {
                    log.warn("Failed to dump webhook payload", dumpEx);
                }
            }

            WebhookEvent event;

            // Check if this is an Axway-formatted event (has 'type' and 'product' fields)
            if (rawPayload.containsKey("type") && rawPayload.containsKey("product")) {
                // Parse as Axway-specific format
                AxwayWebhookEvent axwayEvent = objectMapper.convertValue(rawPayload, AxwayWebhookEvent.class);
                validateEvent(axwayEvent);
                log.info("Received Axway webhook event (native format): {}", axwayEvent.getType());
                log.debug("Axway native event payload: {}", axwayEvent);

                // Convert to generic format for processing
                event = axwayEvent.toWebhookEvent();
            } else {
                // Parse as generic webhook format (backward compatibility)
                event = objectMapper.convertValue(rawPayload, WebhookEvent.class);
                validateEvent(event);
                log.info("Received Axway webhook event (generic format): {}", event.getEventType());
                log.debug("Axway generic event payload: {}", event);
            }
            
            eventProcessingService.processAxwayEvent(event, headers);
            
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setCacheControl("no-cache, no-store, must-revalidate");
            responseHeaders.setPragma("no-cache");
            responseHeaders.setExpires(0);
            
            return ResponseEntity.ok()
                .headers(responseHeaders)
                .body(Map.of(
                    "status", "success",
                    "message", "Axway event processed successfully"
                ));
        } catch (Exception e) {
            log.error("Error processing Axway webhook", e);
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setCacheControl("no-cache, no-store, must-revalidate");
            responseHeaders.setPragma("no-cache");
            responseHeaders.setExpires(0);
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .headers(responseHeaders)
                .body(Map.of(
                    "status", "error",
                    "message", "Failed to process Axway event"
                ));
        }
    }

    @PostMapping(value = "/servicenow", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, String>> handleServiceNowWebhook(
            @RequestBody Map<String, Object> rawPayload,
            @RequestHeader Map<String, String> headers) {

        try {
            WebhookEvent event;

            // Check if this is the new ServiceNow format (has top-level 'event' + 'data' fields)
            if (rawPayload.containsKey("event") && rawPayload.containsKey("data")) {
                ServiceNowWebhookEvent snowEvent = objectMapper.convertValue(rawPayload, ServiceNowWebhookEvent.class);
                validateEvent(snowEvent);
                log.info("Received ServiceNow webhook event (native format): {}", snowEvent.getEvent());
                event = snowEvent.toWebhookEvent();
            } else {
                // Parse as generic webhook format (backward compatibility)
                event = objectMapper.convertValue(rawPayload, WebhookEvent.class);
                validateEvent(event);
                log.info("Received ServiceNow webhook event (generic format): {}", event.getEventType());
                log.debug("ServiceNow event payload: {}", event);
            }

            eventProcessingService.processServiceNowEvent(event, headers);
            
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setCacheControl("no-cache, no-store, must-revalidate");
            responseHeaders.setPragma("no-cache");
            responseHeaders.setExpires(0);
            
            return ResponseEntity.ok()
                .headers(responseHeaders)
                .body(Map.of(
                    "status", "success",
                    "message", "ServiceNow event processed successfully"
                ));
        } catch (Exception e) {
            log.error("Error processing ServiceNow webhook", e);
            HttpHeaders responseHeaders = new HttpHeaders();
            responseHeaders.setCacheControl("no-cache, no-store, must-revalidate");
            responseHeaders.setPragma("no-cache");
            responseHeaders.setExpires(0);
            
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .headers(responseHeaders)
                .body(Map.of(
                    "status", "error",
                    "message", "Failed to process ServiceNow event"
                ));
        }
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, String>> health() {
        return ResponseEntity.ok(Map.of(
            "status", "healthy",
            "service", "webhook-service"
        ));
    }
}