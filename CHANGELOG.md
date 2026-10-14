# Changelog

All notable changes to the examples, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/2.0.0/). Each release is tagged with
the jsyn version its examples use.

## [1.7.0] - 2026-10-14

The examples for jsyn 1.7.0, with jsyn-natives 1.8.0. Upgrading your own code from
jsyn 1.6? Follow jsyn's
[upgrade guide](https://github.com/synauson/jsyn/blob/v1.7.0/docs/upgrading.md#from-16-to-17).

### Added

- The [gRPC voice agent](https://github.com/synauson/examples/tree/v1.7.0/python/grpc-voice-agent):
  the voice agent's conversation in Python, against the Synauson server's gRPC API
  (server 1.8.0 or newer), with a Docker Compose file that runs the server and the agent. Its caller is a
  phone on a SIP call, or the server plays the caller's lines from files. It installs
  `synauson-api` 1.0.1 or newer from PyPI, decides on errors by their `ErrorInfo` reason,
  and notices a dead event stream by its missing heartbeats.

### Changed

- Every example uses jsyn 1.7.0 and jsyn-natives 1.8.0.
- The licensing tour's `air-gapped` chapter follows natives 1.8.0: offline mode on a
  license that isn't air-gapped starts only with the usage receipt an earlier online run
  kept in the state directory. The chapter shows the refusal without one
  (`NO_LICENSE`), then an offline start on a state directory that holds the receipt.
- The licensing tour and the WebRTC testbed read a model's `variant`, and the tour
  checks each refusal by the reason code that now starts every exception message, such
  as `LICENSE_REJECTED`.
- The WebRTC testbed accepts only the participant names jsyn accepts as ids: 1 to 128
  letters, digits, `.`, `_` or `-`.
- The licensing tour follows the license's two pools in natives 1.8.0: it shows the
  license's plan (`free`, `paid` or `developer`) and its conference pool beside the AI
  sessions, waits for its first share before starting a conference, and tells your own
  `maxConferences` cap from the license's limits by `reason()`. `LicenseHealth` reports
  a full conference pool, and an expired developer key as failing.
- The licensing tour and the READMEs give the Free plan's new pools: 4 AI sessions and
  10 conferences at once.

## [1.6.0] - 2026-10-11

The examples for jsyn 1.6.0, with jsyn-natives 1.7.1. Upgrading your own code from
jsyn 1.5? Follow jsyn's
[upgrade guide](https://github.com/synauson/jsyn/blob/v1.6.0/docs/upgrading.md): it
needs a new license key.

### Added

- The [voice agent example](https://github.com/synauson/examples/tree/v1.6.0/java/jsyn-voice-agent):
  speech to text on a recording, text to speech into a WAV file, and a voice agent that
  answers a simulated caller's turns. The agent starts a reply on the caller's eager end
  of turn and drops it if they go on, streams its replies as they are written, stops when
  the caller talks over it, and resumes its event stream after the last event it handled.
  The caller is in the JVM or, with `agent --sip`, on a SIP call; `--late-join` has them
  join a conference that is already running.
- The WebRTC testbed image is tagged with the release (`1.6.0`) as well as `latest`.

### Changed

- Every example uses jsyn 1.6.0 and jsyn-natives 1.7.1.
- The licensing tour and its README follow jsyn 1.6.0's licensing on natives 1.7.1:
  every license has every AI feature and one pool of concurrent AI sessions, shared by
  every runtime on the license through the usage service, and a runtime starts only
  with a valid license file. The capabilities report shows the pool and this runtime's
  share of it.
- The WebRTC testbed and the docs name voice activity and turn detection by their role.
- Every example's README has the same shape: what it shows, what you need, how to run
  it, the output to expect, its settings and troubleshooting.
