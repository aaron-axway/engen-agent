package com.engen.webhookservice.service;

import com.engen.webhookservice.dto.WebhookEvent;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Service for sending email notifications for webhook events
 * Uses EmailTemplateService for template rendering
 */
@Service
@ConditionalOnProperty(value = "email.enabled", havingValue = "true", matchIfMissing = false)
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final EmailTemplateService emailTemplateService;

    @Value("${email.from:noreply@engen-webhook.com}")
    private String fromEmail;

    @Value("${email.to:}")
    private String[] toEmails;

    @Value("${email.cc:}")
    private String[] ccEmails;

    @Value("${email.enabled:false}")
    private boolean emailEnabled;

    public EmailService(JavaMailSender mailSender, EmailTemplateService emailTemplateService) {
        this.mailSender = mailSender;
        this.emailTemplateService = emailTemplateService;
    }

    /**
     * Send email notification for ServiceNow ticket creation
     */
    public void sendServiceNowTicketNotification(WebhookEvent event, String ticketId) {
        if (!emailEnabled || toEmails.length == 0) {
            log.debug("Email notifications disabled or no recipients configured");
            return;
        }

        try {
            // Build variables map for template
            Map<String, String> variables = new HashMap<>();
            variables.put("ticketId", ticketId);
            variables.put("eventId", event.getEventId());
            variables.put("eventType", event.getEventType());
            variables.put("source", event.getSource() != null ? event.getSource() : "N/A");
            variables.put("correlationId", event.getCorrelationId() != null ? event.getCorrelationId() : "N/A");
            variables.put("timestamp", formatTimestamp(event));
            variables.put("payloadSection", formatPayloadSection(event));

            // Render template
            EmailTemplateService.EmailContent content = emailTemplateService.renderTemplate("servicenowTicket", variables);

            sendEmail(content.getSubject(), content.getBody());
            log.info("ServiceNow ticket notification sent for event: {}", event.getEventId());

        } catch (Exception e) {
            log.error("Failed to send ServiceNow ticket notification email for event: {}", event.getEventId(), e);
        }
    }

    /**
     * Send email notification for approval workflow events
     */
    public void sendApprovalNotification(WebhookEvent event, String approvalStatus, String comments) {
        if (!emailEnabled || toEmails.length == 0) {
            log.debug("Email notifications disabled or no recipients configured");
            return;
        }

        try {
            // Build variables map for template
            Map<String, String> variables = new HashMap<>();
            variables.put("approvalStatus", approvalStatus);
            variables.put("approvalStatusColor", emailTemplateService.getApprovalStatusColor(approvalStatus));
            variables.put("eventId", event.getEventId());
            variables.put("eventType", event.getEventType());
            variables.put("correlationId", event.getCorrelationId() != null ? event.getCorrelationId() : "N/A");
            variables.put("commentsRow", emailTemplateService.formatCommentsRow(comments));
            variables.put("timestamp", formatTimestamp(event));

            // Render template
            EmailTemplateService.EmailContent content = emailTemplateService.renderTemplate("approval", variables);

            sendEmail(content.getSubject(), content.getBody());
            log.info("Approval notification sent for event: {}", event.getEventId());

        } catch (Exception e) {
            log.error("Failed to send approval notification email for event: {}", event.getEventId(), e);
        }
    }

    /**
     * Send email notification for webhook event processing errors
     */
    public void sendErrorNotification(WebhookEvent event, String errorMessage, Exception exception) {
        if (!emailEnabled || toEmails.length == 0) {
            log.debug("Email notifications disabled or no recipients configured");
            return;
        }

        try {
            // Build variables map for template
            Map<String, String> variables = new HashMap<>();
            variables.put("eventId", event.getEventId());
            variables.put("eventType", event.getEventType());
            variables.put("source", event.getSource() != null ? event.getSource() : "N/A");
            variables.put("errorMessage", errorMessage);
            variables.put("timestamp", formatTimestamp(event));
            variables.put("exceptionSection", formatExceptionSection(exception));

            // Render template
            EmailTemplateService.EmailContent content = emailTemplateService.renderTemplate("error", variables);

            sendEmail(content.getSubject(), content.getBody());
            log.info("Error notification sent for event: {}", event.getEventId());

        } catch (Exception e) {
            log.error("Failed to send error notification email for event: {}", event.getEventId(), e);
        }
    }

    /**
     * Send generic email
     */
    private void sendEmail(String subject, String body) throws MessagingException {
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(fromEmail);
        helper.setTo(toEmails);

        if (ccEmails != null && ccEmails.length > 0) {
            helper.setCc(ccEmails);
        }

        helper.setSubject(subject);
        helper.setText(body, true); // true = HTML content

        mailSender.send(message);
        log.info("Email sent successfully to: {}", String.join(", ", toEmails));
    }

    /**
     * Format timestamp for email display
     */
    private String formatTimestamp(WebhookEvent event) {
        if (event.getTimestamp() == null) {
            return "N/A";
        }
        return emailTemplateService.formatTimestamp(event.getTimestamp().toString());
    }

    /**
     * Format payload section for email display
     */
    private String formatPayloadSection(WebhookEvent event) {
        if (event.getPayload() == null || event.getPayload().isEmpty()) {
            return "";
        }
        return emailTemplateService.formatPayloadSection(event.getPayload());
    }

    /**
     * Format exception section for email display
     */
    private String formatExceptionSection(Exception exception) {
        if (exception == null) {
            return "";
        }
        String exceptionDetails = exception.getClass().getSimpleName() + ": " + exception.getMessage();
        return emailTemplateService.formatExceptionSection(exceptionDetails);
    }

    /**
     * Check if email service is enabled and configured
     */
    public boolean isEmailEnabled() {
        return emailEnabled && toEmails != null && toEmails.length > 0;
    }
}