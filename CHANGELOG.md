# Changelog

All notable changes to the examples, newest first. The format follows
[Keep a Changelog](https://keepachangelog.com/en/2.0.0/). Each release is tagged with
the jsyn version its examples use.

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
