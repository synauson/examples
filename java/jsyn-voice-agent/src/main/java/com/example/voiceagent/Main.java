package com.example.voiceagent;

import java.nio.file.Path;
import java.time.Duration;

/**
 * Speech in and out with jsyn, as three commands:
 *
 * <pre>
 *   agent [--sip] [--late-join &lt;s&gt;]   a voice agent answering a simulated caller (the default)
 *   transcribe &lt;file.wav&gt;          speech to text on a 16-bit WAV recording
 *   speak "&lt;text&gt;" [out.wav]       text to speech into a WAV file
 * </pre>
 *
 * Needs {@code SYNAUSON_LICENSE_KEY}.
 */
public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Path workDir = Path.of(System.getProperty("voiceagent.workDir", "voice-agent-data"));
        String command = args.length == 0 ? "agent" : args[0];
        int status;
        switch (command) {
            case "agent" -> status = agent(workDir, args);
            case "transcribe" -> {
                if (args.length != 2) {
                    status = usage();
                } else {
                    status = Transcribe.run(workDir, Path.of(args[1]));
                }
            }
            case "speak" -> {
                if (args.length < 2 || args.length > 3) {
                    status = usage();
                } else {
                    Path out = Path.of(args.length == 3 ? args[2] : "speech.wav");
                    status = SpeakToFile.run(workDir, args[1], out);
                }
            }
            default -> status = usage();
        }
        System.exit(status);
    }

    /**
     * {@code --sip}: the caller is on a SIP call (a simulated phone sending RTP) rather than
     * a native participant. {@code --late-join <s>}: the conference runs that long first.
     */
    private static int agent(Path workDir, String[] args) throws Exception {
        boolean sip = false;
        Duration lateJoin = Duration.ZERO;
        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("--sip")) {
                sip = true;
            } else if (args[i].equals("--late-join") && i + 1 < args.length) {
                lateJoin = Duration.ofMillis(Math.round(Double.parseDouble(args[++i]) * 1000));
            } else {
                return usage();
            }
        }
        return CallDemo.run(workDir, new CallDemo.Options(sip, lateJoin, Path.of("call.wav")));
    }

    private static int usage() {
        System.err.println("usage: agent [--sip] [--late-join <seconds>] | transcribe <file.wav>"
                + " | speak \"<text>\" [out.wav]");
        return 2;
    }
}
