# syntax=docker/dockerfile:1.6
# =============================================================================
# The gRPC voice agent in a container: Python and its dependencies, without
# installing either on the host. compose.yaml runs it next to the server.
#
# Build context: this directory.
# =============================================================================
FROM python:3.13.13-slim-trixie
COPY --from=ghcr.io/astral-sh/uv:0.11.21 /uv /bin/uv

WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy UV_PYTHON_DOWNLOADS=never
COPY pyproject.toml uv.lock .python-version ./
RUN uv sync --frozen --no-dev --no-install-project
COPY src/ src/
RUN uv sync --frozen --no-dev

# The caller's lines, for the SIP caller's phone.
COPY caller/ caller/
ENTRYPOINT ["/app/.venv/bin/grpc-voice-agent", "--lines", "/app/caller"]
