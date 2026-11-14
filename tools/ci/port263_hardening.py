#!/usr/bin/env python3
"""Production-JAR 26.3 regression gate, including a real independent Fabric mod.

Run after building Cardboard, tools/port263-fixture and the compat/torture plugins.
The workload driver is the existing Linux STATUS/heap harness, not player bots.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import time
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
LOCK = json.loads((Path(__file__).with_name('port263-mods.lock.json')).read_text())
OPTIONAL_COMPAT_WARNING_RE = re.compile(
    r'^\[\d{2}:\d{2}:\d{2}\] \[main/WARN\]: Error loading class: '
    r'(?P<target>ca/spottedleaf/starlight/common/(?:integration/v0/ChunkSystemHooks|thread/SchedulingUtil)) '
    r'\(java\.lang\.ClassNotFoundException: (?P=target)\)$')


def download(url, target, algorithm=None, digest=None):
    target = Path(target)
    if not target.exists():
        request = urllib.request.Request(url, headers={'User-Agent': 'Cardboard-26.3-compatibility-CI/1.0'})
        with urllib.request.urlopen(request, timeout=120) as response:
            data = response.read()
        if digest and hashlib.new(algorithm, data).hexdigest() != digest:
            raise AssertionError(f'Download checksum mismatch: {target.name}')
        temporary = target.with_suffix(target.suffix + '.part')
        temporary.write_bytes(data)
        temporary.replace(target)
    if digest and hashlib.new(algorithm, target.read_bytes()).hexdigest() != digest:
        raise AssertionError(f'Checksum mismatch: {target.name}')


class Rcon:
    def __init__(self, port, password):
        self.port, self.password = port, password

    def command(self, text):
        with socket.create_connection(('127.0.0.1', self.port), timeout=40) as connection:
            def send(identifier, kind, payload):
                body = struct.pack('<ii', identifier, kind) + payload.encode() + b'\0\0'
                connection.sendall(struct.pack('<i', len(body)) + body)

            def exact(length):
                data = bytearray()
                while len(data) < length:
                    part = connection.recv(length - len(data))
                    if not part:
                        raise ConnectionError('RCON closed before the response finished')
                    data.extend(part)
                return data

            def receive():
                size, = struct.unpack('<i', exact(4))
                if not 10 <= size <= 4 * 1024 * 1024:
                    raise ValueError(f'Invalid RCON packet size: {size}')
                body = exact(size)
                identifier, _ = struct.unpack('<ii', body[:8])
                if body[-2:] != b'\0\0':
                    raise ValueError('Invalid RCON packet terminator')
                return identifier, bytes(body[8:-2])

            send(1, 3, self.password)
            if receive()[0] != 1:
                raise PermissionError('RCON authentication failed')
            send(2, 2, text)
            identifier, first = receive()
            if identifier != 2:
                raise ValueError('Unexpected RCON response')
            if text == 'stop':
                return first.decode()
            # Vanilla expects one request per socket read. Send the completion
            # sentinel only after the first response, avoiding TCP coalescing.
            send(3, 2, 'list')
            parts = [first]
            while True:
                identifier, body = receive()
                if identifier == 3:
                    return b''.join(parts).decode('utf-8')
                if identifier != 2:
                    raise ValueError('Unexpected RCON response')
                parts.append(body)


def wait_until(check, timeout, description):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        result = check()
        if result:
            return result
        time.sleep(1)
    raise TimeoutError(description)


def production_jar(root=ROOT):
    properties = dict(re.findall(r'^\s*(archives_base_name|mod_version)\s*=\s*(\S+)\s*$',
                                (root / 'gradle.properties').read_text(), re.MULTILINE))
    jar = root / 'build/libs' / f"{properties['archives_base_name']}-{properties['mod_version']}.jar"
    if not jar.is_file():
        raise FileNotFoundError(f'Build the canonical production Cardboard JAR first: {jar}')
    return jar


def write_server_properties(directory, password):
    flat = json.dumps({'biome': 'minecraft:plains', 'structure_overrides': ['minecraft:villages'], 'layers': [
        {'block': 'minecraft:bedrock', 'height': 1},
        {'block': 'minecraft:dirt', 'height': 2},
        {'block': 'minecraft:grass_block', 'height': 1},
    ]}, separators=(',', ':'))
    (directory / 'server.properties').write_text(
        f'online-mode=false\nserver-ip=127.0.0.1\nserver-port=25589\nenable-rcon=true\n'
        f'rcon.port=25590\nrcon.password={password}\nbroadcast-rcon-to-ops=false\n'
        f'level-name=audit-world\nlevel-type=minecraft:flat\ngenerator-settings={flat}\ngenerate-structures=true\n'
        'spawn-protection=0\nview-distance=3\nsimulation-distance=3\npause-when-empty-seconds=0\nwhite-list=true\n')


def verify_console_log(path, stack='stock'):
    lines = path.read_text(encoding='utf-8', errors='replace').splitlines()
    errors, expected = [], []
    for line in lines:
        if not re.search(r'\[(?:[^\]]*/)?(?:ERROR|SEVERE|FATAL)\]|Mixin apply failed|Exception', line):
            continue
        # The checksum-pinned C2ME JAR probes these two absent optional targets.
        # Match the complete WARN line, never an ERROR or another missing class.
        if stack == 'performance' and OPTIONAL_COMPAT_WARNING_RE.fullmatch(line):
            expected.append(line)
        else:
            errors.append(line)
    if errors:
        raise AssertionError(f'Server errors in {path.name}:\n' + '\n'.join(errors[:10]))
    if expected:
        print(f'EXPECTED_OPTIONAL_COMPAT_WARNINGS {path.name}: {len(expected)} C2ME ScalableLux/Starlight probes', flush=True)
    return expected


def prepare(directory, stack):
    jar = production_jar()
    if directory.exists():
        raise FileExistsError(f'Use a fresh disposable server directory: {directory}')
    (directory / 'mods').mkdir(parents=True)
    (directory / 'plugins').mkdir()
    cache = Path(os.environ.get('GRADLE_USER_HOME', Path.home() / '.gradle')) / 'caches'
    mc = cache / 'fabric-loom' / LOCK['minecraft']
    metadata = json.loads((mc / 'mojang_minecraft_info.json').read_text())
    server = metadata['downloads']['server']
    download(server['url'], directory / 'server.jar', 'sha1', server['sha1'])
    launcher_url = ('https://meta.fabricmc.net/v2/versions/loader/'
                    f"{LOCK['minecraft']}/{LOCK['loader']}/{LOCK['installer']}/server/jar")
    download(launcher_url, directory / 'fabric-server-launch.jar')
    copy = lambda source, target: shutil.copy2(source, directory / target)
    copy(jar, 'mods/Cardboard.jar')
    fabric = next((cache / 'modules-2/files-2.1/net.fabricmc.fabric-api/fabric-api/0.166.2+26.3').rglob('*.jar'))
    copy(fabric, 'mods/FabricAPI.jar')
    copy(ROOT / 'tools/port263-fixture/build/libs/cardboard-port263-fixture-1.0.0.jar', 'mods/Fixture.jar')
    for source in ['tools/port263-fixture/build/libs/CardboardPort263Audit.jar',
                   'tools/torture/plugin/build/libs/cardboard-torture-plugin-1.0.0.jar',
                   'tools/compatibility/plugin/build/libs/cardboard-compat-probe-0.1.0.jar']:
        copy(ROOT / source, 'plugins/' + Path(source).name)
    if stack == 'performance':
        for mod in LOCK['performance']:
            download(mod['url'], directory / 'mods' / mod['file'], 'sha512', mod['sha512'])
    password = secrets.token_hex(24)
    write_server_properties(directory, password)
    (directory / 'eula.txt').write_text('eula=true\n')
    hashes = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in (directory / 'mods').glob('*.jar')}
    (directory / 'tested-artifacts.json').write_text(json.dumps({'stack': stack, 'sha256': hashes}, indent=2))
    return Rcon(25590, password)


def start(directory, phase, java):
    output = (directory / f'console-{phase}.log').open('w', encoding='utf-8')
    process = subprocess.Popen([java, '-Xms512m', '-Xmx2G', '-XX:NativeMemoryTracking=summary',
                                '--enable-native-access=ALL-UNNAMED', '-Dfile.encoding=UTF-8',
                                '-jar', 'fabric-server-launch.jar', 'nogui'], cwd=directory,
                               stdin=subprocess.PIPE, stdout=output, stderr=subprocess.STDOUT,
                               text=True, encoding='utf-8')
    def ready():
        if process.poll() is not None:
            raise RuntimeError(f'Server exited during startup: {process.returncode}')
        text = (directory / f'console-{phase}.log').read_text(encoding='utf-8', errors='replace')
        return 'RCON running on' in text
    try:
        wait_until(ready, 240, 'Server startup timed out')
        return process, output
    except BaseException:
        stop(process, output)
        raise


def stop(process, output):
    try:
        if process.poll() is None:
            process.stdin.write('stop\n')
            process.stdin.flush()
            try:
                process.wait(timeout=60)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=15)
                raise
        if process.returncode != 0:
            raise RuntimeError(f'Server exited with code {process.returncode}')
    finally:
        output.close()


def probe(directory, rcon, command):
    result = directory / 'plugins/CardboardPort263Audit/results.json'
    result.unlink(missing_ok=True)
    print(rcon.command('port263audit ' + command), flush=True)
    wait_until(result.exists, 120, 'API probe did not finish')
    values = json.loads(result.read_text())
    (directory / f'probe-{command}.json').write_text(json.dumps(values, indent=2))
    if not values or any(value != 'PASS' for value in values.values()):
        raise AssertionError(values)
    if 'PORT263_AUDIT running=false' not in rcon.command('port263audit status'):
        raise AssertionError('Plugin command disappeared after the probe/data reload')
    print(f'API {command}: {len(values)} PASS', flush=True)


def matrix(directory, rcon):
    expected = {'run': (57, 54, 3), 'wave2c': (13, 13, 0), 'wave2d': (13, 13, 0)}
    for command, counts in expected.items():
        rcon.command('cardboardcompat ' + command)
        wait_until(lambda: 'running=false' in rcon.command('cardboardcompat status'), 400,
                   f'Compatibility {command} did not finish')
        summary = rcon.command('cardboardcompat summary')
        (directory / f'compat-{command}.txt').write_text(summary, encoding='utf-8')
        fields = {key: int(value) for key, value in re.findall(r'(total|pass|fail|skip)=(\d+)', summary.splitlines()[0])}
        total, passed, skipped = counts
        if fields != {'total': total, 'pass': passed, 'fail': 0, 'skip': skipped}:
            raise AssertionError(summary)
        print(summary.splitlines()[0], flush=True)


def acceptance(directory, rcon, process, seconds):
    for kind, flag, timeout in [('chunks', 'preparingChunks', 600), ('entity', 'preparingEntity', 120)]:
        rcon.command('cardboardtorture prepare ' + kind)
        wait_until(lambda: flag + '=true' not in rcon.command('cardboardtorture status'), timeout,
                   f'{kind} preparation timed out')
    status = rcon.command('cardboardtorture status')
    if 'entityPrepared=true' not in status:
        raise AssertionError(status)
    rcon.command(f'cardboardtorture start {seconds} stability')
    subprocess.run([sys.executable, str(ROOT / 'tools/torture/run.py'), 'smoke', '--duration', str(seconds),
                    '--host', '127.0.0.1', '--port', '25589', '--pid', str(process.pid),
                    '--log', str(directory / 'logs/latest.log'), '--report-dir', str(directory / 'acceptance')],
                   cwd=ROOT, check=True)
    wait_until(lambda: 'running=false' in rcon.command('cardboardtorture status'), 45, 'Workload cleanup timed out')
    status = rcon.command('cardboardtorture status')
    (directory / 'workload-final.txt').write_text(status, encoding='utf-8')
    for expected in ['workload failures=0', 'entityTickets=0']:
        if expected not in status:
            raise AssertionError(status)
    if re.search(r'failures=[1-9]', status):
        raise AssertionError(status)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stack', choices=['stock', 'performance'], default='stock')
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--duration', type=int, default=300)
    args = parser.parse_args()
    if args.duration < 180:
        parser.error('The acceptance workload must run at least 180 seconds')
    directory = args.directory.resolve()
    java = shutil.which('java')
    if not java:
        raise FileNotFoundError('Java 25 is required')
    rcon = prepare(directory, args.stack)
    for phase in ['initial', 'restart']:
        process, output = start(directory, phase, java)
        try:
            verify_console_log(directory / f'console-{phase}.log', args.stack)
            if phase == 'initial':
                probe(directory, rcon, 'run')
                rcon.command('minecraft:reload')
                wait_until(lambda: 'PORT263_AUDIT running=false' in rcon.command('port263audit status'),
                           60, 'Plugin command disappeared after native reload')
                matrix(directory, rcon)
                acceptance(directory, rcon, process, args.duration)
                probe(directory, rcon, 'persist')
            else:
                probe(directory, rcon, 'verify-persist')
                probe(directory, rcon, 'run')
        finally:
            stop(process, output)
        verify_console_log(directory / f'console-{phase}.log', args.stack)
    (directory / 'success.json').write_text(json.dumps({'passed': True, 'duration_seconds': args.duration, 'stack': args.stack}))
    print('PORT263_HARDENING_PASS', flush=True)


if __name__ == '__main__':
    main()
