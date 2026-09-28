package com.example.licensing;

import com.synauson.jsyn.exception.InvalidArgumentException;
import com.synauson.jsyn.exception.PermissionDeniedException;

import java.nio.file.Path;

import static com.example.licensing.Tour.expect;
import static com.example.licensing.Tour.note;
import static com.example.licensing.Tour.ok;
import static com.example.licensing.Tour.refused;
import static com.example.licensing.Tour.step;

/**
 * Chapter 1: the only things that stop a runtime from starting are a missing or
 * malformed key, and the licensing server saying the key is no good.
 */
final class StartupChapter {

    /** Shaped like a key, but issued to no one. */
    static final String UNKNOWN_KEY = "EXAMPLE-0000-0000-0000-NOT-A-REAL-KEY";

    private StartupChapter() {}

    static void run(Tour tour) {
        Tour.chapter("1 · Startup",
                "Every jsyn runtime runs under a license key; there is no keyless mode (the "
                + "free tier is a key too). `new JSyn(config)` refuses to start only when the key "
                + "is missing or malformed, or when license.synauson.com gives a signed rejection. "
                + "An unreachable server never stops a start.");

        Path state = tour.freshDir("state/startup");

        step("No key: licenseKey(\"\")");
        note("jsyn reads $SYNAUSON_LICENSE_KEY only when licenseKey is null; an empty key counts as none.");
        Tour.Start noKey = Tour.tryStart(tour.config(state).licenseKey("").build());
        expect(noKey.error() instanceof InvalidArgumentException,
                "a runtime without a key fails with InvalidArgumentException");
        refused(noKey.error());
        ok("refused before any network call");

        step("Malformed key: whitespace inside it");
        Tour.Start malformed = Tour.tryStart(tour.config(state).licenseKey("pasted with a space").build());
        expect(malformed.error() instanceof InvalidArgumentException,
                "a malformed key fails with InvalidArgumentException");
        refused(malformed.error());
        ok("refused before any network call");

        step("A key license.synauson.com doesn't know");
        Tour.Start unknown = Tour.tryStart(tour.config(state).licenseKey(UNKNOWN_KEY).build());
        if (unknown.error() != null) {
            expect(unknown.error() instanceof PermissionDeniedException,
                    "a rejected key fails with PermissionDeniedException");
            refused(unknown.error());
            ok("refused: the server answered NOT_FOUND, and the answer was signed");
            note("The licensing server signs its responses and the runtime verifies them with a key built into "
                    + "jsyn, so a spoofed server can't reject (or approve) your key. An unsigned "
                    + "error counts as 'unreachable', never as a rejection. SUSPENDED, EXPIRED and "
                    + "BANNED keys are refused the same way.");
        } else {
            // Without an answer from the server the key can't be judged, so the runtime
            // starts at the free-tier floor and keeps retrying in the background.
            expect("free-tier-floor".equals(unknown.capabilities().license.state),
                    "an unverifiable key starts at the free-tier floor");
            ok("started anyway, at the free-tier floor: license.synauson.com didn't answer "
                    + "within 10 s. " + unknown.capabilities().license.description);
            note("This is on purpose: a licensing outage must never stop your servers from "
                    + "starting (think of autoscaling at peak load). A signed rejection that "
                    + "arrives later stops new work only.");
        }
    }
}
