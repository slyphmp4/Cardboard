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
    'tree-basic-overloads-and-seeded-random', 'tree-consumer-buffered-snapshots-and-edits',
    'tree-predicate-veto-and-filtered-placement', 'tree-legacy-block-change-delegate',
    'tree-modern-types-and-tile-snapshot-data', 'tree-null-callbacks-exceptions-and-blocked-origin',
    'explosion-block-cancellation-and-overload-results', 'explosion-entity-source-and-mutable-block-list',
    'explosion-block-interactions-and-source-null-drop-decay', 'explosion-source-first-overloads-and-damage-exclusion',
    'explosion-yield-and-ignite-cancellation', 'explosion-nested-cancellation-and-location-contract',
    'explosion-tnt-chain-prime-cancellation',
    'legacy-biome-search-overloads', 'poi-nearest-type-radius-and-occupancy',
    'poi-list-filter-and-snapshot-isolation', 'poi-search-validation-and-predicate-errors',
    'template-manager-native-cache-and-snapshots', 'template-fill-palettes-tiles-and-copy-isolation',
    'template-stream-and-file-pdc-roundtrip', 'template-keyed-load-save-and-delete-contracts',
    'template-native-placement-rotation-mirror-and-integrity', 'template-captured-entities-and-placement-flags',
    'template-block-and-entity-transformer-contracts', 'template-validation-and-transformer-failure-cleanup',
    'template-entity-spawn-events-and-cancellation', 'template-region-spawn-queue-and-failure',
    'raid-pdc-stable-container-and-independent-raids', 'raid-pdc-arrays-nested-copy-and-byte-contracts',
    'raid-pdc-native-nbt-codec-roundtrip', 'raid-pdc-old-empty-malformed-nbt-and-validation',
    'world-view-distance-aliases-validation-and-isolation', 'world-void-settings-roundtrip-and-isolation',
    'world-void-native-damage-threshold-and-cancellation', 'world-void-disabled-and-enabled-nonliving-removal',
    'living-damage-cooldown-native-getter-setter',
    'world-note-default-contracts', 'world-classic-particle-validation-and-inputs',
    'player-personal-time-default-relative-fixed-reset-and-isolation',
    'player-personal-weather-default-no-override', 'player-sleeping-ignored-flags-and-native-status',
    'server-command-alias-startup-config', 'server-command-alias-values-defensive-order',
    'server-command-alias-expansion-and-replacement', 'server-command-alias-failed-reload-contract',
    'brigadier-all-argument-factories', 'brigadier-values-ranges-and-validation',
    'brigadier-position-rotation-and-source-resolution', 'brigadier-entity-player-and-profile-resolvers',
    'brigadier-block-item-components-and-predicates', 'brigadier-registry-world-and-component-conversions',
    'brigadier-custom-conversion-and-message-resolution', 'brigadier-command-source-context-and-identity',
    'dialog-inline-types-inputs-and-actions', 'dialog-builder-validation-and-defensive-lists',
    'click-callback-service-uses-expiry-and-reentrancy', 'dialog-callback-response-and-invalid-payload',
    'click-and-dialog-callback-native-tick-queue',
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
    (directory / 'commands.yml').write_text('aliases:\n  port263boot_alias: ["version $1-"]\n')
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

