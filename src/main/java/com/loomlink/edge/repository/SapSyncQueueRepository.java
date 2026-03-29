package com.loomlink.edge.repository;

import com.loomlink.edge.domain.model.SapSyncQueueItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for the SAP Sync Dead Letter Queue.
 * Used by the retry scheduler to find items that need re-sync.
 */
@Repository
public interface SapSyncQueueRepository extends JpaRepository<SapSyncQueueItem, UUID> {

    /** Find all items ready for retry (PENDING_SYNC with next_retry_at in the past). */
    @Query("SELECT s FROM SapSyncQueueItem s WHERE s.syncStatus = 'PENDING_SYNC' " +
           "AND s.nextRetryAt <= :now ORDER BY s.createdAt ASC")
    List<SapSyncQueueItem> findRetryable(Instant now);

    List<SapSyncQueueItem> findBySyncStatusOrderByCreatedAtDesc(String syncStatus);

    @Query("SELECT COUNT(s) FROM SapSyncQueueItem s WHERE s.syncStatus = 'PENDING_SYNC'")
    long countPendingSync();

    @Query("SELECT COUNT(s) FROM SapSyncQueueItem s WHERE s.syncStatus = 'SYNCED'")
    long countSynced();

    @Query("SELECT COUNT(s) FROM SapSyncQueueItem s WHERE s.syncStatus = 'FAILED'")
    long countFailed();
}
