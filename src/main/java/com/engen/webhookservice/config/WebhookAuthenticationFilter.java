package com.engen.webhookservice.config;

import com.engen.webhookservice.service.AuthenticationService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingRequestWrapper;

import java.io.IOException;
import java.util.Collections;

@Component
public class WebhookAuthenticationFilter extends OncePerRequestFilter {

    private final AuthenticationService authenticationService;

    public WebhookAuthenticationFilter(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        
        if (!request.getRequestURI().startsWith("/webhooks/")) {
            filterChain.doFilter(request, response);
            return;
        }

        ContentCachingRequestWrapper wrappedRequest = new ContentCachingRequestWrapper(request);
        
        String source = extractWebhookSource(request.getRequestURI());
        boolean authenticated = false;

        if ("axway".equals(source)) {
            authenticated = authenticationService.authenticateAxwayWebhook(wrappedRequest);
        } else if ("servicenow".equals(source)) {
            authenticated = authenticationService.authenticateServiceNowWebhook(wrappedRequest);
        }

        if (authenticated) {
            UsernamePasswordAuthenticationToken authToken = 
                new UsernamePasswordAuthenticationToken(source, null, Collections.emptyList());
            SecurityContextHolder.getContext().setAuthentication(authToken);
        }

        filterChain.doFilter(wrappedRequest, response);
    }

    private String extractWebhookSource(String uri) {
        if (uri.contains("/axway")) return "axway";
        if (uri.contains("/servicenow")) return "servicenow";
        return "unknown";
    }
}