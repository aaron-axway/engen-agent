package com.engen.webhookservice.service;

import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.client.RestClientException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing OAuth authentication with Highmark OAuth endpoint
 * Supports both Basic Auth (CLIENT_CREDENTIALS) and JWT-based OAuth flows
 * Used for ServiceNow Catalog API access
 */
@Service
public class HighmarkOAuthService {

    private static final Logger log = LoggerFactory.getLogger(HighmarkOAuthService.class);

    @Value("${highmark.oauth.enabled:true}")
    private boolean oauthEnabled;

    @Value("${highmark.oauth.auth-method:basic}")
    private String authMethod; // "basic" or "jwt"

    @Value("${highmark.oauth.token-url:https://test.auth.highmark.com/oauth2/rest/token}")
    private String tokenUrl;

    @Value("${highmark.oauth.client-id:}")
    private String clientId;

    @Value("${highmark.oauth.client-secret:}")
    private String clientSecret;

    @Value("${highmark.oauth.identity-domain:Axway}")
    private String identityDomain;

    @Value("${highmark.oauth.scope:resource.READ}")
    private String scope;

    @Value("${highmark.oauth.token-cache-duration-minutes:55}")
    private int tokenCacheDurationMinutes;

    // JWT-specific configuration (optional, for JWT auth method)
    @Value("${highmark.oauth.username:}")
    private String username;

    @Value("${highmark.oauth.key-id:}")
    private String keyId;

    @Value("${highmark.oauth.private-key-path:}")
    private String privateKeyPath;

    private final RestTemplate restTemplate;

    // Cache for access tokens
    private final Map<String, TokenInfo> tokenCache = new ConcurrentHashMap<>();

    public HighmarkOAuthService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Gets a valid access token for Highmark/ServiceNow Catalog API calls
     * @return Bearer token or null if OAuth is disabled/failed
     */
    public String getAccessToken() {
        if (!oauthEnabled) {
            log.debug("Highmark OAuth is disabled");
            return null;
        }

        // Check if we have a cached valid token
        TokenInfo cachedToken = tokenCache.get("access_token");
        if (cachedToken != null && !cachedToken.isExpired()) {
            log.debug("Using cached Highmark access token");
            return cachedToken.token;
        }

        // Get new token based on configured auth method
        return refreshAccessToken();
    }

    /**
     * Refreshes the access token using the configured authentication method
     * @return New access token or null if failed
     */
    public String refreshAccessToken() {
        try {
            String accessToken = null;

            // Try configured auth method first
            if ("basic".equalsIgnoreCase(authMethod)) {
                log.info("Using Basic Auth for Highmark OAuth token acquisition");
                accessToken = requestTokenBasicAuth();
            } else if ("jwt".equalsIgnoreCase(authMethod)) {
                log.info("Using JWT for Highmark OAuth token acquisition");
                accessToken = requestTokenJWT();
            } else {
                log.warn("Unknown auth method: {}. Trying Basic Auth first, then JWT", authMethod);
                accessToken = requestTokenBasicAuth();
                if (accessToken == null) {
                    log.info("Basic Auth failed, trying JWT method");
                    accessToken = requestTokenJWT();
                }
            }

            if (accessToken != null) {
                // Cache the token
                Instant expiryTime = Instant.now().plus(tokenCacheDurationMinutes, ChronoUnit.MINUTES);
                tokenCache.put("access_token", new TokenInfo(accessToken, expiryTime));
                log.info("Successfully obtained Highmark access token via {}", authMethod);
                return accessToken;
            }

            log.error("Failed to obtain Highmark access token");
            return null;

        } catch (Exception e) {
            log.error("Error refreshing Highmark access token", e);
            return null;
        }
    }

