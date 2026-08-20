#!/usr/bin/env python3
"""Cardboard post-release torture harness.

The network workload performs real Minecraft STATUS handshakes/requests. It is
not presented as a logged-in player bot swarm: authentication, movement and
inventory traffic belong to a future protocol-driver adapter. Bukkit/Paper API
stress is generated inside the server by the companion CardboardTorture plugin.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import math
import re
import shutil
import signal
import subprocess
import sys
import time
import tomllib
from dataclasses import asdict, dataclass, field
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

ERROR_RE = re.compile(
    r"(?:\bERROR\b|\bFATAL\b|Exception|Error:|OutOfMemoryError|"
    r"StackOverflowError|ConcurrentModificationException|Watchdog)",
    re.IGNORECASE,
)

HEAP_USED_RE = re.compile(
    r"^.*\bheap\b.*\bused\s+([0-9]+(?:\.[0-9]+)?)([KMG])B?\b",
    re.IGNORECASE | re.MULTILINE,
)
DISABLE_EXPLICIT_GC_RE = re.compile(
    r"\bDisableExplicitGC\s*=\s*(true|false)\b",
    re.IGNORECASE,
)
GC_RUN_SUCCESS = "Command executed successfully"
MEMORY_GATE_MODES = ("auto", "heap", "rss", "off")
HEAP_LIMIT_KEY = "max_heap_after_full_gc_growth_mb"
RSS_LIMIT_KEY = "max_process_rss_growth_mb"


class MemoryProbeError(RuntimeError):
    """A required memory measurement could not be collected safely."""


class LogProbeError(RuntimeError):
    """A requested server log could not be monitored reliably."""


@dataclass
class Counters:
    attempts: int = 0
    successful_requests: int = 0
    connect_failures: int = 0
    protocol_failures: int = 0
    active: int = 0
    peak_active: int = 0


@dataclass
class Sample:
    elapsed_seconds: float
    active_connections: int
    rss_mb: float | None = None


@dataclass(frozen=True)
class ProcessIdentity:
    pid: int
    start_ticks: int


@dataclass
class Result:
    scenario: str
    started_at: str
    duration_seconds: float
    target: str
    counters: Counters
    samples: list[Sample] = field(default_factory=list)
    rss_start_mb: float | None = None
    rss_end_mb: float | None = None
    rss_growth_mb: float | None = None
    memory_gate_mode: str = "off"
    heap_after_full_gc_start_mb: float | None = None
    heap_after_full_gc_end_mb: float | None = None
    heap_after_full_gc_growth_mb: float | None = None
    memory_error: str | None = None
    completion_reason: str = "unknown"
    task_errors: list[str] = field(default_factory=list)
    log_read_errors: list[str] = field(default_factory=list)
    error_lines: list[str] = field(default_factory=list)
    gates: dict[str, dict[str, Any]] = field(default_factory=dict)
    passed: bool = False


def load_config(path: Path, scenario: str) -> tuple[dict[str, Any], dict[str, Any]]:
    with path.open("rb") as fh:
        config = tomllib.load(fh)
    if scenario not in config:
        raise SystemExit(f"Unknown scenario: {scenario}")
    merged = dict(config.get("defaults", {}))
    merged.update(config[scenario])
    return merged, dict(config.get("limits", {}))


def parse_heap_info_mb(output: str) -> float:
    """Parse the used Java heap from JDK 25's GC.heap_info output."""
    match = HEAP_USED_RE.search(output)
    if match is None:
        raise MemoryProbeError("Could not parse used heap from jcmd GC.heap_info output")

    value = float(match.group(1))
    unit = match.group(2).upper()
    multiplier = {"K": 1.0 / 1024.0, "M": 1.0, "G": 1024.0}[unit]
    return value * multiplier


