package com.loomlink.edge.repository;

import com.loomlink.edge.domain.enums.ComplianceStatus;
import com.loomlink.edge.domain.enums.EmissionClassification;
import com.loomlink.edge.domain.model.EmissionEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Repository for the EmissionEvent entity — core persistence layer for
 * Challenge 02 (Contextual Emission Surveillance).
 *
 * <p>Provides comprehensive query methods for:
 * <ul>
 *   <li>Equipment-level tracking and trend analysis (findByEquipmentTag, findByEquipmentTagAndDetectedAtAfter)</li>
 *   <li>Mission and robot traceability (findByMissionId)</li>
 *   <li>Classification-based queries (findByClassification, countByClassification)</li>
 *   <li>Compliance lifecycle queries (findByComplianceStatus)</li>
 *   <li>Review and experience bank queries (findByReviewStatus)</li>
 *   <li>Spatial analysis (findByLocationArea)</li>
 *   <li>Time-range queries for reporting (findByDetectedAtBetween)</li>
 *   <li>Dashboard and KPI metrics (countByClassification, averageConfidence, countFugitiveEmissions, countFalsePositives)</li>
 * </ul>
 * </p>
 *
 * <p>All queries leverage the indexes defined in the EmissionEvent entity
 * to ensure sub-millisecond response times on typical facility datasets.</p>
 *
 * @see com.loomlink.edge.domain.model.EmissionEvent
 * @see com.loomlink.edge.domain.enums.EmissionClassification
 * @see com.loomlink.edge.domain.enums.ComplianceStatus
 */
@Repository
public interface EmissionEventRepository extends JpaRepository<EmissionEvent, UUID> {

    // ── Basic Finder Methods ────────────────────────────────────────────

    /**
     * Find all emission events for a specific equipment tag.
     *
     * @param tag the SAP equipment tag (e.g., "FLG-4401", "VLV-2203")
     * @return list of emission events for that equipment, ordered by creation
     */
    List<EmissionEvent> findByEquipmentTag(String tag);

    /**
     * Find all emission events from a specific robot mission/inspection.
     *
     * @param missionId the SARA mission identifier
     * @return list of emission events from that mission
     */
    List<EmissionEvent> findByMissionId(String missionId);

    /**
     * Find all events with a specific classification.
     *
     * @param classification the emission classification (e.g., FUGITIVE_EMISSION, NORMAL_PROCESS)
     * @return list of emission events with that classification
     */
    List<EmissionEvent> findByClassification(EmissionClassification classification);

    /**
     * Find all events at a specific compliance lifecycle stage.
     *
     * @param status the compliance status (e.g., DETECTED, CLASSIFIED, DOCUMENTED)
     * @return list of emission events with that compliance status
     */
    List<EmissionEvent> findByComplianceStatus(ComplianceStatus status);

    /**
     * Find all events with a specific review status (PENDING, CONFIRMED, RECLASSIFIED, DISMISSED).
     *
     * @param reviewStatus the review status string
     * @return list of emission events with that review status
     */
    List<EmissionEvent> findByReviewStatus(String reviewStatus);

    /**
     * Find all events in a specific facility location area.
     *
     * @param area the facility location (e.g., "Module B / Deck 2 / Area North")
     * @return list of emission events at that location
     */
    List<EmissionEvent> findByLocationArea(String area);

    /**
     * Find all events detected within a time range (for reporting and historical analysis).
     *
     * @param start the detection time range start (inclusive)
     * @param end the detection time range end (inclusive)
     * @return list of emission events detected in that time period
     */
    List<EmissionEvent> findByDetectedAtBetween(Instant start, Instant end);

    /**
     * Find recent events for a specific equipment tag (trend tracking).
     *
     * <p>Returns all events at the given equipment tag detected since the given instant.
     * Useful for trend analysis (e.g., "is this equipment getting worse?") and
     * for computing previousDetections7d enrichment.</p>
     *
     * @param tag the SAP equipment tag
     * @param since the minimum detection time (inclusive)
     * @return list of emission events at that equipment since the given time, chronologically ordered
     */
    List<EmissionEvent> findByEquipmentTagAndDetectedAtAfter(String tag, Instant since);

