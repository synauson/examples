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
 * does a key the licensing server refuses.
 */
final class StartupChapter {

    /** Shaped like a key, but issued to no one. */
    static final String UNKNOWN_KEY = "EXAMPLE-0000-0000-0000-NOT-A-REAL-KEY";

    private StartupChapter() {}

    static void run(Tour tour) {
        Tour.chapter("1 · Startup",
                "Every runtime needs a license key and a license file that works. new JSyn(config) "
                + "fails when the key is missing or malformed, when license.synauson.com refuses "
                + "it, or when the runtime can't get a license file and has none of its own that "
                + "still works.");

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
        expect(unknown.error() instanceof PermissionDeniedException
                        && unknown.error().getMessage().contains("refused"),
                "the licensing server refuses a key it doesn't know (PermissionDeniedException)");
        refused(unknown.error());
        ok("refused: the licensing server doesn't know this key");
        note("The runtime acts only on answers signed by the licensing server, which it checks "
                + "with a key built into jsyn. SUSPENDED and BANNED keys fail the same way. If the "
                + "server can't be reached, a runtime starts only on a license file of its own that "
                + "still works (chapter 2 shows the cached one, chapter 4 one you provide). An "
                + "expired license still starts, on the Free plan: every AI feature with 2 AI "
                + "sessions. A license file this engine can't accept fails with "
                + "FailedPreconditionException starting ILLEGAL_LICENSE.");
    }
}