def parse_disable_explicit_gc(output: str) -> bool:
    """Return DisableExplicitGC, failing closed when VM.flags is unfamiliar."""
    match = DISABLE_EXPLICIT_GC_RE.search(output)
    if match is None:
        raise MemoryProbeError("Could not determine DisableExplicitGC from jcmd VM.flags -all")
    return match.group(1).lower() == "true"


def parse_process_start_ticks(stat_text: str) -> int:
    """Parse Linux /proc/<pid>/stat field 22 without splitting the comm field."""
    closing_paren = stat_text.rfind(")")
    if closing_paren < 0:
        raise MemoryProbeError("Malformed /proc PID stat: missing process name terminator")

    fields_after_comm = stat_text[closing_paren + 1 :].split()
    # The suffix begins at field 3 (state); field 22 is therefore index 19.
    if len(fields_after_comm) <= 19:
        raise MemoryProbeError("Malformed /proc PID stat: missing process start time")
    try:
        return int(fields_after_comm[19])
    except ValueError as exception:
        raise MemoryProbeError("Malformed /proc PID stat: invalid process start time") from exception


def read_process_identity(pid: int) -> ProcessIdentity:
    try:
        stat_text = Path(f"/proc/{pid}/stat").read_text(
            encoding="utf-8",
            errors="strict",
        )
    except (FileNotFoundError, PermissionError, ProcessLookupError, OSError) as exception:
        raise MemoryProbeError(f"Cannot read process identity for PID {pid}: {exception}") from exception
    return ProcessIdentity(pid=pid, start_ticks=parse_process_start_ticks(stat_text))


def parse_process_cmdline(raw: bytes) -> list[str]:
    """Parse the NUL-separated Linux /proc/<pid>/cmdline payload."""
    if not raw:
        raise MemoryProbeError("Process command line is empty")
    parts = raw.split(b"\0")
    while parts and not parts[-1]:
        parts.pop()
    if not parts or not parts[0]:
        raise MemoryProbeError("Process command line has no argv[0]")
    return [part.decode("utf-8", errors="surrogateescape") for part in parts]


def validate_minecraft_server_cmdline(argv: list[str]) -> None:
    """Reject a PID that is Java but does not resemble a Minecraft server."""
    if not argv or Path(argv[0]).name.lower() != "java":
        actual = Path(argv[0]).name if argv else "<missing>"
        raise MemoryProbeError(
            f"PID command is not Java (argv[0] basename is '{actual}', expected 'java')"
        )

    arguments = argv[1:]
    lowered = [argument.lower() for argument in arguments]
    server_main = any(
        "net.minecraft.server" in argument
        or "fabricserverlauncher" in argument
        or "fabric.server" in argument
        or "craftbukkit.main" in argument
        for argument in lowered
    )

    jar_targets: list[str] = []
    for index, argument in enumerate(lowered[:-1]):
        if argument == "-jar":
            jar_targets.append(lowered[index + 1])
    server_jar = any(
        target.endswith(".jar")
        and any(
            marker in Path(target).name
            for marker in ("server", "fabric", "paper", "purpur", "spigot", "bukkit")
        )
        for target in jar_targets
    )
    jar_with_nogui = bool(jar_targets) and "nogui" in lowered

    if not (server_main or server_jar or jar_with_nogui):
        raise MemoryProbeError(
            "Java PID command line does not contain recognizable Minecraft/Fabric server args"
        )


def verify_minecraft_server_process(pid: int) -> None:
    try:
        raw = Path(f"/proc/{pid}/cmdline").read_bytes()
    except (FileNotFoundError, PermissionError, ProcessLookupError, OSError) as exception:
        raise MemoryProbeError(
            f"Cannot read process command line for PID {pid}: {exception}"
        ) from exception
    validate_minecraft_server_cmdline(parse_process_cmdline(raw))


def ensure_same_process(expected: ProcessIdentity, actual: ProcessIdentity) -> None:
    if expected != actual:
        raise MemoryProbeError(
            "Minecraft PID identity changed during the run "
            f"(start={expected.pid}/{expected.start_ticks}, "
            f"end={actual.pid}/{actual.start_ticks})"
        )


