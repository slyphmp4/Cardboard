# Cardboard post-release torture harness

This directory contains Point 1 of the Cardboard 26.2 hardening roadmap:
post-release torture testing and long-term stability. Point 1 is complete, and
the harness remains intentionally isolated from the production sources. Normal
CI protects its build, unit tests, Python imports, and checked-in scenario
configuration without starting a Minecraft server or repeating a long soak.

Run the harness only against a disposable world. For empty-server testing,
`pause-when-empty-seconds=0` is mandatory. The Bukkit scheduler is not considered
broken; the earlier empty-server stalls were caused by Minecraft pausing the
server when this setting was non-zero.

## What runs

There are two independent layers:

1. `run.py` creates concurrent Minecraft STATUS handshakes and requests, checks
   every response, records RSS as a diagnostic, measures heap use after explicit
   Full GC at both run boundaries, scans only newly appended server log lines for
   fatal/error patterns, and writes a JSON PASS/FAIL report.
2. `CardboardTorture` runs a selected Bukkit/Paper workload inside the server.
   Normal stability work is split into independent scheduler, API data, entity,
   and chunk lifecycle tasks so one subsystem cannot accidentally turn the run
   into a world-generation/entity-storage saturation benchmark.

The STATUS swarm exercises real Minecraft packet parsing and connection churn.
It does not authenticate or simulate player movement, so it is not described as
a player-bot swarm.

## Helper plugin commands and profiles

The start syntax is:

```text
/cardboardtorture start [seconds] [stability|scheduler|api|entity|chunks|saturation]
```

Duration and profile may be given in either order. These are equivalent:

```text
/cardboardtorture start 300 entity
/cardboardtorture start entity 300
```

The default duration is 300 seconds and the default profile is `stability`.
The legacy command `/cardboardtorture start 300` therefore remains a five-minute
stability run. `stability` and `chunks` require at least 60 seconds so their
bounded lifecycle probes have a complete unload-event drain window; the other
profiles retain the general 10-second minimum.

Profiles:

- `stability`: scheduler, API data, entity, and bounded chunk lifecycle tasks,
  each on its own schedule. This is the normal correctness/stability profile.
- `scheduler`: sync and async Bukkit scheduler counters only.
- `api`: ItemStack, ItemMeta, NamespacedKey, PDC, inventory construction, and
  mutation only.
- `entity`: temporary armor stands, zombies, and snowballs only. Every entity is
  spawned inside one reserved pregenerated primary-world chunk. `prepare entity`
  loads and pins that chunk, then lets it settle for 100 server ticks before the
  measured run; targeted spawn events and removal/cleanup are checked.
- `chunks`: chunk lifecycle only, with no entity spawning. It rotates through a
  bounded pregenerated 8x8 pool per loaded Bukkit world, loads with
  `generate=false`, observes the exact target ChunkLoadEvent, requests unload,
  then independently waits for the exact target ChunkUnloadEvent and for the
  chunk to become unloaded. The unload listener also enforces Paper's event-time
  accessibility contract (`isChunkLoaded`, load level, direct chunk data, and a
  read-only block lookup). Multiple bounded probes may await C2ME concurrently;
  each has a 40-second wall-clock timeout and normal runs reserve the final 45
  seconds for drain.
- `saturation`: explicit opt-in continuous remote chunk generation. TPS
  degradation is expected in this profile; it is a generation/saturation
  benchmark and must not be judged as a normal stability failure.

Other commands:

```text
/cardboardtorture prepare chunks
/cardboardtorture prepare entity
/cardboardtorture status
/cardboardtorture stop
/cardboardtorture profiles
```

`prepare chunks` is required before `stability` or `chunks`. It generates only
the bounded 8x8 pool for every loaded Bukkit world, one target per server tick,
then requests unload. Preparation is deliberately outside the measured run.
Monitor `preparingChunks` with `status` and do not start the measured workload
until it is `false`. A normal chunk run refuses to start if any target is still
unprepared; it never silently generates missing terrain.

`prepare entity` is required before `stability` or `entity`, after chunk-pool
preparation. It loads one reserved target in the primary world, attaches a plugin
chunk ticket, computes the safe spawn location, and waits 100 server ticks so
chunk/entity-storage activation is outside the measurement window. Start only
when `status` reports `preparingEntity=false entityPrepared=true`. The ticket is
removed and its absence is verified during workload cleanup, after which the
chunk is eligible to unload normally.

## Build and deploy the helper plugin

From the Cardboard repository root:

### Linux/macOS

```bash
./gradlew -p tools/torture/plugin clean build --stacktrace
```

### Windows

