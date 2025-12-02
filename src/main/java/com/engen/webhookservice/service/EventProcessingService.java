package com.engen.webhookservice.service;

import com.engen.webhookservice.dto.WebhookEvent;
import com.engen.webhookservice.entity.EventRecord;
import com.engen.webhookservice.repository.EventRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@Transactional
public class EventProcessingService {

    private static final Logger log = LoggerFactory.getLogger(EventProcessingService.class);

    private final EventRecordRepository eventRecordRepository;
    private final AxwayApiService axwayApiService;
    private final HighmarkCatalogApiService highmarkCatalogApiService;

    @Value("${webhook.axway.ignored-event-types:}")
    private List<String> ignoredAxwayEventTypes;

    @Autowired(required = false)
    private EmailService emailService;

    public EventProcessingService(EventRecordRepository eventRecordRepository,
                                  AxwayApiService axwayApiService,
                                  HighmarkCatalogApiService highmarkCatalogApiService) {
        this.eventRecordRepository = eventRecordRepository;
        this.axwayApiService = axwayApiService;
        this.highmarkCatalogApiService = highmarkCatalogApiService;
    }

    public void processAxwayEvent(WebhookEvent event, Map<String, String> headers) {
        log.info("Processing Axway event: {} with ID: {}", event.getEventType(), event.getEventId());

        // Check if this event type should be ignored
        if (ignoredAxwayEventTypes != null && ignoredAxwayEventTypes.contains(event.getEventType())) {
            log.debug("Ignoring Axway event type '{}' as it's in the ignored list", event.getEventType());

            // Still save the event to database for audit purposes, but mark it as ignored
            EventRecord record = saveEventRecord(event, "AXWAY", headers);
            record.setStatus("IGNORED");
            record.setProcessedAt(Instant.now());
            eventRecordRepository.save(record);

            return;  // Exit early without processing
        }

        try {
            // Save event to database
            EventRecord record = saveEventRecord(event, "AXWAY", headers);

            // Process based on event type
            switch (event.getEventType()) {
                case "ResourceCreated":
                    handleAxwayCreatedEvent(event);
                    break;
                default:
                    log.warn("Unknown Axway event type: {}", event.getEventType());
            }

            // Update record status
            record.setStatus("PROCESSED");
            record.setProcessedAt(Instant.now());
            eventRecordRepository.save(record);

        } catch (Exception e) {
            log.error("Error processing Axway event: {}", event.getEventId(), e);
            if (emailService != null && emailService.isEmailEnabled()) {
                emailService.sendErrorNotification(event, "Failed to process Axway event", e);
            }
            throw e;
        }
    }

    public void processServiceNowEvent(WebhookEvent event, Map<String, String> headers) {
        log.info("Processing ServiceNow event: {} with ID: {}", event.getEventType(), event.getEventId());

        // Save event to database
        EventRecord record = saveEventRecord(event, "SERVICENOW", headers);

        // Process based on event type
        switch (event.getEventType()) {
            case "incident.created":
                handleServiceNowIncidentCreated(event);
                break;
            case "incident.updated":
                handleServiceNowIncidentUpdated(event);
                break;
            case "incident.resolved":
                handleServiceNowIncidentResolved(event);
                break;
            case "change.requested":
                handleServiceNowChangeRequested(event);
                break;
            case "change.approved":
                handleServiceNowChangeApproved(event);
                break;
            default:
                log.warn("Unknown ServiceNow event type: {}", event.getEventType());
        }

        // Update record status
        record.setStatus("PROCESSED");
        record.setProcessedAt(Instant.now());
        eventRecordRepository.save(record);
    }

    private EventRecord saveEventRecord(WebhookEvent event, String source, Map<String, String> headers) {
        EventRecord record = new EventRecord();
        record.setEventId(event.getEventId());
        record.setEventType(event.getEventType());
        record.setSource(source);
        record.setPayload(event.getPayload());
        record.setHeaders(headers);
        record.setReceivedAt(Instant.now());
        record.setStatus("RECEIVED");
        record.setCorrelationId(event.getCorrelationId());

        return eventRecordRepository.save(record);
    }

