package com.loomlink.edge.repository;

import com.loomlink.edge.domain.enums.MissionStatus;
import com.loomlink.edge.domain.model.RobotMission;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface RobotMissionRepository extends JpaRepository<RobotMission, UUID> {

    List<RobotMission> findAllByOrderByCreatedAtDesc();

    List<RobotMission> findByStatus(MissionStatus status);

    List<RobotMission> findByRobotId(String robotId);

    @Query("SELECT m FROM RobotMission m WHERE m.status IN ('DISPATCHED', 'IN_PROGRESS')")
    List<RobotMission> findActiveMissions();

    @Query("SELECT COUNT(m) FROM RobotMission m WHERE m.status = 'COMPLETED'")
    long countCompleted();

    @Query("SELECT AVG(m.anomaliesConfirmed) FROM RobotMission m WHERE m.status = 'COMPLETED'")
    Double averageAnomaliesPerMission();

    @Query("SELECT SUM(m.waypointCount) FROM RobotMission m WHERE m.status = 'COMPLETED'")
    Long totalWaypointsInspected();
}
