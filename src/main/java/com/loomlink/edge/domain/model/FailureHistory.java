package com.loomlink.edge.domain.model;

import com.loomlink.edge.domain.enums.FailureModeCode;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Historical failure record in the Experience Bank. Fleet sibling matching
 * uses these records to compute survival curves and prescribe next-best-actions.
 */
@Entity
@Table(name = "failure_histories")
public class FailureHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "equipment_tag", nullable = false)
    private String equipmentTag;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_mode_code", nullable = false)
    private FailureModeCode failureModeCode;

    @Column(name = "cause_code")
    private String causeCode;

    @Column(name = "failure_date", nullable = false)
    private LocalDate failureDate;

    /** Operating hours at time of failure — key metric for survival analysis. */
    @Column(name = "operating_hours_at_failure")
    private int operatingHoursAtFailure;

    /** Days from first symptom detection to actual failure. */
    @Column(name = "symptom_to_failure_days")
    private int symptomToFailureDays;

    /** Repair action taken. */
    @Column(name = "repair_action", length = 1000)
    private String repairAction;

    /** Parts used in the repair. */
    @Column(name = "parts_used", length = 500)
    private String partsUsed;

    /** Tools required for the repair. */
    @Column(name = "tools_required", length = 500)
    private String toolsRequired;

    /** Downtime in hours caused by this failure. */
    @Column(name = "downtime_hours")
    private double downtimeHours;

    /** Was this a planned or unplanned maintenance event? */
    @Column(name = "planned")
    private boolean planned;

    /** Source: LEGACY_RECOVERY or LIVE_CLASSIFICATION. */
    @Column(name = "data_source")
    private String dataSource;

    protected FailureHistory() {}

    public static FailureHistory create(String equipmentTag, FailureModeCode failureModeCode,
                                         String causeCode, LocalDate failureDate,
                                         int operatingHoursAtFailure, int symptomToFailureDays,
                                         String repairAction, String partsUsed,
                                         String toolsRequired, double downtimeHours,
                                         boolean planned, String dataSource) {
        FailureHistory fh = new FailureHistory();
        fh.equipmentTag = equipmentTag;
        fh.failureModeCode = failureModeCode;
        fh.causeCode = causeCode;
        fh.failureDate = failureDate;
        fh.operatingHoursAtFailure = operatingHoursAtFailure;
        fh.symptomToFailureDays = symptomToFailureDays;
        fh.repairAction = repairAction;
        fh.partsUsed = partsUsed;
        fh.toolsRequired = toolsRequired;
        fh.downtimeHours = downtimeHours;
        fh.planned = planned;
        fh.dataSource = dataSource;
        return fh;
    }

    public UUID getId() { return id; }
    public String getEquipmentTag() { return equipmentTag; }
    public FailureModeCode getFailureModeCode() { return failureModeCode; }
    public String getCauseCode() { return causeCode; }
    public LocalDate getFailureDate() { return failureDate; }
    public int getOperatingHoursAtFailure() { return operatingHoursAtFailure; }
    public int getSymptomToFailureDays() { return symptomToFailureDays; }
    public String getRepairAction() { return repairAction; }
    public String getPartsUsed() { return partsUsed; }
    public String getToolsRequired() { return toolsRequired; }
    public double getDowntimeHours() { return downtimeHours; }
    public boolean isPlanned() { return planned; }
    public String getDataSource() { return dataSource; }
}
