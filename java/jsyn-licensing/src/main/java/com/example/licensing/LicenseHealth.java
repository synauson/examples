package com.example.licensing;

import com.synauson.jsyn.Capabilities;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The licensing health of a jsyn runtime, from {@link com.synauson.jsyn.JSyn#capabilities()}.
 * Copy it into your application and serve it from a health endpoint or as metrics.
 *
 * <ul>
 *   <li>{@code FAILING}: nothing new starts. The licensing server refused the license,
 *       a developer key expired, or no usage report has gone through for 7 days. Live
 *       calls carry on.
 *   <li>{@code DEGRADED}: still working, but the license needs attention: it expires
 *       soon, has expired (the Free plan), or a renewal was refused; usage reports
 *       aren't getting through; the license file expires soon; or every AI session or
 *       every conference is in use.
 *   <li>{@code OK}: everything else.
 * </ul>
 *
 * <p>{@code capabilities()} reads state the runtime already holds and never touches the
 * network, so polling it every few seconds is fine.
 */
record LicenseHealth(Status status, List<String> reasons) {

    enum Status { OK, DEGRADED, FAILING }

    /** How early to warn that the license file in use will stop working. */
    static final Duration EXPIRY_WARNING = Duration.ofDays(7);

    static LicenseHealth of(Capabilities caps, Instant now) {
        List<String> degraded = new ArrayList<>();
        Capabilities.LicenseInfo license = caps.license;

        switch (license.state) {
            case "rejected", "disconnected" -> {
                return new LicenseHealth(Status.FAILING,
                        List.of(license.problem != null ? license.problem : license.description));
            }
            case "expired" -> {
                if ("developer".equals(license.plan)) {
                    // A developer key starts nothing new once it expires (LICENSE_EXPIRED).
                    return new LicenseHealth(Status.FAILING, List.of("the developer key has expired: "
                            + license.description));
                }
                degraded.add("the license has expired; running on the Free plan: " + license.description);
            }
            case "expiring", "offline", "invalid-kept-last-valid" ->
                    degraded.add(license.problem != null ? license.problem : license.description);
            default -> { }
        }
        Capabilities.UsageService usage = caps.usageService;
        if (usage != null && "unreported".equals(usage.contact) && !"offline".equals(license.state)) {
            // Reports have failed for less than the hour after which the state turns offline.
            degraded.add("usage reports aren't reaching usage.synauson.com"
                    + (usage.graceEnds != null ? "; new calls stop at " + usage.graceEnds : "")
                    + (usage.problem != null ? " (" + usage.problem + ")" : ""));
        }
        if (onLicenseFile(license.state) && license.fileExpiry != null) {
            Instant expiry = OffsetDateTime.parse(license.fileExpiry).toInstant();
            if (now.plus(EXPIRY_WARNING).isAfter(expiry)) {
                degraded.add("the license file stops working at " + license.fileExpiry
                        + ("provided".equals(license.source)
                                ? "; provide a newer one"
                                : "; it hasn't been renewed"));
            }
        }

        if ("full".equals(caps.sessions.level)) {
            degraded.add("every AI session is in use (" + CapabilitiesPrinter.pool(caps.sessions) + ")");
        }
        if (caps.conferences != null && "full".equals(caps.conferences.level)) {
            degraded.add("every conference is in use (" + CapabilitiesPrinter.pool(caps.conferences) + ")");
        }
        return new LicenseHealth(degraded.isEmpty() ? Status.OK : Status.DEGRADED, degraded);
    }

    /**
     * Whether the runtime runs on a license file at the license's own sessions:
     * {@code valid}, {@code expiring}, {@code offline} or {@code invalid-kept-last-valid}.
     */
    static boolean onLicenseFile(String state) {
        return switch (state) {
            case "valid", "expiring", "offline", "invalid-kept-last-valid" -> true;
            default -> false;
        };
    }
}
