package com.example.licensing;

import com.synauson.jsyn.Capabilities;
import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.exception.LimitExceededException;
import com.synauson.jsyn.participant.Conference;

import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;

import static com.example.licensing.CapabilitiesChapter.plain;
import static com.example.licensing.CapabilitiesChapter.withVad;
import static com.example.licensing.Tour.expect;
import static com.example.licensing.Tour.note;
import static com.example.licensing.Tour.ok;
import static com.example.licensing.Tour.refused;
import static com.example.licensing.Tour.step;

/**
 * Chapter 5: the session limit. It fills this runtime's share of the license's session
 * pool, so with a Free key (4 sessions, no burst) the numbers are small and the same every
 * time.
 */
final class LimitsChapter {

    /** The most AI participants the chapter adds to reach the limit. */
    static final int MOST_TO_FILL = 16;

    private LimitsChapter() {}

    static void run(Tour tour) throws InterruptedException {
        Tour.chapter("5 · The session limit",
                "A license limits two things across every runtime on the license: how many AI "
                + "sessions and how many conferences run at the same time. The session limit is "
                + "checked only when a participant with AI is added, the conference limit when a "
                + "conference starts, each against this runtime's share of its pool plus the burst "
                + "on its purchased part (none on the Free base). Past it, that participant or "
                + "conference fails with LimitExceededException and running calls carry on. This "
                + "chapter fills the sessions; the conferences work the same way.");

        if (!Files.isRegularFile(tour.dir("state/online").resolve(OnlineChapter.CACHE_FILE))) {
            throw new Tour.TourFailure("run the online chapter first: this one reuses its license and models");
        }

        try (JSyn jsyn = new JSyn(tour.config(tour.dir("state/online")).build())) {
            Tour.awaitEntitledModels(jsyn, Duration.ofMinutes(10));
            Capabilities caps = Tour.awaitLease(jsyn, Duration.ofSeconds(60));
            int cap = Tour.sessionCap(caps);
            ok("AI sessions " + CapabilitiesPrinter.pool(caps.sessions) + "; this runtime holds "
                    + CapabilitiesPrinter.share(caps.usageService));
            if (cap < 1) {
                ok("other runtimes on this license hold the whole pool, so this one may start "
                        + "no AI session now. Run the tour where nothing else uses the key to see "
                        + "the refusal at the limit.");
            } else if (cap > MOST_TO_FILL) {
                ok("this runtime may run " + (cap == Integer.MAX_VALUE ? "unlimited" : cap)
                        + " AI sessions, too many to fill here. With a Free key the rest of this "
                        + "chapter fills its 4 sessions and shows the refusal.");
            } else {
                fill(jsyn, cap);
            }
            note("A license with purchased sessions admits a burst past its limit (25% of the "
                    + "purchased sessions unless it sets another) and logs each one at WARN as "
                    + "overage: 10 purchased sessions allow 14, up to 17, since the ceiling rounds "
                    + "up. From 80% of the limit each new session logs a warning. The usage service "
                    + "splits the pool among the runtimes on the license by what they use; a "
                    + "runtime alone gets all of it.");
        }

        step("Your own cap is separate: maxConferences(1), on the same license");
        try (JSyn jsyn = new JSyn(tour.config(tour.dir("state/online")).maxConferences(1).build())) {
            // A runtime starts no conference before its first lease (LICENSE_SHARE_PENDING).
            Tour.awaitLease(jsyn, Duration.ofSeconds(60));
            try (Conference only = jsyn.startConference("only-one")) {
                LimitExceededException e = Tour.expectThrows(LimitExceededException.class, () -> jsyn.startConference("second"));
                refused(e);
                expect("CONFERENCE_LIMIT_REACHED".equals(e.reason()), "your own cap refused it");
                Capabilities caps = jsyn.capabilities();
                expect(caps.sessions.inUse == 0, "the license still has every session free");
                ok("refused by your cap (reason() " + e.reason() + ") while the license has room: "
                        + "AI sessions " + CapabilitiesPrinter.pool(caps.sessions) + ", conferences "
                        + CapabilitiesPrinter.pool(caps.conferences) + ". The license's own conference "
                        + "limit refuses with CONFERENCE_POOL_LIMIT_REACHED, and its session limit "
                        + "with SESSION_LIMIT_REACHED.");
            }
        }
    }

    /** Use all {@code cap} sessions, show the refusal past them, and free one. */
    private static void fill(JSyn jsyn, int cap) throws InterruptedException {
        Deque<AutoCloseable> open = new ArrayDeque<>();
        try {
            step("Use every session: " + cap + " participants with VAD, two to a conference");
            Conference first = null;
            Conference current = null;
            for (int i = 1; i <= cap; i++) {
                if (i % 2 == 1) {
                    current = jsyn.startConference("support-call-" + (i + 1) / 2);
                    open.push(current);
                    if (first == null) first = current;
                }
                open.push(current.addNativeParticipant("agent-" + i, withVad()));
            }
            Capabilities caps = jsyn.capabilities();
            expect(caps.sessions.inUse == cap, "every session is in use");
            ok("AI sessions " + CapabilitiesPrinter.pool(caps.sessions));
            if ("full".equals(caps.sessions.level)) {
                ok("health " + LicenseHealth.of(caps, Instant.now()).status()
                        + ": every session is in use, so the next participant with AI will be refused");
            }

            step("Another call wants AI");
            Conference extra = jsyn.startConference("support-call-extra");
            open.push(extra);
            ok("the conference itself starts: it takes a conference, not an AI session");
            LimitExceededException e = Tour.expectThrows(LimitExceededException.class,
                    () -> extra.addNativeParticipant("agent", withVad()));
            refused(e);
            expect("SESSION_LIMIT_REACHED".equals(e.reason()), "the license's session limit refused it");
            ok("its participant with VAD is refused");

            step("Degrade instead of failing the call: join without AI");
            open.push(extra.addNativeParticipant("agent", plain()));
            ok("joined without VAD, so the call goes ahead without AI");
            note("Other reasonable choices are to retry when a session frees up, or to tell the "
                    + "user. Your own maxConferences cap throws LimitExceededException too; the end "
                    + "of this chapter shows how to tell them apart by reason().");

            step("The calls already running are untouched");
            caps = jsyn.capabilities();
            expect(caps.sessions.inUse == cap, "the running participants keep their sessions");
            ok("all " + cap + " participants with VAD still running: AI sessions "
                    + CapabilitiesPrinter.pool(caps.sessions));

            step("One AI participant leaves, freeing its session");
            first.removeParticipant("agent-1");
            open.push(extra.addNativeParticipant("agent-ai", withVad()));
            ok("the waiting call gets VAD now: AI sessions "
                    + CapabilitiesPrinter.pool(jsyn.capabilities().sessions));
        } finally {
            Tour.closeAll(open);
        }
        Capabilities caps = jsyn.capabilities();
        expect(caps.sessions.inUse == 0, "ending the conferences releases every session");
        expect(caps.sessions.peak == cap, "the report keeps the peak");
        ok("all conferences ended: AI sessions " + CapabilitiesPrinter.pool(caps.sessions)
                + ", peak " + caps.sessions.peak + " at " + caps.sessions.peakAt);
    }
}
