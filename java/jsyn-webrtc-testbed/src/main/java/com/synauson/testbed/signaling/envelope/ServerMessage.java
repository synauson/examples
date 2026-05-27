package com.synauson.testbed.signaling.envelope;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.List;

/**
 * Closed hierarchy of messages the server pushes to a browser.
 *
 * <p>One WebSocket per browser carries all six kinds; the TypeScript client
 * discriminates on {@code type}. Variants cover signaling
 * ({@link SdpAnswer}, {@link IceCandidate}), presence ({@link Roster}),
 * inference events ({@link VadEvent}, {@link TurnEvent}), and error
 * surfacing ({@link Error}).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ServerMessage.SdpAnswer.class,    name = "sdp_answer"),
    @JsonSubTypes.Type(value = ServerMessage.IceCandidate.class, name = "ice_candidate"),
    @JsonSubTypes.Type(value = ServerMessage.Roster.class,       name = "roster"),
    @JsonSubTypes.Type(value = ServerMessage.VadEvent.class,     name = "vad"),
    @JsonSubTypes.Type(value = ServerMessage.TurnEvent.class,    name = "turn"),
    @JsonSubTypes.Type(value = ServerMessage.Error.class,        name = "error"),
})
public sealed interface ServerMessage permits
        ServerMessage.SdpAnswer, ServerMessage.IceCandidate, ServerMessage.Roster,
        ServerMessage.VadEvent,  ServerMessage.TurnEvent,    ServerMessage.Error {

    record SdpAnswer(String sdp) implements ServerMessage {}

    record IceCandidate(String candidate, int sdpMLineIndex, boolean endOfCandidates)
            implements ServerMessage {}

    record Roster(List<RosterEntry> participants) implements ServerMessage {}

    record RosterEntry(String participantId, String joinedAt) {}

    /**
     * VAD inference event. For {@code SPEECH_START}, {@code confidence} is
     * non-null and {@code durationMs} is null; for {@code SPEECH_END} it's
     * the other way around. Matches the JSyn record hierarchy in
     * {@link com.synauson.jsyn.event.VadEvent}.
     */
    record VadEvent(
        String participantId, VadKind kind, String t,
        Float confidence, Long durationMs
    ) implements ServerMessage {}

    enum VadKind {
        SPEECH_START("speech_start"), SPEECH_END("speech_end");
        private final String wire;
        VadKind(String wire) { this.wire = wire; }
        @JsonValue public String wire() { return wire; }
    }

    record TurnEvent(
        String participantId, String t, float probability, boolean turnComplete
    ) implements ServerMessage {}

    /**
     * Stable error codes:
     * BAD_REQUEST, INVALID_SDP, CONFERENCE_FULL, REPLACED, INTERNAL.
     */
    record Error(String code, String message) implements ServerMessage {}
}
