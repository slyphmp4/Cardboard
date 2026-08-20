from __future__ import annotations

import asyncio
import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock


RUN_PATH = Path(__file__).resolve().parents[1] / "run.py"
SPEC = importlib.util.spec_from_file_location("cardboard_torture_run", RUN_PATH)
assert SPEC is not None and SPEC.loader is not None
HARNESS = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = HARNESS
SPEC.loader.exec_module(HARNESS)


async def successful_worker(*worker_args, **_kwargs) -> None:
    counters = worker_args[-1]
    counters.attempts += 1
    counters.successful_requests += 1


async def early_stop_worker(*worker_args, **_kwargs) -> None:
    stop = worker_args[-2]
    counters = worker_args[-1]
    counters.attempts += 1
    counters.successful_requests += 1
    stop.set()


async def no_work_worker(*_args, **_kwargs) -> None:
    return None


async def failing_worker(*worker_args, **_kwargs) -> None:
    counters = worker_args[-1]
    counters.attempts += 1
    raise RuntimeError("worker boom")


class HeapParsingTests(unittest.TestCase):
    def test_parses_real_jdk_25_g1_heap_info(self) -> None:
        output = """398379:
garbage-first heap   total reserved 7340032K, committed 7340032K, used 552865K [0x0, 0x1)
 region size 4096K, 20 young (81920K), 0 survivors (0K)
"""
        self.assertAlmostEqual(552865 / 1024, HARNESS.parse_heap_info_mb(output))

    def test_parses_supported_units(self) -> None:
        cases = {
            "collector heap committed 1G, used 2G": 2048.0,
            "collector heap committed 1G, used 512M": 512.0,
            "collector heap committed 1G, used 1024K": 1.0,
        }
        for output, expected in cases.items():
            with self.subTest(output=output):
                self.assertEqual(expected, HARNESS.parse_heap_info_mb(output))

    def test_rejects_malformed_heap_info(self) -> None:
        for output in ("", "Native memory tracking is not enabled", "heap used unknown"):
            with self.subTest(output=output):
                with self.assertRaises(HARNESS.MemoryProbeError):
                    HARNESS.parse_heap_info_mb(output)

    def test_disable_explicit_gc_parser_is_fail_closed(self) -> None:
        self.assertFalse(
            HARNESS.parse_disable_explicit_gc("bool DisableExplicitGC = false {product}")
        )
        self.assertTrue(
            HARNESS.parse_disable_explicit_gc("bool DisableExplicitGC = true {product}")
        )
        with self.assertRaises(HARNESS.MemoryProbeError):
            HARNESS.parse_disable_explicit_gc("unrelated VM flags")


class ProcessIdentityTests(unittest.TestCase):
    def test_parses_start_ticks_when_process_name_contains_spaces(self) -> None:
        suffix = ["S"] + ["0"] * 18 + ["987654"]
        stat_text = f"123 (java server worker) {' '.join(suffix)}"
        self.assertEqual(987654, HARNESS.parse_process_start_ticks(stat_text))

    def test_rejects_malformed_proc_stat(self) -> None:
        for stat_text in ("", "123 java", "123 (java) S 0"):
            with self.subTest(stat_text=stat_text):
                with self.assertRaises(HARNESS.MemoryProbeError):
                    HARNESS.parse_process_start_ticks(stat_text)

    def test_process_restart_is_rejected(self) -> None:
        start = HARNESS.ProcessIdentity(pid=123, start_ticks=10)
        HARNESS.ensure_same_process(start, HARNESS.ProcessIdentity(pid=123, start_ticks=10))
        with self.assertRaises(HARNESS.MemoryProbeError):
            HARNESS.ensure_same_process(
                start,
                HARNESS.ProcessIdentity(pid=123, start_ticks=11),
            )


