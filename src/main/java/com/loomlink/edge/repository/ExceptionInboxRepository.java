package com.loomlink.edge.repository;

import com.loomlink.edge.domain.model.ExceptionInboxItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
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
}
