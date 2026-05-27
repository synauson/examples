package com.synauson.testbed.signaling.envelope;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class EnvelopeJsonTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test void clientSdpOfferRoundTrips() throws Exception {
        var original = new ClientMessage.SdpOffer("v=0\r\no=- ...");
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"type\":\"sdp_offer\"");
        assertThat(mapper.readValue(json, ClientMessage.class)).isEqualTo(original);
    }

    @Test void clientIceCandidateRoundTrips() throws Exception {
        var original = new ClientMessage.IceCandidate("candidate:foo", 0);
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"type\":\"ice_candidate\"");
        assertThat(mapper.readValue(json, ClientMessage.class)).isEqualTo(original);
    }

    @Test void clientLeaveRoundTrips() throws Exception {
        var original = new ClientMessage.Leave();
        String json = mapper.writeValueAsString(original);
        assertThat(json).isEqualTo("{\"type\":\"leave\"}");
        assertThat(mapper.readValue(json, ClientMessage.class)).isInstanceOf(ClientMessage.Leave.class);
    }

    @Test void serverSdpAnswerRoundTrips() throws Exception {
        var original = new ServerMessage.SdpAnswer("v=0\r\n...");
        assertThat(mapper.readValue(mapper.writeValueAsString(original), ServerMessage.class))
            .isEqualTo(original);
    }

    @Test void serverIceCandidateRoundTrips() throws Exception {
        var original = new ServerMessage.IceCandidate("candidate:bar", 0, false);
        assertThat(mapper.readValue(mapper.writeValueAsString(original), ServerMessage.class))
            .isEqualTo(original);
    }

    @Test void serverRosterRoundTrips() throws Exception {
        var original = new ServerMessage.Roster(List.of(
            new ServerMessage.RosterEntry("alice", "2026-05-26T19:42:00.123Z"),
            new ServerMessage.RosterEntry("bob",   "2026-05-26T19:43:30.456Z")));
        assertThat(mapper.readValue(mapper.writeValueAsString(original), ServerMessage.class))
            .isEqualTo(original);
    }

    @Test void serverVadSpeechStartRoundTrips() throws Exception {
        var original = new ServerMessage.VadEvent(
            "alice", ServerMessage.VadKind.SPEECH_START, "12:42:14.802", 0.87f, null);
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"kind\":\"speech_start\"");
        assertThat(mapper.readValue(json, ServerMessage.class)).isEqualTo(original);
    }

    @Test void serverVadSpeechEndRoundTrips() throws Exception {
        var original = new ServerMessage.VadEvent(
            "alice", ServerMessage.VadKind.SPEECH_END, "12:42:18.502", null, 3700L);
        String json = mapper.writeValueAsString(original);
        assertThat(json).contains("\"kind\":\"speech_end\"");
        assertThat(json).contains("\"durationMs\":3700");
        assertThat(mapper.readValue(json, ServerMessage.class)).isEqualTo(original);
    }

    @Test void serverTurnEventRoundTrips() throws Exception {
        var original = new ServerMessage.TurnEvent("alice", "12:42:18.327", 0.91f, true);
        assertThat(mapper.readValue(mapper.writeValueAsString(original), ServerMessage.class))
            .isEqualTo(original);
    }

    @Test void serverErrorRoundTrips() throws Exception {
        var original = new ServerMessage.Error("CONFERENCE_FULL", "two is the limit");
        assertThat(mapper.readValue(mapper.writeValueAsString(original), ServerMessage.class))
            .isEqualTo(original);
    }
}
