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
import re
import signal
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


def read_new_log_errors(path: Path | None, offset: int) -> tuple[int, list[str]]:
    if path is None or not path.exists():
        return offset, []
    try:
        with path.open("r", encoding="utf-8", errors="replace") as fh:
            fh.seek(offset)
            lines = fh.readlines()
            new_offset = fh.tell()
    except OSError:
        return offset, []
    errors = [line.rstrip() for line in lines if ERROR_RE.search(line)]
    return new_offset, errors


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
    host = args.host or str(config["host"])
    port = args.port or int(config["port"])
    duration = args.duration or float(config["duration_seconds"])
    count = args.connections or int(config["connections"])
    rate = args.rate or float(config["connect_rate_per_second"])
    timeout = float(config.get("connect_timeout_seconds", 3))
    protocol_version = int(config.get("protocol_version", 0))
    reconnect = bool(config.get("reconnect", True))
    sample_interval = float(config.get("sample_interval_seconds", 5))

    if count < 1 or rate <= 0 or duration <= 0:
        raise SystemExit("connections, rate, and duration must be positive")

    started_wall = datetime.now(timezone.utc).isoformat()
    started = time.monotonic()
    counters = Counters()
    samples: list[Sample] = []
    errors: list[str] = []
    log_offset = args.log.stat().st_size if args.log and args.log.exists() else 0
    rss_start = read_rss_mb(args.pid)
    stop = asyncio.Event()

    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop.set)
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
        while not stop.is_set():
            elapsed = time.monotonic() - started
            if elapsed >= duration:
                stop.set()
                break

            rss = read_rss_mb(args.pid)
            log_offset, new_errors = read_new_log_errors(args.log, log_offset)
            errors.extend(new_errors)
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
        await asyncio.gather(launcher_task, return_exceptions=True)
        await asyncio.gather(*workers, return_exceptions=True)

    elapsed = time.monotonic() - started
    rss_end = read_rss_mb(args.pid)
    rss_growth = (
        rss_end - rss_start
        if rss_start is not None and rss_end is not None
        else None
    )

    connect_failure_ratio = counters.connect_failures / max(counters.attempts, 1)
    protocol_failure_ratio = counters.protocol_failures / max(counters.attempts, 1)

    gates: dict[str, dict[str, Any]] = {}

    def gate(name: str, value: float | int | None, limit: float | int, ok: bool) -> None:
        gates[name] = {"value": value, "limit": limit, "pass": ok}

    max_connect_failure_ratio = float(limits.get("max_connect_failure_ratio", 1.0))
    max_protocol_failure_ratio = float(limits.get("max_protocol_failure_ratio", 1.0))
    max_rss_growth = float(limits.get("max_process_rss_growth_mb", float("inf")))
    max_errors = int(limits.get("max_error_lines", 0))

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
    if rss_growth is not None:
        gate(
            "rss_growth_mb",
            round(rss_growth, 3),
            max_rss_growth,
            rss_growth <= max_rss_growth,
        )

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
    if rss_growth is not None:
        print(f"RSS      : {rss_start:.1f} -> {rss_end:.1f} MB ({rss_growth:+.1f} MB)")
    print(f"Errors   : {len(errors)}")
    print(f"Report   : {report_path}")
    print("RESULT   : " + ("PASS" if passed else "FAIL"))
    return 0 if passed else 1


def parse_args() -> argparse.Namespace:
    root = Path(__file__).resolve().parent
    parser = argparse.ArgumentParser(description="Cardboard post-release torture harness")
    parser.add_argument("scenario", choices=("smoke", "burst", "soak"))
    parser.add_argument("--config", type=Path, default=root / "scenarios.toml")
    parser.add_argument("--host")
    parser.add_argument("--port", type=int)
    parser.add_argument("--duration", type=float)
    parser.add_argument("--connections", type=int)
    parser.add_argument("--rate", type=float)
    parser.add_argument("--pid", type=int, help="Minecraft server PID; Linux /proc RSS sampling")
    parser.add_argument("--log", type=Path, help="latest.log path; only new lines are scanned")
    parser.add_argument("--report-dir", type=Path, default=root / "reports")
    return parser.parse_args()


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
