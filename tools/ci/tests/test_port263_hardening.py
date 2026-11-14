import importlib.util
import json
from pathlib import Path
import socket
import struct
import tempfile
import threading
import unittest

SPEC = importlib.util.spec_from_file_location('hardening', Path(__file__).resolve().parents[1] / 'port263_hardening.py')
hardening = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(hardening)


class ProductionJarTests(unittest.TestCase):
    def test_uses_canonical_jar_with_shadow_and_stale_artifacts_present(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'gradle.properties').write_text(' archives_base_name = Cardboard\n mod_version = 26.3.0-alpha.1\n')
            (root / 'build/libs').mkdir(parents=True)
            canonical = root / 'build/libs/Cardboard-26.3.0-alpha.1.jar'
            for name in [canonical.name, 'Cardboard-26.3.0-alpha.1-all.jar', 'Cardboard-26.2.27.jar']:
                (canonical.parent / name).touch()
            self.assertEqual(canonical, hardening.production_jar(root))
            canonical.unlink()
            with self.assertRaises(FileNotFoundError):
                hardening.production_jar(root)


class ServerFixtureTests(unittest.TestCase):
    def test_flat_world_has_explicit_layers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            hardening.write_server_properties(root, 'test-password')
            values = dict(line.split('=', 1) for line in (root / 'server.properties').read_text().splitlines())
            self.assertEqual('minecraft:flat', values['level-type'])
            settings = json.loads(values['generator-settings'])
            self.assertEqual('minecraft:plains', settings['biome'])
            self.assertTrue(settings['layers'])
            self.assertTrue(all(layer['height'] > 0 and layer['block'].startswith('minecraft:')
                                for layer in settings['layers']))

    def test_log_gate_reports_native_errors(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'console-initial.log'
            log.write_text('[Worker-Main-2/ERROR]: No key layers in MapLike[{}]\n')
            with self.assertRaisesRegex(AssertionError, 'No key layers'):
                hardening.verify_console_log(log)
            log.write_text('[Server thread/INFO]: Done\n')
            hardening.verify_console_log(log)

    def test_only_exact_pinned_optional_warnings_are_accepted_on_performance_stack(self):
        with tempfile.TemporaryDirectory() as directory:
            log = Path(directory) / 'console-initial.log'
            for target in ['integration/v0/ChunkSystemHooks', 'thread/SchedulingUtil']:
                name = 'ca/spottedleaf/starlight/common/' + target
                warning = f'[15:55:11] [main/WARN]: Error loading class: {name} (java.lang.ClassNotFoundException: {name})'
                log.write_text(warning + '\n')
                self.assertEqual([warning], hardening.verify_console_log(log, 'performance'))
                with self.assertRaises(AssertionError):
                    hardening.verify_console_log(log, 'stock')
                for invalid in [warning.replace('/WARN', '/ERROR'),
                                warning.replace(name, 'another/missing/Class'),
                                warning + ' unexpected failure']:
                    log.write_text(invalid + '\n')
                    with self.assertRaises(AssertionError):
                        hardening.verify_console_log(log, 'performance')


class RconTests(unittest.TestCase):
    def test_collects_every_fragment_before_decoding_utf8(self):
        listener = socket.socket()
        listener.bind(('127.0.0.1', 0))
        listener.listen()
        failures = []

        def server():
            try:
                with listener.accept()[0] as connection:
                    def exact(size):
                        result = b''
                        while len(result) < size:
                            result += connection.recv(size - len(result))
                        return result

                    def receive():
                        size, = struct.unpack('<i', exact(4))
                        return exact(size)

                    def send(identifier, data):
                        body = struct.pack('<ii', identifier, 0) + data + b'\0\0'
                        packet = struct.pack('<i', len(body)) + body
                        # TCP can fragment the length header and payload too.
                        for byte in packet:
                            connection.sendall(bytes([byte]))

                    self.assertIn(b'secret', receive())
                    send(1, b'')
                    self.assertIn(b'command', receive())
                    payload = ('x' * 9000 + '\u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0430').encode()
                    send(2, payload[:9001])
                    self.assertIn(b'list', receive())  # Completion sentinel.
                    send(2, payload[9001:])
                    send(3, b'done')
            except BaseException as failure:
                failures.append(failure)
            finally:
                listener.close()

        thread = threading.Thread(target=server, daemon=True)
        thread.start()
        result = hardening.Rcon(listener.getsockname()[1], 'secret').command('command')
        thread.join(timeout=5)
        self.assertFalse(thread.is_alive())
        self.assertFalse(failures, failures)
        self.assertEqual('x' * 9000 + '\u043f\u0440\u043e\u0432\u0435\u0440\u043a\u0430', result)


if __name__ == '__main__':
    unittest.main()
