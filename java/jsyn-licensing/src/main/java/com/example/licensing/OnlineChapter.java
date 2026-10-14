package com.example.licensing;

import com.synauson.jsyn.Capabilities;
import com.synauson.jsyn.JSyn;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;

import static com.example.licensing.Tour.expect;
import static com.example.licensing.Tour.note;
import static com.example.licensing.Tour.ok;
import static com.example.licensing.Tour.step;

/**
 * Chapter 2: a normal start. The runtime renews its license through the usage service,
 * caches the license file, leases its share of the license's pools, and downloads the
 * models.
 */
final class OnlineChapter {

    /** The name the runtime caches its license file under, in its state directory. */
    static final String CACHE_FILE = "license.lic";

    private OnlineChapter() {}

    static void run(Tour tour) throws IOException, InterruptedException {
        Tour.chapter("2 · Online",
                "At startup the runtime reports to usage.synauson.com, which checks out a license "
                + "file from license.synauson.com for it: a file signed by Synauson that names its "
                + "plan and how many AI sessions and conferences the license may run at once. The "
                + "runtime caches the file in the state directory and renews it about once a day. "
                + "Every 30 seconds it reports its AI session and conference counts and gets back its "
                + "share of each of the license's two pools, which every runtime on the license "
                + "shares. JSyn.capabilities() reports what the runtime "
                + "knows about its license.");

        Path state = tour.freshDir("state/online");

        step("Start with your key and an empty state directory");
        try (JSyn jsyn = new JSyn(tour.config(state).build())) {
            Capabilities caps = jsyn.capabilities();
            if (!LicenseHealth.onLicenseFile(caps.license.state)) {
                CapabilitiesPrinter.print(caps);
                throw new Tour.TourFailure("the runtime didn't check out a license file ("
                        + caps.license.description + "). The rest of the tour needs one check-out; "
                        + "check this machine can reach usage.synauson.com and "
                        + "license.synauson.com over HTTPS.");
            }
            caps = Tour.awaitLease(jsyn, Duration.ofSeconds(60));
            CapabilitiesPrinter.print(caps);
            expect("server".equals(caps.license.source), "a fresh start checks out from the server");
            expect(caps.license.plan != null && !"unlicensed".equals(caps.license.plan),
                    "a license in force names its plan");
            for (Capabilities.CapabilityInfo c : caps.capabilities) {
                expect(c.entitled, "every license includes " + c.code);
            }
            ok(caps.license.state + ", from a file just checked out through the usage service, on "
                    + "the " + caps.license.plan + " plan with every AI feature, AI sessions "
                    + CapabilitiesPrinter.pool(caps.sessions) + " and conferences "
                    + CapabilitiesPrinter.pool(caps.conferences));
            ok("the usage service leased this runtime " + CapabilitiesPrinter.share(caps.usageService)
                    + "; counted on " + caps.limitsScope);

            step("The cached license file");
            Path cached = state.resolve(CACHE_FILE);
            expect(Files.isRegularFile(cached), "the runtime caches the license file as " + cached);
            String firstLine = Files.readAllLines(cached).get(0);
            ok(cached + " · " + Files.size(cached) + " bytes · " + firstLine);
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                String mode = PosixFilePermissions.toString(Files.getPosixFilePermissions(cached));
                expect(mode.equals("rw-------"), "the cached file is readable by its owner only");
                ok("mode " + mode + ": the file contains your key, so keep it as private as the key");
            }
            note("Keep the state directory on a volume so restarts reuse the file and the last "
                    + "contact with the usage service, and keep the file out of images and version "
                    + "control.");

            step("Wait for the models");
            note("Every license has every AI feature, so the runtime downloads every model, in "
                    + "the background, and checks each against checksums and signatures pinned in "
                    + "this jsyn build.");
            caps = Tour.awaitEntitledModels(jsyn, Duration.ofMinutes(10));
            for (Capabilities.ModelInfo m : caps.models) {
                ok(m.id + " " + m.variant + ": " + m.state);
            }
        }

        step("Restart while Synauson is unreachable (simulated with offline(true))");
        Tour.Start restart = Tour.tryStart(tour.config(state).offline(true).build());
        expect(restart.error() == null, "a restart without the usage service succeeds on the cached file");
        Capabilities.LicenseInfo license = restart.capabilities().license;
        expect(LicenseHealth.onLicenseFile(license.state) && "cache".equals(license.source),
                "a restart without the usage service runs on the cached file");
        Capabilities.UsageService usage = restart.capabilities().usageService;
        ok("still " + license.state + ", from the cache"
                + (usage != null && usage.graceEnds != null ? ", until " + usage.graceEnds : ""));
        note("A license file works offline for 7 days after the last usage report that got "
                + "through, also across restarts that keep the state directory: it holds the "
                + "receipt of that report (usage/receipt.json), and with it the share this "
                + "runtime was leased. Offline mode refuses to start without one (chapter 4). "
                + "A running runtime "
                + "whose reports fail does the same: after an hour its state is offline, after 7 "
                + "days disconnected, and then no new conference or participant starts until a "
                + "report gets through. Calls in progress always continue. LicenseHealth reports "
                + "DEGRADED while the runtime can't report and FAILING once it is disconnected.");
    }
}
