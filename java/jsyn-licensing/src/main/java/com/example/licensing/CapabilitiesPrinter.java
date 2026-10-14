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
        if (l.plan != null) row("plan", l.plan);
        if (l.source != null) row("file from", l.source);
        if (l.fileExpiry != null) row("file expires", l.fileExpiry + " (renewed about daily; without contact new calls stop sooner, below)");
        if (LicenseHealth.onLicenseFile(l.state)) row("license expires", l.licenseExpiry != null ? l.licenseExpiry : "never");
        if (l.problem != null) row("problem", l.problem);

        String overdraft = caps.overdraft == 0 ? "no burst"
                : String.format("a %.0f%% burst", caps.overdraft * 100);
        row("AI sessions", pool(caps.sessions) + " · counted on " + caps.limitsScope + ", " + overdraft);
        if (caps.conferences != null) row("conferences", pool(caps.conferences));
        Capabilities.UsageService u = caps.usageService;
        if (u != null) {
            row("usage service", u.contact + (u.share != null || u.shareCeiling != null ? " · " + share(u) : "")
                    + (u.instances != null ? " · " + u.instances + " instance(s), " + u.poolInUse + " session(s)"
                            + (u.conferencePoolInUse != null ? " and " + u.conferencePoolInUse + " conference(s)" : "") + " in use" : ""));
            if (u.graceEnds != null) row("new calls stop at", u.graceEnds + " without a report getting through");
            if (u.problem != null) row("usage problem", u.problem);
        }
        for (Capabilities.CapabilityInfo c : caps.capabilities) {
            row("  " + c.code, c.entitled
                    ? "entitled" + (c.includedBy != null ? " (included by " + c.includedBy + ")" : "")
                    : "NOT entitled");
        }
        for (Capabilities.ModelInfo m : caps.models) {
            row("model " + m.id, m.variant + " (release " + m.release + ") · " + m.state
                    + (m.detail != null ? " · " + m.detail : ""));
        }
        LicenseHealth health = LicenseHealth.of(caps, Instant.now());
        row("health", health.status() + (health.reasons().isEmpty() ? "" : " · " + String.join("; ", health.reasons())));
    }

    /** This runtime's shares of the license's two pools, as the usage service leased them. */
    static String share(Capabilities.UsageService u) {
        if (u == null) return "no share (no usage service)";
        return share(u.share, u.shareCeiling, "session(s)") + " and "
                + share(u.conferenceShare, u.conferenceShareCeiling, "conference(s)");
    }

    private static String share(Integer share, Integer ceiling, String unit) {
        if (share == null && ceiling == null) return "no " + unit.replace("(s)", "") + " limit (unlimited)";
        return "a share of " + share + " " + unit
                + (ceiling != null && !ceiling.equals(share) ? ", up to " + ceiling + " with the burst" : "");
    }

    /** One of the license's pools on this runtime: AI sessions or conferences. */
    static String pool(Capabilities.Pool s) {
        return s.inUse + " in use of " + (s.limit == null ? "unlimited" : s.limit)
                + (s.ceiling != null && !s.ceiling.equals(s.limit) ? " (up to " + s.ceiling + " with the burst)" : "")
                + ", " + s.level;
    }

    private static void row(String key, String value) {
        System.out.printf("    %-26s %s%n", key, value);
    }
}
