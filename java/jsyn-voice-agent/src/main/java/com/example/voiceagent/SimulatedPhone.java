package com.example.voiceagent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Test scaffolding, not part of an agent: a phone at the far end of a SIP call. jsyn's SIP
 * participant is only the call's media; your SIP stack does the signaling and the SDP,
 * and gives jsyn the phone's address, port and codec. This phone sends the caller's
 * rehearsed lines as PCMU over RTP, 20 ms a packet in real time, from a UDP socket, and
 * keeps the RTP it gets back, which is the agent's voice.
 */
final class SimulatedPhone implements Caller {
    private static final int FRAME = 160; // 20 ms at 8 kHz
    private static final int PCMU = 0;

    private final DatagramSocket socket;
    private final Map<String, byte[]> lines = new HashMap<>();
    private final BlockingQueue<byte[]> outgoing = new LinkedBlockingQueue<>();
    private final ByteArrayOutputStream heard = new ByteArrayOutputStream();
    private volatile InetSocketAddress callee;
    private volatile boolean running = true;
    private final Thread sender;
    private final Thread receiver;

    /** A phone on the loopback address, with the lines (16 kHz PCM) it will say. */
    SimulatedPhone(Map<String, byte[]> lines16k) throws SocketException {
        lines16k.forEach((text, pcm) -> lines.put(text, G711.encode16k(pcm)));
        socket = new DatagramSocket(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        sender = new Thread(this::send, "simulated-phone-send");
        receiver = new Thread(this::receive, "simulated-phone-receive");
        sender.setDaemon(true);
        receiver.setDaemon(true);
    }

    /** The phone's address, as its SDP would give it. */
    String ip() {
        return socket.getLocalAddress().getHostAddress();
    }

    int rtpPort() {
        return socket.getLocalPort();
    }

    /** The call is answered: send to the port in the answer's SDP. */
    void connect(int calleeRtpPort) {
        callee = new InetSocketAddress(socket.getLocalAddress(), calleeRtpPort);
        sender.start();
        receiver.start();
    }

    @Override
    public void say(String text) throws InterruptedException {
        byte[] audio = lines.get(text);
        if (audio == null) {
            throw new IllegalArgumentException("not rehearsed: " + text);
        }
        System.out.println("  (caller starts: \"" + text + "\")");
        for (int at = 0; at < audio.length; at += FRAME) {
            byte[] frame = new byte[FRAME];
            java.util.Arrays.fill(frame, G711.SILENCE);
            System.arraycopy(audio, at, frame, 0, Math.min(FRAME, audio.length - at));
            outgoing.add(frame);
        }
        while (!outgoing.isEmpty()) {
            Thread.sleep(20);
        }
    }

    @Override
    public byte[] heard() {
        synchronized (heard) {
            return heard.toByteArray();
        }
    }

    @Override
    public int sampleRate() {
        return 8_000;
    }

    /** Sends a packet every 20 ms: the next frame of a line, or silence. */
    private void send() {
        byte[] silence = new byte[FRAME];
        java.util.Arrays.fill(silence, G711.SILENCE);
        ThreadLocalRandom random = ThreadLocalRandom.current();
        int ssrc = random.nextInt();
        int seq = random.nextInt(1 << 16);
        int timestamp = random.nextInt();
        boolean talking = false;
        long start = System.nanoTime();
        for (long n = 0; running; n++) {
            byte[] frame = outgoing.poll();
            ByteBuffer packet = ByteBuffer.allocate(12 + FRAME);
            packet.put((byte) 0x80)
                    .put((byte) ((frame != null && !talking ? 0x80 : 0) | PCMU)) // marker: a talkspurt starts
                    .putShort((short) (seq + n))
                    .putInt((int) (timestamp + n * FRAME))
                    .putInt(ssrc)
                    .put(frame == null ? silence : frame);
            talking = frame != null;
            try {
                socket.send(new DatagramPacket(packet.array(), packet.capacity(), callee));
            } catch (IOException e) {
                if (running) System.err.println("phone: send failed: " + e.getMessage());
            }
            long wait = start + (n + 1) * 20_000_000L - System.nanoTime();
            if (wait > 0) {
                try {
                    Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                } catch (InterruptedException e) {
                    return;
                }
            }
        }
    }

    /** Keeps the PCMU it receives, decoded, in arrival order. */
    private void receive() {
        byte[] buffer = new byte[2048];
        while (running) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (IOException e) {
                return; // closed
            }
            byte[] b = packet.getData();
            int len = packet.getLength();
            if (len < 12 || (b[0] & 0xC0) != 0x80 || (b[1] & 0x7F) != PCMU) {
                continue; // not RTP, or not PCMU
            }
            int offset = 12 + 4 * (b[0] & 0x0F);
            if ((b[0] & 0x10) != 0 && len >= offset + 4) { // a header extension
                offset += 4 + 4 * (((b[offset + 2] & 0xFF) << 8) | (b[offset + 3] & 0xFF));
            }
            int end = (b[0] & 0x20) != 0 ? len - (b[len - 1] & 0xFF) : len; // padding
            synchronized (heard) {
                for (int i = offset; i < end; i++) {
                    short s = G711.decode(b[i]);
                    heard.write(s & 0xFF);
                    heard.write((s >> 8) & 0xFF);
                }
            }
        }
    }

    @Override
    public void close() throws InterruptedException {
        running = false;
        socket.close();
        sender.join(2_000);
        receiver.join(2_000);
    }
}
