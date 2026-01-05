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
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing OAuth JWT authentication with Axway Amplify
 * Handles JWT creation, token exchange, and token caching/refresh
 */
@Service
public class AxwayOAuthService {

    private static final Logger log = LoggerFactory.getLogger(AxwayOAuthService.class);

    @Value("${axway.oauth.client-id:}")
    private String clientId;

    @Value("${axway.oauth.private-key-path:private_key.pem}")
    private String privateKeyPath;

    @Value("${axway.oauth.public-key-path:public_key.pem}")
    private String publicKeyPath;

    // Base64-encoded keys from environment variables (takes precedence over file paths)
    @Value("${axway.oauth.private-key-base64:}")
    private String privateKeyBase64;

    @Value("${axway.oauth.public-key-base64:}")
    private String publicKeyBase64;

    @Value("${axway.oauth.token-endpoint:}")
    private String tokenEndpoint;

    @Value("${axway.oauth.enabled:false}")
    private boolean oauthEnabled;

    @Value("${axway.oauth.token-cache-duration-minutes:55}")
    private int tokenCacheDurationMinutes;

    private final RestTemplate restTemplate;
    
    // Cache for access tokens
    private final Map<String, TokenInfo> tokenCache = new ConcurrentHashMap<>();

    public AxwayOAuthService(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    /**
     * Gets a valid access token for Axway API calls
     * @return Bearer token or null if OAuth is disabled/failed
     */
    public String getAccessToken() {
        if (!oauthEnabled) {
            log.debug("Axway OAuth is disabled");
            return null;
        }

        // Check if we have a cached valid token
        TokenInfo cachedToken = tokenCache.get("access_token");
        if (cachedToken != null && !cachedToken.isExpired()) {
            log.debug("Using cached Axway access token");
            return cachedToken.token;
        }

        // Get new token
        return refreshAccessToken();
    }

    /**
     * Refreshes the access token by performing JWT-based OAuth flow
     * @return New access token or null if failed
     */
    public String refreshAccessToken() {
        try {
            // Step 1: Create JWT assertion
            String jwtAssertion = createJwtAssertion();
            if (jwtAssertion == null) {
                log.error("Failed to create JWT assertion");
                return null;
            }

            // Step 2: Exchange JWT for access token
            String accessToken = exchangeJwtForToken(jwtAssertion);
            if (accessToken != null) {
                // Cache the token
                Instant expiryTime = Instant.now().plus(tokenCacheDurationMinutes, ChronoUnit.MINUTES);
                tokenCache.put("access_token", new TokenInfo(accessToken, expiryTime));
                log.info("Successfully obtained Axway access token");
                return accessToken;
            }

            log.error("Failed to exchange JWT for access token");
            return null;

        } catch (Exception e) {
            log.error("Error refreshing Axway access token", e);
            return null;
        }
    }

    /**
     * Creates a JWT assertion for Axway OAuth using RS256 signature
     * @return JWT string or null if failed
     */
    private String createJwtAssertion() {
        try {
            // Parse RSA private key (from base64 env var or file)
            PrivateKey privateKey = parseRSAPrivateKey();
            if (privateKey == null) {
                log.error("Failed to parse Axway OAuth private key");
                return null;
            }

            // Compute KID from public key per Axway requirements
            String computedKid = computeKidFromPublicKey();
            if (computedKid == null) {
                log.error("Failed to compute KID from public key");
                return null;
            }
            
            Instant now = Instant.now();
            
            // Issuer format: "JWT:{clientID}" per Axway's keypairauthenticator.go
            String issuer = "JWT:" + clientId;

            // Create JWT with RS256 signature matching Axway's requirements
            String jwt = Jwts.builder()
                .issuer(issuer)                                        // "JWT:{clientID}"
                .subject(clientId)                                      // Client ID as subject
                .audience().add(tokenEndpoint).and()                    // Token endpoint as audience
                .id(UUID.randomUUID().toString())                       // Unique JWT ID
                .issuedAt(Date.from(now))                              // Current timestamp
                .expiration(Date.from(now.plus(60, ChronoUnit.SECONDS))) // Expires in 60 seconds
                .header().add("kid", computedKid).and()                 // KID computed from public key
                .signWith(privateKey)
                .compact();

            log.debug("Created JWT assertion for Axway OAuth with RS256");
            return jwt;

        } catch (Exception e) {
            log.error("Error creating JWT assertion", e);
            return null;
        }
    }

    /**
     * Computes KID from public key by taking SHA-256 hash of DER-encoded public key
     * This matches Axway's requirement for KID computation
     * Checks base64 env var first, falls back to file path
     * @return Base64 URL-encoded KID or null if failed
     */
    private String computeKidFromPublicKey() {
        try {
            String keyContent;

            // Check if base64-encoded key is provided via env var (takes precedence)
            if (publicKeyBase64 != null && !publicKeyBase64.isEmpty()) {
                log.debug("Loading Axway public key from base64 environment variable");
                keyContent = publicKeyBase64;
            } else if (publicKeyPath != null && !publicKeyPath.isEmpty()) {
                // Fall back to file path
                log.debug("Loading Axway public key from file: {}", publicKeyPath);
                try {
                    keyContent = new String(Files.readAllBytes(Paths.get(publicKeyPath)));
                } catch (IOException e) {
                    // If not found in file system, try classpath
                    Resource resource = new ClassPathResource(publicKeyPath);
                    keyContent = new String(resource.getInputStream().readAllBytes());
                }
            } else {
                log.error("Axway OAuth public key not configured (neither base64 nor file path)");
                return null;
            }

            // Remove PEM headers and footers, and whitespace (handles both PEM and raw base64)
            keyContent = keyContent
                .replace("-----BEGIN PUBLIC KEY-----", "")
                .replace("-----END PUBLIC KEY-----", "")
                .replace("-----BEGIN RSA PUBLIC KEY-----", "")
                .replace("-----END RSA PUBLIC KEY-----", "")
                .replaceAll("\\s", "");

            // Decode the public key
            byte[] keyBytes = Base64.getDecoder().decode(keyContent);
            X509EncodedKeySpec keySpec = new X509EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            PublicKey publicKey = keyFactory.generatePublic(keySpec);

            // Get DER encoding of the public key
            byte[] derEncoded = publicKey.getEncoded();

            // Compute SHA-256 hash
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(derEncoded);

            // Return base64 URL-encoded hash (no padding)
            String kid = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            log.debug("Computed KID from public key: {}", kid);
            return kid;

        } catch (Exception e) {
            log.error("Error computing KID from public key", e);
            return null;
        }
    }
    
    /**
     * Parses RSA private key from base64 env var or PEM file
     * Checks base64 env var first, falls back to file path
     * @return PrivateKey or null if failed
     */
    private PrivateKey parseRSAPrivateKey() {
        try {
            String keyContent;

            // Check if base64-encoded key is provided via env var (takes precedence)
            if (privateKeyBase64 != null && !privateKeyBase64.isEmpty()) {
                log.debug("Loading Axway private key from base64 environment variable");
                keyContent = privateKeyBase64;
            } else if (privateKeyPath != null && !privateKeyPath.isEmpty()) {
                // Fall back to file path
                log.debug("Loading Axway private key from file: {}", privateKeyPath);
                try {
                    keyContent = new String(Files.readAllBytes(Paths.get(privateKeyPath)));
                } catch (IOException e) {
                    // If not found in file system, try classpath
                    Resource resource = new ClassPathResource(privateKeyPath);
                    keyContent = new String(resource.getInputStream().readAllBytes());
                }
            } else {
                log.error("Axway OAuth private key not configured (neither base64 nor file path)");
                return null;
            }

            // Remove PEM headers and footers, and whitespace (handles both PEM and raw base64)
            keyContent = keyContent
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("-----BEGIN RSA PRIVATE KEY-----", "")
                .replace("-----END RSA PRIVATE KEY-----", "")
                .replaceAll("\\s", "");

            byte[] keyBytes = Base64.getDecoder().decode(keyContent);
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(keyBytes);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            log.error("Error parsing RSA private key", e);
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
            if (tokenEndpoint == null || tokenEndpoint.isEmpty()) {
                log.error("Axway OAuth token endpoint not configured");
                return null;
            }

            // Prepare request headers
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);
            headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);

            // Prepare request body - Axway uses client_credentials grant type
            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "client_credentials");
            body.add("client_assertion_type", "urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
            body.add("client_assertion", jwtAssertion);
            body.add("client_id", clientId);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);

