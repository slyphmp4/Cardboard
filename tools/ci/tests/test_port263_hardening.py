import importlib.util
from pathlib import Path
import socket
import struct
import threading
import unittest

SPEC = importlib.util.spec_from_file_location('hardening', Path(__file__).resolve().parents[1] / 'port263_hardening.py')
hardening = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(hardening)


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
