package com.example.licensing;

import com.synauson.jsyn.Capabilities;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.example.licensing.Tour.expect;
import static com.example.licensing.Tour.note;
import static com.example.licensing.Tour.ok;
import static com.example.licensing.Tour.step;

/**
 * Chapter 4: running without license.synauson.com. A license file from a connected
 * machine works offline until it expires; without one, the runtime runs at the
 * free-tier floor.
 */
final class AirGappedChapter {

    /** Any key other than the one the license file was checked out with. */
    static final String OTHER_KEY = "EXAMPLE-1111-1111-1111-SOME-OTHER-KEY";

    private AirGappedChapter() {}

    static void run(Tour tour) throws IOException {
        Tour.chapter("4 · Air-gapped and offline",
                "With offline(true) the runtime never contacts license.synauson.com, so it neither "
                + "renews its license nor downloads models. It uses the licenseFile you configure, "
                + "else its cached file, else the free-tier floor. An air-gapped host needs a license "
                + "file for its key and a filled model store.");

        Path checkedOut = tour.dir("state/online").resolve(OnlineChapter.CACHE_FILE);
        if (!Files.isRegularFile(checkedOut)) {
            throw new Tour.TourFailure("run the online chapter first: this one needs its license file");
        }

        step("Carry the license file across (here, a copy standing in for the USB stick)");
        Path transfer = tour.freshDir("air-gapped/transfer").resolve("license.lic");
        Files.copy(checkedOut, transfer);
        ok("copied " + checkedOut.getFileName() + " to " + transfer);

        step("Start offline with licenseFile(...) and an empty state directory");
        Tour.Start provided = Tour.tryStart(tour.config(tour.freshDir("air-gapped/state"))
                .offline(true)
                .licenseFile(transfer.toString())
                .build());
        expect(provided.error() == null, "an offline start with a license file succeeds");
        Capabilities.LicenseInfo license = provided.capabilities().license;
        expect("licensed".equals(license.state) && "provided".equals(license.source),
                "an offline start runs on the provided license file");
        ok("licensed from the provided file, with the same capabilities and limits as online");
        ok("valid until " + license.fileExpiry + ": check out and carry over a new file before then");
        note("A connected runtime also falls back to a configured licenseFile when it can't "
                + "reach the server, which covers a first start during an outage.");

        step("The same file with a different key");
        Tour.Start otherKey = Tour.tryStart(tour.config(tour.freshDir("air-gapped/state"))
                .licenseKey(OTHER_KEY)
                .offline(true)
                .licenseFile(transfer.toString())
                .build());
        expect(otherKey.error() == null, "a mismatched file doesn't stop the start");
        expect("free-tier-floor".equals(otherKey.capabilities().license.state),
                "a license file is used only with the key it was checked out with");
        ok("the runtime ignores the file, logs why, and runs at the free-tier floor: a license "
                + "file only works with the key it was checked out with");

        step("Offline with no license file at all");
        Tour.Start floor = Tour.tryStart(tour.config(tour.freshDir("air-gapped/state")).offline(true).build());
        expect(floor.error() == null, "an offline start without a license file succeeds");
        Capabilities caps = floor.capabilities();
        expect("free-tier-floor".equals(caps.license.state), "no license file means the free-tier floor");
        CapabilitiesPrinter.print(caps);
        ok("the free-tier floor: " + caps.conferences.limit + " conferences, "
                + caps.aiConferences.limit + " of them with AI, and both capabilities. Every runtime "
                + "with a well-formed key gets at least this.");
    }
}
