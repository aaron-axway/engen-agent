package com.engen.webhookservice.service;

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

    @Value("${webhook.axway.token:}")
    private String axwayToken;

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

            // Use timing-safe comparison to prevent timing attacks
            if (MessageDigest.isEqual(axwayToken.getBytes(StandardCharsets.UTF_8),
                                      token.getBytes(StandardCharsets.UTF_8))) {
                log.debug("Axway webhook authenticated via API key");
                return true;
            }
        }

        log.warn("Axway webhook authentication failed");
        return false;
    }
}