    // Axway event handlers
    private void handleAxwayCreatedEvent(WebhookEvent event) {
        log.info("Handling Axway API {} event, resource kind: {}", event.getEventType(), event.getKind());

        if (event.getKind().equals("AssetRequest")) {
            String selfLink = event.getSelfLink();
            Map<String, Object> assetReq = axwayApiService.getResourceBySelflink(selfLink);
            List<Map<String, Object>> references = event.getReferences();
            for (Map<String, Object> ref : references) {
                String assetSelfLink = ref.get("selfLink").toString();
                String refKind = ref.get("kind").toString();
                if (refKind.equals("Asset")) {
                    Map<String, Object> asset = axwayApiService.getResourceBySelflink(assetSelfLink);
                    Map<String, Object> owner = (Map<String, Object>) asset.get("owner");
                    if (owner != null) {
                        String ownerId = owner.get("id").toString();
                        Map<String, Object> team = axwayApiService.getTeam(ownerId);
                        List<Object> users = (List<Object>) team.get("users");

                        List<String> emails = new java.util.ArrayList<>();
                        for (Object u : users) {
                            List<String> roles = (List<String>) ((Map<String, Object>) u).get("roles");
                            for (String role : roles) {
                                if (role.equals("api_access")) {
                                    System.out.println("API Access Manager found");
                                    @SuppressWarnings("unchecked")
                                    Map<String, Object> user = (Map<String, Object>) u;
                                    String guid = (String) user.get("guid");
                                    Map<String, Object> ud = axwayApiService.getUser(guid);
                                    System.out.println(ud.get("email"));
                                    emails.add(ud.get("email").toString());
                                }
                            }

                        }

                        // Auto-approve the AssetRequest in Axway
                        axwayApiService.setApproved(selfLink);

                        // Create ServiceNow catalog item request via Highmark OAuth
                        if (highmarkCatalogApiService.isApiEnabled()) {
                            try {
                                Map<String, Object> catalogVariables = buildCatalogVariables(
                                    assetReq, asset, team, emails, selfLink, event
                                );

                                log.info("Submitting catalog item request with variables: {}", catalogVariables.keySet());
                                Map<String, Object> catalogResponse = highmarkCatalogApiService.submitCatalogItemRequest(catalogVariables);

                                if (catalogResponse != null && catalogResponse.containsKey("request_number")) {
                                    log.info("Successfully created ServiceNow catalog request: {}",
                                             catalogResponse.get("request_number"));

                                    // Update event record with catalog request info
                                    String eventId = event.getEventId();
                                    if (eventId != null) {
                                        updateEventWithTicketInfo(eventId, catalogResponse.get("request_number").toString());
                                    }
                                } else {
                                    log.warn("Catalog request response missing request_number: {}", catalogResponse);
                                }
                            } catch (Exception e) {
                                log.error("Failed to submit catalog item request to Highmark", e);
                                if (emailService != null) {
                                    emailService.sendErrorNotification(
                                        event,
                                        "Failed to create catalog request for AssetRequest: " + selfLink,
                                        e
                                    );
                                }
                            }
                        } else {
                            log.warn("Highmark Catalog API is disabled - skipping catalog request creation");
                        }

//                    if(emails.size() > 0) {
//                        // Send email notification to API Access Managers
//                        if (emailService != null && emailService.isEmailEnabled()) {
//                            String subject = "New API Access Request - Approval Required";
//                            String body = String.format(
//                                "A new API access request has been submitted and requires your approval.\n\n" +
//                                "Access Request Details:\n" +
//                                "- Event ID: %s\n" +
//                                "- Event Type: %s\n" +
//                                "- Team ID: %s\n\n" +
//                                "Please review and approve this request in the Axway platform.\n\n" +
//                                "API Access Managers notified: %s",
//                                event.getEventId(),
//                                event.getEventType(),
//                                ownerId,
//                                String.join(", ", emails)
//                            );
//
//                            try {
//                                //emailService.sendEmail(emails, subject, body);
//                                log.info("Sent access request notification to {} API Access Managers", emails.size());
//                            } catch (Exception e) {
//                                log.error("Failed to send access request notification emails", e);
//                            }
//                        } else {
//                            log.warn("Email service not configured - cannot send access request notifications to: {}", emails);
//                        }
//                    }
                    }
                }
            }
        }
    }



    // ServiceNow event handlers
    private void handleServiceNowIncidentCreated(WebhookEvent event) {
        log.info("Handling ServiceNow incident created event");
        // Implement specific business logic for incident creation
    }

