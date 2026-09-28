package com.example.licensing;

import com.synauson.jsyn.Capabilities;

import java.time.Instant;

/** Prints a {@link Capabilities} report the way an operator would want to read it. */
final class CapabilitiesPrinter {

    private CapabilitiesPrinter() {}

    static void print(Capabilities caps) {
        Capabilities.LicenseInfo l = caps.license;
        row("license", l.state + " · " + l.description);
        if (l.licenseId != null) row("license id", l.licenseId);
        if (l.source != null) row("file from", l.source);
        if (l.fileExpiry != null) row("file valid until", l.fileExpiry + " (offline validity; renewed about daily)");
        if (l.state.equals("licensed")) row("license expires", l.licenseExpiry != null ? l.licenseExpiry : "never");

        String overdraft = caps.overdraft == 0 ? "no overdraft"
                : String.format("overdraft %.0f%%", caps.overdraft * 100);
        row("limits", "counted on " + caps.limitsScope + ", " + overdraft);
        row("  conferences", usage(caps.conferences));
        row("  AI conferences", usage(caps.aiConferences));
        for (Capabilities.CapabilityInfo c : caps.capabilities) {
            row("  " + c.code, (c.entitled ? "entitled" : "NOT entitled") + " · streams " + usage(c.streams));
        }
        for (Capabilities.ModelInfo m : caps.models) {
            row("model " + m.id, m.version + " (release " + m.release + ") · " + m.state
                    + (m.detail != null ? " · " + m.detail : ""));
        }
        LicenseHealth health = LicenseHealth.of(caps, Instant.now());
        row("health", health.status() + (health.reasons().isEmpty() ? "" : " · " + String.join("; ", health.reasons())));
    }

    static String usage(Capabilities.Usage u) {
        return u.inUse + " in use of " + (u.limit == null ? "unlimited" : u.limit);
    }

    private static void row(String key, String value) {
        System.out.printf("    %-26s %s%n", key, value);
    }
}
