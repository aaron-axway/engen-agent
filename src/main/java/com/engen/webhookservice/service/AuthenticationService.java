package com.engen.webhookservice.service;

import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Service
public class AuthenticationService {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationService.class);

    @Value("${webhook.axway.secret:}")
    private String axwaySecret;

    @Value("${webhook.axway.token:}")
    private String axwayToken;

    @Value("${webhook.servicenow.secret:}")
    private String serviceNowSecret;

    @Value("${webhook.servicenow.username:}")
    private String serviceNowUsername;

    @Value("${webhook.servicenow.password:}")
    private String serviceNowPassword;

    public boolean authenticateAxwayWebhook(ContentCachingRequestWrapper request) {
        // Check for API Key/Token authentication
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null) {
            // Axway sends the API key directly in Authorization header (no "Bearer" prefix)
            // But also support "Bearer" prefix for flexibility
            String token = authHeader;
            if (authHeader.startsWith("Bearer ")) {
                token = authHeader.substring(7);
            }
            
            if (axwayToken.equals(token)) {
                log.debug("Axway webhook authenticated via API key");
                return true;
            }
        }

        // Check for HMAC signature validation
        String signature = request.getHeader("X-Axway-Signature");
        if (signature != null && !axwaySecret.isEmpty()) {
            try {
                byte[] bodyBytes = request.getContentAsByteArray();
                String body = new String(bodyBytes, StandardCharsets.UTF_8);
                
                String calculatedSignature = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, axwaySecret)
                    .hmacHex(body);

                // Use timing-safe comparison to prevent timing attacks
                if (MessageDigest.isEqual(calculatedSignature.getBytes(StandardCharsets.UTF_8),
                                          signature.getBytes(StandardCharsets.UTF_8))) {
                    log.debug("Axway webhook authenticated via HMAC signature");
                    return true;
                }
            } catch (Exception e) {
                log.error("Error validating Axway HMAC signature", e);
            }
        }

        log.warn("Axway webhook authentication failed");
        return false;
    }

    public boolean authenticateServiceNowWebhook(ContentCachingRequestWrapper request) {
        // Check Basic Authentication
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Basic ")) {
            String encodedCredentials = authHeader.substring(6);
            String credentials = new String(java.util.Base64.getDecoder().decode(encodedCredentials));
            String[] parts = credentials.split(":");
            
            if (parts.length == 2 && 
                serviceNowUsername.equals(parts[0]) && 
                serviceNowPassword.equals(parts[1])) {
                log.debug("ServiceNow webhook authenticated via Basic auth");
                return true;
            }
        }

        // Check HMAC signature validation for ServiceNow
        String signature = request.getHeader("X-ServiceNow-Signature");
        if (signature != null && !serviceNowSecret.isEmpty()) {
            try {
                byte[] bodyBytes = request.getContentAsByteArray();
                String body = new String(bodyBytes, StandardCharsets.UTF_8);
                
                String calculatedSignature = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, serviceNowSecret)
                    .hmacHex(body);

                // Use timing-safe comparison to prevent timing attacks
                if (MessageDigest.isEqual(calculatedSignature.getBytes(StandardCharsets.UTF_8),
                                          signature.getBytes(StandardCharsets.UTF_8))) {
                    log.debug("ServiceNow webhook authenticated via HMAC signature");
                    return true;
                }
            } catch (Exception e) {
                log.error("Error validating ServiceNow HMAC signature", e);
            }
        }

        log.warn("ServiceNow webhook authentication failed");
        return false;
    }
}