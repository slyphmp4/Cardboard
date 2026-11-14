#!/usr/bin/env python3
"""Run public API probes on a checksum-pinned Paper control server."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
CASES = {
    'ray-legacy-location-blocks', 'ray-position-block-filters-and-fluids',
    'ray-entities-combined-nearest-and-radius', 'ray-builder-targets-modes-and-input-copies',
    'line-of-sight-blocked-clear-world-and-range', 'ray-validation-and-empty-result-contracts',
    'drop-natural-callback-before-spawn-event', 'drop-cancellation-all-overloads',
    'drop-natural-centered-jitter-and-input-copy', 'drop-exact-location-defaults-and-single-event',
    'drop-callback-failure-and-null-validation',
}


def main():
    directory = ROOT / 'build/paper-ray-reference'
    # Never reuse an existing world, including a user-supplied server directory.
    directory.mkdir(parents=True, exist_ok=False)
    lock = json.loads((ROOT / 'tools/ci/paper-ray-reference.lock.json').read_text())
    request = urllib.request.Request(lock['url'], headers={'User-Agent': 'Cardboard-ray-reference-CI'})
    with urllib.request.urlopen(request, timeout=60) as response:
        content = response.read()
    if hashlib.sha256(content).hexdigest() != lock['sha256']:
        raise AssertionError('Paper reference checksum mismatch')
    (directory / 'paper.jar').write_bytes(content)
    (directory / 'plugins').mkdir()
    shutil.copy2(ROOT / 'tools/port263-fixture/build/libs/CardboardPaperRayReference.jar', directory / 'plugins')
    (directory / 'eula.txt').write_text('eula=true\n')
    flat = {'biome': 'minecraft:plains', 'layers': [
        {'block': 'minecraft:bedrock', 'height': 1}, {'block': 'minecraft:dirt', 'height': 2},
        {'block': 'minecraft:grass_block', 'height': 1}]}
    (directory / 'server.properties').write_text(
        'online-mode=false\nserver-ip=127.0.0.1\nserver-port=0\nwhite-list=true\n'
        'level-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\n'
        'view-distance=2\nsimulation-distance=2\npause-when-empty-seconds=0\n'
        f'generator-settings={json.dumps(flat, separators=(",", ":"))}\n')
    java = shutil.which('java')
    if not java:
        raise FileNotFoundError('Java 25 is required')
    with (directory / 'console.log').open('w', encoding='utf-8') as output:
        process = subprocess.run([java, '-Xms256m', '-Xmx1500m', '--enable-native-access=ALL-UNNAMED',
                                  '-Dcardboard.paperRayReference=true', '-jar', 'paper.jar', 'nogui'],
                                 cwd=directory, stdout=output, stderr=subprocess.STDOUT, timeout=300)
    if process.returncode != 0:
        raise AssertionError(f'Paper exited with status {process.returncode}')
    result = directory / 'plugins/CardboardPaperRayReference/results.json'
    values = json.loads(result.read_text())
    if set(values) != CASES or any(value != 'PASS' for value in values.values()):
        raise AssertionError(values)
    (directory / 'success.json').write_text(json.dumps({'passed': True, 'cases': len(values), 'paper_build': lock['paper_build']}))
    print(f'PAPER_API_REFERENCE_PASS {len(values)} cases on Paper {lock["minecraft"]} build {lock["paper_build"]}', flush=True)


if __name__ == '__main__':
    main()
