package com.loomlink.edge.repository;

import com.loomlink.edge.domain.enums.FailureModeCode;
import com.loomlink.edge.domain.model.FailureHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FailureHistoryRepository extends JpaRepository<FailureHistory, UUID> {

    List<FailureHistory> findByEquipmentTag(String equipmentTag);

    List<FailureHistory> findByEquipmentTagAndFailureModeCode(String equipmentTag, FailureModeCode code);

    /** Find all failure records for fleet siblings with the same failure mode. */
    @Query("SELECT fh FROM FailureHistory fh WHERE fh.equipmentTag IN :siblingTags " +
           "AND fh.failureModeCode = :failureMode ORDER BY fh.failureDate DESC")
    List<FailureHistory> findSiblingFailures(List<String> siblingTags, FailureModeCode failureMode);

    /** Average operating hours at failure for a given failure mode across a set of assets. */
    @Query("SELECT AVG(fh.operatingHoursAtFailure) FROM FailureHistory fh " +
           "WHERE fh.equipmentTag IN :tags AND fh.failureModeCode = :failureMode")
    Double averageHoursAtFailure(List<String> tags, FailureModeCode failureMode);

    @Query("SELECT COUNT(fh) FROM FailureHistory fh WHERE fh.dataSource = :source")
    long countByDataSource(String source);
}
