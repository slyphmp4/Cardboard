# Cardboard Paper/Bukkit Compatibility Matrix

Point 4 validates Cardboard 26.2 against real Bukkit/Paper API behaviour and real plugins. A failing Cardboard result is not automatically classified as a Cardboard bug: reproduce it minimally, run the same probe against reference Paper 26.2, then classify the divergence.

## Result states

- `PASS` — observed behaviour matches the probe contract.
- `FAIL` — the probe contract failed and requires investigation.
- `SKIP` — the environment cannot exercise the check (for example, an optional real plugin is not installed).
- `UNSUPPORTED` — Cardboard deliberately does not implement the API/behaviour yet.
- `PAPER_DIFFERENCE` — a confirmed behaviour difference from reference Paper 26.2.

## Runtime probe

Build the standalone probe with JDK 25:

```bash
./gradlew -p tools/compatibility/plugin clean build --no-daemon --stacktrace
```

Deploy `tools/compatibility/plugin/build/libs/cardboard-compat-probe-0.1.0.jar` to the server `plugins/` directory, restart the disposable compatibility server, then run through RCON:

```text
cardboardcompat run
cardboardcompat status
```

The first baseline covers server/plugin-manager access, RCON `CommandSender`, permissions, scheduler main/async execution, world/block/chunk access, inventories, ItemMeta/PDC, plugin config save/reload, temporary entity spawn/remove, synchronous entity/creature/projectile events, and enable-state checks for PlaceholderAPI, UltraPermissions, and CoreProtect when present.

The entity probes spawn temporary entities at the first world's spawn and remove them immediately. Run Point 4 on a disposable/test server rather than a production world.

## Investigation rule

For every `FAIL`:

1. Reduce to the smallest public Bukkit/Paper API call that still fails.
2. Run that same probe jar and scenario on reference Paper 26.2 where possible.
3. If Paper behaves the same, fix the test expectation instead of Cardboard.
4. If Paper differs, record `PAPER_DIFFERENCE`, implement the Cardboard fix, and add a regression test before changing the matrix to `PASS`.

## Point 4 baseline result

Point 4 is complete for the primary Cardboard 26.2 compatibility scope.

- Wave 1: 22/22 PASS.
- Wave 2A: 40/40 PASS.
- Wave 2B: 57/57 PASS.
- Wave 2C: 13/13 PASS after comparison with exact Paper 26.2 build 110.
- Wave 2D: 13/13 PASS after comparison with exact Paper 26.2 build 110.
- Wave 3: 14/14 PASS against CoreProtect 24.0, PlaceholderAPI 2.12.3, and UltraPermissions 5.6.8.
- Confirmed Cardboard/Paper behaviour differences in the tested scope: 0.

The initial Wave 2C and Wave 2D save-policy failures were reproduced
identically on Paper 26.2 build 110 and were therefore classified as probe
expectation errors rather than Cardboard incompatibilities.

The machine-readable summary is stored in `tools/compatibility/matrix.json`.
