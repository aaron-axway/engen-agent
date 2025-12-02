package com.engen.webhookservice.service;

import com.engen.webhookservice.entity.EventRecord;
import com.engen.webhookservice.repository.EventRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

@Service
public class AxwayApiService {

    private static final Logger log = LoggerFactory.getLogger(AxwayApiService.class);

    private final RestTemplate restTemplate;
    private final EventRecordRepository eventRecordRepository;
    private final AxwayOAuthService axwayOAuthService;

    @Value("${webhook.axway.api.base-url:}")
    private String axwayApiBaseUrl;

    @Value("${webhook.axway.platform.base-url:https://platform.axway.com/api/v1}")
    private String axwayPlatformBaseUrl;

    @Value("${webhook.axway.api.token:}")
    private String axwayApiToken;

    public AxwayApiService(EventRecordRepository eventRecordRepository, AxwayOAuthService axwayOAuthService) {
        this.restTemplate = new RestTemplate();
        this.eventRecordRepository = eventRecordRepository;
        this.axwayOAuthService = axwayOAuthService;
    }

    /**
     * Gets the authorization header for Axway API calls
     * Uses OAuth JWT if configured, otherwise falls back to static token
     * @return Authorization header value or null if not configured
     */
    private String getAuthorizationHeader() {
        // Try OAuth JWT first
        if (axwayOAuthService.isOAuthEnabled()) {
            String accessToken = axwayOAuthService.getAccessToken();
            if (accessToken != null) {
                return "Bearer " + accessToken;
            }
        }
        
        // Fallback to static token
        if (axwayApiToken != null && !axwayApiToken.isEmpty()) {
            return "Bearer " + axwayApiToken;
        }
        
        return null;
    }

    public void updateApprovalState(String eventId, String approvalState, String approvalComments) {
        Optional<EventRecord> eventRecordOpt = eventRecordRepository.findByEventId(eventId);
        
        if (eventRecordOpt.isEmpty()) {
            log.error("Event record not found for eventId: {}", eventId);
            return;
        }

        EventRecord eventRecord = eventRecordOpt.get();
        
        try {
            log.info("Updating Axway approval state for event {} to: {}", eventId, approvalState);
            
            // Extract the request ID from the original event payload
            String requestId = extractRequestId(eventRecord);
            if (requestId == null) {
                log.error("Unable to extract request ID from event payload for event: {}", eventId);
                eventRecord.setCallbackStatus("FAILED_NO_REQUEST_ID");
                eventRecord.setCallbackAttemptedAt(Instant.now());
                eventRecordRepository.save(eventRecord);
                return;
            }

            // Prepare the approval update payload
            Map<String, Object> approvalPayload = Map.of(
                "requestId", requestId,
                "approvalState", approvalState,
                "comments", approvalComments != null ? approvalComments : "",
                "approvedBy", "webhook-service",
                "timestamp", Instant.now().toString()
            );

            // Make API call to Axway
            boolean success = callAxwayApprovalApi(requestId, approvalPayload);
            
            if (success) {
                eventRecord.setApprovalState(approvalState);
                eventRecord.setCallbackStatus("SUCCESS");
                log.info("Successfully updated Axway approval state for request: {}", requestId);
            } else {
                eventRecord.setCallbackStatus("FAILED_API_CALL");
                log.error("Failed to update Axway approval state for request: {}", requestId);
            }
            
        } catch (Exception e) {
            log.error("Error updating Axway approval state for event: {}", eventId, e);
            eventRecord.setCallbackStatus("FAILED_EXCEPTION");
            eventRecord.setErrorMessage(e.getMessage());
        } finally {
            eventRecord.setCallbackAttemptedAt(Instant.now());
            eventRecordRepository.save(eventRecord);
        }
    }

    private String extractRequestId(EventRecord eventRecord) {
        try {
            Map<String, Object> payload = eventRecord.getPayload();
            if (payload == null) {
                return null;
            }
            
            // Check different possible locations for request ID
            Object requestId = payload.get("requestId");
            if (requestId == null) {
                requestId = payload.get("request_id");
            }
            if (requestId == null) {
                requestId = payload.get("id");
            }
            if (requestId == null) {
                // Check nested data object
                Object data = payload.get("data");
                if (data instanceof Map) {
                    Map<String, Object> dataMap = (Map<String, Object>) data;
                    requestId = dataMap.get("requestId");
                    if (requestId == null) {
                        requestId = dataMap.get("request_id");
                    }
                    if (requestId == null) {
                        requestId = dataMap.get("id");
                    }
                }
            }
            
            return requestId != null ? requestId.toString() : null;
        } catch (Exception e) {
            log.error("Error extracting request ID from event payload", e);
            return null;
        }
    }