    /**
     * Requests access token using Basic Auth with CLIENT_CREDENTIALS grant type
     * This is the primary method documented for Highmark OAuth
     * Identity domain is passed as a query parameter per Highmark specification
     * @return Access token or null if failed
     */
    private String requestTokenBasicAuth() {
        try {
            // Prepare Basic Auth header
            String credentials = clientId + ":" + clientSecret;
            String encodedCredentials = Base64.getEncoder().encodeToString(credentials.getBytes());

            // Build URL with identity domain as query parameter (per Highmark spec)
            String requestUrl = tokenUrl;
            if (identityDomain != null && !identityDomain.trim().isEmpty()) {
                requestUrl = tokenUrl + "?identityDomain=" + identityDomain;
            }

            // Prepare request headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("Authorization", "Basic " + encodedCredentials);

            // Prepare request body
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "CLIENT_CREDENTIALS");
            body.add("scope", scope);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            // Make the request
            log.debug("Requesting Highmark token from: {}", requestUrl);
            ResponseEntity<Map> response = restTemplate.exchange(
                requestUrl,
                HttpMethod.POST,
                request,
                Map.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                String accessToken = (String) responseBody.get("access_token");

                if (accessToken != null) {
                    log.info("Successfully obtained Highmark access token via Basic Auth");
                    log.debug("Token response: {}", responseBody);
                    return accessToken;
                } else {
                    log.error("No access_token in Highmark OAuth response: {}", responseBody);
                }
            } else {
                log.error("Highmark OAuth token request failed with status: {}",
                        response.getStatusCode());
            }

        } catch (RestClientException e) {
            log.error("Error requesting Highmark access token via Basic Auth", e);
        } catch (Exception e) {
            log.error("Unexpected error during Highmark OAuth token request", e);
        }