class ProcessCommandLineTests(unittest.TestCase):
    def test_accepts_fabric_server_jar_and_paper_nogui_launches(self) -> None:
        fabric = HARNESS.parse_process_cmdline(
            b"/opt/java/bin/java\0-Xmx7G\0-jar\0/data/fabric-server-launch.jar\0nogui\0"
        )
        HARNESS.validate_minecraft_server_cmdline(fabric)
        HARNESS.validate_minecraft_server_cmdline(
            ["java", "-Xmx7G", "-jar", "/data/paper.jar", "nogui"]
        )

    def test_accepts_minecraft_server_main_class(self) -> None:
        HARNESS.validate_minecraft_server_cmdline(
            ["/usr/bin/java", "-Xmx7G", "net.minecraft.server.Main", "nogui"]
        )

    def test_rejects_non_java_and_unrelated_java_processes(self) -> None:
        for argv in (
            ["/usr/bin/python3", "server.py"],
            ["/usr/bin/java", "-jar", "compiler.jar"],
            ["/usr/bin/java", "-version"],
        ):
            with self.subTest(argv=argv):
                with self.assertRaises(HARNESS.MemoryProbeError):
                    HARNESS.validate_minecraft_server_cmdline(argv)

    def test_empty_cmdline_is_fail_closed(self) -> None:
        with self.assertRaises(HARNESS.MemoryProbeError):
            HARNESS.parse_process_cmdline(b"")


