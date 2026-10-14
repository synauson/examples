package com.example.licensing;

import com.synauson.jsyn.Capabilities;
import com.synauson.jsyn.exception.PermissionDeniedException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static com.example.licensing.Tour.expect;
import static com.example.licensing.Tour.note;
import static com.example.licensing.Tour.ok;
import static com.example.licensing.Tour.refused;
import static com.example.licensing.Tour.step;

/**
 * Chapter 4: running without Synauson. Offline mode on an ordinary license needs the
 * usage receipt an earlier online run kept in the state directory, and works for 7 days
 * after that report; an air-gapped license, arranged with Synauson, needs no receipt and
 * works until its file expires. Without a license file that works, or without the
 * receipt, the start is refused.
 */
final class AirGappedChapter {

    /** Any key other than the one the license file was checked out with. */
    static final String OTHER_KEY = "EXAMPLE-1111-1111-1111-SOME-OTHER-KEY";

    /** Where a runtime keeps its usage receipt, under its state directory. */
    static final String USAGE_DIR = "usage";

    private AirGappedChapter() {}

    static void run(Tour tour) throws IOException {
        Tour.chapter("4 · Offline and air-gapped",
                "With offline(true) the runtime never contacts Synauson: no usage reports, no "
                + "renewals, no model downloads. It uses the licenseFile you configure, else its "
                + "cached file, if that still works, and otherwise refuses to start. An ordinary "
                + "license also needs the usage receipt that an earlier online run kept in the "
                + "state directory: the receipt is the last report that got through, which starts "
                + "the 7 days the license works offline, and it holds the shares of the license's "
                + "pools the runtime may use. Hosts that can never reach Synauson need an "
                + "air-gapped license, arranged with Synauson on the order form: its file needs no "
                + "receipt, sends no reports and works until it expires. Either way the host needs "
                + "a filled model store.");

        Path onlineState = tour.dir("state/online");
        Path checkedOut = onlineState.resolve(OnlineChapter.CACHE_FILE);
        Path receipts = onlineState.resolve(USAGE_DIR);
        if (!Files.isRegularFile(checkedOut) || !Files.isDirectory(receipts)) {
            throw new Tour.TourFailure("run the online chapter first: this one needs its license "
                    + "file and usage receipt");
        }

        step("Carry the license file across (here, a copy standing in for the USB stick)");
        Path transfer = tour.freshDir("air-gapped/transfer").resolve("license.lic");
        Files.copy(checkedOut, transfer);
        ok("copied " + checkedOut.getFileName() + " to " + transfer);

        step("Start offline with licenseFile(...) and an empty state directory");
        Tour.Start bare = Tour.tryStart(tour.config(tour.freshDir("air-gapped/state"))
                .offline(true)
                .licenseFile(transfer.toString())
                .build());
        if (bare.error() == null && airGapped(bare.capabilities())) {
            ok("your license is air-gapped, so its file alone starts the runtime offline, with "
                    + "AI sessions " + CapabilitiesPrinter.pool(bare.capabilities().sessions)
                    + " counted on " + bare.capabilities().limitsScope);
        } else {
            expect(bare.error() instanceof PermissionDeniedException
                            && bare.error().getMessage().startsWith("NO_LICENSE:"),
                    "offline mode on a license that isn't air-gapped refuses to start without a "
                    + "usage receipt (PermissionDeniedException, NO_LICENSE)");
            refused(bare.error());
            ok("refused: the license file is good, but this state directory holds no receipt, so "
                    + "the runtime has no share of the pools and no record of a report");
        }

        step("Start offline on a state directory that holds the online run's receipt");
        note("Here the online chapter's usage/ folder is copied into a new state directory. On "
                + "your host, start online once with the state directory on a volume, then keep "
                + "that directory for the offline runs.");
        Path state = tour.freshDir("air-gapped/state");
        copyTree(receipts, state.resolve(USAGE_DIR));
        Tour.Start provided = Tour.tryStart(tour.config(state)
                .offline(true)
                .licenseFile(transfer.toString())
                .build());
        expect(provided.error() == null, "an offline start with a license file and a kept receipt succeeds");
        Capabilities caps = provided.capabilities();
        Capabilities.LicenseInfo license = caps.license;
        expect(LicenseHealth.onLicenseFile(license.state) && "provided".equals(license.source),
                "an offline start runs on the provided license file");
        ok(license.state + " from the provided file, with the same sessions as online: "
                + CapabilitiesPrinter.pool(caps.sessions));
        if (caps.usageService != null && caps.usageService.graceEnds != null) {
            ok("new calls stop at " + caps.usageService.graceEnds + ", 7 days after the report "
                    + "the receipt records, unless a runtime with this key reports before then");
        }
        note("A connected runtime also falls back to a configured licenseFile when it can't "
                + "reach the usage service, which covers a first start during an outage.");

        step("The same file with a different key");
        Tour.Start otherKey = Tour.tryStart(tour.config(tour.freshDir("air-gapped/state"))
                .licenseKey(OTHER_KEY)
                .offline(true)
                .licenseFile(transfer.toString())
                .build());
        expect(otherKey.error() instanceof PermissionDeniedException
                        && otherKey.error().getMessage().startsWith("NO_LICENSE:"),
                "a license file is used only with the key it was checked out with");
        refused(otherKey.error());
        ok("refused: the runtime ignores a file checked out for another key, logs why, and "
                + "has no other license file");

        step("Offline with no license file at all");
        Tour.Start none = Tour.tryStart(tour.config(tour.freshDir("air-gapped/state")).offline(true).build());
        expect(none.error() instanceof PermissionDeniedException
                        && none.error().getMessage().startsWith("NO_LICENSE:"),
                "an offline start without a license file is refused");
        refused(none.error());
        ok("refused: no license file, no start. A runtime that may reach Synauson would have "
                + "checked one out instead.");
    }

    /** Whether the runtime runs on an air-gapped license, which never reports. */
    static boolean airGapped(Capabilities caps) {
        return caps.usageService != null && "air-gapped".equals(caps.usageService.contact);
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> paths = Files.walk(from)) {
            for (Path p : paths.toList()) {
                Path target = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(target);
                } else {
                    Files.copy(p, target);
                }
            }
        }
    }
}