    private boolean callAxwayApprovalApi(String requestId, Map<String, Object> approvalPayload) {
        if (axwayApiBaseUrl.isEmpty()) {
            log.warn("Axway API configuration missing - base URL not configured");
            return false;
        }

        String authHeader = getAuthorizationHeader();
        if (authHeader == null) {
            log.warn("Axway API authentication not configured - no OAuth JWT or static token available");
            return false;
        }

        try {
            String url = axwayApiBaseUrl + "/requests/" + requestId + "/approval";
            
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", authHeader);
            headers.set("X-Requested-By", "webhook-service");

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(approvalPayload, headers);
            
            restTemplate.exchange(url, HttpMethod.PUT, request, Map.class);
            
            return true;
        } catch (Exception e) {
            log.error("Failed to call Axway approval API for request: {}", requestId, e);
            return false;
        }
    }

    public Optional<EventRecord> findEventByRequestId(String requestId) {
        return eventRecordRepository.findByRelatedEventId(requestId);
    }

    public void linkEvents(String originalEventId, String relatedEventId) {
        Optional<EventRecord> eventRecordOpt = eventRecordRepository.findByEventId(originalEventId);
        if (eventRecordOpt.isPresent()) {
            EventRecord eventRecord = eventRecordOpt.get();
            eventRecord.setRelatedEventId(relatedEventId);
            eventRecordRepository.save(eventRecord);
            log.debug("Linked event {} to related event {}", originalEventId, relatedEventId);
        }
    }
    
    /**
     * Fetch API Service details from Axway Amplify
     */
    public Map<String, Object> getApiService(String apiServiceId) {
        if (axwayApiBaseUrl.isEmpty()) {
            log.warn("Axway API configuration missing - base URL not configured");
            return null;
        }

        String authHeader = getAuthorizationHeader();
        if (authHeader == null) {
            log.warn("Axway API authentication not configured - cannot fetch API service details");
            return null;
        }
        
        try {
            String url = axwayApiBaseUrl + "/apis/management/v1alpha1/apiservices/" + apiServiceId;
            log.debug("Fetching API service details from: {}", url);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", authHeader);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            
            HttpEntity<Void> request = new HttpEntity<>(headers);
            Map<String, Object> response = restTemplate.exchange(url, HttpMethod.GET, request, Map.class).getBody();
            
            log.info("Successfully fetched API service details for: {}", apiServiceId);
            return response;
        } catch (Exception e) {
            log.error("Failed to fetch API service details for: {}", apiServiceId, e);
            return null;
        }
    }
    
    /**
     * Fetch API Service Instance details from Axway Amplify
     */
    public Map<String, Object> getApiServiceInstance(String environmentName, String instanceName) {
        if (axwayApiBaseUrl.isEmpty()) {
            log.warn("Axway API configuration missing - base URL not configured");
            return null;
        }

        String authHeader = getAuthorizationHeader();
        if (authHeader == null) {
            log.warn("Axway API authentication not configured - cannot fetch API service instance");
            return null;
        }
        
        try {
            String url = String.format("%s/apis/management/v1alpha1/environments/%s/apiserviceinstances/%s",
                axwayApiBaseUrl, environmentName, instanceName);
            log.debug("Fetching API service instance from: {}", url);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", authHeader);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            
            HttpEntity<Void> request = new HttpEntity<>(headers);
            Map<String, Object> response = restTemplate.exchange(url, HttpMethod.GET, request, Map.class).getBody();
            
            log.info("Successfully fetched API service instance: {}/{}", environmentName, instanceName);
            return response;
        } catch (Exception e) {
            log.error("Failed to fetch API service instance: {}/{}", environmentName, instanceName, e);
            return null;
        }
    }
    
    /**
     * Fetch Subscription details from Axway Amplify
     */
    public Map<String, Object> getSubscription(String subscriptionId) {
        if (axwayApiBaseUrl.isEmpty()) {
            log.warn("Axway API configuration missing - base URL not configured");
            return null;
        }

        String authHeader = getAuthorizationHeader();
        if (authHeader == null) {
            log.warn("Axway API authentication not configured - cannot fetch subscription details");
            return null;
        }
        
        try {
            String url = axwayApiBaseUrl + "/apis/management/v1alpha1/subscriptions/" + subscriptionId;
            log.debug("Fetching subscription details from: {}", url);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", authHeader);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            
            HttpEntity<Void> request = new HttpEntity<>(headers);
            Map<String, Object> response = restTemplate.exchange(url, HttpMethod.GET, request, Map.class).getBody();
            
            log.info("Successfully fetched subscription details for: {}", subscriptionId);
            return response;
        } catch (Exception e) {
            log.error("Failed to fetch subscription details for: {}", subscriptionId, e);
            return null;
        }
    }

    public Map<String, Object> getTeam(String teamId) {
        if (axwayPlatformBaseUrl.isEmpty()) {
            log.warn("Axway Platform API configuration missing - platform base URL not configured");
            return null;
        }
        
        String url = String.format("%s/team/%s", axwayPlatformBaseUrl, teamId);
        Map<String, Object> response = getResource(url);
        @SuppressWarnings( "unchecked")
        Map<String, Object> team = (Map<String, Object>) response.get("result");
        return team;
    }

