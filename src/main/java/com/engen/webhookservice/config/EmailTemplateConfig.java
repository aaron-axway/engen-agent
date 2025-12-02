package com.engen.webhookservice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;

import jakarta.validation.constraints.NotNull;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuration class for email templates loaded from email-templates.yml
 * Allows email content to be modified without code changes
 */
@Configuration
@ConfigurationProperties(prefix = "email.templates")
@PropertySource(value = "classpath:email-templates.yml", factory = YamlPropertySourceFactory.class)
public class EmailTemplateConfig {

    private Map<String, TemplateConfig> templates = new HashMap<>();

    public Map<String, TemplateConfig> getTemplates() {
        return templates;
    }

    public void setTemplates(Map<String, TemplateConfig> templates) {
        this.templates = templates;
    }

    /**
     * Gets a specific template by name
     * @param templateName Name of the template (e.g., "servicenowTicket", "approval", "error")
     * @return Template configuration or null if not found
     */
    public TemplateConfig getTemplate(String templateName) {
        return templates.get(templateName);
    }

    /**
     * Individual email template configuration
     */
    public static class TemplateConfig {

        @NotNull(message = "Email template subject cannot be null")
        private String subject;

        @NotNull(message = "Email template body cannot be null")
        private String body;

        public String getSubject() {
            return subject;
        }

        public void setSubject(String subject) {
            this.subject = subject;
        }

        public String getBody() {
            return body;
        }

        public void setBody(String body) {
            this.body = body;
        }

        @Override
        public String toString() {
            return "TemplateConfig{" +
                    "subject='" + subject + '\'' +
                    ", body length=" + (body != null ? body.length() : 0) +
                    '}';
        }
    }
}
