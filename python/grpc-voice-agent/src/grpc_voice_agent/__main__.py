"""A voice agent over the Synauson server's gRPC API, answering a simulated caller.

grpc-voice-agent [--server HOST:PORT] [--caller file|sip] [--late-join SECONDS]
"""

from __future__ import annotations

import argparse
import asyncio
import contextlib
import logging
import signal
import sys
from pathlib import Path

from .call import CallOptions, run_call


async def run_until_terminated(options: CallOptions) -> None:
    """Runs the call. SIGTERM (docker stop, a container platform scaling down) cancels it
    as Ctrl-C does, so the call's finally blocks end the conference on the server instead
    of the process dying with the conference still running. Unix only: elsewhere
    add_signal_handler is not available."""
    task = asyncio.current_task()
    assert task is not None
    loop = asyncio.get_running_loop()
    with contextlib.suppress(NotImplementedError):
        loop.add_signal_handler(signal.SIGTERM, task.cancel)
    await run_call(options)


def main() -> None:
    parser = argparse.ArgumentParser(prog="grpc-voice-agent", description=__doc__.splitlines()[0])
    parser.add_argument(
        "--server", default="localhost:50051", help="the server's gRPC address (localhost:50051)"
    )
    parser.add_argument(
        "--caller",
        choices=["sip", "file"],
        default="sip",
        help="sip: a simulated phone sends the caller's lines over RTP (the default); "
        "file: the server plays them from files",
    )
    parser.add_argument(
        "--late-join",
        type=float,
        default=0.0,
        metavar="SECONDS",
        help="let the conference run this long before the caller joins",
    )
    parser.add_argument(
        "--lines",
        type=Path,
        default=Path("caller"),
        help="the directory of the caller's WAV lines (sip)",
    )
    parser.add_argument(
        "--lines-uri",
        default="file:///caller",
        help="the same directory as a URI the server can read (file; default file:///caller)",
    )
    parser.add_argument(
        "--phone-ip", help="the address the server sends the call's RTP to (sip; found if unset)"
    )
    args = parser.parse_args()
    # The conversation is printed; diagnostics (waiting for models, a lost stream, the
    # engine's recoverable errors) are logged, to stderr.
    logging.basicConfig(level=logging.INFO, format="%(levelname)s %(name)s: %(message)s")
    options = CallOptions(
        target=args.server,
        caller=args.caller,
        late_join=args.late_join,
        lines_dir=args.lines,
        lines_uri=args.lines_uri,
        phone_ip=args.phone_ip,
    )
    try:
        asyncio.run(run_until_terminated(options))
    except asyncio.CancelledError:
        sys.exit(128 + signal.SIGTERM)  # terminated, after the cleanup ran


if __name__ == "__main__":
    main()
