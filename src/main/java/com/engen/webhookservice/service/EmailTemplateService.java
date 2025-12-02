package com.engen.webhookservice.service;

import com.engen.webhookservice.config.EmailTemplateConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Map;

/**
 * Service for rendering email templates with placeholder replacement
 * Loads templates from EmailTemplateConfig and replaces {placeholder} syntax
 */
@Service
public class EmailTemplateService {

    private static final Logger log = LoggerFactory.getLogger(EmailTemplateService.class);

    private final EmailTemplateConfig emailTemplateConfig;
    private final String subjectPrefix;

    public EmailTemplateService(
            EmailTemplateConfig emailTemplateConfig,
            @Value("${email.subjectPrefix:[Webhook Service]}") String subjectPrefix) {
        this.emailTemplateConfig = emailTemplateConfig;
        this.subjectPrefix = subjectPrefix;
    }

    /**
     * Renders an email template with the provided variables
     * @param templateName Name of the template (e.g., "servicenowTicket", "approval", "error")
     * @param variables Map of placeholder names to values
     * @return Rendered email content with subject and body
     */
    public EmailContent renderTemplate(String templateName, Map<String, String> variables) {
        EmailTemplateConfig.TemplateConfig template = emailTemplateConfig.getTemplate(templateName);

        if (template == null) {
            log.error("Email template '{}' not found", templateName);
            throw new IllegalArgumentException("Email template not found: " + templateName);
        }

        // Add subject prefix to variables if not already present
        if (!variables.containsKey("subjectPrefix")) {
            variables.put("subjectPrefix", subjectPrefix);
        }

        // Render subject and body
        String subject = replacePlaceholders(template.getSubject(), variables);
        String body = replacePlaceholders(template.getBody(), variables);

        log.debug("Rendered template '{}' with {} variables", templateName, variables.size());
        return new EmailContent(subject, body);
    }

    /**
     * Replaces all {placeholder} instances in the text with values from the map
     * Handles null values by replacing with empty string
     */
    private String replacePlaceholders(String text, Map<String, String> variables) {
        if (text == null) {
            return "";
        }

        String result = text;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(placeholder, value);
        }

        return result;
    }

    /**
     * Formats a timestamp in RFC-1123 format with system timezone
     */
    public String formatTimestamp(String timestamp) {
        if (timestamp == null || timestamp.isEmpty()) {
            return "";
        }

        try {
            ZonedDateTime zdt = ZonedDateTime.parse(timestamp);
            ZonedDateTime localZdt = zdt.withZoneSameInstant(ZoneId.systemDefault());
            return localZdt.format(DateTimeFormatter.RFC_1123_DATE_TIME);
        } catch (Exception e) {
            log.warn("Failed to parse timestamp '{}', using as-is", timestamp);
            return timestamp;
        }
    }

    /**
     * Formats a payload map as an HTML table section
     * Returns empty string if payload is null or empty
     */
    public String formatPayloadSection(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<h3>Payload</h3>\n");
        sb.append("<pre style='background-color: #f5f5f5; padding: 10px; border: 1px solid #ddd;'>\n");

        payload.forEach((key, value) -> {
            sb.append(key).append(": ").append(value).append("\n");
        });

        sb.append("</pre>\n");
        return sb.toString();
    }

    /**
     * Formats a comments row for approval template
     * Returns empty string if comments are null or empty
     */
    public String formatCommentsRow(String comments) {
        if (comments == null || comments.trim().isEmpty()) {
            return "";
        }

        return String.format(
            "<tr><td><strong>Comments</strong></td><td>%s</td></tr>\n",
            escapeHtml(comments)
        );
    }

    /**
     * Formats exception details as an HTML section
     * Returns empty string if exception message is null or empty
     */
    public String formatExceptionSection(String exceptionDetails) {
        if (exceptionDetails == null || exceptionDetails.trim().isEmpty()) {
            return "";
        }

        return String.format(
            "<h3>Exception Details</h3>\n" +
            "<pre style='background-color: #fff0f0; padding: 10px; border: 1px solid #ffcccc; color: #cc0000;'>\n%s\n</pre>\n",
            escapeHtml(exceptionDetails)
        );
    }

    /**
     * Returns color code for approval status
     */
    public String getApprovalStatusColor(String approvalStatus) {
        if (approvalStatus == null) {
            return "black";
        }

        return switch (approvalStatus.toUpperCase()) {
            case "APPROVED" -> "green";
            case "REJECTED" -> "red";
            case "PENDING" -> "orange";
            default -> "black";
        };
    }

    /**
     * Basic HTML escaping for user-provided content
     */
    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }

        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    /**
     * Container for rendered email content
     */
    public static class EmailContent {
        private final String subject;
        private final String body;

        public EmailContent(String subject, String body) {
            this.subject = subject;
            this.body = body;
        }

        public String getSubject() {
            return subject;
        }

        public String getBody() {
            return body;
        }

        @Override
        public String toString() {
            return "EmailContent{" +
                    "subject='" + subject + '\'' +
                    ", bodyLength=" + (body != null ? body.length() : 0) +
                    '}';
        }
    }
}
