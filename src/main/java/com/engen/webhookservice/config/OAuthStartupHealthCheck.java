package com.engen.webhookservice.config;

import com.engen.webhookservice.service.AxwayOAuthService;
import com.engen.webhookservice.service.HighmarkOAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Startup health check that validates OAuth connections
 * Shuts down the application if critical OAuth services fail
 */
@Component
public class OAuthStartupHealthCheck implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OAuthStartupHealthCheck.class);

    private final AxwayOAuthService axwayOAuthService;
    private final HighmarkOAuthService highmarkOAuthService;
    private final ApplicationContext applicationContext;

    @Value("${oauth.startup-health-check.enabled:true}")
    private boolean healthCheckEnabled;

    @Value("${oauth.startup-health-check.shutdown-on-failure:true}")
    private boolean shutdownOnFailure;

    @Value("${oauth.startup-health-check.required-services:axway,highmark}")
    private String requiredServicesConfig;

    public OAuthStartupHealthCheck(AxwayOAuthService axwayOAuthService,
                                 HighmarkOAuthService highmarkOAuthService,
                                 ApplicationContext applicationContext) {
        this.axwayOAuthService = axwayOAuthService;
        this.highmarkOAuthService = highmarkOAuthService;
        this.applicationContext = applicationContext;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!healthCheckEnabled) {
            log.info("OAuth startup health check is disabled - skipping");
            return;
        }

        log.info("Starting OAuth connection health checks...");
        
        List<String> requiredServices = Arrays.asList(requiredServicesConfig.split(","));
        boolean allHealthy = true;
        StringBuilder failureReport = new StringBuilder();
        failureReport.append("\n=== OAuth Connection Health Check Report ===\n");

        // Check Axway OAuth if required
        if (requiredServices.contains("axway")) {
            boolean axwayHealthy = checkAxwayOAuth();
            if (!axwayHealthy) {
                allHealthy = false;
                failureReport.append("Axway OAuth: FAILED\n");
            } else {
                failureReport.append("Axway OAuth: HEALTHY\n");
            }
        } else {
            failureReport.append("Axway OAuth: SKIPPED (not required)\n");
        }

        // Check Highmark OAuth if required
        if (requiredServices.contains("highmark")) {
            boolean highmarkHealthy = checkHighmarkOAuth();
            if (!highmarkHealthy) {
                allHealthy = false;
                failureReport.append("Highmark OAuth: FAILED\n");
            } else {
                failureReport.append("Highmark OAuth: HEALTHY\n");
            }
        } else {
            failureReport.append("Highmark OAuth: SKIPPED (not required)\n");
        }

        failureReport.append("==========================================\n");

        if (allHealthy) {
            log.info("All required OAuth connections are healthy - Application startup successful");
            log.info(failureReport.toString());
        } else {
            log.error("OAuth connection failures detected");
            log.error(failureReport.toString());
            
            // Log detailed failure information
            logDetailedFailureInfo();
            
            if (shutdownOnFailure) {
                // Shutdown the application
                log.error("Initiating application shutdown due to OAuth connection failures...");
                System.exit(SpringApplication.exit(applicationContext, () -> 1));
            } else {
                log.warn("Continuing startup despite OAuth failures (shutdown-on-failure is disabled)");
            }
        }
    }

    /**
     * Test Axway OAuth connection
     */
    private boolean checkAxwayOAuth() {
        try {
            if (!axwayOAuthService.isOAuthEnabled()) {
                log.warn("Axway OAuth is not properly configured - configuration missing");
                log.warn("Axway OAuth Status: {}", axwayOAuthService.getOAuthStatus());
                return false; // Configuration is missing when it should be enabled
            }

            log.info("Testing Axway OAuth connection...");
            String accessToken = axwayOAuthService.getAccessToken();

            if (accessToken != null && !accessToken.isEmpty()) {
                log.info("Axway OAuth connection successful - token obtained");
                return true;
            } else {
                log.error("Axway OAuth connection failed - no access token obtained");
                return false;
            }
        } catch (Exception e) {
            log.error("Axway OAuth connection failed with exception", e);
            return false;
        }
    }

    /**
     * Test Highmark OAuth connection
     */
    private boolean checkHighmarkOAuth() {
        try {
            if (!highmarkOAuthService.isOAuthEnabled()) {
                log.warn("Highmark OAuth is not properly configured - configuration missing");
                log.warn("Highmark OAuth Status: {}", highmarkOAuthService.getOAuthStatus());
                return false; // Configuration is missing when it should be enabled
            }

            log.info("Testing Highmark OAuth connection...");
            String accessToken = highmarkOAuthService.getAccessToken();

            if (accessToken != null && !accessToken.isEmpty()) {
                log.info("Highmark OAuth connection successful - token obtained");
                return true;
            } else {
                log.error("Highmark OAuth connection failed - no access token obtained");
                return false;
            }
        } catch (Exception e) {
            log.error("Highmark OAuth connection failed with exception", e);
            return false;
        }
    }

    /**
     * Log detailed failure information for troubleshooting
     */
    private void logDetailedFailureInfo() {
        log.error("\n=== DETAILED OAUTH FAILURE ANALYSIS ===");

        // Axway OAuth Details
        if (axwayOAuthService.isOAuthEnabled()) {
            var axwayStatus = axwayOAuthService.getOAuthStatus();
            log.error("Axway OAuth Status: {}", axwayStatus);

            log.error("Axway OAuth Configuration Check:");
            log.error("  - Enabled: {}", axwayStatus.get("enabled"));
            log.error("  - Configured: {}", axwayStatus.get("configured"));
            log.error("  - Token Endpoint: {}", axwayStatus.get("tokenEndpoint"));
            log.error("  - Client ID: {}", axwayStatus.get("clientId"));
        }

        // Highmark OAuth Details
        if (highmarkOAuthService.isOAuthEnabled()) {
            var highmarkStatus = highmarkOAuthService.getOAuthStatus();
            log.error("Highmark OAuth Status: {}", highmarkStatus);

            log.error("Highmark OAuth Configuration Check:");
            log.error("  - Enabled: {}", highmarkStatus.get("enabled"));
            log.error("  - Auth Method: {}", highmarkStatus.get("authMethod"));
            log.error("  - Configured: {}", highmarkStatus.get("configured"));
            log.error("  - Token URL: {}", highmarkStatus.get("tokenUrl"));
            log.error("  - Client ID: {}", highmarkStatus.get("clientId"));
            log.error("  - Identity Domain: {}", highmarkStatus.get("identityDomain"));
        }

        log.error("=== TROUBLESHOOTING SUGGESTIONS ===");
        log.error("1. Verify OAuth credentials in .env file");
        log.error("2. Check private key files are present and readable");
        log.error("3. Ensure network connectivity to OAuth endpoints");
        log.error("4. Verify certificates are uploaded to respective platforms");
        log.error("5. Check application registry configurations");
        log.error("=====================================");
    }
}