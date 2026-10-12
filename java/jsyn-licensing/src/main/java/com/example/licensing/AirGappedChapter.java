package com.example.licensing;

import com.synauson.jsyn.Capabilities;
import com.synauson.jsyn.exception.PermissionDeniedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.example.licensing.Tour.expect;
import static com.example.licensing.Tour.note;
import static com.example.licensing.Tour.ok;
import static com.example.licensing.Tour.refused;
import static com.example.licensing.Tour.step;

/**
 * Chapter 4: running without Synauson. A license file from a connected machine works
 * offline for 7 days after its last contact; an air-gapped license, arranged with
 * Synauson, until its file expires. Without a license file that works, the start is
 * refused.
 */
final class AirGappedChapter {

    /** Any key other than the one the license file was checked out with. */
    static final String OTHER_KEY = "EXAMPLE-1111-1111-1111-SOME-OTHER-KEY";

    private AirGappedChapter() {}

    static void run(Tour tour) throws IOException {
        Tour.chapter("4 · Offline and air-gapped",
                "With offline(true) the runtime never contacts Synauson: no usage reports, no "
                + "renewals, no model downloads. It uses the licenseFile you configure, else its "
                + "cached file, if that still works, and otherwise refuses to start. An ordinary "
                + "license file works for 7 days after the last usage report that got through (or, "
                + "with no record of one, after its check-out). Hosts that can never reach "
                + "Synauson need an air-gapped license, arranged with Synauson on the order form: "
                + "its file sends no reports and works until it expires. Either way the host needs "
                + "a filled model store.");

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
        expect(provided.error() == null, "an offline start with a fresh license file succeeds");
        Capabilities caps = provided.capabilities();
        Capabilities.LicenseInfo license = caps.license;
        expect(LicenseHealth.onLicenseFile(license.state) && "provided".equals(license.source),
                "an offline start runs on the provided license file");
        ok(license.state + " from the provided file, with the same sessions as online: "
                + CapabilitiesPrinter.sessions(caps.sessions));
        if (caps.usageService != null && caps.usageService.graceEnds != null) {
            ok("new calls stop at " + caps.usageService.graceEnds + ", 7 days after the file's "
                    + "check-out, unless a runtime with this key reports before then");
        }
        note("A connected runtime also falls back to a configured licenseFile when it can't "
                + "reach the usage service, which covers a first start during an outage.");

        step("The same file with a different key");
        Tour.Start otherKey = Tour.tryStart(tour.config(tour.freshDir("air-gapped/state"))
                .licenseKey(OTHER_KEY)
                .offline(true)
                .licenseFile(transfer.toString())
                .build());
        expect(otherKey.error() instanceof PermissionDeniedException,
                "a license file is used only with the key it was checked out with");
        refused(otherKey.error());
        ok("refused: the runtime ignores a file checked out for another key, logs why, and "
                + "has no other license file");

        step("Offline with no license file at all");
        Tour.Start none = Tour.tryStart(tour.config(tour.freshDir("air-gapped/state")).offline(true).build());
        expect(none.error() instanceof PermissionDeniedException,
                "an offline start without a license file is refused");
        refused(none.error());
        ok("refused: no license file, no start. A runtime that may reach Synauson would have "
                + "checked one out instead.");
    }
}