```bat
gradlew.bat -p tools\torture\plugin clean build --stacktrace
```

The build runs the workload-plan regression tests and produces:

```text
tools/torture/plugin/build/libs/cardboard-torture-plugin-1.0.0.jar
```

On the current VPS the repository is `/home/ubuntu/Cardboard`, the server data
directory is `/opt/minecraft/server`, the Compose project is
`/opt/minecraft/compose.yml`, and the container is `minecraft`. Build and deploy
with:

```bash
cd /home/ubuntu/Cardboard
sudo ./gradlew -p tools/torture/plugin clean build --stacktrace
sudo install -m 0644 \
  tools/torture/plugin/build/libs/cardboard-torture-plugin-1.0.0.jar \
  /opt/minecraft/server/plugins/cardboard-torture-plugin-1.0.0.jar
sha256sum \
  tools/torture/plugin/build/libs/cardboard-torture-plugin-1.0.0.jar \
  /opt/minecraft/server/plugins/cardboard-torture-plugin-1.0.0.jar
cd /opt/minecraft
docker compose restart mc
```

Wait for the server to become healthy and confirm that `CardboardTorture` loaded
before preparing chunks. Do not deploy this plugin to a production world.

## External scenarios and gates

The STATUS runner profiles in `scenarios.toml` are separate from the helper
plugin profiles:

- `smoke`: 3 minutes, 10 concurrent workers, 2 new workers/s.
- `burst`: 15 minutes, 100 concurrent workers, 20 new workers/s.
- `soak`: 6 hours, 50 concurrent workers, 5 new workers/s.

Default gates are:

- connection failures <= 5%;
- malformed/missing status responses <= 1%;
- heap-after-Full-GC growth <= 512 MiB, measured with the host JDK's `jcmd`;
- the configured duration completes naturally and at least one request runs;
- no launcher/worker task fails and the requested server log remains readable;
- zero newly logged ERROR/FATAL/exception/watchdog lines.

The heap gate is fail-closed: `--pid` must identify the host Java process,
`jcmd` must attach, explicit GC must be enabled, and the PID must still identify
the same process at the final sample. Its `/proc/<pid>/cmdline` must also look
like a Java Minecraft/Fabric server launch, preventing an unrelated JVM from
being measured accidentally. RSS remains in the report as a diagnostic
because G1 with equal `-Xms`/`-Xmx` keeps the heap committed. A legacy scenario
containing only `max_process_rss_growth_mb` still selects the old RSS gate.
`--memory-gate off` is permitted only for an explicitly intentional network-only
run. Thresholds live in `scenarios.toml`; do not silently relax or omit a gate.
This gate detects retained Java-heap growth; it does not by itself exclude a
native/off-heap leak. A native-memory acceptance claim requires restarting the
JVM with Native Memory Tracking enabled and adding an explicit NMT gate.

## Exact five-minute acceptance workflow

Prepare the bounded chunks before the measured run. This preparation may
generate terrain and is intentionally not covered by the validation timer:

```bash
cd /home/ubuntu/Cardboard

grep -qx 'pause-when-empty-seconds=0' \
  /opt/minecraft/server/server.properties

docker exec minecraft rcon-cli "cardboardtorture stop"
docker exec minecraft rcon-cli "cardboardtorture prepare chunks"

while docker exec minecraft rcon-cli "cardboardtorture status" \
    | grep -q 'preparingChunks=true'; do
  sleep 2
done

docker exec minecraft rcon-cli "cardboardtorture status"
docker exec minecraft rcon-cli "cardboardtorture prepare entity"

while docker exec minecraft rcon-cli "cardboardtorture status" \
    | grep -q 'preparingEntity=true'; do
  sleep 2
done

ENTITY_STATUS=$(docker exec minecraft rcon-cli "cardboardtorture status")
printf '%s\n' "$ENTITY_STATUS"
printf '%s\n' "$ENTITY_STATUS" \
  | grep -q 'preparingEntity=false entityPrepared=true'
```

Obtain the host PID of the Java child, not Docker's init/`mc-server-runner` PID.
Start the STATUS runner first and leave it in the background. Wait for its
`HARNESS_READY memoryGate=heap` marker: at that point it has recorded its log
offset, verified the Java process identity, and completed the baseline Full-GC
heap measurement. The runner lasts 315 seconds so it also observes workload
shutdown and cleanup after the 300-second stability profile ends.

