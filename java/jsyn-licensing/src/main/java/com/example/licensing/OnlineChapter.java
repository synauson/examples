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
 * Chapter 2: a normal start. The runtime exchanges the key for a signed license file,
 * caches it, and downloads the models the license includes.
 */
final class OnlineChapter {

    /** The name the runtime caches its license file under, in its state directory. */
    static final String CACHE_FILE = "license.lic";

    private OnlineChapter() {}

    static void run(Tour tour) throws IOException, InterruptedException {
        Tour.chapter("2 · Online",
                "At startup the runtime sends the key to license.synauson.com and checks out a "
                + "license file, signed by Synauson, that lists the license's capabilities and "
                + "limits. It caches the file in the state directory and renews it about once a "
                + "day. JSyn.capabilities() reports what the runtime knows about its license.");

        Path state = tour.freshDir("state/online");

        step("Start with your key and an empty state directory");
        try (JSyn jsyn = new JSyn(tour.config(state).build())) {
            Capabilities caps = jsyn.capabilities();
            CapabilitiesPrinter.print(caps);
            if (!"licensed".equals(caps.license.state)) {
                throw new Tour.TourFailure("the runtime didn't check out a license file ("
                        + caps.license.description + "). The rest of the tour needs one check-out "
                        + "from license.synauson.com; check this machine can reach it over HTTPS.");
            }
            expect("server".equals(caps.license.source), "a fresh start checks out from the server");
            ok("licensed, from a file just checked out from the server");

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
            note("Keep the state directory on a volume so restarts reuse the file, and keep the "
                    + "file out of images and version control.");

            step("Wait for the models the license includes");
            note("They download in the background and are checked against checksums and "
                    + "signatures pinned in this jsyn build. A model no licensed capability uses "
                    + "reports not-entitled and isn't downloaded.");
            caps = Tour.awaitEntitledModels(jsyn, Duration.ofMinutes(5));
            for (Capabilities.ModelInfo m : caps.models) {
                ok(m.id + " " + m.version + ": " + m.state);
            }
        }

        step("Restart while license.synauson.com is unreachable (simulated with offline(true))");
        Tour.Start restart = Tour.tryStart(tour.config(state).offline(true).build());
        expect(restart.error() == null, "a restart without the server succeeds");
        Capabilities.LicenseInfo license = restart.capabilities().license;
        expect("licensed".equals(license.state) && "cache".equals(license.source),
                "a restart without the server runs on the cached file");
        ok("still licensed, from the cache, valid offline until " + license.fileExpiry);
        note("A running runtime whose renewals fail does the same: it keeps its file until that "
                + "expiry, about 30 days after the last renewal, and then drops to the free-tier "
                + "floor. LicenseHealth reports DEGRADED while it runs on the cache.");
    }
}