        return null;
    }

    /**
     * Requests access token using JWT-based OAuth flow
     * This is a fallback method, similar to ServiceNow OAuth
     * @return Access token or null if failed
     */
    private String requestTokenJWT() {
        try {
            // Step 1: Create JWT assertion
            String jwtAssertion = createJwtAssertion();
            if (jwtAssertion == null) {
                log.error("Failed to create JWT assertion for Highmark");
                return null;
            }

            // Step 2: Exchange JWT for access token
            String accessToken = exchangeJwtForToken(jwtAssertion);
            if (accessToken != null) {
                log.info("Successfully obtained Highmark access token via JWT");
                return accessToken;
            }

            log.error("Failed to exchange JWT for Highmark access token");
            return null;

        } catch (Exception e) {
            log.error("Error requesting Highmark access token via JWT", e);
            return null;
        }
    }

    /**
     * Creates a JWT assertion for Highmark OAuth using RS256 signature
     * @return JWT string or null if failed
     */
    private String createJwtAssertion() {
        try {
            if (privateKeyPath == null || privateKeyPath.isEmpty()) {
                log.error("Highmark OAuth private key not configured");
                return null;
            }

            // Parse RSA private key from file
            PrivateKey privateKey = parseRSAPrivateKeyFromFile(privateKeyPath);
            if (privateKey == null) {
                log.error("Failed to parse Highmark OAuth private key");
                return null;
            }

            Instant now = Instant.now();

            // Create JWT with RS256 signature
            String jwt = Jwts.builder()
                    .issuer(clientId)
                    .subject(username)
                    .audience().add(clientId).and()
                    .id(UUID.randomUUID().toString())
                    .issuedAt(Date.from(now))
                    .expiration(Date.from(now.plus(5, ChronoUnit.MINUTES)))
                    .header().add("kid", keyId).and()
                    .signWith(privateKey)
                    .compact();

            log.debug("Created JWT assertion for Highmark OAuth with RS256");
            return jwt;

        } catch (Exception e) {
            log.error("Error creating JWT assertion for Highmark", e);
            return null;
        }
    }

    /**
     * Parses RSA private key from PEM file
     * @param keyPath Path to the private key file
     * @return PrivateKey or null if failed
     */
    private PrivateKey parseRSAPrivateKeyFromFile(String keyPath) {
        try {
            // Try to read from file system first
            String keyContent;
            try {
                keyContent = new String(Files.readAllBytes(Paths.get(keyPath)));
            } catch (IOException e) {
                // If not found in file system, try classpath
                Resource resource = new ClassPathResource(keyPath);
                keyContent = new String(resource.getInputStream().readAllBytes());
            }

            // Remove PEM headers and footers, and whitespace
            keyContent = keyContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

            byte[] keyBytes = Base64.getDecoder().decode(keyContent);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            log.error("Error parsing RSA private key from file: {}", keyPath, e);
            return null;
        }
    }

    /**
     * Exchanges JWT assertion for access token
     * @param jwtAssertion The JWT assertion
     * @return Access token or null if failed
     */
    private String exchangeJwtForToken(String jwtAssertion) {
        try {
            // Prepare request headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
            headers.set("X-OAUTH-IDENTITY-DOMAIN-NAME", identityDomain);

            // Prepare request body
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "urn:ietf:params:oauth:grant-type:jwt-bearer");
            body.add("client_id", clientId);
            body.add("client_secret", clientSecret);
            body.add("assertion", jwtAssertion);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            // Make the request
            ResponseEntity<Map> response = restTemplate.exchange(
                tokenUrl,
                HttpMethod.POST,
                request,
                Map.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                String accessToken = (String) responseBody.get("access_token");

                if (accessToken != null) {
                    log.info("Successfully exchanged JWT for Highmark access token");
                    return accessToken;
                } else {
                    log.error("No access_token in Highmark OAuth response: {}", responseBody);
                }
            } else {
                log.error("Highmark OAuth token exchange failed with status: {}",
                        response.getStatusCode());
            }

        } catch (RestClientException e) {
            log.error("Error exchanging JWT for Highmark access token", e);
        } catch (Exception e) {
            log.error("Unexpected error during Highmark OAuth token exchange", e);
        }

        return null;
    }

    /**
     * Checks if OAuth is properly configured and enabled
     * @return true if OAuth is enabled and configured
     */
    public boolean isOAuthEnabled() {
        boolean basicAuthConfigured = clientId != null && !clientId.isEmpty() &&
                                      clientSecret != null && !clientSecret.isEmpty() &&
                                      tokenUrl != null && !tokenUrl.isEmpty();

        boolean jwtConfigured = basicAuthConfigured &&
                                username != null && !username.isEmpty() &&
                                keyId != null && !keyId.isEmpty() &&
                                privateKeyPath != null && !privateKeyPath.isEmpty();

        // Basic auth requires less config, JWT requires more
        if ("basic".equalsIgnoreCase(authMethod)) {
            return oauthEnabled && basicAuthConfigured;
        } else if ("jwt".equalsIgnoreCase(authMethod)) {
            return oauthEnabled && jwtConfigured;
        } else {
            // For "both" or unknown, require at least basic auth config
            return oauthEnabled && basicAuthConfigured;
        }
    }

    /**
     * Clears the token cache (useful for testing or when tokens are revoked)
     */
    public void clearTokenCache() {
        tokenCache.clear();
        log.info("Highmark OAuth token cache cleared");
    }

    /**
     * Inner class to hold token information with expiry
     */
    private static class TokenInfo {
        final String token;
        final Instant expiryTime;

        TokenInfo(String token, Instant expiryTime) {
            this.token = token;
            this.expiryTime = expiryTime;
        }

        boolean isExpired() {
            return Instant.now().isAfter(expiryTime);
        }
    }

    /**
     * Gets OAuth configuration status for health checks
     * @return Configuration status map
     */
    public Map<String, Object> getOAuthStatus() {
        return Map.of(
            "enabled", oauthEnabled,
            "authMethod", authMethod,
            "configured", isOAuthEnabled(),
            "hasValidToken", tokenCache.containsKey("access_token") &&
                        !tokenCache.get("access_token").isExpired(),
            "tokenUrl", tokenUrl != null ? tokenUrl : "not configured",
            "clientId", clientId != null && !clientId.isEmpty() ? "configured" : "not configured",
            "identityDomain", identityDomain
        );
    }

    /**
     * Tests OAuth connectivity by attempting to get a token
     * @return true if successful
     */
    public boolean testConnectivity() {
        log.info("Testing Highmark OAuth connectivity...");
        String token = getAccessToken();
        boolean success = token != null && !token.isEmpty();
        if (success) {
            log.info("Highmark OAuth connectivity test: SUCCESS");
        } else {
            log.error("Highmark OAuth connectivity test: FAILED");
        }
        return success;
    }
}