def select_memory_gate_mode(limits: dict[str, Any], requested: str) -> str:
    if requested not in MEMORY_GATE_MODES:
        raise ValueError(f"Unknown memory gate mode: {requested}")
    if requested != "auto":
        return requested
    if HEAP_LIMIT_KEY in limits:
        return "heap"
    if RSS_LIMIT_KEY in limits:
        return "rss"
    raise MemoryProbeError(
        "Memory gate 'auto' found no configured heap or legacy RSS limit; "
        "use --memory-gate off only for an intentional network-only run"
    )


def memory_gate_limit(mode: str, limits: dict[str, Any]) -> float | None:
    if mode == "heap":
        key = HEAP_LIMIT_KEY
    elif mode == "rss":
        key = RSS_LIMIT_KEY
    elif mode == "off":
        return None
    else:
        raise ValueError(f"Memory gate mode must be resolved before validation: {mode}")

    if key not in limits:
        raise MemoryProbeError(f"Memory gate '{mode}' requires [{key}] in [limits]")
    try:
        limit = float(limits[key])
    except (TypeError, ValueError) as exception:
        raise MemoryProbeError(f"Memory gate limit [{key}] must be numeric") from exception
    if not math.isfinite(limit) or limit < 0:
        raise MemoryProbeError(f"Memory gate limit [{key}] must be finite and non-negative")
    return limit


def resolve_jcmd(configured: str | None) -> str:
    command = configured or "jcmd"
    resolved = shutil.which(command)
    if resolved is None:
        raise MemoryProbeError(
            f"Could not find jcmd executable '{command}' on PATH; use --jcmd"
        )
    return resolved


def run_jcmd(
    executable: str,
    pid: int,
    *command: str,
    timeout: float,
) -> str:
    argv = [executable, str(pid), *command]
    try:
        completed = subprocess.run(
            argv,
            capture_output=True,
            text=True,
            timeout=timeout,
            check=False,
            shell=False,
        )
    except subprocess.TimeoutExpired as exception:
        raise MemoryProbeError(
            f"jcmd {' '.join(command)} timed out after {timeout:g} seconds"
        ) from exception
    except OSError as exception:
        raise MemoryProbeError(f"Could not execute jcmd: {exception}") from exception

    if completed.returncode != 0:
        detail = (completed.stderr or completed.stdout).strip()
        raise MemoryProbeError(
            f"jcmd {' '.join(command)} exited {completed.returncode}: {detail}"
        )
    return completed.stdout


def collect_heap_after_full_gc_mb(
    executable: str,
    pid: int,
    timeout: float,
) -> float:
    gc_output = run_jcmd(executable, pid, "GC.run", timeout=timeout)
    if GC_RUN_SUCCESS.lower() not in gc_output.lower():
        raise MemoryProbeError(f"jcmd GC.run did not confirm success: {gc_output.strip()}")
    heap_output = run_jcmd(executable, pid, "GC.heap_info", timeout=timeout)
    return parse_heap_info_mb(heap_output)


