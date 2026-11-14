# Minecraft 26.3 hardening

This is an alpha port on `codex/port-minecraft-26.3`, tracked by
[PR #58](https://github.com/slyphmp4/Cardboard/pull/58). Passing these gates is
bounded evidence, not a guarantee of complete Paper API parity or compatibility
with every Fabric mod/plugin pack.

## Reproduced and corrected

* `World.isChunkGenerated` compared a `CompletableFuture` with null, reporting
  missing terrain as generated. It now inspects loaded/saved FULL status, also
  accepting old FULL chunks which Minecraft will upgrade. `loadChunk(false)`
  refuses missing/partial terrain without generating it.
* Cached enum registries omitted injected mod entities/materials. Registration
  now publishes a new immutable map in the existing registry, preserving cached
  registry references, iteration and key lookup. Mod materials retain their real
  namespace and convert to the correct ItemType/BlockType. Native item/block
  conversion no longer relies on a stale Material registry snapshot.
* Spawning a mod EntityType through Bukkit incorrectly attempted to instantiate
  the generic `Entity` interface. The explicit native type is now used, through
  the existing spawn-event/addition path. Vanilla spawn paths are preserved.
* Public component factories failed because their ServiceLoader registrations
  were missing. The actual supported providers are registered; the component
  logger and missing bed/attribute/blocking bridges are implemented.
* `CraftItemStack.serializeAsBytes` was inherited from an API implementation
  which delegates back to a Craft stack, causing a null delegate exception.
  The Craft implementation now writes the native item codec with DataVersion.
* Sign text builders shared their mutable line lists with previously built
  values, and edits left filtered lines stale. Built values now snapshot both
  variants; editing one line preserves other filtered lines. The same aliasing
  defect was reproduced on official Paper 26.3 build 249.
* `reloadData`/`resetRecipes` did nothing. They now use native resource reload,
  discover enabled packs, and report completion/failure. Successful Fabric data
  reloads rebuild native/plugin command registrations instead of silently losing
  plugin commands with the replaced dispatcher. The plugin API emits one
  `ServerResourcesReloadedEvent` with PLUGIN cause.

## Repeatable gates

With Java 25 and Python 3.11+ on Linux:

```sh
./gradlew build --no-daemon --max-workers=2
./gradlew -p tools/port263-fixture build --no-daemon --max-workers=2
./gradlew -p tools/compatibility/plugin build --no-daemon --max-workers=2
./gradlew -p tools/torture/plugin build --no-daemon --max-workers=2
python3 -m unittest discover -s tools/ci/tests -v
python3 tools/ci/port263_hardening.py --stack stock --directory build/audit-stock --duration 300
python3 tools/ci/port263_hardening.py --stack performance --directory build/audit-performance --duration 300
```

Use fresh disposable directories; never point this fixture at a player world.
It accepts the EULA for its disposable test server, binds to loopback and uses a
random RCON password. Credentials and world files are excluded from CI artifacts.

The runner boots a standalone Fabric launcher with the **production Cardboard
JAR**, not Loom's development classpath. An independently compiled Fabric mod
registers an entity, block/item and custom persistent data component. The separate
Paper API plugin verifies factories, chunk queries, item edits/serialization,
mod registries/spawning, recipe reset and its completion event. It also verifies
commands after plugin/native resource reloads and saves/reopens a real world to
check mod entity/block/item-component persistence.

The inherited compatibility matrix expects 57 cases (54 PASS and three explicit
SKIP for absent PlaceholderAPI, UltraPermissions and CoreProtect), followed by
13 Wave2C and 13 Wave2D cases with no failures. These are fixed assertions, not a
claim that the three absent plugins were tested. The workload runs API/item,
entity and chunk load/unload operations concurrently with real Minecraft STATUS
requests. The existing harness checks protocol errors, new log errors and heap
growth after Full GC on the actual Java process. STATUS traffic is not a swarm
of logged-in players. Cleanup must finish without workload failures or tickets.

The performance matrix uses exact, checksum-pinned versions in
`tools/ci/port263-mods.lock.json`: Lithium 0.26.3, FerriteCore 9.0.0 and C2ME
0.4.2-alpha.0.119 for 26.3. C2ME remains experimental. These mods are test inputs,
not bundled dependencies or a promise of compatibility with other versions.

Local pre-publication checks passed the root build's 79 tests, the new public API
probe's 11 cases, and a full stop/restart persistence check. A prior five-minute
chunk-fix workload passed 13,730 STATUS requests, 3,311 entity spawn/removal pairs
and 256 verified chunk cycles without errors; that run preceded the other API
fixes and is not presented as validation of the final head. Final-head CI and
longer-run results belong in the PR's validation record.

## Artifact and fixture follow-up

The runner selects the canonical JAR using the checked-in Gradle coordinates;
Shadow's `-all.jar` and old-version artifacts cannot be selected instead. The
independent mod also registers `minecraft_fixture:dirt`, an item without a block
or damage component and with a stack size of 16. Public API checks cover its
namespace, Material enumeration, item/block classification, stack size, zero
durability and byte serialization. Native-wrapper constructors retain their
registry IDs. Unit tests explicitly bind 26.3's registry components after
bootstrap before checking item durability.

On `7f7167cb`, both stock and performance configurations passed 12 API cases,
the compatibility matrices, five-minute workloads and persistence preparation.
The strict console gate then correctly rejected a native startup error: the
flat-world fixture omitted its required generator layers. The fixture now writes
explicit plains/bedrock/dirt/grass settings. Console errors remain fatal and are
checked immediately after startup as well as after shutdown, with matching
lines included in failures. A complete run must also pass the subsequent restart
and persisted-content checks; the partial runs are not full hardening passes.

The pinned C2ME build probes two optional ScalableLux/Starlight classes at
startup (`ChunkSystemHooks` and `SchedulingUtil`). Only their exact
`main/WARN` class-not-found lines are accepted in the performance matrix and
reported as expected compatibility warnings. The original log retains them.
The same lines at ERROR level, other missing classes, or the stock matrix still
fail the gate. This does not claim that ScalableLux/Starlight itself was tested.

## Inherited API stubs: first incremental batch

The first batch restores Location-based biome/block-state reads and all biome
write paths in CraftWorld. Reads reuse CraftRegionAccessor and preserve floored
negative coordinates and block-state snapshots. Writes update the stored 4x4x4
biome cell, clamp Y like Paper, mark the chunk unsaved, and leave absent chunks
unloaded. The legacy column setter includes the world's negative build heights.
A narrow section bridge retains all neighboring palette entries and supports a
read-only biome container by copying it before mutation.

Three additional public API groups cover reads/snapshots, cell/column/height
boundaries and no-load writes. The native fixture independently reads stored
biomes, and the process-restart gate now verifies a changed biome alongside the
existing custom entity/block/item component. The biome uses a separate,
already-saved chunk so unrelated block/entity writes
cannot hide a missing dirty mark. All mutations in API probes restore
the original blocks/biomes and release their chunk tickets. These checks are
repeated on both stock and performance configurations after a full restart.
Validation results for this batch are recorded separately from the earlier
16-group run; adding probes alone is not evidence that they pass.

Fixture-only commit `d9bf0342` reproduced two failures on both stock and
performance matrices: Location biome reads and stored biome mutation. All other
17 groups passed on that unchanged production baseline (CI run `43627571714`).

Next batches remain separate: tree generation; explosions; POI/legacy biome searches and structure templates; raid PDC; world
settings/effects; player time/sleep behavior; server restart/command aliases.

## Ray tracing and line of sight

The next batch replaces Position and builder ray stubs and reconnects the
Location overloads to the same implementation. The legacy block path previously
cast a Bukkit CraftWorld to native LevelAccessor, which fails at runtime even
when the ray misses. Conversion now receives the native world.

Native clipping uses COLLIDER/OUTLINE/VISUAL and the requested fluid mode. A
narrow ClipContext adapter applies the block predicate to both solid and fluid
shapes, allowing the ray to continue past excluded blocks. Entity predicates,
expanded hit boxes and the closest block/entity result are preserved. Builder
configuration snapshots caller Location/Vector values and checks required fields
and target selection. Line of sight uses the existing RegionAccessor collision
query, including its cross-world and 128-block range behavior.

Six additional public API groups exercise legacy/Position rays, hit faces and
distances, filtered foreground blocks and fluids, entity radius and nearest
selection, builder modes/targets/copies, argument validation and line of sight.
All scene blocks and entities are restored/removed, and existing anchor tickets
are retained. The same helper runs on checksum-pinned Paper 26.3 build 249 via
`./gradlew -p tools/port263-fixture paperRayReferenceJar` followed by
`python3 tools/ci/paper_ray_reference.py`. The control plugin requires an explicit
test-runner flag and shuts down its fresh disposable reference world after the
probes. Its Jar is isolated from the production mod and normal API audit plugin.
Implementation and test setup alone are not a runtime pass; validation belongs
to the exact-head CI record in the PR.

## Item drop overloads

All exact and natural drop overloads share the existing callback-aware spawn
gate. The callback runs once before ItemSpawnEvent, so listeners see its stack
and pickup-delay edits. Cancellation leaves the returned Item unspawned, and a
callback exception propagates before either the event or entity addition.

The natural callback overload was previously stubbed; the two-argument paths
also bypassed the common item-spawn event gate. Natural offsets now match Paper
26.3: X/Z are within -0.25 to 0.25 of the supplied point, and Y includes the same
centered offset minus half the item entity's type height. Caller Location and
ItemStack values are preserved. The exact drop path retains its coordinates and
default ten-tick pickup delay. Null location/stack arguments keep Paper's argument
validation rather than silently returning null.

Five additional groups check callback/event ordering and edits, cancellation of
all four overloads, centered jitter and input copies, exact defaults/event count,
and callback exception/null-argument behavior. All listeners and created Item
entities are cleaned up even when a probe fails. The same groups join the pinned
Paper public-API control (11 groups) and production Cardboard probe (30 groups).
Exact-head runtime and saved-world/workload results belong in the PR record.

## Limits still requiring work

Cardboard contains inherited API stubs independent of this version migration.
The source audit found 939 TODO/unsupported markers; that count is **not** a count
of confirmed defects. Examples requiring separate implementation/verification
include raid persistent data and the full native
Brigadier argument provider. Existing dialog/click callback
implementations also need end-to-end client verification; the presence of a
builder/provider alone does not establish working callbacks.

## World search and dragon follow-up

The unchanged `2c1d6df2` production code was tested with four additional probe
groups on `3a1416fc`: both matrices passed the original 12 cases and failed the
new structure lookup, structure presence, raid lookup and dragon battle groups.
The native oracle found a plains village, while Bukkit returned null; the
presence query looked up a structure type key in the structure registry; raid
ID lookup and the world's End battle getter were unimplemented.

The follow-up restores modern/type/legacy structure searches, defensive result
locations, exact structure presence, raid enumeration/ID/nearest lookup without
squared-radius integer overflow, and the native DragonBattle state APIs. Mixins
provide narrow access to the real fight and raid map. Empty-crystal API respawns
retain their intended native tick behavior, including reconstruction from saved
data; ordinary missing-crystal respawns retain vanilla cancellation.

The standalone probe exercises real healing crystals, native references, portal
idempotence, default/custom-position gateways, mutable fight state, native search
results and a 50,000-block raid. The integrated fixture uses the connected owner
to exercise native empty-respawn ticking, saved-data codec decoding and ordinary
missing-reference cancellation in both worlds. These controlled states do not
replace a full player-driven dragon combat/resurrection test.

CI configures a 1,800-second workload for each stock/performance matrix, with a
75-minute job limit including build, preparation and restart. The final PR
validation record must establish its completion; configuring it is not a pass.

No six-hour soak, arbitrary modpack validation, client FPS benchmark or visual
artifact inspection is implied by the bounded server gates. Existing CI also
checks integrated two-world lifecycle, two-client LAN, LuckPerms, common plugin
startup and reproducible builds. Keep the PR in draft until remaining release
criteria and the intended real-world mod/plugin pack have been reviewed.
