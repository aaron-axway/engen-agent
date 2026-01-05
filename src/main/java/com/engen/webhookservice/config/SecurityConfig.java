package com.engen.webhookservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final WebhookAuthenticationFilter webhookAuthenticationFilter;

    public SecurityConfig(WebhookAuthenticationFilter webhookAuthenticationFilter) {
        this.webhookAuthenticationFilter = webhookAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())  // Disable request caching for webhooks
            .headers(headers -> headers
                .frameOptions(frame -> frame.sameOrigin())  // Allow H2 console frames from same origin
                .contentTypeOptions(contentType -> {})      // X-Content-Type-Options: nosniff
                .httpStrictTransportSecurity(hsts -> hsts   // HSTS - browsers will enforce HTTPS
                    .includeSubDomains(true)
                    .maxAgeInSeconds(31536000))             // 1 year
            )
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/actuator/health", "/webhooks/health").permitAll()
                .requestMatchers("/h2-console/**").permitAll()  // Allow H2 console access
                .requestMatchers("/webhooks/axway", "/webhooks/servicenow").authenticated()
                .anyRequest().denyAll()
            )
            .addFilterBefore(webhookAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }
}