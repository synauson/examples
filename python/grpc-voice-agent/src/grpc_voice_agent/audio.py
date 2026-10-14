"""WAV files, G.711 mu-law and resampling: what the simulated phone needs to put the caller's
lines on an RTP stream and to keep what it hears."""

from __future__ import annotations

import math
import struct
import wave
from array import array
from pathlib import Path


def read_wav(path: Path) -> tuple[array[int], int]:
    """A 16-bit PCM WAV file's samples, mixed down to mono, and its sample rate."""
    with wave.open(str(path), "rb") as w:
        if w.getsampwidth() != 2:
            raise ValueError(f"{path}: need 16-bit PCM, got {8 * w.getsampwidth()}-bit")
        channels = w.getnchannels()
        rate = w.getframerate()
        samples = array("h", w.readframes(w.getnframes()))
    if channels > 1:
        samples = array(
            "h",
            (sum(samples[i : i + channels]) // channels for i in range(0, len(samples), channels)),
        )
    return samples, rate


def write_wav(path: Path, samples: array[int], rate: int) -> None:
    """Writes 16-bit mono PCM as a WAV file."""
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(rate)
        w.writeframes(samples.tobytes())


def to_8k(samples: array[int], rate: int) -> array[int]:
    """Resamples 16 or 48 kHz audio to the 8 kHz of a phone call: a low-pass filter below
    4 kHz, then every second (or sixth) sample."""
    if rate == 8_000:
        return samples
    if rate not in (16_000, 48_000):
        raise ValueError(f"need 8, 16 or 48 kHz audio, got {rate} Hz")
    step = rate // 8_000
    taps = _low_pass(cutoff=3_600 / rate, length=16 * step + 1)
    half = len(taps) // 2
    out = array("h")
    for centre in range(0, len(samples), step):
        acc = 0.0
        for k, tap in enumerate(taps):
            i = centre + k - half
            if 0 <= i < len(samples):
                acc += tap * samples[i]
        out.append(max(-32768, min(32767, round(acc))))
    return out


def _low_pass(cutoff: float, length: int) -> list[float]:
    """A windowed-sinc low-pass filter; cutoff is a fraction of the sample rate."""
    mid = (length - 1) / 2
    taps = []
    for n in range(length):
        x = n - mid
        sinc = 2 * cutoff if x == 0 else math.sin(2 * math.pi * cutoff * x) / (math.pi * x)
        window = 0.54 - 0.46 * math.cos(2 * math.pi * n / (length - 1))  # Hamming
        taps.append(sinc * window)
    total = sum(taps)
    return [t / total for t in taps]


_BIAS = 0x84
_CLIP = 32635


def ulaw_encode(sample: int) -> int:
    """One 16-bit sample as a G.711 mu-law byte."""
    sign = 0x80 if sample < 0 else 0
    magnitude = min(-sample if sample < 0 else sample, _CLIP) + _BIAS
    exponent = max(magnitude.bit_length() - 8, 0)
    mantissa = (magnitude >> (exponent + 3)) & 0x0F
    return ~(sign | (exponent << 4) | mantissa) & 0xFF


def _ulaw_decode(byte: int) -> int:
    byte = ~byte & 0xFF
    magnitude = ((((byte & 0x0F) << 3) + _BIAS) << ((byte & 0x70) >> 4)) - _BIAS
    return -magnitude if byte & 0x80 else magnitude


_ULAW_TO_PCM = [_ulaw_decode(b) for b in range(256)]
ULAW_SILENCE = ulaw_encode(0)


def ulaw_encode_all(samples: array[int]) -> bytes:
    return bytes(ulaw_encode(s) for s in samples)


def ulaw_decode_all(payload: bytes) -> array[int]:
    return array("h", (_ULAW_TO_PCM[b] for b in payload))


RTP_HEADER = struct.Struct("!BBHII")
PCMU = 0


def rtp_packet(seq: int, timestamp: int, ssrc: int, payload: bytes, marker: bool) -> bytes:
    """An RTP packet (RFC 3550) carrying PCMU (payload type 0)."""
    return (
        RTP_HEADER.pack(
            0x80,
            (0x80 if marker else 0) | PCMU,
            seq & 0xFFFF,
            timestamp & 0xFFFFFFFF,
            ssrc,
        )
        + payload
    )


def rtp_payload(packet: bytes) -> tuple[int, bytes] | None:
    """An RTP packet's payload type and payload, or None if it isn't RTP."""
    if len(packet) < RTP_HEADER.size or packet[0] >> 6 != 2:
        return None
    csrc_count = packet[0] & 0x0F
    offset = RTP_HEADER.size + 4 * csrc_count
    if packet[0] & 0x10:  # a header extension
        if len(packet) < offset + 4:
            return None
        (words,) = struct.unpack_from("!H", packet, offset + 2)
        offset += 4 + 4 * words
    end = len(packet)
    if packet[0] & 0x20:  # padding
        end -= packet[-1]
    if offset > end:
        return None
    return packet[1] & 0x7F, packet[offset:end]
