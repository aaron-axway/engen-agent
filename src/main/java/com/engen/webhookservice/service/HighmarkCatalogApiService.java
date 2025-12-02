package com.engen.webhookservice.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;

import java.util.HashMap;
import java.util.Map;

/**
 * Service for integrating with Highmark ServiceNow Catalog API
 * Handles catalog item submission (order_now) for API Access Requests
 */
@Service
public class HighmarkCatalogApiService {

    private static final Logger log = LoggerFactory.getLogger(HighmarkCatalogApiService.class);

    @Value("${highmark.catalog.enabled:true}")
    private boolean catalogApiEnabled;

    @Value("${highmark.catalog.base-url:https://apiintdev.hmhs.com/snow/v1}")
    private String catalogBaseUrl;

    @Value("${highmark.catalog.catalog-item-sys-id:2a10c025eb587a1ce2b7f1fbcad0cdda}")
    private String catalogItemSysId;

    @Value("${highmark.catalog.default-data-governance:Internal}")
    private String defaultDataGovernance;

    @Value("${highmark.catalog.default-dg-class-tags:Standard}")
    private String defaultDgClassTags;

    @Value("${highmark.catalog.default-approval-state:pending}")
    private String defaultApprovalState;

    @Value("${highmark.catalog.default-need-by-days:30}")
    private int defaultNeedByDays;

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final HighmarkOAuthService oauthService;

    @Autowired(required = false)
    private EmailService emailService;

    public HighmarkCatalogApiService(RestTemplate restTemplate,
                                     ObjectMapper objectMapper,
                                     HighmarkOAuthService oauthService) {
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
        this.oauthService = oauthService;
    }