def evaluate_memory_gates(
    mode: str,
    limit: float | None,
    *,
    heap_start_mb: float | None,
    heap_end_mb: float | None,
    rss_start_mb: float | None,
    rss_end_mb: float | None,
    measurement_error: str | None = None,
) -> tuple[dict[str, dict[str, Any]], float | None, float | None]:
    """Pure memory gate evaluation; returns gates and heap/RSS growth."""
    heap_growth = (
        heap_end_mb - heap_start_mb
        if heap_start_mb is not None and heap_end_mb is not None
        else None
    )
    rss_growth = (
        rss_end_mb - rss_start_mb
        if rss_start_mb is not None and rss_end_mb is not None
        else None
    )

    if mode == "off":
        return {}, heap_growth, rss_growth
    if measurement_error is not None:
        return {
            "memory_measurement": {
                "value": measurement_error,
                "limit": "available and comparable",
                "pass": False,
            }
        }, heap_growth, rss_growth
    if limit is None:
        raise ValueError(f"Resolved memory gate '{mode}' has no limit")

    if mode == "heap":
        if heap_growth is None:
            raise ValueError("Heap gate requires start and end heap measurements")
        return {
            "heap_after_full_gc_growth_mb": {
                "value": round(heap_growth, 3),
                "limit": limit,
                "pass": heap_growth <= limit,
            }
        }, heap_growth, rss_growth
    if mode == "rss":
        if rss_growth is None:
            raise ValueError("RSS gate requires start and end RSS measurements")
        return {
            "rss_growth_mb": {
                "value": round(rss_growth, 3),
                "limit": limit,
                "pass": rss_growth <= limit,
            }
        }, heap_growth, rss_growth
    raise ValueError(f"Unknown resolved memory gate mode: {mode}")


def encode_varint(value: int) -> bytes:
    value &= 0xFFFFFFFF
    out = bytearray()
    while True:
        byte = value & 0x7F
        value >>= 7
        if value:
            byte |= 0x80
        out.append(byte)
        if not value:
            return bytes(out)


async def read_varint(reader: asyncio.StreamReader) -> int:
    value = 0
    for position in range(5):
        raw = await reader.readexactly(1)
        byte = raw[0]
        value |= (byte & 0x7F) << (7 * position)
        if (byte & 0x80) == 0:
            return value
    raise ValueError("VarInt is too large")


async def read_status_response(reader: asyncio.StreamReader) -> dict[str, Any]:
    packet_length = await read_varint(reader)
    if packet_length <= 0 or packet_length > 2_000_000:
        raise ValueError(f"Invalid status packet length: {packet_length}")
    packet = asyncio.StreamReader()
    packet.feed_data(await reader.readexactly(packet_length))
    packet.feed_eof()
    packet_id = await read_varint(packet)
    if packet_id != 0:
        raise ValueError(f"Expected status response packet 0, got {packet_id}")
    json_length = await read_varint(packet)
    payload = await packet.readexactly(json_length)
    decoded = json.loads(payload.decode("utf-8"))
    if not isinstance(decoded, dict):
        raise ValueError("Status response is not a JSON object")
    return decoded


def status_request(host: str, port: int, protocol_version: int) -> bytes:
    host_bytes = host.encode("utf-8")
    handshake = b"".join(
        (
            encode_varint(0),
            encode_varint(protocol_version),
            encode_varint(len(host_bytes)),
            host_bytes,
            port.to_bytes(2, byteorder="big", signed=False),
            encode_varint(1),
        )
    )
    framed_handshake = encode_varint(len(handshake)) + handshake
    status = encode_varint(1) + encode_varint(0)
    return framed_handshake + status


def read_rss_mb(pid: int | None) -> float | None:
    if pid is None:
        return None
    status = Path(f"/proc/{pid}/status")
    try:
        for line in status.read_text(encoding="utf-8", errors="replace").splitlines():
            if line.startswith("VmRSS:"):
                kb = int(line.split()[1])
                return kb / 1024.0
    except (FileNotFoundError, PermissionError, ProcessLookupError, ValueError):
        return None
    return None


def prepare_log_tail(path: Path) -> int:
    """Validate a requested log and return a text-stream offset at its end."""
    try:
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            fh.seek(0, 2)
            return fh.tell()
    except OSError as exception:
        raise LogProbeError(f"Cannot read requested log '{path}': {exception}") from exception


