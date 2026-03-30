package com.loomlink.edge.domain.enums;

/**
 * EU 2024/1787 compliance lifecycle for emission events.
 *
 * <p>The regulation mandates documented detection, quantified mapping,
 * and response timelines from detection to remediation. This enum tracks
 * where each emission event sits in that compliance lifecycle.</p>
 */
public enum ComplianceStatus {

    DETECTED("Emission detected by robot sensor, pending classification"),
    CLASSIFIED("Classified by Loom Link contextual engine"),
    DOCUMENTED("EU 2024/1787 compliance record auto-generated"),
    WORK_ORDER_CREATED("SAP maintenance work order triggered"),
    UNDER_INVESTIGATION("Field team dispatched for verification"),
    REMEDIATED("Leak repaired and confirmed sealed"),
    CLOSED("Compliance record closed, response timeline met"),
    FALSE_POSITIVE("Reclassified as non-emission after review");

    private final String description;

    ComplianceStatus(String description) {
        this.description = description;
    }

    public String getDescription() { return description; }
}