    /**
     * Submits a catalog item request to ServiceNow via order_now API
     * @param variables The catalog item variables (requested_for, need_by_date, etc.)
     * @return The created request details including sys_id and request_number
     */
    public Map<String, Object> submitCatalogItemRequest(Map<String, Object> variables) {
        if (!catalogApiEnabled) {
            log.warn("Highmark Catalog API integration is disabled. Returning simulated response.");
            return createSimulatedResponse(variables);
        }

        // Build the order_now endpoint URL
        String endpoint = String.format("%s/servicecatalog/items/%s/order_now",
                                       catalogBaseUrl, catalogItemSysId);

        try {
            // Prepare the catalog order payload
            Map<String, Object> orderPayload = new HashMap<>();
            orderPayload.put("sysparm_quantity", 1);
            orderPayload.put("variables", variables);

            // Create headers with Bearer token from Highmark OAuth
            HttpHeaders headers = createRequestHeaders();
            if (headers == null) {
                log.error("Failed to create request headers - OAuth token acquisition failed");
                return createErrorResponse("Failed to obtain OAuth access token", 401);
            }

            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);

            HttpEntity<Map<String, Object>> request = new HttpEntity<>(orderPayload, headers);

            log.info("Submitting catalog item request to Highmark: {} with variables: {}",
                     endpoint, variables.keySet());
            log.debug("Request payload: {}", orderPayload);

            ResponseEntity<Map> response = restTemplate.exchange(endpoint, HttpMethod.POST, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> result = (Map<String, Object>) response.getBody().get("result");
                if (result != null) {
                    log.info("Successfully created ServiceNow catalog request: {} with sys_id: {}",
                             result.get("request_number"), result.get("sys_id"));
                    return result;
                } else {
                    log.warn("Response body does not contain 'result' field: {}", response.getBody());
                    return response.getBody();
                }
            }

            log.error("Failed to create catalog request. Status: {}, Body: {}",
                      response.getStatusCode(), response.getBody());
            return createErrorResponse("Failed to create catalog request", response.getStatusCode().value());

        } catch (HttpClientErrorException e) {
            log.error("Highmark Catalog API error: {} - {}", e.getStatusCode(), e.getResponseBodyAsString());
            sendErrorNotification("Highmark Catalog API Error", e.getMessage(), variables);
            return createErrorResponse(e.getMessage(), e.getStatusCode().value());
        } catch (RestClientException e) {
            log.error("Error calling Highmark Catalog API", e);
            sendErrorNotification("Highmark Catalog API Connection Error", e.getMessage(), variables);
            return createErrorResponse(e.getMessage(), 500);
        } catch (Exception e) {
            log.error("Unexpected error submitting catalog item request", e);
            sendErrorNotification("Highmark Catalog API Unexpected Error", e.getMessage(), variables);
            return createErrorResponse(e.getMessage(), 500);
        }
    }

    /**
     * Gets the status of a catalog request
     * @param requestNumber The catalog request number (e.g., REQ0159161)
     * @return The request details
     */
    public Map<String, Object> getRequestStatus(String requestNumber) {
        if (!catalogApiEnabled) {
            log.warn("Highmark Catalog API integration is disabled");
            return createSimulatedResponse(Map.of("request_number", requestNumber));
        }

        String endpoint = String.format("%s/table/sc_request?sysparm_query=number=%s",
                                       catalogBaseUrl.replace("/servicecatalog", ""), requestNumber);

        try {
            HttpHeaders headers = createRequestHeaders();
            if (headers == null) {
                log.error("Failed to create request headers");
                return createErrorResponse("Failed to obtain OAuth access token", 401);
            }

            HttpEntity<Void> request = new HttpEntity<>(headers);

            log.debug("Fetching request status: {}", requestNumber);
            ResponseEntity<Map> response = restTemplate.exchange(endpoint, HttpMethod.GET, request, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }

            log.error("Failed to fetch request status. Status: {}", response.getStatusCode());
            return createErrorResponse("Failed to fetch request status", response.getStatusCode().value());

        } catch (Exception e) {
            log.error("Error fetching request status for {}", requestNumber, e);
            return createErrorResponse(e.getMessage(), 500);
        }
    }

    /**
     * Creates HTTP headers with Bearer token from Highmark OAuth service
     * @return Headers with Authorization token, or null if token acquisition fails
     */
    private HttpHeaders createRequestHeaders() {
        HttpHeaders headers = new HttpHeaders();

        // Get OAuth access token from Highmark OAuth service
        String accessToken = oauthService.getAccessToken();

        if (accessToken != null && !accessToken.isEmpty()) {
            headers.set("Authorization", "Bearer " + accessToken);
            log.debug("Using Highmark OAuth Bearer token for API request");
            return headers;
        }

        log.error("Failed to obtain Highmark OAuth access token");
        return null;
    }

    /**
     * Checks if the Catalog API is enabled
     * @return true if enabled
     */
    public boolean isApiEnabled() {
        return catalogApiEnabled && oauthService.isOAuthEnabled();
    }

    /**
     * Gets the default field values for catalog variables
     * @return Map of default values
     */
    public Map<String, String> getDefaultValues() {
        return Map.of(
            "data_governance", defaultDataGovernance,
            "dg_class_tags", defaultDgClassTags,
            "approval_state", defaultApprovalState
        );
    }

    /**
     * Gets the configured catalog item sys_id
     * @return Catalog item sys_id
     */
    public String getCatalogItemSysId() {
        return catalogItemSysId;
    }

    /**
     * Gets the default need-by days offset
     * @return Number of days to add to current date for need_by_date field
     */
    public int getDefaultNeedByDays() {
        return defaultNeedByDays;
    }

    /**
     * Creates a simulated response for testing when API is disabled
     */
    private Map<String, Object> createSimulatedResponse(Map<String, Object> variables) {
        Map<String, Object> result = new HashMap<>();
        result.put("sys_id", "simulated-sys-id-" + System.currentTimeMillis());
        result.put("number", "REQ" + String.format("%07d", System.currentTimeMillis() % 10000000));
        result.put("request_number", result.get("number"));
        result.put("parent_id", null);
        result.put("request_id", result.get("sys_id"));
        result.put("parent_table", "task");
        result.put("table", "sc_request");
        result.put("status", "simulated");
        result.put("variables", variables);

        log.info("Simulated catalog request response: {}", result.get("request_number"));
        return result;
    }

    /**
     * Creates an error response map
     */
    private Map<String, Object> createErrorResponse(String message, int statusCode) {
        Map<String, Object> error = new HashMap<>();
        error.put("error", message);
        error.put("status_code", statusCode);
        error.put("success", false);
        error.put("timestamp", System.currentTimeMillis());
        return error;
    }

    /**
     * Logs error details for monitoring and troubleshooting
     * Note: Email notifications are handled at the EventProcessingService level where WebhookEvent is available
     */
    private void sendErrorNotification(String subject, String errorMessage, Map<String, Object> variables) {
        // Log detailed error information for troubleshooting
        log.error("Highmark Catalog API Error - {}: {}", subject, errorMessage);
        log.error("Catalog Item Sys ID: {}", catalogItemSysId);
        log.error("Endpoint: {}", catalogBaseUrl);
        log.error("Variables: {}", variables);

        // Email notifications are sent from EventProcessingService where WebhookEvent context is available
    }

    /**
     * Gets service status for health checks
     * @return Status map
     */
    public Map<String, Object> getServiceStatus() {
        return Map.of(
            "catalogApiEnabled", catalogApiEnabled,
            "oauthEnabled", oauthService.isOAuthEnabled(),
            "catalogBaseUrl", catalogBaseUrl,
            "catalogItemSysId", catalogItemSysId,
            "configured", isApiEnabled()
        );
    }
}
