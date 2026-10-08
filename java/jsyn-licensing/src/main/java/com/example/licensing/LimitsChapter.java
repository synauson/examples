package com.example.licensing;

import com.synauson.jsyn.Capabilities;
import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.exception.LimitExceededException;
import com.synauson.jsyn.participant.Conference;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static com.example.licensing.CapabilitiesChapter.plain;
import static com.example.licensing.CapabilitiesChapter.withVad;
import static com.example.licensing.Tour.expect;
import static com.example.licensing.Tour.note;
import static com.example.licensing.Tour.ok;
import static com.example.licensing.Tour.refused;
import static com.example.licensing.Tour.step;

/**
 * Chapter 5: usage limits. It runs at the free-tier floor (offline, no license file), so
 * the numbers are the same for every key: 10 conferences, 2 of them with AI.
 */
final class LimitsChapter {

    private LimitsChapter() {}

    static void run(Tour tour) throws InterruptedException {
        Tour.chapter("5 · Usage limits",
                "Limits count what runs at the same time: conferences, conferences with AI, and "
                + "streams per capability. A limit is checked only when something new starts. Past "
                + "the limit, plus the license's overdraft if it has one, the new thing fails with "
                + "LimitExceededException and running calls carry on. This chapter runs at the "
                + "free-tier floor, so the numbers are the same for every key.");

        try (JSyn jsyn = new JSyn(tour.config(tour.freshDir("state/limits")).offline(true).build())) {
            Capabilities caps = jsyn.capabilities();
            expect("free-tier-floor".equals(caps.license.state), "offline without a license file is the free-tier floor");
            if (!Tour.modelReady(caps, "sentito-1")) {
                throw new Tour.TourFailure("sentito-1 isn't in the model store; run the online "
                        + "chapter first (offline runtimes never download models)");
            }
            int aiLimit = caps.aiConferences.limit;
            int conferenceLimit = caps.conferences.limit;

            Deque<AutoCloseable> open = new ArrayDeque<>();
            try {
                step("Fill the AI-conference limit: " + aiLimit + " conferences, each with a VAD participant");
                List<Conference> aiConferences = new ArrayList<>();
                for (int i = 1; i <= aiLimit; i++) {
                    Conference c = jsyn.startConference("support-call-" + i);
                    open.push(c);
                    open.push(c.addNativeParticipant("agent", withVad()));
                    aiConferences.add(c);
                }
                caps = jsyn.capabilities();
                ok("AI conferences " + CapabilitiesPrinter.usage(caps.aiConferences));
                ok("health " + LicenseHealth.of(caps, Instant.now()).status()
                        + ": a limit is fully used, so the next AI conference will be refused");

                step("One more call wants AI");
                Conference extra = jsyn.startConference("support-call-" + (aiLimit + 1));
                open.push(extra);
                ok("the conference itself starts: conferences " + CapabilitiesPrinter.usage(jsyn.capabilities().conferences));
                LimitExceededException e = Tour.expectThrows(LimitExceededException.class, () -> extra.addNativeParticipant("agent", withVad()));
                refused(e);
                ok("its first AI stream is refused, since it would make a third AI conference");

                step("Degrade instead of failing the call: join without AI");
                open.push(extra.addNativeParticipant("agent", plain()));
                ok("joined without VAD, so the call goes ahead without AI");
                note("Other reasonable choices are to retry when capacity frees up, or to tell the "
                        + "user. Your own maxConferences cap throws LimitExceededException too; the end "
                        + "of this chapter shows how to tell the two apart.");

                step("The calls already running are untouched");
                caps = jsyn.capabilities();
                expect(caps.aiConferences.inUse == aiLimit, "the running AI conferences keep their slots");
                expect(Tour.capability(caps, CapabilitiesChapter.VAD).streams.inUse == aiLimit,
                        "each running call keeps its VAD stream");
                ok("all " + aiLimit + " AI conferences still running, each with its VAD stream: "
                        + CapabilitiesChapter.VAD + " streams "
                        + CapabilitiesPrinter.usage(Tour.capability(caps, CapabilitiesChapter.VAD).streams));

                step("One AI call ends, freeing its slot");
                aiConferences.get(0).close();
                open.remove(aiConferences.get(0));
                open.push(extra.addNativeParticipant("agent-ai", withVad()));
                ok("the waiting call gets VAD now: AI conferences "
                        + CapabilitiesPrinter.usage(jsyn.capabilities().aiConferences));

                step("Fill the conference limit (" + conferenceLimit + ") with calls without AI");
                int running = jsyn.capabilities().conferences.inUse;
                for (int i = running + 1; i <= conferenceLimit; i++) {
                    open.push(jsyn.startConference("plain-call-" + i));
                }
                ok("conferences " + CapabilitiesPrinter.usage(jsyn.capabilities().conferences));
                LimitExceededException full = Tour.expectThrows(LimitExceededException.class, () -> jsyn.startConference("one-too-many"));
                refused(full);
                ok("conference " + (conferenceLimit + 1) + " is refused");
            } finally {
                Tour.closeAll(open);
            }
            caps = jsyn.capabilities();
            expect(caps.conferences.inUse == 0 && caps.aiConferences.inUse == 0,
                    "ending the conferences releases everything they held");
            ok("all conferences ended: conferences " + CapabilitiesPrinter.usage(caps.conferences)
                    + ", AI conferences " + CapabilitiesPrinter.usage(caps.aiConferences));
            note("With an overdraft of 0.25, a limit of 10 admits up to 13 (the ceiling rounds up) "
                    + "and logs the ones above 10 at WARN as overage. For now each runtime counts its "
                    + "own usage: capabilities().limitsScope is 'this instance'.");
        }

        step("Your own cap is separate: maxConferences(1), on the same license");
        try (JSyn jsyn = new JSyn(tour.config(tour.freshDir("state/limits")).offline(true).maxConferences(1).build());
             Conference first = jsyn.startConference("only-one")) {
            LimitExceededException e = Tour.expectThrows(LimitExceededException.class, () -> jsyn.startConference("second"));
            refused(e);
            Capabilities caps = jsyn.capabilities();
            expect(caps.conferences.limit != null && caps.conferences.inUse < caps.conferences.limit,
                    "the license still has room, so the refusal came from maxConferences");
            ok("refused by your cap while the license still has room (conferences "
                    + CapabilitiesPrinter.usage(caps.conferences) + "). The message names the limit, "
                    + "and capabilities() shows only the license's.");
        }
    }
}
