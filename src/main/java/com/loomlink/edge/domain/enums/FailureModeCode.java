package com.loomlink.edge.domain.enums;

/**
 * ISO 14224 Failure Mode codes — the standardized taxonomy that SAP expects
 * but technicians routinely bypass in favor of free-text descriptions.
 *
 * These are the codes Loom Link's Semantic Engine maps free-text entries onto.
 * Subset representative of rotating equipment commonly found in North Sea assets.
 *
 * @see <a href="https://www.iso.org/standard/64076.html">ISO 14224:2016</a>
 */
public enum FailureModeCode {

    // ── Mechanical Failures ──────────────────────────────────────────
    AIR("Abnormal instrument reading"),
    BRD("Breakdown"),
    ELP("External leakage - process medium"),
    ELU("External leakage - utility medium"),
    ERO("Erosion"),
    FTS("Failure to start on demand"),
    FTF("Failure to function on demand"),
    HIO("High output"),
    INL("Internal leakage"),
    LOO("Low output"),
    NOI("Noise"),
    OHE("Overheating"),
    PLU("Plugged / choked"),
    SER("Minor in-service problems"),
    STD("Structural deficiency"),
    UST("Spurious stop"),
    UNK("Unknown"),
    VIB("Vibration"),

    // ── Electrical / Instrument Failures ─────────────────────────────
    OTH("Other"),
    PDE("Parameter deviation"),
    SLL("Spurious low level alarm"),
    SHL("Spurious high level alarm");

    private final String description;

    FailureModeCode(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }
}
