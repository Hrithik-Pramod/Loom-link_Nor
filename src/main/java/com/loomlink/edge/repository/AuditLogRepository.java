package com.loomlink.edge.repository;

import com.loomlink.edge.domain.model.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for the immutable audit trail. Read-only queries only —
 * AuditLog records are never updated or deleted.
 */
@Repository
public interface AuditLogRepository extends JpaRepository<AuditLog, UUID> {

    List<AuditLog> findBySapNotificationNumber(String sapNotificationNumber);

    List<AuditLog> findByEquipmentTag(String equipmentTag);

    List<AuditLog> findByGatePassed(boolean gatePassed);

    List<AuditLog> findByCreatedAtBetween(Instant start, Instant end);

    @Query("SELECT a FROM AuditLog a ORDER BY a.createdAt DESC")
    List<AuditLog> findRecentLogs();

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.gatePassed = true")
    long countPassedClassifications();

    @Query("SELECT COUNT(a) FROM AuditLog a WHERE a.gatePassed = false")
    long countRejectedClassifications();

    @Query("SELECT AVG(a.confidenceScore) FROM AuditLog a WHERE a.gatePassed = true")
    Double averagePassedConfidence();

    @Query("SELECT AVG(a.totalPipelineLatencyMs) FROM AuditLog a")
    Double averagePipelineLatency();
}
