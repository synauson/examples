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
 * Chapter 1: what stops a runtime from starting. A missing or malformed key does, and so
 * does a key the licensing server refuses. An unreachable server doesn't.
 */
final class StartupChapter {

    /** Shaped like a key, but issued to no one. */
    static final String UNKNOWN_KEY = "EXAMPLE-0000-0000-0000-NOT-A-REAL-KEY";

    private StartupChapter() {}

    static void run(Tour tour) {
        Tour.chapter("1 · Startup",
                "Every runtime needs a license key. new JSyn(config) fails only when the key is "
                + "missing or malformed, or when license.synauson.com refuses it. If the server "
                + "can't be reached, the runtime starts anyway.");

        Path state = tour.freshDir("state/startup");

        step("No key: licenseKey(\"\")");
        note("jsyn reads $SYNAUSON_LICENSE_KEY only when licenseKey is null; an empty key counts as none.");
        Tour.Start noKey = Tour.tryStart(tour.config(state).licenseKey("").build());
        expect(noKey.error() instanceof InvalidArgumentException,
                "a runtime without a key fails with InvalidArgumentException");
        refused(noKey.error());
        ok("refused without contacting the server");

        step("Malformed key: whitespace inside it");
        Tour.Start malformed = Tour.tryStart(tour.config(state).licenseKey("pasted with a space").build());
        expect(malformed.error() instanceof InvalidArgumentException,
                "a malformed key fails with InvalidArgumentException");
        refused(malformed.error());
        ok("refused without contacting the server");

        step("A key license.synauson.com doesn't know");
        Tour.Start unknown = Tour.tryStart(tour.config(state).licenseKey(UNKNOWN_KEY).build());
        if (unknown.error() != null) {
            expect(unknown.error() instanceof PermissionDeniedException,
                    "a rejected key fails with PermissionDeniedException");
            refused(unknown.error());
            ok("refused: the server answered NOT_FOUND");
            note("The runtime acts only on answers signed by the licensing server, which it "
                    + "checks with a key built into jsyn. An unsigned error counts as the server "
                    + "being unreachable. SUSPENDED, EXPIRED and BANNED keys fail the same way.");
        } else {
            // With no answer from the server the key can't be checked, so the runtime
            // starts at the free-tier floor and keeps retrying in the background.
            expect("free-tier-floor".equals(unknown.capabilities().license.state),
                    "an unverifiable key starts at the free-tier floor");
            ok("started at the free-tier floor, because license.synauson.com didn't answer "
                    + "within 10 s: " + unknown.capabilities().license.description);
            note("New instances keep starting during a licensing outage, for example while "
                    + "autoscaling. If the server refuses the key later, only new work stops.");
        }
    }
}