class LogMonitoringTests(unittest.TestCase):
    def test_missing_requested_log_fails_preflight(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "missing.log"
            with self.assertRaisesRegex(HARNESS.LogProbeError, "Cannot read requested log"):
                HARNESS.prepare_log_tail(missing)

    def test_unreadable_log_is_not_silently_ignored(self) -> None:
        fake_path = mock.Mock()
        fake_path.open.side_effect = PermissionError("denied")
        with self.assertRaisesRegex(HARNESS.LogProbeError, "denied"):
            HARNESS.prepare_log_tail(fake_path)

        offset, errors, read_error = HARNESS.read_new_log_errors(fake_path, 17)
        self.assertEqual(17, offset)
        self.assertEqual([], errors)
        self.assertIn("denied", read_error)


class TaskFailureTests(unittest.TestCase):
    def test_gathered_exceptions_are_described(self) -> None:
        failures = HARNESS.task_failure_messages(
            "worker",
            [None, RuntimeError("boom"), asyncio.CancelledError()],
        )
        self.assertEqual(2, len(failures))
        self.assertIn("worker[1] RuntimeError: boom", failures)
        self.assertIn("worker[2] CancelledError", failures[1])


class MemoryModeTests(unittest.TestCase):
    def test_auto_prefers_new_heap_limit(self) -> None:
        limits = {
            HARNESS.HEAP_LIMIT_KEY: 512,
            HARNESS.RSS_LIMIT_KEY: 1024,
        }
        self.assertEqual("heap", HARNESS.select_memory_gate_mode(limits, "auto"))

    def test_auto_preserves_legacy_rss_config(self) -> None:
        self.assertEqual(
            "rss",
            HARNESS.select_memory_gate_mode({HARNESS.RSS_LIMIT_KEY: 1024}, "auto"),
        )

    def test_only_explicit_off_disables_memory_gate(self) -> None:
        with self.assertRaisesRegex(HARNESS.MemoryProbeError, "--memory-gate off"):
            HARNESS.select_memory_gate_mode({}, "auto")
        self.assertEqual(
            "off",
            HARNESS.select_memory_gate_mode({HARNESS.HEAP_LIMIT_KEY: 512}, "off"),
        )

    def test_active_modes_require_valid_limits(self) -> None:
        self.assertEqual(
            512.0,
            HARNESS.memory_gate_limit("heap", {HARNESS.HEAP_LIMIT_KEY: 512}),
        )
        self.assertEqual(
            1024.0,
            HARNESS.memory_gate_limit("rss", {HARNESS.RSS_LIMIT_KEY: 1024}),
        )
        for mode, limits in (
            ("heap", {}),
            ("rss", {}),
            ("heap", {HARNESS.HEAP_LIMIT_KEY: -1}),
            ("heap", {HARNESS.HEAP_LIMIT_KEY: "not-a-number"}),
            ("heap", {HARNESS.HEAP_LIMIT_KEY: float("nan")}),
            ("heap", {HARNESS.HEAP_LIMIT_KEY: float("inf")}),
        ):
            with self.subTest(mode=mode, limits=limits):
                with self.assertRaises(HARNESS.MemoryProbeError):
                    HARNESS.memory_gate_limit(mode, limits)


class MemoryGateEvaluationTests(unittest.TestCase):
    def test_heap_gate_ignores_large_diagnostic_rss_growth(self) -> None:
        gates, heap_growth, rss_growth = HARNESS.evaluate_memory_gates(
            "heap",
            512.0,
            heap_start_mb=400.0,
            heap_end_mb=461.0,
            rss_start_mb=5637.3,
            rss_end_mb=6881.7,
        )
        self.assertEqual(61.0, heap_growth)
        self.assertAlmostEqual(1244.4, rss_growth)
        self.assertTrue(gates["heap_after_full_gc_growth_mb"]["pass"])
        self.assertNotIn("rss_growth_mb", gates)

    def test_heap_gate_handles_equal_pass_and_over_limit_fail(self) -> None:
        equal, _, _ = HARNESS.evaluate_memory_gates(
            "heap",
            512.0,
            heap_start_mb=100.0,
            heap_end_mb=612.0,
            rss_start_mb=None,
            rss_end_mb=None,
        )
        over, _, _ = HARNESS.evaluate_memory_gates(
            "heap",
            512.0,
            heap_start_mb=100.0,
            heap_end_mb=612.001,
            rss_start_mb=None,
            rss_end_mb=None,
        )
        self.assertTrue(equal["heap_after_full_gc_growth_mb"]["pass"])
        self.assertFalse(over["heap_after_full_gc_growth_mb"]["pass"])

    def test_legacy_rss_gate_is_unchanged(self) -> None:
        passed, _, growth = HARNESS.evaluate_memory_gates(
            "rss",
            1024.0,
            heap_start_mb=None,
            heap_end_mb=None,
            rss_start_mb=1000.0,
            rss_end_mb=2024.0,
        )
        failed, _, _ = HARNESS.evaluate_memory_gates(
            "rss",
            1024.0,
            heap_start_mb=None,
            heap_end_mb=None,
            rss_start_mb=1000.0,
            rss_end_mb=2024.001,
        )
        self.assertEqual(1024.0, growth)
        self.assertTrue(passed["rss_growth_mb"]["pass"])
        self.assertFalse(failed["rss_growth_mb"]["pass"])

    def test_measurement_failure_always_creates_failing_gate(self) -> None:
        gates, heap_growth, _ = HARNESS.evaluate_memory_gates(
            "heap",
            512.0,
            heap_start_mb=400.0,
            heap_end_mb=None,
            rss_start_mb=5000.0,
            rss_end_mb=6000.0,
            measurement_error="final jcmd failed",
        )
        self.assertIsNone(heap_growth)
        self.assertFalse(gates["memory_measurement"]["pass"])
        self.assertIn("final jcmd failed", gates["memory_measurement"]["value"])

    def test_explicit_off_keeps_diagnostic_growth_without_a_gate(self) -> None:
        gates, heap_growth, rss_growth = HARNESS.evaluate_memory_gates(
            "off",
            None,
            heap_start_mb=None,
            heap_end_mb=None,
            rss_start_mb=10.0,
            rss_end_mb=20.0,
        )
        self.assertEqual({}, gates)
        self.assertIsNone(heap_growth)
        self.assertEqual(10.0, rss_growth)


class JcmdTests(unittest.TestCase):
    @mock.patch.object(HARNESS.shutil, "which", return_value="/usr/bin/jcmd")
    def test_jcmd_is_resolved_from_path(self, which: mock.Mock) -> None:
        self.assertEqual("/usr/bin/jcmd", HARNESS.resolve_jcmd(None))
        which.assert_called_once_with("jcmd")

    @mock.patch.object(HARNESS.shutil, "which", return_value=None)
    def test_missing_jcmd_is_fail_closed(self, _which: mock.Mock) -> None:
        with self.assertRaises(HARNESS.MemoryProbeError):
            HARNESS.resolve_jcmd(None)

    @mock.patch.object(HARNESS.subprocess, "run")
    def test_run_jcmd_uses_argument_list_timeout_and_no_shell(self, runner: mock.Mock) -> None:
        runner.return_value = subprocess.CompletedProcess(
            args=[],
            returncode=0,
            stdout="123:\nCommand executed successfully\n",
            stderr="",
        )
        output = HARNESS.run_jcmd(
            "/usr/bin/jcmd",
            123,
            "GC.run",
            timeout=30.0,
        )
        self.assertIn("successfully", output)
        runner.assert_called_once_with(
            ["/usr/bin/jcmd", "123", "GC.run"],
            capture_output=True,
            text=True,
            timeout=30.0,
            check=False,
            shell=False,
        )

    @mock.patch.object(HARNESS.subprocess, "run")
    def test_run_jcmd_rejects_timeout_and_nonzero_exit(self, runner: mock.Mock) -> None:
        runner.side_effect = subprocess.TimeoutExpired(["jcmd"], 1)
        with self.assertRaises(HARNESS.MemoryProbeError):
            HARNESS.run_jcmd("jcmd", 123, "GC.run", timeout=1.0)

        runner.side_effect = None
        runner.return_value = subprocess.CompletedProcess(
            args=[],
            returncode=1,
            stdout="",
            stderr="attach failed",
        )
        with self.assertRaises(HARNESS.MemoryProbeError):
            HARNESS.run_jcmd("jcmd", 123, "GC.run", timeout=1.0)

    @mock.patch.object(HARNESS, "run_jcmd")
    def test_full_gc_must_confirm_success_before_heap_parse(self, jcmd: mock.Mock) -> None:
        jcmd.side_effect = [
            "123:\nCommand executed successfully\n",
            "123:\ngarbage-first heap committed 1G, used 512M\n",
        ]
        self.assertEqual(
            512.0,
            HARNESS.collect_heap_after_full_gc_mb("jcmd", 123, 30.0),
        )

        jcmd.reset_mock()
        jcmd.side_effect = ["123:\nGC is disabled\n"]
        with self.assertRaises(HARNESS.MemoryProbeError):
            HARNESS.collect_heap_after_full_gc_mb("jcmd", 123, 30.0)


class CliCompatibilityTests(unittest.TestCase):
    def test_old_cli_arguments_are_unchanged(self) -> None:
        args = HARNESS.parse_args(
            [
                "smoke",
                "--host",
                "127.0.0.1",
                "--port",
                "25565",
                "--duration",
                "315",
                "--connections",
                "12",
                "--rate",
                "3",
                "--pid",
                "123",
            ]
        )
        self.assertEqual("smoke", args.scenario)
        self.assertEqual(123, args.pid)
        self.assertEqual(315.0, args.duration)
        self.assertEqual("auto", args.memory_gate)
        self.assertEqual(120.0, args.jcmd_timeout)

    def test_new_memory_cli_arguments(self) -> None:
        args = HARNESS.parse_args(
            [
                "burst",
                "--memory-gate",
                "heap",
                "--jcmd",
                "/opt/jdk/bin/jcmd",
                "--jcmd-timeout",
                "45",
            ]
        )
        self.assertEqual("heap", args.memory_gate)
        self.assertEqual("/opt/jdk/bin/jcmd", args.jcmd)
        self.assertEqual(45.0, args.jcmd_timeout)


class RunFailClosedTests(unittest.IsolatedAsyncioTestCase):
    def make_args(self, root: Path, *extra: str):
        config = root / "scenarios.toml"
        config.write_text(
            """[defaults]
host = "127.0.0.1"
port = 25565
connect_timeout_seconds = 0.01
sample_interval_seconds = 0.001

[smoke]
duration_seconds = 0.005
connections = 1
connect_rate_per_second = 1000
reconnect = false

[limits]
max_connect_failure_ratio = 1.0
max_protocol_failure_ratio = 1.0
max_heap_after_full_gc_growth_mb = 512
max_error_lines = 0
""",
            encoding="utf-8",
        )
        return HARNESS.parse_args(
            [
                "smoke",
                "--config",
                str(config),
                "--report-dir",
                str(root / "reports"),
                *extra,
            ]
        )

    async def test_heap_gate_without_pid_fails_before_workload(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            args = self.make_args(Path(directory))
            with self.assertRaisesRegex(SystemExit, "requires --pid"):
                await HARNESS.run(args)

    async def test_requested_missing_log_fails_before_workload(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = self.make_args(
                root,
                "--memory-gate",
                "off",
                "--log",
                str(root / "missing.log"),
            )
            with self.assertRaisesRegex(SystemExit, "Log preflight failed"):
                await HARNESS.run(args)

    async def test_non_server_pid_fails_memory_preflight(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            args = self.make_args(Path(directory), "--pid", "123")
            with (
                mock.patch.object(
                    HARNESS,
                    "read_process_identity",
                    return_value=HARNESS.ProcessIdentity(123, 10),
                ),
                mock.patch.object(
                    HARNESS,
                    "verify_minecraft_server_process",
                    side_effect=HARNESS.MemoryProbeError("not a server PID"),
                ),
            ):
                with self.assertRaisesRegex(SystemExit, "not a server PID"):
                    await HARNESS.run(args)

    async def test_disable_explicit_gc_fails_preflight(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            args = self.make_args(Path(directory), "--pid", "123")
            with (
                mock.patch.object(
                    HARNESS,
                    "read_process_identity",
                    return_value=HARNESS.ProcessIdentity(123, 10),
                ),
                mock.patch.object(HARNESS, "verify_minecraft_server_process"),
                mock.patch.object(HARNESS, "resolve_jcmd", return_value="jcmd"),
                mock.patch.object(
                    HARNESS,
                    "run_jcmd",
                    return_value="bool DisableExplicitGC = true {product}",
                ),
            ):
                with self.assertRaisesRegex(SystemExit, "DisableExplicitGC=true"):
                    await HARNESS.run(args)

    async def test_final_snapshot_failure_writes_failing_report(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = self.make_args(root, "--pid", "123")
            identity = HARNESS.ProcessIdentity(123, 10)

            with (
                mock.patch.object(HARNESS, "read_process_identity", return_value=identity),
                mock.patch.object(HARNESS, "verify_minecraft_server_process"),
                mock.patch.object(HARNESS, "resolve_jcmd", return_value="jcmd"),
                mock.patch.object(
                    HARNESS,
                    "run_jcmd",
                    return_value="bool DisableExplicitGC = false {product}",
                ),
                mock.patch.object(
                    HARNESS,
                    "collect_heap_after_full_gc_mb",
                    side_effect=[400.0, HARNESS.MemoryProbeError("final jcmd failed")],
                ),
                mock.patch.object(HARNESS, "read_rss_mb", return_value=6000.0),
                mock.patch.object(HARNESS, "status_worker", new=successful_worker),
            ):
                exit_code = await HARNESS.run(args)

            self.assertEqual(1, exit_code)
            reports = list((root / "reports").glob("smoke-*.json"))
            self.assertEqual(1, len(reports))
            report = json.loads(reports[0].read_text(encoding="utf-8"))
            self.assertFalse(report["passed"])
            self.assertEqual("heap", report["memory_gate_mode"])
            self.assertEqual("final jcmd failed", report["memory_error"])
            self.assertFalse(report["gates"]["memory_measurement"]["pass"])
            self.assertEqual(6000.0, report["rss_start_mb"])
            self.assertEqual(6000.0, report["rss_end_mb"])

    async def test_heap_gate_passes_while_large_rss_growth_remains_diagnostic(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = self.make_args(root, "--pid", "123")
            identity = HARNESS.ProcessIdentity(123, 10)
            rss_values = iter([5637.3, 5637.3, 6881.7])

            with (
                mock.patch.object(HARNESS, "read_process_identity", return_value=identity),
                mock.patch.object(HARNESS, "verify_minecraft_server_process"),
                mock.patch.object(HARNESS, "resolve_jcmd", return_value="jcmd"),
                mock.patch.object(
                    HARNESS,
                    "run_jcmd",
                    return_value="bool DisableExplicitGC = false {product}",
                ),
                mock.patch.object(
                    HARNESS,
                    "collect_heap_after_full_gc_mb",
                    side_effect=[400.0, 461.0],
                ),
                mock.patch.object(
                    HARNESS,
                    "read_rss_mb",
                    side_effect=lambda _pid: next(rss_values, 6881.7),
                ),
                mock.patch.object(HARNESS, "status_worker", new=successful_worker),
            ):
                exit_code = await HARNESS.run(args)

            self.assertEqual(0, exit_code)
            reports = list((root / "reports").glob("smoke-*.json"))
            self.assertEqual(1, len(reports))
            report = json.loads(reports[0].read_text(encoding="utf-8"))
            self.assertTrue(report["passed"])
            self.assertEqual(61.0, report["heap_after_full_gc_growth_mb"])
            self.assertAlmostEqual(1244.4, report["rss_growth_mb"])
            self.assertIn("heap_after_full_gc_growth_mb", report["gates"])
            self.assertNotIn("rss_growth_mb", report["gates"])

    async def test_early_stop_cannot_pass_completion_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = self.make_args(root, "--memory-gate", "off")
            with mock.patch.object(HARNESS, "status_worker", new=early_stop_worker):
                exit_code = await HARNESS.run(args)

            self.assertEqual(1, exit_code)
            report = json.loads(
                next((root / "reports").glob("smoke-*.json")).read_text(encoding="utf-8")
            )
            self.assertEqual("stopped_early", report["completion_reason"])
            self.assertFalse(report["gates"]["completion"]["pass"])
            self.assertTrue(report["gates"]["useful_work"]["pass"])

    async def test_signal_stop_is_recorded_and_cannot_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = self.make_args(root, "--memory-gate", "off")
            loop = asyncio.get_running_loop()

            def register_signal(sig, callback, *callback_args) -> None:
                if sig == HARNESS.signal.SIGTERM:
                    callback(*callback_args)

            with (
                mock.patch.object(loop, "add_signal_handler", side_effect=register_signal),
                mock.patch.object(loop, "remove_signal_handler"),
                mock.patch.object(HARNESS, "status_worker", new=successful_worker),
            ):
                exit_code = await HARNESS.run(args)

            self.assertEqual(1, exit_code)
            report = json.loads(
                next((root / "reports").glob("smoke-*.json")).read_text(encoding="utf-8")
            )
            self.assertEqual("signal:SIGTERM", report["completion_reason"])
            self.assertFalse(report["gates"]["completion"]["pass"])
            self.assertFalse(report["gates"]["useful_work"]["pass"])

    async def test_zero_attempts_fails_useful_work_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = self.make_args(root, "--memory-gate", "off")
            with mock.patch.object(HARNESS, "status_worker", new=no_work_worker):
                exit_code = await HARNESS.run(args)

            self.assertEqual(1, exit_code)
            report = json.loads(
                next((root / "reports").glob("smoke-*.json")).read_text(encoding="utf-8")
            )
            self.assertTrue(report["gates"]["completion"]["pass"])
            self.assertFalse(report["gates"]["useful_work"]["pass"])
            self.assertTrue(report["gates"]["task_failures"]["pass"])

    async def test_worker_exception_returned_by_gather_fails_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = self.make_args(root, "--memory-gate", "off")
            with mock.patch.object(HARNESS, "status_worker", new=failing_worker):
                exit_code = await HARNESS.run(args)

            self.assertEqual(1, exit_code)
            report = json.loads(
                next((root / "reports").glob("smoke-*.json")).read_text(encoding="utf-8")
            )
            self.assertFalse(report["gates"]["task_failures"]["pass"])
            self.assertIn("worker boom", report["task_errors"][0])

    async def test_launcher_exception_returned_by_gather_fails_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            args = self.make_args(root, "--memory-gate", "off")

            def invalid_worker(*_args, **_kwargs):
                return None

            with mock.patch.object(HARNESS, "status_worker", new=invalid_worker):
                exit_code = await HARNESS.run(args)

            self.assertEqual(1, exit_code)
            report = json.loads(
                next((root / "reports").glob("smoke-*.json")).read_text(encoding="utf-8")
            )
            self.assertFalse(report["gates"]["task_failures"]["pass"])
            self.assertIn("launcher[0] TypeError", report["task_errors"][0])

    async def test_log_read_failure_is_a_failing_gate(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log_path = root / "latest.log"
            log_path.write_text("ready\n", encoding="utf-8")
            args = self.make_args(
                root,
                "--memory-gate",
                "off",
                "--log",
                str(log_path),
            )
            with (
                mock.patch.object(HARNESS, "status_worker", new=successful_worker),
                mock.patch.object(
                    HARNESS,
                    "read_new_log_errors",
                    return_value=(0, [], "log read denied"),
                ),
            ):
                exit_code = await HARNESS.run(args)

            self.assertEqual(1, exit_code)
            report = json.loads(
                next((root / "reports").glob("smoke-*.json")).read_text(encoding="utf-8")
            )
            self.assertFalse(report["gates"]["log_read_failures"]["pass"])
            self.assertEqual(["log read denied"], report["log_read_errors"])

    async def test_post_memory_log_scan_catches_gc_time_error(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            log_path = root / "latest.log"
            log_path.write_text("ready\n", encoding="utf-8")
            args = self.make_args(
                root,
                "--pid",
                "123",
                "--log",
                str(log_path),
            )
            identity = HARNESS.ProcessIdentity(123, 10)
            measurements = 0

            def collect_heap(*_args, **_kwargs) -> float:
                nonlocal measurements
                measurements += 1
                if measurements == 2:
                    with log_path.open("a", encoding="utf-8") as log_file:
                        log_file.write("[ERROR] emitted during final GC\n")
                    return 461.0
                return 400.0

            with (
                mock.patch.object(HARNESS, "read_process_identity", return_value=identity),
                mock.patch.object(HARNESS, "verify_minecraft_server_process"),
                mock.patch.object(HARNESS, "resolve_jcmd", return_value="jcmd"),
                mock.patch.object(
                    HARNESS,
                    "run_jcmd",
                    return_value="bool DisableExplicitGC = false {product}",
                ),
                mock.patch.object(
                    HARNESS,
                    "collect_heap_after_full_gc_mb",
                    side_effect=collect_heap,
                ),
                mock.patch.object(HARNESS, "read_rss_mb", return_value=6000.0),
                mock.patch.object(HARNESS, "status_worker", new=successful_worker),
            ):
                exit_code = await HARNESS.run(args)

            self.assertEqual(1, exit_code)
            report = json.loads(
                next((root / "reports").glob("smoke-*.json")).read_text(encoding="utf-8")
            )
            self.assertFalse(report["gates"]["error_lines"]["pass"])
            self.assertIn("emitted during final GC", report["error_lines"][0])
            self.assertTrue(report["gates"]["heap_after_full_gc_growth_mb"]["pass"])


if __name__ == "__main__":
    unittest.main()