def read_new_log_errors(
    path: Path | None,
    offset: int,
) -> tuple[int, list[str], str | None]:
    if path is None:
        return offset, [], None
    try:
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            fh.seek(0, 2)
            end_offset = fh.tell()
            # A truncated/recreated log starts a new readable stream.
            if offset > end_offset:
                offset = 0
            fh.seek(offset)
            lines = fh.readlines()
            new_offset = fh.tell()
    except OSError as exception:
        return offset, [], f"Cannot read requested log '{path}': {exception}"
    errors = [line.rstrip() for line in lines if ERROR_RE.search(line)]
    return new_offset, errors, None


def task_failure_messages(role: str, results: list[Any]) -> list[str]:
    """Describe exceptions returned by asyncio.gather(return_exceptions=True)."""
    failures: list[str] = []
    for index, result in enumerate(results):
        if isinstance(result, BaseException):
            detail = str(result) or "no detail"
            failures.append(f"{role}[{index}] {type(result).__name__}: {detail}")
    return failures


async def status_worker(
    worker_id: int,
    host: str,
    port: int,
    protocol_version: int,
    request_timeout: float,
    reconnect: bool,
    stop: asyncio.Event,
    counters: Counters,
) -> None:
    del worker_id
    payload = status_request(host, port, protocol_version)
    while not stop.is_set():
        writer: asyncio.StreamWriter | None = None
        active = False
        counters.attempts += 1
        try:
            reader, writer = await asyncio.wait_for(
                asyncio.open_connection(host, port), timeout=request_timeout
            )
            counters.active += 1
            active = True
            counters.peak_active = max(counters.peak_active, counters.active)
            writer.write(payload)
            await asyncio.wait_for(writer.drain(), timeout=request_timeout)
            await asyncio.wait_for(read_status_response(reader), timeout=request_timeout)
            counters.successful_requests += 1
        except (ConnectionError, OSError, asyncio.TimeoutError):
            counters.connect_failures += 1
        except (asyncio.IncompleteReadError, UnicodeDecodeError, json.JSONDecodeError, ValueError):
            counters.protocol_failures += 1
        finally:
            if active:
                counters.active = max(0, counters.active - 1)
            if writer is not None:
                writer.close()
                try:
                    await writer.wait_closed()
                except (ConnectionError, OSError):
                    pass

        if not reconnect or stop.is_set():
            return
        await asyncio.sleep(0.2)


