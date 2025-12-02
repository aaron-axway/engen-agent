package com.engen.webhookservice.service;

import com.engen.webhookservice.repository.EventRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Service
@ConditionalOnProperty(name = "event.cleanup.enabled", havingValue = "true", matchIfMissing = false)
public class EventCleanupService {

    private static final Logger log = LoggerFactory.getLogger(EventCleanupService.class);

    private final EventRecordRepository eventRecordRepository;

    @Value("${event.cleanup.retention-days:30}")
    private int retentionDays;
    
    @Value("${event.cleanup.retention-minutes:0}")
    private int retentionMinutes;

    @Value("${event.cleanup.batch-size:1000}")
    private int batchSize;

    public EventCleanupService(EventRecordRepository eventRecordRepository) {
        this.eventRecordRepository = eventRecordRepository;
    }

    @Scheduled(cron = "${event.cleanup.cron:0 0 2 * * ?}")  // Default: 2 AM daily
    @Transactional
    public void cleanupOldEvents() {
        // Use minutes if specified, otherwise use days
        int effectiveRetentionMinutes = retentionMinutes > 0 ? retentionMinutes : retentionDays * 24 * 60;
        log.info("Starting event cleanup task. Retention period: {} minutes ({} days)", 
                effectiveRetentionMinutes, effectiveRetentionMinutes / (24.0 * 60));
        
        try {
            Instant cutoffDate = Instant.now().minus(effectiveRetentionMinutes, ChronoUnit.MINUTES);
            
            // Check if there are any old events to delete
            long oldEventCount = eventRecordRepository.countOldEvents(cutoffDate);
            
            if (oldEventCount == 0) {
                log.debug("Event cleanup completed. No events to delete.");
                return;
            }
            
            // Delete old events
            int deleted = eventRecordRepository.deleteOldEvents(cutoffDate);
            
            if (deleted > 0) {
                log.info("Event cleanup completed. Deleted {} events older than {}", deleted, cutoffDate);
            } else {
                log.info("Event cleanup completed. No events to delete.");
            }
            
        } catch (DataAccessException e) {
            // Handle case where table doesn't exist yet
            if (e.getMessage() != null && e.getMessage().contains("not found")) {
                log.debug("Event table not found - no events to clean up yet");
            } else {
                log.error("Error during event cleanup", e);
            }
        } catch (Exception e) {
            log.error("Error during event cleanup", e);
        }
    }
    
    @Scheduled(cron = "${event.cleanup.ignored-cron:0 30 1 * * ?}")  // Default: 1:30 AM daily
    @Transactional
    public void cleanupIgnoredEvents() {
        // For ignored events, use the same retention as regular events when using minutes
        int effectiveRetentionMinutes = retentionMinutes > 0 ? retentionMinutes : Math.min(retentionDays, 7) * 24 * 60;
        log.info("Starting ignored events cleanup. Retention period: {} minutes ({} days)", 
                effectiveRetentionMinutes, effectiveRetentionMinutes / (24.0 * 60));
        
        try {
            Instant cutoffDate = Instant.now().minus(effectiveRetentionMinutes, ChronoUnit.MINUTES);
            int deleted = eventRecordRepository.deleteIgnoredEvents(cutoffDate);
            
            if (deleted > 0) {
                log.info("Deleted {} ignored events older than {}", deleted, cutoffDate);
            }
        } catch (DataAccessException e) {
            // Handle case where table doesn't exist yet
            if (e.getMessage() != null && e.getMessage().contains("not found")) {
                log.debug("Event table not found - no ignored events to clean up yet");
            } else {
                log.error("Error during ignored events cleanup", e);
            }
        } catch (Exception e) {
            log.error("Error during ignored events cleanup", e);
        }
    }
}