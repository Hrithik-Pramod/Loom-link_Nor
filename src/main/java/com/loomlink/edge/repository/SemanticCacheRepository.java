package com.loomlink.edge.repository;

import com.loomlink.edge.domain.model.SemanticCacheEntry;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Repository for the Semantic Cache — enables near-instant lookups
 * of previously verified classifications by text similarity.
 *
 * <p>Strategy: two-tier lookup for maximum speed:</p>
 * <ol>
 *   <li>Exact normalized text match (hash index, ~1ms)</li>
 *   <li>Cosine similarity on embedding hash (approximate, used as fallback)</li>
 * </ol>
 */
@Repository
public interface SemanticCacheRepository extends JpaRepository<SemanticCacheEntry, UUID> {

    /** Exact match on normalized text — fastest path, covers copy-paste or template scenarios. */
    Optional<SemanticCacheEntry> findByNormalizedText(String normalizedText);

    /** Find by embedding hash — serves as the locality-sensitive hash for similarity. */
    List<SemanticCacheEntry> findByTextEmbeddingHash(long textEmbeddingHash);

    /** Find all entries for a specific equipment tag. */
    List<SemanticCacheEntry> findByEquipmentTag(String equipmentTag);

    @Query("SELECT COUNT(e) FROM SemanticCacheEntry e")
    long cacheSize();

    @Query("SELECT SUM(e.hitCount) FROM SemanticCacheEntry e")
    Long totalHits();

    @Query("SELECT e FROM SemanticCacheEntry e ORDER BY e.hitCount DESC")
    List<SemanticCacheEntry> findTopHits();
}