    /**
     * Find all events ordered by detection time, most recent first.
     *
     * <p>Used for dashboard "latest emissions" feeds and for audit trail inspection.</p>
     *
     * @return list of all emission events, newest first
     */
    List<EmissionEvent> findAllByOrderByDetectedAtDesc();

    // ── Dashboard & KPI Metrics ────────────────────────────────────────

    /**
     * Count emission events grouped by classification for dashboard statistics.
     *
     * <p>Returns a list of Object arrays [classification (String), count (Long)]
     * representing the distribution of emissions across classification categories.
     * Useful for regulatory reporting (fugitive vs. planned) and anomaly detection.</p>
     *
     * @return list of [classification string, count] pairs
     */
    @Query("SELECT e.classification, COUNT(e) FROM EmissionEvent e GROUP BY e.classification")
    List<Object[]> countByClassification();

    /**
     * Calculate the average confidence score of all classified emissions.
     *
     * <p>Useful for monitoring model quality and performance trending.
     * A declining average suggests degraded model performance or changing
     * sensor characteristics.</p>
     *
     * @return the average confidence score (0.0 - 1.0), or null if no events exist
     */
    @Query("SELECT AVG(e.confidence) FROM EmissionEvent e WHERE e.classification IS NOT NULL")
    Double averageConfidence();

    /**
     * Count fugitive emissions (real leaks requiring regulatory documentation).
     *
     * <p>Key KPI for EU 2024/1787 compliance and facility emissions inventory.
     * Fugitive emissions require immediate response and remediation tracking.</p>
     *
     * @return the total count of FUGITIVE_EMISSION classified events
     */
    @Query("SELECT COUNT(e) FROM EmissionEvent e WHERE e.classification = 'FUGITIVE_EMISSION'")
    long countFugitiveEmissions();

    /**
     * Count false positives and non-emission events that passed the gate.
     *
     * <p>Measures model false positive rate by counting events classified as
     * SENSOR_ARTIFACT, MAINTENANCE_ACTIVITY, PLANNED_VENTING, or NORMAL_PROCESS
     * that nonetheless passed the Emission Reflector Gate (gatePassed = true).
     * These events consume operational overhead without corresponding real emissions.</p>
     *
     * <p>A high count suggests either overly sensitive gate thresholds or
     * declining model quality in edge conditions.</p>
     *
     * @return count of non-emission events that passed the gate
     */
    @Query("""
        SELECT COUNT(e) FROM EmissionEvent e
        WHERE (e.classification IN ('SENSOR_ARTIFACT', 'MAINTENANCE_ACTIVITY', 'PLANNED_VENTING', 'NORMAL_PROCESS'))
          AND e.gatePassed = true
        """)
    long countFalsePositivesSuppressed();

    /**
     * Find trend data for a specific equipment: all events in the last 7 days, ordered by time.
     *
     * <p>Used by trend analysis services to determine if an equipment's emission
     * pattern is ESCALATING, STABLE, DECLINING, or represents a FIRST_DETECTION.
     * Returns the complete event history for local time-series analysis.</p>
     *
     * @param equipmentTag the SAP equipment tag
     * @param sevenDaysAgo the instant representing 7 days before now
     * @return list of all events for that equipment in the last 7 days, chronologically ordered (oldest first)
     */
    @Query("""
        SELECT e FROM EmissionEvent e
        WHERE e.equipmentTag = :equipmentTag
          AND e.detectedAt >= :sevenDaysAgo
        ORDER BY e.detectedAt ASC
        """)
    List<EmissionEvent> findTrendDataLast7Days(
        @Param("equipmentTag") String equipmentTag,
        @Param("sevenDaysAgo") Instant sevenDaysAgo);

    /**
     * Spatial summary: count emission events grouped by facility location area.
     *
     * <p>Returns a list of Object arrays [locationArea (String), count (Long)]
     * representing the distribution of emissions across facility zones.
     * Useful for identifying hotspots and prioritizing facility zones for
     * maintenance or additional monitoring.</p>
     *
     * @return list of [location area string, count] pairs
     */
    @Query("""
        SELECT e.locationArea, COUNT(e) FROM EmissionEvent e
        GROUP BY e.locationArea
        ORDER BY COUNT(e) DESC
        """)
    List<Object[]> countByLocationArea();
}
