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

    private static final java.util.regex.Pattern VALID_PATH_SEGMENT = java.util.regex.Pattern.compile("^[a-zA-Z0-9._~:@!$&'()*+,;=/-]+$");

    /**
     * Validates a path segment to prevent SSRF attacks via URL manipulation.
     */
    private String validatePathSegment(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be null or blank");
        }
        if (value.contains("..") || value.contains("//") || value.contains("?") || value.contains("#") || value.contains("\n") || value.contains("\r")) {
            throw new IllegalArgumentException(fieldName + " contains invalid characters");
        }
        if (!VALID_PATH_SEGMENT.matcher(value).matches()) {
            throw new IllegalArgumentException(fieldName + " contains invalid path characters");
        }
        return value;
    }

    /**
     * Validates a selfLink to ensure it is a relative path, preventing SSRF via absolute URLs.
     */
    private String validateSelfLink(String selfLink, String fieldName) {
        validatePathSegment(selfLink, fieldName);
        if (!selfLink.startsWith("/")) {
            throw new IllegalArgumentException(fieldName + " must be a relative path starting with /");
        }
        if (selfLink.contains("://")) {
            throw new IllegalArgumentException(fieldName + " must not contain a protocol scheme");
        }
        return selfLink;
    }

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
            validatePathSegment(apiServiceId, "apiServiceId");
            String url = axwayApiBaseUrl + "/apis/management/v1alpha1/apiservices/" + apiServiceId;
            log.debug("Fetching API service details from: {}", url);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", authHeader);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            
            HttpEntity<Void> request = new HttpEntity<>(headers);
            Map<String, Object> response = restTemplate.exchange(url, HttpMethod.GET, request, Map.class).getBody();
            
            log.info("Successfully fetched API service details for: {}", apiServiceId);
            return response;
        } catch (IllegalArgumentException e) {
            log.error("Invalid apiServiceId: {}", e.getMessage());
            return null;
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
            validatePathSegment(environmentName, "environmentName");
            validatePathSegment(instanceName, "instanceName");
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
        } catch (IllegalArgumentException e) {
            log.error("Invalid environment/instance name: {}", e.getMessage());
            return null;
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
            validatePathSegment(subscriptionId, "subscriptionId");
            String url = axwayApiBaseUrl + "/apis/management/v1alpha1/subscriptions/" + subscriptionId;
            log.debug("Fetching subscription details from: {}", url);
            
            HttpHeaders headers = new HttpHeaders();
            headers.set("Authorization", authHeader);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            
            HttpEntity<Void> request = new HttpEntity<>(headers);
            Map<String, Object> response = restTemplate.exchange(url, HttpMethod.GET, request, Map.class).getBody();
            
            log.info("Successfully fetched subscription details for: {}", subscriptionId);
            return response;
        } catch (IllegalArgumentException e) {
            log.error("Invalid subscriptionId: {}", e.getMessage());
            return null;
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
        
        try {
            validatePathSegment(teamId, "teamId");
            String url = String.format("%s/team/%s", axwayPlatformBaseUrl, teamId);
            Map<String, Object> response = getResource(url);
            @SuppressWarnings( "unchecked")
            Map<String, Object> team = (Map<String, Object>) response.get("result");
            return team;
        } catch (IllegalArgumentException e) {
            log.error("Invalid teamId: {}", e.getMessage());
            return null;
        }
    }

    public Map<String, Object> getUser(String userId) {
        if (axwayPlatformBaseUrl.isEmpty()) {
            log.warn("Axway Platform API configuration missing - platform base URL not configured");
            return null;
        }

        try {
            validatePathSegment(userId, "userId");
            String url = String.format("%s/user/%s", axwayPlatformBaseUrl, userId);
            Map<String, Object> response = getResource(url);
            if (response == null) {
                log.warn("Could not fetch user with ID: {} - user may no longer exist", userId);
                return null;
            }
            @SuppressWarnings( "unchecked")
            Map<String, Object> user = (Map<String, Object>) response.get("result");
            return user;
        } catch (IllegalArgumentException e) {
            log.error("Invalid userId: {}", e.getMessage());
            return null;
        }
    }

    public Map<String, Object> setApproved(String selfLink) {
        return updateApprovalSubResource(selfLink, "approved");
    }

    public Map<String, Object> updateApprovalSubResource(String selfLink, String state) {
        validateSelfLink(selfLink, "selfLink");
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
        validateSelfLink(selfLink, "selfLink");
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
        validateSelfLink(selfLink, "selfLink");
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