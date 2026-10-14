"""A stand-in for your LLM: picks a canned reply by keyword and streams it word by word, the
way a streaming completion arrives."""

from __future__ import annotations

import asyncio
import re
from collections.abc import AsyncIterator

GOODBYE = "You're welcome. Goodbye!"

# How long the stand-in takes for each token. A real LLM takes about as long.
TOKEN_DELAY_S = 0.03


def reply_to(caller_said: str) -> str:
    said = caller_said.lower()
    if "bye" in said or "thank" in said:
        return GOODBYE
    if "four" in said or "people" in said or "seven" in said:
        return "Got it, a table for four. Is there anything else I can do for you?"
    if "table" in said or "book" in said:
        return (
            "Sure, I can book that for you. For how many people, and at what time "
            "would you like to come in tomorrow evening?"
        )
    return "Sorry, could you say that again?"


async def stream_reply(caller_said: str) -> AsyncIterator[str]:
    """The reply as tokens: words with their trailing space."""
    for token in re.findall(r"\S+\s*", reply_to(caller_said)):
        await asyncio.sleep(TOKEN_DELAY_S)
        yield token


def is_goodbye(reply: str) -> bool:
    return reply.strip() == GOODBYE