async def run(args: argparse.Namespace) -> int:
    config, limits = load_config(args.config, args.scenario)
    host = args.host if args.host is not None else str(config["host"])
    port = args.port if args.port is not None else int(config["port"])
    duration = args.duration if args.duration is not None else float(config["duration_seconds"])
    count = args.connections if args.connections is not None else int(config["connections"])
    rate = args.rate if args.rate is not None else float(config["connect_rate_per_second"])
    timeout = float(config.get("connect_timeout_seconds", 3))
    protocol_version = int(config.get("protocol_version", 0))
    reconnect = bool(config.get("reconnect", True))
    sample_interval = float(config.get("sample_interval_seconds", 5))

    if count < 1 or rate <= 0 or duration <= 0:
        raise SystemExit("connections, rate, and duration must be positive")

    counters = Counters()
    samples: list[Sample] = []
    errors: list[str] = []
    log_read_errors: list[str] = []
    try:
        log_offset = prepare_log_tail(args.log) if args.log is not None else 0
    except LogProbeError as exception:
        raise SystemExit(f"Log preflight failed: {exception}") from exception

    def scan_log() -> None:
        nonlocal log_offset
        log_offset, new_errors, read_error = read_new_log_errors(args.log, log_offset)
        errors.extend(new_errors)
        if read_error is not None and read_error not in log_read_errors:
            log_read_errors.append(read_error)

    requested_memory_mode = getattr(args, "memory_gate", "auto")
    jcmd_timeout = float(getattr(args, "jcmd_timeout", 120.0))
    process_identity: ProcessIdentity | None = None
    jcmd_executable: str | None = None
    heap_start: float | None = None
    heap_end: float | None = None
    memory_error: str | None = None

    try:
        memory_mode = select_memory_gate_mode(limits, requested_memory_mode)
        memory_limit = memory_gate_limit(memory_mode, limits)
        if memory_mode != "off":
            if args.pid is None:
                raise MemoryProbeError(
                    f"Memory gate '{memory_mode}' requires --pid; "
                    "use --memory-gate off only for an intentional network-only run"
                )
            process_identity = read_process_identity(args.pid)
            verify_minecraft_server_process(args.pid)

        if memory_mode == "heap":
            if not math.isfinite(jcmd_timeout) or jcmd_timeout <= 0:
                raise MemoryProbeError("--jcmd-timeout must be positive")
            jcmd_executable = resolve_jcmd(getattr(args, "jcmd", None))
            flags_output = run_jcmd(
                jcmd_executable,
                args.pid,
                "VM.flags",
                "-all",
                timeout=jcmd_timeout,
            )
            if parse_disable_explicit_gc(flags_output):
                raise MemoryProbeError(
                    "DisableExplicitGC=true prevents a reliable after-Full-GC heap gate"
                )
            heap_start = collect_heap_after_full_gc_mb(
                jcmd_executable,
                args.pid,
                jcmd_timeout,
            )
            assert process_identity is not None
            ensure_same_process(process_identity, read_process_identity(args.pid))
    except MemoryProbeError as exception:
        raise SystemExit(f"Memory preflight failed: {exception}") from exception

    rss_start = read_rss_mb(args.pid)
    if memory_mode == "rss" and rss_start is None:
        raise SystemExit(f"Memory preflight failed: cannot read RSS for PID {args.pid}")

    if requested_memory_mode == "auto" and memory_mode == "rss":
        print(
            f"WARNING: [{RSS_LIMIT_KEY}] selects the legacy raw RSS gate; "
            f"migrate to [{HEAP_LIMIT_KEY}]"
        )
    if requested_memory_mode == "off" and (
        HEAP_LIMIT_KEY in limits or RSS_LIMIT_KEY in limits
    ):
        print("WARNING: memory gate explicitly disabled by --memory-gate off")

    ready_heap = (
        f" heapAfterFullGcMb={heap_start:.3f}" if heap_start is not None else ""
    )
    print(
        f"HARNESS_READY memoryGate={memory_mode}{ready_heap}",
        flush=True,
    )

    started_wall = datetime.now(timezone.utc).isoformat()
    started = time.monotonic()
    stop = asyncio.Event()
    completed_duration = False
    interrupted_by: str | None = None

    loop = asyncio.get_running_loop()
    registered_signals: list[signal.Signals] = []

    def request_stop(received_signal: signal.Signals) -> None:
        nonlocal interrupted_by
        interrupted_by = received_signal.name
        stop.set()

    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, request_stop, sig)
            registered_signals.append(sig)
        except (NotImplementedError, RuntimeError):
            pass

    workers: list[asyncio.Task[None]] = []
    spawn_delay = 1.0 / rate

    async def launcher() -> None:
        for idx in range(count):
            if stop.is_set():
                return
            workers.append(
                asyncio.create_task(
                    status_worker(
                        idx,
                        host,
                        port,
                        protocol_version,
                        timeout,
                        reconnect,
                        stop,
                        counters,
                    )
                )
            )
            await asyncio.sleep(spawn_delay)

    launcher_task = asyncio.create_task(launcher())

    try:
        # Give the launcher and its first scheduled worker one event-loop turn
        # each before enforcing the duration. This guarantees that every
        # positive-duration run can start useful work even when setup or a
        # heavily loaded event loop consumes most of a very short test duration.
        await asyncio.sleep(0)
        await asyncio.sleep(0)

        while not stop.is_set():
            elapsed = time.monotonic() - started
            if elapsed >= duration:
                completed_duration = True
                stop.set()
                break

            rss = read_rss_mb(args.pid)
            scan_log()
            samples.append(
                Sample(
                    elapsed_seconds=round(elapsed, 3),
                    active_connections=counters.active,
                    rss_mb=round(rss, 3) if rss is not None else None,
                )
            )
            await asyncio.sleep(sample_interval)
    finally:
        stop.set()
        launcher_results = await asyncio.gather(launcher_task, return_exceptions=True)
        worker_results = await asyncio.gather(*workers, return_exceptions=True)
        for sig in registered_signals:
            try:
                loop.remove_signal_handler(sig)
            except (NotImplementedError, RuntimeError):
                pass

    elapsed = time.monotonic() - started
    task_errors = task_failure_messages("launcher", launcher_results)
    task_errors.extend(task_failure_messages("worker", worker_results))
    scan_log()

    try:
        if memory_mode != "off":
            assert process_identity is not None
            ensure_same_process(process_identity, read_process_identity(args.pid))
        if memory_mode == "heap":
            assert jcmd_executable is not None
            heap_end = collect_heap_after_full_gc_mb(
                jcmd_executable,
                args.pid,
                jcmd_timeout,
            )
            ensure_same_process(process_identity, read_process_identity(args.pid))
    except MemoryProbeError as exception:
        memory_error = str(exception)

    # GC.run and GC.heap_info can themselves expose server/JVM errors. This scan
    # deliberately occurs after the final memory probe.
    scan_log()

    rss_end = read_rss_mb(args.pid)
    if memory_mode == "rss" and rss_end is None and memory_error is None:
        memory_error = f"Cannot read final RSS for PID {args.pid}"

    memory_gates, heap_growth, rss_growth = evaluate_memory_gates(
        memory_mode,
        memory_limit,
        heap_start_mb=heap_start,
        heap_end_mb=heap_end,
        rss_start_mb=rss_start,
        rss_end_mb=rss_end,
        measurement_error=memory_error,
    )

    connect_failure_ratio = counters.connect_failures / max(counters.attempts, 1)
    protocol_failure_ratio = counters.protocol_failures / max(counters.attempts, 1)

    gates: dict[str, dict[str, Any]] = {}

    def gate(name: str, value: Any, limit: Any, ok: bool) -> None:
        gates[name] = {"value": value, "limit": limit, "pass": ok}

    max_connect_failure_ratio = float(limits.get("max_connect_failure_ratio", 1.0))
    max_protocol_failure_ratio = float(limits.get("max_protocol_failure_ratio", 1.0))
    max_errors = int(limits.get("max_error_lines", 0))

    if interrupted_by is not None:
        completion_reason = f"signal:{interrupted_by}"
    elif completed_duration:
        completion_reason = "duration_completed"
    else:
        completion_reason = "stopped_early"
    completed_cleanly = completed_duration and interrupted_by is None

    gate(
        "completion",
        completion_reason,
        "duration_completed",
        completed_cleanly,
    )
    gate("useful_work", counters.attempts, ">= 1 attempt", counters.attempts > 0)
    gate("task_failures", len(task_errors), 0, not task_errors)
    if args.log is not None:
        gate("log_read_failures", len(log_read_errors), 0, not log_read_errors)

    gate(
        "connect_failure_ratio",
        round(connect_failure_ratio, 6),
        max_connect_failure_ratio,
        connect_failure_ratio <= max_connect_failure_ratio,
    )
    gate(
        "protocol_failure_ratio",
        round(protocol_failure_ratio, 6),
        max_protocol_failure_ratio,
        protocol_failure_ratio <= max_protocol_failure_ratio,
    )
    gate("error_lines", len(errors), max_errors, len(errors) <= max_errors)
    gates.update(memory_gates)

    passed = all(item["pass"] for item in gates.values())
    result = Result(
        scenario=args.scenario,
        started_at=started_wall,
        duration_seconds=round(elapsed, 3),
        target=f"{host}:{port}",
        counters=counters,
        samples=samples,
        rss_start_mb=round(rss_start, 3) if rss_start is not None else None,
        rss_end_mb=round(rss_end, 3) if rss_end is not None else None,
        rss_growth_mb=round(rss_growth, 3) if rss_growth is not None else None,
        memory_gate_mode=memory_mode,
        heap_after_full_gc_start_mb=(
            round(heap_start, 3) if heap_start is not None else None
        ),
        heap_after_full_gc_end_mb=(
            round(heap_end, 3) if heap_end is not None else None
        ),
        heap_after_full_gc_growth_mb=(
            round(heap_growth, 3) if heap_growth is not None else None
        ),
        memory_error=memory_error,
        completion_reason=completion_reason,
        task_errors=task_errors[-200:],
        log_read_errors=log_read_errors[-200:],
        error_lines=errors[-200:],
        gates=gates,
        passed=passed,
    )

    args.report_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    report_path = args.report_dir / f"{args.scenario}-{stamp}.json"
    report_path.write_text(json.dumps(asdict(result), indent=2), encoding="utf-8")

    print(f"Scenario : {args.scenario}")
    print(f"Target   : {host}:{port}")
    print(f"Duration : {elapsed:.1f}s")
    print(f"Attempts : {counters.attempts}")
    print(f"Status OK: {counters.successful_requests} (peak active {counters.peak_active})")
    print(f"Connect failures : {counters.connect_failures}")
    print(f"Protocol failures: {counters.protocol_failures}")
    print(f"Memory gate: {memory_mode}")
    if heap_growth is not None:
        print(
            f"Heap/Full GC: {heap_start:.1f} -> {heap_end:.1f} MB "
            f"({heap_growth:+.1f} MB)"
        )
    if rss_growth is not None:
        diagnostic = " [diagnostic only]" if memory_mode != "rss" else ""
        print(
            f"RSS      : {rss_start:.1f} -> {rss_end:.1f} MB "
            f"({rss_growth:+.1f} MB){diagnostic}"
        )
    if memory_error is not None:
        print(f"Memory error: {memory_error}")
    print(f"Completion: {completion_reason}")
    if task_errors:
        print(f"Task failures: {len(task_errors)} ({task_errors[0]})")
    if log_read_errors:
        print(f"Log read failures: {len(log_read_errors)} ({log_read_errors[0]})")
    print(f"Errors   : {len(errors)}")
    print(f"Report   : {report_path}")
    print("RESULT   : " + ("PASS" if passed else "FAIL"))
    return 0 if passed else 1


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Cardboard post-release torture harness")
    parser.add_argument("scenario", choices=("smoke", "burst", "soak"))
    parser.add_argument("--config", type=Path, default=root / "scenarios.toml")
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    parser.add_argument("--duration", type=float)
    parser.add_argument("--connections", type=int)
    parser.add_argument("--rate", type=float)
    parser.add_argument(
        "--pid",
        type=int,
        help="Minecraft Java host PID; used for memory gates and Linux /proc RSS sampling",
    )
    parser.add_argument(
        "--memory-gate",
        choices=MEMORY_GATE_MODES,
        default="auto",
        help="auto selects heap for new configs and RSS for legacy configs",
    )
    parser.add_argument(
        "--jcmd",
        help="jcmd executable; defaults to resolving jcmd on PATH",
    )
    parser.add_argument(
        "--jcmd-timeout",
        type=float,
        default=120.0,
        help="timeout in seconds for each jcmd operation",
    )
    parser.add_argument("--log", type=Path, help="latest.log path; only new lines are scanned")
    parser.add_argument("--report-dir", type=Path, default=root / "reports")
    return parser.parse_args(argv)


def main() -> int:
    if sys.version_info < (3, 11):
        print("Python 3.11+ is required (tomllib).", file=sys.stderr)
        return 2
    try:
        return asyncio.run(run(parse_args()))
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
