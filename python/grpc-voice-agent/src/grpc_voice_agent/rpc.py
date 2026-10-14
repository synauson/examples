"""What every call to the server shares: a deadline, and reading why a call failed."""

from __future__ import annotations

from collections.abc import Callable
from typing import TypeVar, cast

import grpc
from google.rpc import error_details_pb2
from grpc_status import rpc_status

# The deadline of each unary call, in seconds. They all answer at once (Speak returns
# once the speaker has the text, not after synthesis), so a call that takes longer has
# lost the server. wait_for_ready=True rides out a short reconnect within it.
TIMEOUT_S = 10.0

V = TypeVar("V", bound=int)

# The ErrorInfo domain of every error the server itself sends.
DOMAIN = "synauson.com"


def error_reason(err: grpc.aio.AioRpcError) -> str | None:
    """The failure's ErrorInfo reason ("UTTERANCE_ENDED", say), or None when it has none.

    Every error the server sends carries a google.rpc.ErrorInfo in its status details,
    and errors.proto's ErrorReason lists the reasons. Branch on the reason, never on
    the status code or the message: one code covers several reasons. Errors the gRPC
    library raises itself (a dropped connection, a deadline) carry none.
    """
    try:
        # An AioRpcError's status is final, so the sync helper reads it (rpc_status.aio's
        # is for a live call). The stubs type it for grpc.Call only; the error has the
        # three methods it reads (code, details, trailing_metadata).
        status = rpc_status.from_call(cast(grpc.Call, err))
    except ValueError:
        return None  # the trailer's status disagrees with the call's; not the server's
    for detail in status.details if status else []:
        info = error_details_pb2.ErrorInfo()
        if detail.Unpack(info) and info.domain == DOMAIN:
            return info.reason
    return None


def enum_name(name: Callable[[V], str], value: V) -> str:
    """An enum value's name, from the enum's Name (agent_pb2.UtteranceFailure.Name, say).
    A value this client doesn't know, which a newer server may send, comes out as its
    number instead of raising."""
    try:
        return name(value)
    except ValueError:
        return str(value)