    public Map<String, Object> getUser(String userId) {
        if (axwayPlatformBaseUrl.isEmpty()) {
            log.warn("Axway Platform API configuration missing - platform base URL not configured");
            return null;
        }

        String url = String.format("%s/user/%s", axwayPlatformBaseUrl, userId);
        Map<String, Object> response = getResource(url);
        @SuppressWarnings( "unchecked")
        Map<String, Object> user = (Map<String, Object>) response.get("result");
        return user;
    }

    public Map<String, Object> setApproved(String selfLink) {
        return updateApprovalSubResource(selfLink, "approved");
    }

    public Map<String, Object> updateApprovalSubResource(String selfLink, String state) {
        String link = String.format("%s/approval", selfLink);
        Map<String, Object> payload = Map.of("approval", Map.of(
                        "state", Map.of(
                                "name", state,
                                "reason", "from code"
                        )
                )
        );
        return updateResourceBySelflink(link, payload);
    }

    public Map<String, Object> getResourceBySelflink(String selfLink) {
//        if (axwayApiBaseUrl.isEmpty()) {
//            log.warn("Axway API configuration missing - base URL not configured");
//            return null;
//        }
//
//        String authHeader = getAuthorizationHeader();
//        if (authHeader == null) {
//            log.warn("Axway API authentication not configured - cannot fetch access request");
//            return null;
//        }

        String url = String.format("%s/apis%s", axwayApiBaseUrl, selfLink);
        return getResource(url);
    }
    
    /**
     * Generic method to fetch any resource from Axway API
     */
    public Map<String, Object> getResource(String url) {
        String authHeader = getAuthorizationHeader();
        if (authHeader == null) {
            log.warn("Axway API authentication not configured - cannot fetch resource");
            return null;
        }
        
        try {
            log.debug("Fetching Axway resource from: {}", url);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", authHeader);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            
            HttpEntity<Void> request = new HttpEntity<>(headers);
            Map<String, Object> response = restTemplate.exchange(url, HttpMethod.GET, request, Map.class).getBody();
            
            log.info("Successfully fetched resource from: {}", url);
            return response;
        } catch (Exception e) {
            log.error("Failed to fetch resource from: {}", url, e);
            return null;
        }
    }
    public Map<String, Object> updateResourceBySelflink(String selfLink, Map<String, Object> payload) {
        String url = String.format("%s/apis%s", axwayApiBaseUrl, selfLink);
        return updateResource(url, payload);
    }

    public Map<String, Object> updateResource(String url, Map<String, Object> payload) {
        if (axwayApiBaseUrl.isEmpty()) {
            log.warn("Axway API configuration missing - base URL not configured");
            return null;
        }

        String authHeader = getAuthorizationHeader();
        if (authHeader == null) {
            log.warn("Axway API authentication not configured - cannot fetch resource");
            return null;
        }

        try {
            log.debug("Updating Axway resource from: {}", url);

            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", authHeader);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            headers.setContentType(MediaType.APPLICATION_JSON);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(payload, headers);
            Map<String, Object> response = restTemplate.exchange(url, HttpMethod.PUT, request, Map.class).getBody();

            log.info("Successfully updated resource from: {}", url);
            return response;
        } catch (Exception e) {
            log.error("Failed to update resource from: {}", url, e);
            return null;
        }
    }
    
    /**
     * Extract resource details from webhook event and fetch additional data
     */
    public Map<String, Object> enrichEventWithAxwayData(Map<String, Object> eventPayload) {
        try {
            // Try to extract resource identifiers from the event payload
            Map<String, Object> enrichedData = new java.util.HashMap<>(eventPayload);
            
            // Check for API service reference
            Object apiServiceRef = extractNestedValue(eventPayload, "apiService", "name");
            if (apiServiceRef != null) {
                Map<String, Object> apiService = getApiService(apiServiceRef.toString());
                if (apiService != null) {
                    enrichedData.put("apiServiceDetails", apiService);
                }
            }
            
            // Check for subscription reference
            Object subscriptionRef = extractNestedValue(eventPayload, "subscription", "id");
            if (subscriptionRef != null) {
                Map<String, Object> subscription = getSubscription(subscriptionRef.toString());
                if (subscription != null) {
                    enrichedData.put("subscriptionDetails", subscription);
                }
            }
            
            // Check for environment and instance references
            Object environmentRef = extractNestedValue(eventPayload, "metadata", "scope", "name");
            Object instanceRef = extractNestedValue(eventPayload, "apiServiceInstance", "name");
            if (environmentRef != null && instanceRef != null) {
                Map<String, Object> instance = getApiServiceInstance(environmentRef.toString(), instanceRef.toString());
                if (instance != null) {
                    enrichedData.put("instanceDetails", instance);
                }
            }
            
            return enrichedData;
        } catch (Exception e) {
            log.error("Error enriching event with Axway data", e);
            return eventPayload;
        }
    }
    
    private Object extractNestedValue(Map<String, Object> map, String... keys) {
        Object current = map;
        for (String key : keys) {
            if (current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
            } else {
                return null;
            }
        }
        return current;
    }
}