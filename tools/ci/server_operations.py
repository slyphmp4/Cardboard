#!/usr/bin/env python3
"""Restart only disposable loopback servers, with a script-created second JVM and saved-data proof."""
import json
import os
from pathlib import Path
import shlex
import shutil
import signal
import subprocess
import port263_hardening as h

ROOT = h.ROOT


def wait_for(check, timeout, message):
    return h.wait_until(check, timeout, message)


def clean_children(directory):
    for name in ['child-pids.txt', 'script-pids.txt']:
        path = directory / name
        if path.exists():
            for value in path.read_text().splitlines():
                try:
                    pid = int(value)
                    handle = os.pidfd_open(pid)
                    try:
                        process = Path('/proc') / str(pid)
                        if (process / 'cwd').resolve() == directory and any(token in (process / 'cmdline').read_bytes() for token in [b'fabric-server-launch.jar', b'restart launcher.sh']):
                            signal.pidfd_send_signal(handle, signal.SIGTERM)
                    finally:
                        os.close(handle)
                except (OSError, ValueError):
                    pass


def one(mode, java):
    directory = (ROOT / 'build/server-operations' / mode).resolve()
    rcon = h.prepare(directory, 'stock')
    wrapper = directory / 'java-fixture'
    wrapper.write_text('#!/bin/sh\nexec ' + shlex.quote(java) + ' -Dcardboard.restartFixture=true "$@"\n')
    wrapper.chmod(0o700)
    script = directory / 'restart launcher.sh'
    script.write_text('#!/bin/sh\n'
        'echo $$ >> script-pids.txt\n'
        'echo restart >> launcher-invocations.txt\n'
        'printf "%s\\n" "$@" > launcher-arguments.txt\n'
        + shlex.quote(str(wrapper)) + ' -Xms512m -Xmx2G --enable-native-access=ALL-UNNAMED '
        '-Dfile.encoding=UTF-8 -Dcardboard.disposableAliasProbe=true -jar fabric-server-launch.jar nogui '
        '< /dev/null > console-child.log 2>&1 &\n'
        'child=$!\necho "$child" >> child-pids.txt\nwait "$child"\n'
        'code=$?\nprintf "%s\\n" "$code" > child-exit.txt\nexit "$code"\n')
    configured = '"restart launcher.sh" "two words" "$(touch unintended-launch)" "literal;value"'
    if mode == 'command':
        configured = './missing-startup-file.sh'
    (directory / 'spigot.yml').write_text(json.dumps({'settings': {'restart-script': configured}, 'messages': {'restart': 'Restart fixture'}}))
    original = output = None
    try:
        original, output = h.start(directory, 'original', str(wrapper))
        first = json.loads((directory / 'restart-fixture-started.json').read_text())['pid']
        if first != original.pid:
            raise AssertionError('Fixture did not identify the actual first JVM')
        h.probe(directory, rcon, 'persist')
        reply = rcon.command('port263audit serverops ' + mode)
        if 'SERVER_OPS_RESTART_ARMED' not in reply:
            raise AssertionError(reply)
        try:
            original.wait(timeout=30)
        except subprocess.TimeoutExpired as failure:
            raise AssertionError(f'{mode}: restart entry point did not stop the original server') from failure
        if original.returncode != 0:
            raise AssertionError(f'{mode}: original JVM exited {original.returncode}')
        h.stop(original, output)
        output = None
        disabled = json.loads((directory / f'restart-fixture-disabled-{first}.json').read_text())
        request = json.loads((directory / 'restart-fixture-request.json').read_text())
        if not disabled['primaryThread'] or request['primaryThread'] != (mode != 'spigot'):
            raise AssertionError('Shutdown did not marshal to the server thread')
        h.verify_console_log(directory / 'console-original.log', 'stock')
        if mode == 'command':
            if not json.loads((directory / 'restart-fixture-command.json').read_text())['dispatched']:
                raise AssertionError('The restart command was absent')
            if (directory / 'launcher-invocations.txt').exists():
                raise AssertionError('A missing script was launched')
            second, second_output = h.start(directory, 'supervisor', str(wrapper))
            try:
                if second.pid == first:
                    raise AssertionError('The supervisor did not create a fresh JVM')
                h.probe(directory, rcon, 'verify-persist')
            finally:
                h.stop(second, second_output)
            h.verify_console_log(directory / 'console-supervisor.log', 'stock')
        else:
            def ready():
                path = directory / 'console-child.log'
                return path.exists() and 'RCON running on' in path.read_text(errors='replace')
            wait_for(ready, 240, 'Configured startup script did not start a second server')
            started = json.loads((directory / 'restart-fixture-started.json').read_text())['pid']
            child_pids = (directory / 'child-pids.txt').read_text().splitlines()
            if started == first or child_pids != [str(started)]:
                raise AssertionError('Restart did not create exactly one new JVM')
            if (directory / 'launcher-invocations.txt').read_text().splitlines() != ['restart']:
                raise AssertionError('Duplicate restart requests installed duplicate launchers')
            if (directory / 'launcher-arguments.txt').read_text().splitlines() != ['two words', '$(touch unintended-launch)', 'literal;value']:
                raise AssertionError('Startup arguments were split or expanded')
            if (directory / 'unintended-launch').exists():
                raise AssertionError('A startup argument was executed as shell code')
            h.probe(directory, rcon, 'verify-persist')
            rcon.command('stop')
            wait_for(lambda: (directory / 'child-exit.txt').exists(), 60, 'Restarted JVM did not stop normally')
            if (directory / 'child-exit.txt').read_text().strip() != '0':
                raise AssertionError('Restarted JVM exited unsuccessfully')
            h.verify_console_log(directory / 'console-child.log', 'stock')
        result = {'mode': mode, 'first_jvm': first, 'passed': True, 'saved_data_groups': 3}
        (directory / 'restart-proof.json').write_text(json.dumps(result, indent=2))
        print('SERVER_OPERATIONS_RESTART_PASS ' + json.dumps(result), flush=True)
        return result
    finally:
        try:
            if original is not None and output is not None:
                h.stop(original, output)
        finally:
            clean_children(directory)


def main():
    java = shutil.which('java')
    if not java:
        raise FileNotFoundError('Java 25 is required')
    results = []
    failures = []
    for mode in ['api', 'spigot', 'command']:
        try:
            results.append(one(mode, java))
        except Exception as failure:
            failures.append({'mode': mode, 'error': repr(failure)})
            print('SERVER_OPERATIONS_RESTART_FAIL ' + json.dumps(failures[-1]), flush=True)
    (ROOT / 'build/server-operations/results.json').write_text(json.dumps({'passed': results, 'failed': failures}, indent=2))
    if failures:
        raise AssertionError(failures)
    print('SERVER_OPERATIONS_RESTART_ALL_PASS', flush=True)


if __name__ == '__main__':
    main()
