package com.synauson.testbed.signaling.envelope;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Closed hierarchy of messages a browser can send.
 *
 * <p>{@link com.synauson.testbed.signaling.RoomWebSocketHandler}
 * dispatches incoming frames with a pattern-match {@code switch}; the
 * {@code sealed permits} clause makes that switch <em>exhaustive at compile
 * time</em>. Add a permit without updating the dispatch and the project fails
 * to build — exactly the failure mode we want for protocol evolution.
 *
 * <p>JSON shape: {@code {"type":"<discriminator>", ...record fields...}}.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ClientMessage.SdpOffer.class,     name = "sdp_offer"),
    @JsonSubTypes.Type(value = ClientMessage.IceCandidate.class, name = "ice_candidate"),
    @JsonSubTypes.Type(value = ClientMessage.Leave.class,        name = "leave"),
})
public sealed interface ClientMessage
        permits ClientMessage.SdpOffer, ClientMessage.IceCandidate, ClientMessage.Leave {

    /** Browser's WebRTC SDP offer; forwarded to {@code WebRtcParticipantSpec.sdpOffer}. */
    record SdpOffer(String sdp) implements ClientMessage {}

    /** Trickle-ICE candidate from the browser's RTCPeerConnection. */
    record IceCandidate(String candidate, int sdpMLineIndex) implements ClientMessage {}

    /** Voluntary disconnect; handled identically to a TCP close. */
    record Leave() implements ClientMessage {}
}