            // Make the request
            ResponseEntity<Map> response = restTemplate.exchange(
                tokenEndpoint,
                HttpMethod.POST,
                request,
                Map.class
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> responseBody = response.getBody();
                String accessToken = (String) responseBody.get("access_token");
                
                if (accessToken != null) {
                    log.info("Successfully exchanged JWT for Axway access token");
                    return accessToken;
                } else {
                    log.error("No access_token in Axway OAuth response (response keys: {})", responseBody.keySet());
                }
            } else {
                log.error("Axway OAuth token exchange failed with status: {}", 
                        response.getStatusCode());
            }

        } catch (RestClientException e) {
            log.error("Error exchanging JWT for Axway access token", e);
        } catch (Exception e) {
            log.error("Unexpected error during Axway OAuth token exchange", e);
        }

        return null;
    }

    /**
     * Checks if OAuth is properly configured and enabled
     * Accepts either base64 env var or file path for keys
     * @return true if OAuth is enabled and configured
     */
    public boolean isOAuthEnabled() {
        boolean hasPrivateKey = (privateKeyBase64 != null && !privateKeyBase64.isEmpty()) ||
                                (privateKeyPath != null && !privateKeyPath.isEmpty());
        boolean hasPublicKey = (publicKeyBase64 != null && !publicKeyBase64.isEmpty()) ||
                               (publicKeyPath != null && !publicKeyPath.isEmpty());

        return oauthEnabled &&
            clientId != null && !clientId.isEmpty() &&
            hasPrivateKey &&
            hasPublicKey &&
            tokenEndpoint != null && !tokenEndpoint.isEmpty();
    }

    /**
     * Clears the token cache (useful for testing or when tokens are revoked)
     */
    public void clearTokenCache() {
        tokenCache.clear();
        log.info("Axway OAuth token cache cleared");
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
            "configured", isOAuthEnabled(),
            "hasValidToken", tokenCache.containsKey("access_token") && 
                        !tokenCache.get("access_token").isExpired(),
            "tokenEndpoint", tokenEndpoint != null ? tokenEndpoint : "not configured",
            "clientId", clientId != null && !clientId.isEmpty() ? "configured" : "not configured"
        );
    }
}