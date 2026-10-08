package com.example.licensing;

import com.synauson.jsyn.Capabilities;
import com.synauson.jsyn.JSyn;
import com.synauson.jsyn.JSynConfig;
import com.synauson.jsyn.exception.JSynException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Deque;
import java.util.concurrent.Callable;
import java.util.stream.Stream;

/**
 * What every chapter shares: where the tour keeps its files, the license key, and
 * the helpers that print the tour and check its claims.
 *
 * <p>Every runtime in the tour uses one model store, so the models downloaded in the
 * online chapter serve the offline ones. Each chapter gets its own state directory
 * (where the runtime caches its license file), so one chapter's cache never leaks
 * into another's result.
 */
final class Tour {

    final Path workDir;
    final Path modelStore;
    final String licenseKey;

    Tour(Path workDir, String licenseKey) {
        this.workDir = workDir;
        this.modelStore = workDir.resolve("models");
        this.licenseKey = licenseKey;
    }

    /** A config for this tour's key and model store, caching its license in {@code stateDir}. */
    JSynConfig.Builder config(Path stateDir) {
        return JSynConfig.builder()
                .licenseKey(licenseKey)
                .modelStore(modelStore.toString())
                .stateDir(stateDir.toString());
    }

    Path dir(String name) {
        return workDir.resolve(name);
    }

    /** An empty directory, removing whatever an earlier run left there. */
    Path freshDir(String name) {
        Path dir = dir(name);
        try {
            if (Files.exists(dir)) {
                try (Stream<Path> paths = Files.walk(dir)) {
                    for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.delete(p);
                    }
                }
            }
            return Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** How a runtime start went: either it started, and reported this, or it threw. */
    record Start(Capabilities capabilities, JSynException error) {}

    /** Start a runtime, take its capabilities report and stop it again. */
    static Start tryStart(JSynConfig config) {
        try (JSyn jsyn = new JSyn(config)) {
            return new Start(jsyn.capabilities(), null);
        } catch (JSynException e) {
            return new Start(null, e);
        }
    }

    /**
     * Poll {@link JSyn#capabilities()} until every model the license includes is ready.
     *
     * <p>A runtime downloads the models its license includes in the background at
     * startup. Until a model is on disk, adding a participant that needs it throws
     * {@link com.synauson.jsyn.exception.FailedPreconditionException}.
     */
    static Capabilities awaitEntitledModels(JSyn jsyn, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Capabilities caps = jsyn.capabilities();
            boolean pending = false;
            for (Capabilities.ModelInfo m : caps.models) {
                switch (m.state) {
                    case "ready", "not-entitled" -> { }
                    case "invalid" -> throw new TourFailure(
                            m.id + " is in the model store but failed verification: " + m.detail);
                    default -> pending = true;
                }
            }
            if (!pending) return caps;
            if (System.nanoTime() > deadline) {
                throw new TourFailure("the licensed models didn't download within " + timeout);
            }
            Thread.sleep(500);
        }
    }

    /**
     * Run {@code call} expecting it to throw {@code type}, and return what it threw. If it
     * succeeds instead, close whatever it started and fail the tour.
     */
    static <E extends Exception> E expectThrows(Class<E> type, Callable<?> call) {
        Object started;
        try {
            started = call.call();
        } catch (Exception e) {
            if (type.isInstance(e)) return type.cast(e);
            throw new TourFailure("expected " + type.getSimpleName() + ", got " + e);
        }
        if (started instanceof AutoCloseable c) {
            try {
                c.close();
            } catch (Exception ignored) {
                // The tour is failing anyway.
            }
        }
        throw new TourFailure("expected " + type.getSimpleName() + ", but the call succeeded");
    }

    /** Close what a chapter opened, newest first: participants before their conference. */
    static void closeAll(Deque<AutoCloseable> open) {
        while (!open.isEmpty()) {
            try {
                open.pop().close();
            } catch (Exception e) {
                // Closing is best effort; the runtime frees everything when it stops.
            }
        }
    }

    static boolean modelReady(Capabilities caps, String modelId) {
        return caps.models.stream().anyMatch(m -> m.id.equals(modelId) && "ready".equals(m.state));
    }

    static Capabilities.CapabilityInfo capability(Capabilities caps, String code) {
        return caps.capabilities.stream()
                .filter(c -> c.code.equals(code))
                .findFirst()
                .orElseThrow(() -> new TourFailure("the capabilities report has no " + code));
    }

    // ---- output --------------------------------------------------------------

    static void chapter(String title, String intro) {
        System.out.println();
        System.out.println("━━━ " + title + " " + "━".repeat(Math.max(3, 72 - title.length())));
        System.out.println(wrap(intro, ""));
        System.out.println();
    }

    static void step(String text) {
        System.out.println("▸ " + text);
    }

    static void ok(String text) {
        System.out.println(wrap(text, "  ✓ "));
    }

    static void note(String text) {
        System.out.println(wrap(text, "    "));
    }

    /** Print what a refused call said, as an application would log it. */
    static void refused(Throwable e) {
        System.out.println(wrap(e.getClass().getSimpleName() + ": " + e.getMessage(), "    │ "));
    }

    /** A claim the tour makes. When it doesn't hold, the tour fails. */
    static void expect(boolean holds, String claim) {
        if (!holds) throw new TourFailure("expected: " + claim);
    }

    private static String wrap(String text, String prefix) {
        StringBuilder out = new StringBuilder();
        StringBuilder line = new StringBuilder(prefix);
        String indent = " ".repeat(prefix.length());
        for (String word : text.split(" ")) {
            if (line.length() + word.length() > 88 && line.length() > prefix.length()) {
                out.append(line.toString().stripTrailing()).append('\n');
                line = new StringBuilder(indent);
            }
            line.append(word).append(' ');
        }
        return out.append(line.toString().stripTrailing()).toString();
    }

    /** A claim of the tour that didn't hold. */
    static final class TourFailure extends RuntimeException {
        TourFailure(String message) {
            super(message);
        }
    }
}
