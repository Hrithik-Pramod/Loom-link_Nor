package com.loomlink.edge.domain.model;

import com.loomlink.edge.domain.enums.EquipmentClass;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.util.UUID;

/**
 * An asset in the facility fleet — the physical equipment that generates
 * notifications, vibration data, and failure histories. The Experience Bank
 * stores the complete "life story" of every asset.
 */
@Entity
@Table(name = "assets")
public class Asset {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Equipment tag in SAP (e.g., "P-1001A"). */
    @Column(name = "equipment_tag", nullable = false, unique = true)
    private String equipmentTag;

    /** Human-readable name (e.g., "Seawater Lift Pump A"). */
    @Column(name = "asset_name", nullable = false)
    private String assetName;

    @Enumerated(EnumType.STRING)
    @Column(name = "equipment_class", nullable = false)
    private EquipmentClass equipmentClass;

    /** Manufacturer (e.g., "Sulzer", "Siemens"). */
    @Column(name = "manufacturer")
    private String manufacturer;

    /** Model number for fleet sibling matching. */
    @Column(name = "model_number")
    private String modelNumber;

    /** SAP Plant code. */
    @Column(name = "sap_plant")
    private String sapPlant;

    /** Functional location in the facility. */
    @Column(name = "functional_location")
    private String functionalLocation;

    /** Installation date — critical for age-based RUL calculations. */
    @Column(name = "installation_date")
    private LocalDate installationDate;

    /** Design life in years — used to compute remaining design margin. */
    @Column(name = "design_life_years")
    private int designLifeYears;

    /** Current operational status. */
    @Column(name = "operational_status")
    private String operationalStatus;

    /** Is this a standby unit in a 1oo2 redundancy configuration? */
    @Column(name = "is_standby")
    private boolean isStandby;

    /** If standby, the active sibling's equipment tag. */
    @Column(name = "redundancy_partner_tag")
    private String redundancyPartnerTag;

    /** Duty class — hours/year of operation. */
    @Column(name = "duty_hours_per_year")
    private int dutyHoursPerYear;

    /** Current cumulative operating hours. */
    @Column(name = "cumulative_operating_hours")
    private int cumulativeOperatingHours;

    protected Asset() {}

    public static Asset create(String equipmentTag, String assetName, EquipmentClass equipmentClass,
                                String manufacturer, String modelNumber, String sapPlant,
                                String functionalLocation, LocalDate installationDate,
                                int designLifeYears, int dutyHoursPerYear) {
        Asset a = new Asset();
        a.equipmentTag = equipmentTag;
        a.assetName = assetName;
        a.equipmentClass = equipmentClass;
        a.manufacturer = manufacturer;
        a.modelNumber = modelNumber;
        a.sapPlant = sapPlant;
        a.functionalLocation = functionalLocation;
        a.installationDate = installationDate;
        a.designLifeYears = designLifeYears;
        a.dutyHoursPerYear = dutyHoursPerYear;
        a.operationalStatus = "RUNNING";
        a.isStandby = false;
        a.cumulativeOperatingHours = 0;
        return a;
    }

    // Getters
    public UUID getId() { return id; }
    public String getEquipmentTag() { return equipmentTag; }
    public String getAssetName() { return assetName; }
    public EquipmentClass getEquipmentClass() { return equipmentClass; }
    public String getManufacturer() { return manufacturer; }
    public String getModelNumber() { return modelNumber; }
    public String getSapPlant() { return sapPlant; }
    public String getFunctionalLocation() { return functionalLocation; }
    public LocalDate getInstallationDate() { return installationDate; }
    public int getDesignLifeYears() { return designLifeYears; }
    public String getOperationalStatus() { return operationalStatus; }
    public boolean isStandby() { return isStandby; }
    public String getRedundancyPartnerTag() { return redundancyPartnerTag; }
    public int getDutyHoursPerYear() { return dutyHoursPerYear; }
    public int getCumulativeOperatingHours() { return cumulativeOperatingHours; }

    public void setOperationalStatus(String status) { this.operationalStatus = status; }
    public void setStandby(boolean standby) { this.isStandby = standby; }
    public void setRedundancyPartnerTag(String tag) { this.redundancyPartnerTag = tag; }
    public void setCumulativeOperatingHours(int hours) { this.cumulativeOperatingHours = hours; }
}
