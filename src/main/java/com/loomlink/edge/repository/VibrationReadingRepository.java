package com.loomlink.edge.repository;

import com.loomlink.edge.domain.model.VibrationReading;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface VibrationReadingRepository extends JpaRepository<VibrationReading, UUID> {

    List<VibrationReading> findByEquipmentTagOrderByRecordedAtDesc(String equipmentTag);

    /** Latest N readings for an asset (for trend analysis). */
    @Query("SELECT vr FROM VibrationReading vr WHERE vr.equipmentTag = :tag " +
           "ORDER BY vr.recordedAt DESC LIMIT :limit")
    List<VibrationReading> findLatestReadings(String tag, int limit);

    /** Readings within a time window for RUL computation. */
    @Query("SELECT vr FROM VibrationReading vr WHERE vr.equipmentTag = :tag " +
           "AND vr.recordedAt >= :from AND vr.recordedAt <= :to ORDER BY vr.recordedAt ASC")
    List<VibrationReading> findReadingsInWindow(String tag, Instant from, Instant to);

    /** Latest single reading for an asset. */
    @Query("SELECT vr FROM VibrationReading vr WHERE vr.equipmentTag = :tag " +
           "ORDER BY vr.recordedAt DESC LIMIT 1")
    VibrationReading findLatestReading(String tag);
}
