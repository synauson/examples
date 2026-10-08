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
 *   <li>{@code FAILING}: the licensing server refused the license. Live calls carry on,
 *       but nothing new starts until the license is fixed.
 *   <li>{@code DEGRADED}: still working, but the server has been unreachable, the license
 *       file expires soon, or a limit is fully used.
 *   <li>{@code OK}: everything else.
 * </ul>
 *
 * <p>{@code capabilities()} reads state the runtime already holds and never touches the
 * network, so polling it every few seconds is fine.
 */
record LicenseHealth(Status status, List<String> reasons) {

    enum Status { OK, DEGRADED, FAILING }

    /** How early to warn that the license file in use will stop working offline. */
    static final Duration EXPIRY_WARNING = Duration.ofDays(7);

    static LicenseHealth of(Capabilities caps, Instant now) {
        List<String> degraded = new ArrayList<>();
        Capabilities.LicenseInfo license = caps.license;

        switch (license.state) {
            case "rejected" -> {
                return new LicenseHealth(Status.FAILING, List.of(license.description));
            }
            case "free-tier-floor" -> degraded.add("running at free-tier limits: " + license.description);
            default -> {
                // A runtime prefers a fresh check-out; it runs on its cached file only
                // while license.synauson.com can't be reached.
                if ("cache".equals(license.source)) {
                    degraded.add("license.synauson.com unreachable; running on the cached license file");
                }
                if (license.fileExpiry != null) {
                    Instant expiry = OffsetDateTime.parse(license.fileExpiry).toInstant();
                    if (now.plus(EXPIRY_WARNING).isAfter(expiry)) {
                        degraded.add("the license file stops working offline at " + license.fileExpiry
                                + ("provided".equals(license.source)
                                        ? "; provide a newer one"
                                        : "; renewals are failing"));
                    }
                }
            }
        }

        atLimit(degraded, "conferences", caps.conferences);
        atLimit(degraded, "AI conferences", caps.aiConferences);
        for (Capabilities.CapabilityInfo c : caps.capabilities) {
            atLimit(degraded, c.code + " streams", c.streams);
        }
        return new LicenseHealth(degraded.isEmpty() ? Status.OK : Status.DEGRADED, degraded);
    }

    private static void atLimit(List<String> degraded, String what, Capabilities.Usage usage) {
        if (usage.limit != null && usage.inUse >= usage.limit) {
            degraded.add(what + " at the license limit (" + usage.inUse + " of " + usage.limit + ")");
        }
    }
}