```bash
cd /home/ubuntu/Cardboard

JAVA_PID=$(docker top minecraft -eo pid,args \
  | awk 'NR > 1 && $2 == "java" { print $1; exit }')
test -n "$JAVA_PID"

sudo install -d -o ubuntu -g ubuntu -m 0755 tools/torture/reports
VALIDATION_CONSOLE="tools/torture/reports/validation-$(date +%Y%m%d-%H%M%S).log"

python3 tools/torture/run.py smoke \
  --duration 315 \
  --host 127.0.0.1 \
  --port 25565 \
  --pid "$JAVA_PID" \
  --jcmd /usr/bin/jcmd \
  --log /opt/minecraft/server/logs/latest.log \
  >"$VALIDATION_CONSOLE" 2>&1 &
RUNNER_PID=$!

for _ in $(seq 1 30); do
  grep -q '^HARNESS_READY memoryGate=heap' "$VALIDATION_CONSOLE" && break
  if ! kill -0 "$RUNNER_PID" 2>/dev/null; then
    wait "$RUNNER_PID" || true
    cat "$VALIDATION_CONSOLE"
    exit 1
  fi
  sleep 1
done
grep -q '^HARNESS_READY memoryGate=heap' "$VALIDATION_CONSOLE"

START_RESPONSE=$(docker exec minecraft rcon-cli \
  "cardboardtorture start 300 stability")
printf '%s\n' "$START_RESPONSE"

if ! printf '%s\n' "$START_RESPONSE" \
    | grep -q 'profile stability started for 300 seconds'; then
  kill "$RUNNER_PID"
  wait "$RUNNER_PID" || true
  cat "$VALIDATION_CONSOLE"
  exit 1
fi

if wait "$RUNNER_PID"; then
  RUNNER_RC=0
else
  RUNNER_RC=$?
fi

cat "$VALIDATION_CONSOLE"
docker exec minecraft rcon-cli "cardboardtorture status"
test "$RUNNER_RC" -eq 0
```

Acceptance requires all of the following:

- the runner prints `RESULT   : PASS`, with every attempt successful, zero
  connection/protocol failures, and zero new runtime error lines;
- the `heap_after_full_gc_growth` gate passes; raw RSS is diagnostic only;
- the helper reports `running=false`, `profile=stability`, zero workload/API/
  entity/chunk failures, zero pending/aborted chunk probes, zero rejected or
  timed-out unloads, and zero unexpectedly generated normal chunks;
- sync and async scheduler counters remain nearly identical;
- API, entity, target entity-event, chunk load/unload-event, and unload-verified
  counters all show useful work;
- normal chunk attempts, accepted loads, exact load events, unload requests,
  accepted unloads, exact unload events, unload access checks, and verified
  unloads are equal and non-zero; unload access failures are zero;
- spawned and retired entity counts match, `trackedEntities=0`,
  `entityAnchors=0`, `entityTickets=0`, and no helper tasks remain (`removed`
  counts plugin removals while
  `alreadyInvalid` records projectiles/entities that retired naturally first);
- saturation counters remain zero.

If the runner is interrupted, stop and clean up the helper explicitly:

```bash
docker exec minecraft rcon-cli "cardboardtorture stop"
```

Do not start another six-hour soak after this command block. First review the
JSON report, the complete server log, and all helper counters and obtain Point 1
acceptance.

## Point 1 short acceptance (2026-08-20)

The refactored harness and the corrected logical chunk lifecycle bridge passed
the required short validation on the production VPS with Fabric Loader 0.19.3,
Fabric API 0.158.0+26.2, C2ME enabled, Temurin 25, and
`pause-when-empty-seconds=0`.

Deployed artifacts:

```text
/opt/minecraft/server/mods/Cardboard-26.2.jar
SHA256 439b741a421e3a1c2e2314897a856ffd2ff9a44fc8eb6ded38314fcd032923ca

/opt/minecraft/server/plugins/cardboard-torture-plugin-1.0.0.jar
SHA256 8a9ef2e25d2e3495f1ad9edebd44e2aac3754e4fb482c2d13d0b7a3f8f2926cb
```

The isolated 90-second `chunks` profile plus a 105-second STATUS runner passed:

```text
5039/5039 successful STATUS requests
0 connection failures, 0 protocol failures, 0 runtime error lines
heap after Full GC: 404.5 -> 414.4 MiB (+9.8 MiB)
RSS diagnostic: 5139.9 -> 5146.1 MiB (+6.2 MiB)

attempts=45 loadAccepted=45 targetLoadEvents=45
unloadRequests=45 unloadAccepted=45 targetUnloadEvents=45
accessChecks=45 accessFailures=0 unloadVerified=45
pending=0 aborted=0 failures=0
```

Its runner report is
`/home/ubuntu/Cardboard/tools/torture/reports/smoke-20260820-135717.json`.

The final 300-second `stability` profile plus a 315-second STATUS runner also
passed:

