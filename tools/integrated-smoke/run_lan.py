"""Run two Minecraft clients and a dedicated LuckPerms server; require successful exits."""
import json
import os
from pathlib import Path
import subprocess
import time

root = Path(__file__).resolve().parents[2]
launches = json.loads((root / 'build/integrated-lan-launch.json').read_text())
exchange = root / 'build/integrated-lan-exchange'
if exchange.exists() or (root / 'run-integrated-smoke/integrated-smoke.success').exists():
    raise SystemExit('Use fresh disposable run directories for this smoke')
logs = [root / 'integrated-lan-host.log', root / 'integrated-lan-guest.log', root / 'integrated-lan-luckperms-server.log']
processes = []
handles = []
try:
    for launch, log in zip(launches, logs):
        handle = log.open('w', encoding='utf-8')
        handles.append(handle)
        processes.append(subprocess.Popen(launch['command'], cwd=launch['cwd'], stdout=handle,
                                          stderr=subprocess.STDOUT, start_new_session=True))
    deadline = time.monotonic() + 900
    next_progress = time.monotonic() + 30
    while any(process.poll() is None for process in processes):
        if any(process.poll() not in (None, 0) for process in processes):
            raise RuntimeError(f'Client failed: {[process.poll() for process in processes]}')
        if time.monotonic() > deadline:
            raise TimeoutError('LAN smoke exceeded 15 minutes')
        if time.monotonic() > next_progress:
            markers = sorted(path.name for path in exchange.glob('*'))
            print(f'LAN smoke progress: markers={markers}, exits={[process.poll() for process in processes]}', flush=True)
            next_progress = time.monotonic() + 30
        time.sleep(1)
    if any(process.returncode != 0 for process in processes):
        raise RuntimeError(f'Client exit codes: {[process.returncode for process in processes]}')
    host = logs[0].read_text(errors='replace')
    guest = logs[1].read_text(errors='replace')
    for marker in ['INTEGRATED_SMOKE_PASS', 'INTEGRATED_LAN_HOST_PASS 1', 'INTEGRATED_LAN_HOST_PASS 2']:
        if marker not in host:
            raise AssertionError(f'Missing host marker: {marker}')
    if 'INTEGRATED_LAN_GUEST_PASS' not in guest:
        raise AssertionError('Missing guest PASS marker')
    dedicated = logs[2].read_text(errors='replace')
    if 'LUCKPERMS_FABRIC_PASS SmokeGuest' not in dedicated or 'DEDICATED_LUCKPERMS_PASS' not in dedicated:
        raise AssertionError('Missing dedicated LuckPerms PASS markers')
    if not (exchange / 'guest.success').is_file() or not (exchange / 'dedicated.success').is_file() or not (root / 'run-integrated-smoke/integrated-smoke.success').is_file():
        raise AssertionError('Missing client success files')
    print('INTEGRATED_LAN_LUCKPERMS_PASS: both clients and dedicated server exited normally', flush=True)
finally:
    for process in processes:
        if process.poll() is None:
            # Only these disposable client process groups are owned by this harness.
            os.killpg(process.pid, 15)
    for handle in handles:
        handle.close()
    for log in logs:
        if log.exists():
            for line in log.read_text(errors='replace').splitlines():
                if any(marker in line for marker in ['INTEGRATED_', 'DEDICATED_', 'LUCKPERMS_FABRIC_']):
                    print(line, flush=True)
            print(f'--- {log.name} (last 100 lines) ---', flush=True)
            print('\n'.join(log.read_text(errors='replace').splitlines()[-100:]), flush=True)
