package com.example.licensing;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A tour of jsyn licensing that checks what it shows.
 *
 * <p>Each chapter starts real runtimes, does what an application would do, and checks
 * each statement it prints. If the runtime behaves differently, the tour exits non-zero.
 * The chapters are {@link StartupChapter}, {@link OnlineChapter},
 * {@link CapabilitiesChapter}, {@link AirGappedChapter} and {@link LimitsChapter}.
 *
 * <p>Run every chapter with {@code ./gradlew run}, or some with
 * {@code ./gradlew run --args="limits"}. The online chapter checks out the license file
 * and downloads the models that later chapters reuse. The tour needs
 * {@code SYNAUSON_LICENSE_KEY} (a free-tier key works), GStreamer 1.26, and HTTPS access
 * to {@code license.synauson.com} and Cloudflare R2 ({@code *.r2.cloudflarestorage.com}),
 * where the models download from.
 */
public final class LicensingTour {

    interface Chapter {
        void run(Tour tour) throws Exception;
    }

    private static final Map<String, Chapter> CHAPTERS = new LinkedHashMap<>();
    static {
        CHAPTERS.put("startup", StartupChapter::run);
        CHAPTERS.put("online", OnlineChapter::run);
        CHAPTERS.put("capabilities", CapabilitiesChapter::run);
        CHAPTERS.put("air-gapped", AirGappedChapter::run);
        CHAPTERS.put("limits", LimitsChapter::run);
    }

    public static void main(String[] args) {
        String key = System.getenv("SYNAUSON_LICENSE_KEY");
        if (key == null || key.isBlank()) {
            System.err.println("Set SYNAUSON_LICENSE_KEY to your Synauson license key (a free-tier key works).");
            System.exit(2);
        }
        List<String> names = args.length == 0 ? List.copyOf(CHAPTERS.keySet()) : List.of(args);
        for (String name : names) {
            if (!CHAPTERS.containsKey(name)) {
                System.err.println("Unknown chapter '" + name + "'. Chapters: " + String.join(", ", CHAPTERS.keySet()));
                System.exit(2);
            }
        }

        Tour tour = new Tour(Path.of(System.getProperty("tour.workDir", "build/licensing-tour")).toAbsolutePath(), key);
        System.out.println("jsyn licensing tour · files under " + tour.workDir);
        for (String name : names) {
            try {
                CHAPTERS.get(name).run(tour);
            } catch (Tour.TourFailure e) {
                System.err.println("\n✗ " + name + ": " + e.getMessage());
                System.exit(1);
            } catch (Exception e) {
                System.err.println("\n✗ " + name + ": unexpected " + e);
                e.printStackTrace();
                System.exit(1);
            }
        }
        System.out.println("\n✓ Tour complete. Every statement above was checked against a live runtime.");
    }
}