```text
15297/15297 successful STATUS requests
0 connection failures, 0 protocol failures, 0 runtime error lines
heap after Full GC: 415.3 -> 496.9 MiB (+81.6 MiB; limit +512 MiB)
RSS diagnostic: 5157.1 -> 5629.7 MiB (+472.6 MiB)

durationMs=300957 sync=6020 async=6020 (~20.00 TPS)
API iterations=6020 inventories=6020 items=325080 failures=0
entities attempts=3311 spawned=3311 removed=3311 retired=3311 failures=0
target entity events=3311 creature=1806 projectile=1505
chunk attempts/loadAccepted/loadEvents/unloadRequests/unloadAccepted/
  unloadEvents/accessChecks/unloadVerified=256 for every field
accessFailures=0 pending=0 aborted=0 rejected=0 timeouts=0
trackedEntities=0 auxiliaryTasks=0 entityAnchors=0 entityTickets=0
saturation attempts=0 workload failures=0
```

The stability runner report is
`/home/ubuntu/Cardboard/tools/torture/reports/smoke-20260820-140311.json`.
There were no `Can't keep up`, ERROR/FATAL, exception, watchdog, or torture
failure lines during the measured profile. Four non-failing vanilla connection
teardown warnings (`handleDisconnection() called twice`) occurred under the
STATUS connection swarm and did not correspond to a failed request. The server
remained healthy after cleanup.

This is the short acceptance required before deciding whether to schedule a new
long soak. It does not authorize Point 2 work, and no new six-hour run was
started.

## Historical Point 1 results

The pre-refactor combined workload established these network baselines:

- smoke: PASS, 8,664/8,664 successful STATUS requests, zero connection,
  protocol, or runtime errors;
- burst: PASS, 377,943/377,943 successful STATUS requests, zero connection,
  protocol, or runtime errors;
- six-hour soak: the server survived 21,601.5 seconds and completed
  5,093,135/5,093,135 requests with zero connection, protocol, or runtime
  errors. Internal scheduler/entity/chunk work also completed and all tracked
  entities were cleaned up.

The final pre-refactor helper status from that soak was:

```text
sync=264492 async=264493 trackedEntities=0
events entity=145470 creature=79347 projectile=66123
events chunkLoad=226952 chunkUnload=226962
chunks attempts=13225 unloadRequests=13226 unloadVerified=13226
```

Those counters are retained as historical evidence, not as targets for the new
isolated profiles, whose workload-specific status fields are more precise.

The soak report was marked FAIL only by the raw RSS gate:

```text
5637.3 MiB -> 6881.7 MiB (+1244.4 MiB), configured limit +1024 MiB
```

That historical result is not evidence of a Java heap leak for the tested
`-Xms7G -Xmx7G` configuration. Heap use was about 1.26 GiB before explicit Full
GC and about 461 MiB afterwards, while RSS stayed high because G1 kept the
seven-gigabyte heap committed. Preserve the old failed report. The current
runner still records RSS, but its configured stability gate compares heap use
after explicit Full GC at the beginning and end instead of treating committed
address space as a leak. Native Memory Tracking remains unavailable unless the
JVM is restarted with NMT enabled and is not silently substituted or skipped.

Historical reports are under `tools/torture/reports/` and are git-ignored. The
consolidated VPS report is `/home/ubuntu/Cardboard/soak-final-report.txt`.

## Test discipline

For every accepted run, record:

- Cardboard JAR SHA256 and commit/tag;
- helper plugin JAR SHA256;
- Java version and JVM flags;
- Fabric Loader and Fabric API versions;
- installed mods/plugins and C2ME version/config;
- CPU/RAM and OS;
- scenario and helper profile configuration;
- beginning/end memory measurements;
- complete runner JSON and server log;
- final `/cardboardtorture status` output.

A run is valid only while the exact Cardboard JAR remains unchanged. If a
Cardboard compatibility fix is made, add its regression test, rebuild, and begin
a new run series while retaining the failing report that motivated the fix.

Before deploying harness changes, run both regression suites:

```bash
./gradlew -p tools/torture/plugin clean build --no-daemon --stacktrace
python3 -m py_compile tools/torture/run.py tools/torture/tests/test_run.py
python3 -m unittest discover -s tools/torture/tests -p 'test_*.py' -v
```

These are the same hermetic checks used by GitHub Actions. The checked-in
`scenarios.toml` profiles and limits are parsed and validated by the Python
suite. Live STATUS traffic, RCON commands, JVM memory probes, helper counters,
and server-log acceptance remain manual because they require an isolated
Minecraft deployment; CI never connects to the production VPS.
