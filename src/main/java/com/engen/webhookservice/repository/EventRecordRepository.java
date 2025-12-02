package com.engen.webhookservice.repository;

import com.engen.webhookservice.entity.EventRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface EventRecordRepository extends JpaRepository<EventRecord, Long> {
    
    Optional<EventRecord> findByEventId(String eventId);
    
    List<EventRecord> findBySource(String source);
    
    List<EventRecord> findByStatus(String status);
    
    List<EventRecord> findBySourceAndStatus(String source, String status);
    
    @Query("SELECT e FROM EventRecord e WHERE e.receivedAt BETWEEN :startTime AND :endTime")
    List<EventRecord> findEventsInTimeRange(@Param("startTime") Instant startTime, 
                                           @Param("endTime") Instant endTime);
    
    @Query("SELECT e FROM EventRecord e WHERE e.status = 'FAILED' AND e.retryCount < :maxRetries")
    List<EventRecord> findFailedEventsForRetry(@Param("maxRetries") Integer maxRetries);
    
    List<EventRecord> findByCorrelationId(String correlationId);
    
    Optional<EventRecord> findByRelatedEventId(String relatedEventId);
    
    List<EventRecord> findByApprovalState(String approvalState);
    
    List<EventRecord> findByCallbackStatus(String callbackStatus);
    
    @Modifying
    @Query("DELETE FROM EventRecord e WHERE e.receivedAt < :cutoffDate")
    int deleteOldEvents(@Param("cutoffDate") Instant cutoffDate);
    
    @Query("SELECT e FROM EventRecord e WHERE e.receivedAt < :cutoffDate")
    List<EventRecord> findOldEvents(@Param("cutoffDate") Instant cutoffDate, org.springframework.data.domain.Pageable pageable);
    
    @Modifying
    @Query("DELETE FROM EventRecord e WHERE e.status = 'IGNORED' AND e.receivedAt < :cutoffDate")
    int deleteIgnoredEvents(@Param("cutoffDate") Instant cutoffDate);
    
    @Query("SELECT COUNT(e) FROM EventRecord e WHERE e.receivedAt < :cutoffDate")
    long countOldEvents(@Param("cutoffDate") Instant cutoffDate);
}