    private void handleServiceNowIncidentUpdated(WebhookEvent event) {
        log.info("Handling ServiceNow incident updated event");
        // Implement specific business logic for incident update
    }

    private void handleServiceNowIncidentResolved(WebhookEvent event) {
        log.info("Handling ServiceNow incident resolved event");
        // Implement specific business logic for incident resolution
    }

    private void handleServiceNowChangeRequested(WebhookEvent event) {
        // Legacy method - ServiceNow direct API integration has been replaced by Highmark Catalog API
        // This method is no longer active as the workflow now uses Highmark for catalog item requests
        log.info("Received ServiceNow change requested event - legacy workflow (no longer processed)");
        log.warn("ServiceNow change.requested events are not processed in the current Highmark workflow");
    }

    private Object extractFromPayload(Map<String, Object> payload, String key) {
        if (payload == null) return null;

        // Direct key
        Object value = payload.get(key);
        if (value != null) return value;

        // Check in data object
        Object data = payload.get("data");
        if (data instanceof Map) {
            value = ((Map<String, Object>) data).get(key);
            if (value != null) return value;
        }

        // Check in payload object
        Object nestedPayload = payload.get("payload");
        if (nestedPayload instanceof Map) {
            value = ((Map<String, Object>) nestedPayload).get(key);
        }

        return value;
    }

    private void handleServiceNowChangeApproved(WebhookEvent event) {
        log.info("Handling ServiceNow change approved event");

        // Extract approval details from ServiceNow event
        String approvalComments = extractApprovalComments(event);
        String approvalState = "APPROVED";

        // Send email notification about approval
        if (emailService != null && emailService.isEmailEnabled()) {
            emailService.sendApprovalNotification(event, approvalState, approvalComments);
        }

        // Find the related Axway event using correlation ID
        if (event.getCorrelationId() != null) {
            Optional<EventRecord> relatedEvent = eventRecordRepository.findByEventId(event.getCorrelationId());
            if (relatedEvent.isPresent()) {
                log.info("Found related Axway event, updating approval state");
                axwayApiService.updateApprovalState(event.getCorrelationId(), approvalState, approvalComments);
            } else {
                log.warn("No related Axway event found for correlation ID: {}", event.getCorrelationId());
            }
        } else {
            log.warn("No correlation ID provided in ServiceNow approval event");
        }
    }

    private String extractApprovalComments(WebhookEvent event) {
        try {
            Map<String, Object> payload = event.getPayload();
            if (payload != null) {
                Object comments = payload.get("comments");
                if (comments == null) {
                    comments = payload.get("approval_comments");
                }
                if (comments == null) {
                    Object data = payload.get("data");
                    if (data instanceof Map) {
                        Map<String, Object> dataMap = (Map<String, Object>) data;
                        comments = dataMap.get("comments");
                        if (comments == null) {
                            comments = dataMap.get("approval_comments");
                        }
                    }
                }
                return comments != null ? comments.toString() : "Approved via ServiceNow";
            }
        } catch (Exception e) {
            log.error("Error extracting approval comments from event payload", e);
        }
        return "Approved via ServiceNow";
    }

