package com.loomlink.edge.repository;

import com.loomlink.edge.domain.model.ExceptionInboxItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Repository for the Exception Inbox — rejected classifications awaiting
 * Senior Engineer review.
 */
@Repository
public interface ExceptionInboxRepository extends JpaRepository<ExceptionInboxItem, UUID> {

    List<ExceptionInboxItem> findByReviewStatusOrderByCreatedAtDesc(String reviewStatus);

    List<ExceptionInboxItem> findByEquipmentTagOrderByCreatedAtDesc(String equipmentTag);

    List<ExceptionInboxItem> findByPriorityOrderByCreatedAtDesc(String priority);

    @Query("SELECT e FROM ExceptionInboxItem e WHERE e.reviewStatus = 'PENDING' ORDER BY " +
           "CASE e.priority WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1 WHEN 'MEDIUM' THEN 2 ELSE 3 END, " +
           "e.createdAt DESC")
    List<ExceptionInboxItem> findPendingByPriority();

    @Query("SELECT COUNT(e) FROM ExceptionInboxItem e WHERE e.reviewStatus = 'PENDING'")
    long countPending();

    @Query("SELECT COUNT(e) FROM ExceptionInboxItem e WHERE e.reviewStatus = 'APPROVED'")
    long countApproved();

    @Query("SELECT COUNT(e) FROM ExceptionInboxItem e WHERE e.reviewStatus = 'RECLASSIFIED'")
    long countReclassified();

    @Query("SELECT COUNT(e) FROM ExceptionInboxItem e WHERE e.reviewStatus = 'DISMISSED'")
    long countDismissed();

    // ── SLA & Metrics Queries ──────────────────────────────────────────

    /** All resolved items (APPROVED, RECLASSIFIED, or DISMISSED). */
    @Query("SELECT e FROM ExceptionInboxItem e WHERE e.reviewStatus IN ('APPROVED', 'RECLASSIFIED', 'DISMISSED')")
    List<ExceptionInboxItem> findResolved();

    /** Count of resolved items where SLA was met. */
    @Query("SELECT COUNT(e) FROM ExceptionInboxItem e WHERE e.slaMet = true")
    long countSlaMet();

    /** Count of resolved items where SLA was missed. */
    @Query("SELECT COUNT(e) FROM ExceptionInboxItem e WHERE e.slaMet = false")
    long countSlaMissed();

    /** Count of resolved items (any non-PENDING status). */
    @Query("SELECT COUNT(e) FROM ExceptionInboxItem e WHERE e.reviewStatus IN ('APPROVED', 'RECLASSIFIED', 'DISMISSED')")
    long countResolved();

    /** Items by priority for breakdown stats. */
    @Query("SELECT e FROM ExceptionInboxItem e WHERE e.reviewStatus IN ('APPROVED', 'RECLASSIFIED', 'DISMISSED') AND e.priority = :priority")
    List<ExceptionInboxItem> findResolvedByPriority(@Param("priority") String priority);
}
