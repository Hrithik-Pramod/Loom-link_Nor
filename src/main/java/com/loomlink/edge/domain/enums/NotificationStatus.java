package com.loomlink.edge.domain.enums;

/**
 * Tracks the lifecycle of a maintenance notification through the Loom Link pipeline.
 *
 * <pre>
 *   RECEIVED → ANALYZING → CLASSIFIED → VERIFIED → WRITTEN_BACK
 *                              ↓             ↓
 *                          UNCLASSIFIABLE  REJECTED (Reflector Gate)
 * </pre>
 */
public enum NotificationStatus {

    /** Raw notification intercepted from SAP OData stream. */
    RECEIVED,

    /** Currently being processed by the Semantic Engine (Mistral 7B on HM90). */
    ANALYZING,

    /** Semantic Engine has produced an ISO 14224 classification. */
    CLASSIFIED,

    /** Reflector Gate has verified confidence ≥ threshold — cleared for SAP write-back. */
    VERIFIED,

    /** Structured failure code written back to SAP via BAPI. */
    WRITTEN_BACK,

    /** Semantic Engine could not extract a meaningful classification. */
    UNCLASSIFIABLE,

    /** Reflector Gate rejected — confidence below threshold. Flagged for human review. */
    REJECTED
}