    /**
     * Builds catalog variables for Highmark ServiceNow Catalog API request
     * Maps AssetRequest data to the required catalog item variables
     */
    private Map<String, Object> buildCatalogVariables(
            Map<String, Object> assetRequest,
            Map<String, Object> asset,
            Map<String, Object> team,
            List<String> apiAccessEmails,
            String selfLink,
            WebhookEvent event) {

        Map<String, Object> variables = new HashMap<>();

        try {
            // Extract metadata
            Map<String, Object> assetReqMetadata = (Map<String, Object>) assetRequest.get("metadata");
            Map<String, Object> assetMetadata = (Map<String, Object>) asset.get("metadata");

            // 1. requested_for - Use first API access manager email or default
            // Note: This should be converted to ServiceNow user sys_id in production
            String requestedFor = !apiAccessEmails.isEmpty() ? apiAccessEmails.get(0) : "default-user";
            variables.put("requested_for", requestedFor);

            // 2. need_by_date - Current date + configured days (default 30)
            java.time.LocalDate needByDate = java.time.LocalDate.now()
                .plusDays(highmarkCatalogApiService.getDefaultNeedByDays());
            String needByDateStr = needByDate.format(java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy"));
            variables.put("need_by_date", needByDateStr);

            // 3. application_name - From asset name or AssetRequest name
            String applicationName = assetMetadata != null && assetMetadata.containsKey("name")
                ? assetMetadata.get("name").toString()
                : (assetReqMetadata != null && assetReqMetadata.containsKey("name")
                    ? assetReqMetadata.get("name").toString()
                    : "Axway API Access");
            variables.put("application_name", applicationName);

            // 4. api_resource_name - From asset title or name
            String apiResourceName = asset.containsKey("title")
                ? asset.get("title").toString()
                : (assetMetadata != null && assetMetadata.containsKey("name")
                    ? assetMetadata.get("name").toString()
                    : "Unknown Resource");
            variables.put("api_resource_name", apiResourceName);

            // 5. api_owner - From team name or first API access manager
            String apiOwner = team != null && team.containsKey("name")
                ? team.get("name").toString()
                : (!apiAccessEmails.isEmpty() ? apiAccessEmails.get(0) : "Unknown Owner");
            variables.put("api_owner", apiOwner);

            // 6. data_governance - Use configured default
            variables.put("data_governance", highmarkCatalogApiService.getDefaultValues().get("data_governance"));

            // 7. dg_class_tags - Use configured default
            variables.put("dg_class_tags", highmarkCatalogApiService.getDefaultValues().get("dg_class_tags"));

            // 8. selflink_to_engage - Full Axway selfLink URL
            variables.put("selflink_to_engage", selfLink != null ? selfLink : "");

            // 9. approval_state - Use configured default
            variables.put("approval_state", highmarkCatalogApiService.getDefaultValues().get("approval_state"));

            // Additional useful fields
            if (event != null) {
                variables.put("event_id", event.getEventId());
                variables.put("event_type", event.getEventType());
            }

            // Add team information as description
            if (team != null && team.containsKey("id")) {
                variables.put("team_id", team.get("id").toString());
            }

            // Add API access managers list
            if (!apiAccessEmails.isEmpty()) {
                variables.put("api_access_managers", String.join(", ", apiAccessEmails));
            }

            log.info("Built catalog variables for AssetRequest: {}", assetReqMetadata != null ? assetReqMetadata.get("name") : "unknown");
            log.debug("Catalog variables: {}", variables);

        } catch (Exception e) {
            log.error("Error building catalog variables", e);
            // Return minimal variables on error
            variables.put("requested_for", "error-user");
            variables.put("need_by_date", java.time.LocalDate.now().plusDays(30).toString());
            variables.put("application_name", "Error Processing Request");
            variables.put("api_resource_name", "See Logs");
            variables.put("api_owner", "System Error");
            variables.put("data_governance", "Unknown");
            variables.put("dg_class_tags", "Unknown");
            variables.put("selflink_to_engage", selfLink != null ? selfLink : "");
            variables.put("approval_state", "pending");
        }

        return variables;
    }

    /**
     * Generate a ServiceNow ticket ID (simulation)
     * In a real implementation, this would call ServiceNow APIs
     */
    private String generateServiceNowTicketId(WebhookEvent event) {
        // Generate a realistic ticket ID format
        long timestamp = System.currentTimeMillis() % 1000000;
        return String.format("CHG%06d", timestamp);
    }

    /**
     * Update event record with ServiceNow ticket information
     */
    private void updateEventWithTicketInfo(String eventId, String ticketId) {
        try {
            Optional<EventRecord> eventRecord = eventRecordRepository.findByEventId(eventId);
            if (eventRecord.isPresent()) {
                EventRecord record = eventRecord.get();
                record.setCallbackStatus("SERVICENOW_TICKET_CREATED");

                // Add ticket ID to payload
                Map<String, Object> payload = record.getPayload();
                if (payload != null) {
                    payload.put("serviceNowTicketId", ticketId);
                    record.setPayload(payload);
                }

                eventRecordRepository.save(record);
                log.debug("Updated event {} with ServiceNow ticket ID: {}", eventId, ticketId);
            }
        } catch (Exception e) {
            log.error("Failed to update event {} with ticket ID {}", eventId, ticketId, e);
        }
    }
}