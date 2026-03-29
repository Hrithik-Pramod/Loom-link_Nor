package com.loomlink.edge;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Loom Link Edge Logic — Sovereign Intelligence Layer for Nordic Energy Matchbox.
 *
 * <p>Challenge 01: Maintenance Optimization</p>
 * <p>Intercepts free-text SAP notifications, classifies them via Mistral 7B on the
 * HM90 Sovereign Node, validates through the Reflector Gate, and writes back
 * ISO 14224 failure codes via SAP BAPI simulation.</p>
 *
 * <p>100% Local Execution · SAP Clean Core · Hallucination-Proof Governance</p>
 */
@SpringBootApplication
@EnableScheduling
public class LoomLinkEdgeApplication {

    public static void main(String[] args) {
        System.out.println("""

                ╔══════════════════════════════════════════════════════════╗
                ║                                                          ║
                ║   LOOM LINK — Sovereign Intelligence Layer               ║
                ║   Challenge 01: Maintenance Optimization                 ║
                ║                                                          ║
                ║   Nordic Energy Matchbox 2026                            ║
                ║   100% Local · SAP Clean Core · Reflector Gate Active    ║
                ║                                                          ║
                ╚══════════════════════════════════════════════════════════╝
                """);
        SpringApplication.run(LoomLinkEdgeApplication.class, args);
    }
}
