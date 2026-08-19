# Cardboard post-release torture harness

This directory is the first post-release hardening stage for Cardboard 26.2.
It is intentionally isolated from production sources so `ver-26.2-release`
can stay frozen while the released JAR is stress-tested.

## What this tests

Two workloads run at the same time:

1. `run.py` creates concurrent Minecraft STATUS handshakes/requests, checks the
   returned status packet, samples server RSS on Linux, scans new server log
   lines for fatal/error patterns, and writes a JSON PASS/FAIL report.
2. `CardboardTorture` is a Bukkit/Paper helper plugin that continuously stresses
   chunk load/unload requests, entity/projectile creation and removal,
   spawn/chunk event dispatch, ItemMeta/PDC, inventory creation/mutation, and
   both sync and async scheduler paths.

The STATUS swarm is deliberately not called a player-bot swarm: it exercises
real Minecraft packet parsing and connection churn but does not authenticate or
simulate player movement. A protocol-capable 26.2 player driver can be added as
another workload without changing the report/gate layer.

## Profiles

- `smoke`: 3 minutes, 10 concurrent workers, 2 new workers/s.
- `burst`: 15 minutes, 100 concurrent workers, 20 new workers/s.
- `soak`: 6 hours, 50 concurrent workers, 5 new workers/s.

Default gates:

- connection failures <= 5%
- malformed/missing status responses <= 1%
- RSS growth <= 1024 MiB when `--pid` is supplied
- zero newly logged ERROR/FATAL/exception/watchdog lines

Thresholds live in `scenarios.toml`; keep any change to them explicit in test
results so a regression cannot be hidden by silently relaxing a gate.

## Build the helper plugin

From the Cardboard repository root:

### Linux/macOS

```bash
./gradlew -p tools/torture/plugin clean build
```

### Windows

```bat
gradlew.bat -p tools\torture\plugin clean build
```

The JAR is produced under:

```text
tools/torture/plugin/build/libs/cardboard-torture-plugin-1.0.0.jar
```

Copy it into the test server's Bukkit/Cardboard plugin directory and restart the
server. Do not run this workload on a production world.

## Run order

Use a disposable copy of the world and the same released Cardboard 26.2 JAR for
all three stages.

### 1. Smoke

Server console:

```text
cardboardtorture start 180
```

Harness machine:

```bash
python3 tools/torture/run.py smoke \
  --host 127.0.0.1 \
  --port 25565 \
  --pid <JAVA_PID> \
  --log /path/to/server/logs/latest.log
```

Do not continue if smoke fails. Inspect the JSON report and matching server log.

### 2. Burst

Server console:

```text
cardboardtorture start 900
```

```bash
python3 tools/torture/run.py burst \
  --host 127.0.0.1 \
  --port 25565 \
  --pid <JAVA_PID> \
  --log /path/to/server/logs/latest.log
```

### 3. Soak

Server console:

```text
cardboardtorture start 21600
```

```bash
python3 tools/torture/run.py soak \
  --host 127.0.0.1 \
  --port 25565 \
  --pid <JAVA_PID> \
  --log /path/to/server/logs/latest.log
```

Reports are written to `tools/torture/reports/` and are git-ignored.

## Test discipline

For comparable results, record:

- Cardboard JAR SHA256
- Cardboard commit/tag
- Java version and JVM flags
- Fabric Loader/Fabric API versions
- installed mods/plugins
- C2ME version/config if present
- CPU/RAM and OS
- scenario configuration
- beginning/end RSS
- complete server log

A run is valid only when the exact released Cardboard JAR remains unchanged.
If a Cardboard code fix is made, start a new run series and keep the failing
report that motivated the fix.

## Cleanup

The helper plugin tracks the entities it creates and removes them when the test
stops or the plugin disables. It does not intentionally modify blocks. Chunk
loads may generate terrain, which is why a disposable test world is required.

Commands:

```text
/cardboardtorture status
/cardboardtorture stop
```

## Next hardening additions

After this harness has produced clean smoke/burst/soak baselines, extend stage 1
with a protocol-capable 26.2 player driver for authenticated join/quit,
movement, teleport/dimension, inventory click/drag, chat/commands and death/
respawn cycles. The report format and health gates in `run.py` should remain the
single source of truth so the same suite can be moved into CI in stage 2.
