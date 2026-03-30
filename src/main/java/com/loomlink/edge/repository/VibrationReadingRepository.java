package com.loomlink.edge.repository;

import com.loomlink.edge.domain.model.VibrationReading;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface VibrationReadingRepository extends JpaRepository<VibrationReading, UUID> {

    List<VibrationReading> findByEquipmentTagOrderByRecordedAtDesc(String equipmentTag);

    /** Latest N readings for an asset (for trend analysis). */
    @Query("SELECT vr FROM VibrationReading vr WHERE vr.equipmentTag = :tag " +
           "ORDER BY vr.recordedAt DESC")
    List<VibrationReading> findLatestReadings(@Param("tag") String tag, Pageable pageable);

    /** Readings within a time window for RUL computation. */
    @Query("SELECT vr FROM VibrationReading vr WHERE vr.equipmentTag = :tag " +
           "AND vr.recordedAt >= :from AND vr.recordedAt <= :to ORDER BY vr.recordedAt ASC")
    List<VibrationReading> findReadingsInWindow(@Param("tag") String tag,
                                                 @Param("from") Instant from,
                                                 @Param("to") Instant to);

    /** Latest single reading for an asset. */
    default VibrationReading findLatestReading(String tag) {
        List<VibrationReading> results = findLatestReadings(tag, Pageable.ofSize(1));
        return results.isEmpty() ? null : results.get(0);
    }
}
