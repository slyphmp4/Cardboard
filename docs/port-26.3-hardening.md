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

The later sections track the separate tree, explosion, search/template, raid PDC,
world/player state, server restart/alias, Brigadier and dialog batches.

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
of confirmed defects. The raid, native Brigadier and game dialog/callback gaps
identified by that audit are tracked in the later implementation and validation
sections. The presence of a builder/provider alone does not establish working
callbacks; their native client and shutdown gates are mandatory.

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

## Tree generation follow-up

World tree overloads now dispatch through RegionAccessor. Native features write
to a BlockStateListPopulator for callback overloads; proposed state/fluid/tile
reads see that buffer, and snapshots retain their original placement accessor.
Callbacks can edit or veto each proposed block before placement. Existing world
tiles are copied before buffered updates. Captured tile snapshots use the existing
CraftBlockEntityState placement path, including persistent data.

CraftTreeType maps every 26.3 TreeType explicitly, including all three poplars,
pale oak and creaking pale oak, and corrects mega spruce/pine selection. The legacy
BlockChangeDelegate receives generated proposals without implicit world writes.
A callback failure after earlier accepted blocks may leave those blocks placed,
matching Paper; this API does not provide whole-tree transactional rollback.

Six shared public scenarios cover seeded generation, consumer edits, predicate
veto/selection, delegates, modern tree types/tile data, null callbacks, first
callback exceptions and an obstructed trunk origin. The checksum-pinned Paper
control runs the same source. Tests use guarded air above a disposable flat world,
then clear their blocks. On `85a8472e`, all 12 checks passed, including 36 API
groups before/after process restart, persistence and both 30-minute workloads.

## Explosion follow-up

Coordinate and source-first World overloads now share the native explosion path
and report its actual cancellation result. Source-null API blasts use ordinary
destruction without drop decay, TNT sources retain TNT rules and other sources
retain mob rules. The source-damage flag changes the native entity query without
removing source attribution. A scoped context associates each API call with its
own native explosion and is removed in a finally path, including nested calls.

Native block/entity explosion events expose the mutable affected-block list and
yield. Yield changes feed the existing vanilla block-loot path. Ignition and TNT
chain priming have separate cancellable events; cancelled priming keeps the TNT
block and suppresses its primed entity. Cancelled block explosions suppress the
native client explosion packet. Native entity damage occurs before the block
event, and fire retains its separate ignite event, matching pinned Paper's order.

Seven shared public scenarios cover overload results, source attribution, edited
block lists, mob/TNT rules, source-null drop behavior, source-damage inclusion,
zero yield, ignition, TNT chain cancellation and nested invocation state. One
additional Cardboard case calls Minecraft's native explosion entrypoint directly.
The control runs the same seven public groups on official Paper 26.3 build 249.
All scenes guard a high air volume in a disposable world and clean their blocks,
entities and listeners. On `fc2c7ecd`, all 12 checks passed, including 44 API
groups before/after process restart, persistence and both 30-minute workloads.
These scenarios do not establish full player-driven combat or arbitrary modpack
compatibility.

## Legacy biome and POI searches

The two deprecated biome overloads defer to World's public defaults: 8x8 search
intervals for the default overload, and the caller's step for both axes in the
explicit-step overload. This preserves their behavior while using the existing
native biome search.

Nearest/all POI queries use the world's actual PoiManager and existing registered
PoiType/Occupancy conversions. They validate input, retain type/radius/occupancy
filtering and propagate predicate errors. List results are immutable snapshots;
each result returns a defensive location. No separate POI registry is created.

Four shared public groups compare legacy biome overloads with their modern
equivalents, query real fixture workstations, check occupancy/radius/filtering,
validate inputs and verify result isolation and predicate recovery. The same
source runs on the pinned Paper control. Exact head `3b47bd62` passed all 12
checks, 48 API groups before/after a full process restart, persistence and both
1,800.6-second workloads, with zero connection/protocol/workload errors.


## Structure templates (validated on 070a0c4a)

The cached StructureManager uses Minecraft's actual template repository, resource
loader/data fixer and validated world-template paths. Registration and snapshots
share the same native objects used by structure blocks; empty cache entries do
not appear in public maps. File overloads close owned streams. Copying clones NBT.

CraftStructure/CraftPalette capture real native block, tile and entity data and
place it through the native algorithm, including palettes, mirror/rotation and
integrity. Each native template owns a stable PDC persisted under BukkitValues;
loading an older template clears any previously held PDC values.

API placement attaches transformers to its settings. A scoped native frame also
covers calls nested from callbacks, so a vanilla placement cannot inherit an
outer API transform. Only real template block writes and captured entity additions
are transformed. Tile NBT edits and loot seeds survive native placement; barrier
clearing and neighbour updates retain their native order. Callback regions retain
the original WorldGenLevel and release their link in finally, including errors.
Region fluid snapshots are implemented as a required callback dependency.

Ten shared groups cover cache semantics, fill/copy/palettes, tile/structure PDC,
streams/files/resources, placement/rotation/mirror/integrity, entity flags,
transformers, spawn events and callback-region queue recovery. The same source runs on pinned Paper 249.
A Cardboard-only native oracle verifies the actual template cache, stable PDC
clearing on old-NBT reload, and a nested vanilla placement inside a callback.
A separate template file (not a registered cached object) is saved, then loaded
and placed after a complete server-process restart.

Core head `125146a6` passed all 12 checks, 57 API groups before/after full process
restart, both persistence groups and two 1,800.5-second workloads, with zero
connection/protocol/workload errors. Stock heap change after Full GC: -289.9 MB;
performance: -13.6 MB. That gate did not cover spawn-event cancellation.

The entity-event follow-up routes captured and queued region entities through
the existing world's spawn-event bridge for normal ServerLevel additions, after
entity transformers and before insertion. ProjectileLaunchEvent retains its
existing native hook. Cancellation discards the unspawned entity. WorldGenRegion
additions keep their native path. Region links are always released; entities
queued by a failed transformer are abandoned. Earlier native block/entity writes
are not rolled back.

Exact follow-up head `070a0c4a` passed all 12 checks, 59 API groups before/after
full process restart, both persistence groups and the 38-group Paper control.
Stock: 1,800.4 seconds, heap change after Full GC -14.4 MB. Performance:
1,800.5 seconds, heap change -177.7 MB. Both reported zero connection/protocol/
workload errors.


## Raid persistent data (validated on b5ee2e65)

Each native raid owns one stable observed PDC, shared by every Bukkit wrapper.
The getter binds that state to the actual Raids SavedData owner using a weak
reference. An optional BukkitValues field wraps the original native MapCodec;
vanilla fields and constructors remain intact. Old and empty data remain valid,
and malformed optional PDC is ignored like Paper. Decoding fills the observed
container instead of replacing it.

Changes through set/remove/put/putAll/clear/bytes and mutable copyTo notify the
target owner. Readonly item-view copies use the target setter as well; direct raw
map writes would bypass this notification. Other PDC constructors retain a no-op
observer. No World PDC or unrelated metadata implementation is added.

Four shared groups exercise real native fixture raids and the native NBT codec
with NbtOps (preserving numeric tag types), including nested values, arrays,
bytes, old/malformed saves and mutable/readonly copies. A Cardboard-only oracle
clears SavedData's dirty flag and checks each held-container write in the SAME
server task, independent of incidental raid ticks.

Restart persistence first saves an owned inactive raid at an unloaded far-away
position, then changes ONLY its held PDC and checks immediate dirtiness. A full
server-process restart must load its string/long-array values and stable
container. The fixture does not load terrain and removes its owned raids.

Exact head `b5ee2e65` passed all 12 checks, 64 API groups before/after full
process restart, all three persistence groups and the 42-group pinned-Paper
control. Both production configurations completed 1,800.5-second workloads with
zero connection/protocol/workload errors. Heap change after Full GC: stock
-261.7 MB; performance -132.6 MB. The extra native-codec probes also require
mandatory vanilla fields to remain mandatory and decoded containers to retain
their dirty observer after binding.


## World settings (validated on 871ea255)

Per-world view distance uses the actual ChunkMap tracker, validates [2, 32] and
updates connected players' chunk-cache radius. Deprecated no-tick methods defer
to World's public aliases. The server default and other worlds are unchanged.

Void settings belong to each CraftWorld (vanilla defaults true / 4 / -64).
Narrow server-side injections adjust the native below-world check and living
entity damage amount. The original removal/damage path remains responsible for
entities. Only the void source uses a dedicated single VOID event wrapper so
cancellation and damage edits reach native hurtServer; the existing generic
HEAD event skips that source. Other damage sources keep their existing path.
Client entity predictions retain native behavior.

Four shared groups exercise distance/aliases/validation/isolation, void setting
roundtrips, actual native living damage/thresholds/VOID event cancellation and
amount edits, and nonliving entity removal. A Cardboard-only oracle reads the
native chunk tracker. The original shared source passes all 46 pinned-Paper groups.
Disposable client probes require the connected owner and a fresh LAN guest to
receive the world-specific radius in two successive integrated worlds.

Fixture-only `98466466` reproduces exactly five new grouped failures on both
stock/performance configurations while all previous 64 groups pass; both client
jobs detect the missing setter. Initial production validation exposed an
unimplemented living damage cooldown and missing initial guest radius; the
follow-up below resolves those separately.


## World settings follow-up (validated on 871ea255)

The native living-entity damage cooldown is `damageCooldownTime`, distinct from
Entity's invulnerability timer. The Bukkit no-damage tick getter/setter use a
narrow native bridge to that field. A separate shared native-field oracle checks
both directions; the real void-damage scenario also verifies reset cooldowns and
one edited/cancelled VOID event.

PlayerList.sendLevelInfo sends the destination world's chunk-cache radius on
login, respawn and dimension transfer, matching the pinned Paper path. Existing
clients continue to receive updates from the world setter. The two-process LAN
fixture checks the radius of a fresh guest in both successive worlds; timeout
diagnostics include actual client radius and world/player initialization.

Fixture-only `19abb6ce` reproduced the cooldown and edited-void failures on both
configurations with the previous 68 groups passing; pinned Paper passed all 47
shared groups. The fresh LAN guest had a loaded world/player but radius 2 rather
than the world's 4.

Exact head `871ea255` passed all 12 checks: 70 Cardboard API groups before/after
restart, all 47 shared Paper groups, three persistence groups and both client
lifecycles, including fresh guest radii in both worlds. Stock completed 1,800.5
seconds (88,824 STATUS successes, heap change after Full GC -36.0 MB); performance
completed 1,800.6 seconds (88,235 successes, -158.8 MB). Both reported zero
connection/protocol/workload errors.


## World notes and classic particles (validated on 47c26c5e)

Two inherited CraftWorld overrides silently discarded notes and every classic
single-speed particle call. They now delegate to the exact pinned World defaults:
notes use the instrument sound, RECORDS category, volume 3 and Note.getPitch();
classic particles forward equal per-axis speeds and DEFAULT randomization to
the existing modern native packet path. Receiver selection, visibility and force
handling remain in that existing implementation.

Fixture-only `ec2786d6` adds two shared groups covering note input/default
contracts, particle data validation across classic overloads and unchanged input
locations. Both Cardboard configurations reproduced exactly the two new failures
while the previous 70 groups passed; the LAN fixture independently detected the
missing note sound packet. Pinned Paper 26.3 build 249 passes all 49 shared groups.
Disposable
outbound observers on the actual owner's local channel and guest's TCP channel
check sound volume/pitch/category and particle count/position/force/recipients;
the LAN runner requires a packet-proof marker in both successive worlds.

Exact head `47c26c5e` passed all 12 checks, 72 API groups before/after full
process restart, all 49 pinned-Paper groups, all three persistence groups and
both client lifecycles. The real packet scopes passed in both LAN worlds.
Stock completed 1,800.5 seconds (88,965 STATUS successes, heap delta after Full
GC -15.3 MB); performance completed 1,800.4 seconds (89,105 successes, -13.8 MB).
Both reported zero connection/protocol/workload errors.


## Personal player time, weather and sleep (validated on c6cfad2d)

Nine CraftPlayer accessors/mutators now use transient state on the native player.
Relative time adds a signed offset to the native default clock; fixed time uses
its current day boundary. A reset restores offset zero and relative ticking.
The game connection personalizes only the default-clock entry of 26.3 time
packets, preserving game time, other clocks, partial ticks and the native rate
(or zero for fixed time). This includes world info, broadcasts and callback sends.
No-clock dimensions keep the native no-packet setter behavior.

Weather starts with no override. Explicit weather sends the pinned Paper rain
event and progresses native rain level by 0.01 per player tick. Native world
rain events/levels cannot replace an override; thunder remains native for
DOWNFALL and zero for CLEAR. Reset resumes world weather. A scoped bypass lets
owned packets through and restores its flag on exceptions. Suppressed writes
with a callback send the current personal rain snapshot so completion is retained.

Sleep-ignore updates the real world's sleep tracker immediately. Native counting
includes ignored active players but excludes spectators; deep sleep still requires
at least one real deep sleeper, matching pinned Paper. Settings are not persisted
or stored in a global player/world map.

Fixture-only `409baf29` reproduced exactly three new failures on both Cardboard
configurations while all prior 72 groups passed. Official pinned Paper 26.3 build
249 passed all 52 shared groups. Unregistered native players cover API defaults,
signed/large relative and fixed offsets, reset/isolation and sleep thresholds.
The deep-sleep fixture uses an actual bed, restores both block snapshots and
sets the native sleep timer to a fully sleeping state.

Real owner/guest channels additionally check packet isolation, paused/custom-rate
clock broadcasts, callback completion, native weather filtering/reset and the
live world's ignored/spectator counts. Two 65-tick rain phases check gradual
changes and repeated native clock syncs. Every observer and setting is cleaned
up; a new proof marker is required in both successive LAN worlds.

Exact head `c6cfad2d` passed all 12 checks, 75 API groups before/after full
process restart, all 52 pinned-Paper groups, three persistence groups and both
client lifecycles. The real personal-state scopes ran 136/159 server ticks with
10 native clock samples each. Both workloads lasted 1,800.5 seconds with zero
connection/protocol/workload errors. Stock: 89,014 STATUS successes, heap delta
after Full GC -20.5 MB; performance: 88,329 successes, -123.0 MB.


## Server aliases and graceful restart (runtime validation pending)

commands.yml is loaded per CraftServer with the existing defaults and defensive
ordered scalar/list aliases. Explicit alias sections do not force the bundled
default alias. Missing/malformed reloads return false before replacing the live
configuration, aliases or native tree; malformed startup files are not overwritten.
SimpleCommandMap retains its existing macro/target validation implementation.

CommandMapImpl records only aliases it installed and their displaced commands.
Removal restores a displaced command only while the label still belongs to that
alias, preserving replacements made by other plugins. Empty aliases are tracked
as well. Data-pack reload removes the overlay before replacing native wrappers,
avoiding stale registry/dispatcher references. Successful alias reload rebuilds
the native dispatcher and refreshes connected clients.

Server.restart(), Server.Spigot.restart() and the permission-gated restart command
share one controller per server. Requests are deduplicated; async callers marshal
kicks and native halt to the server thread. The normal server lifecycle disables
plugins, saves worlds and closes listeners. A validated dedicated startup script
is launched once when native stopServer returns; Unix file paths and arguments use an argv
vector rather than shell evaluation. A missing script still stops normally so an
external supervisor can own relaunch. No forced System.exit is introduced.

Integrated restart saves/closes the world and leaves the client JVM running; it
does not arm an external launcher or automatically reopen that world. The client
fixture invokes the ordinary API in its first world and Spigot API in the second,
joins native shutdown, checks global/thread cleanup and opens the next world.
A valid sentinel script must never execute at final client exit. Windows launcher
construction is retained, while actual process restart coverage currently runs
on Linux.

Fixture-only head `9fa6a006` reproduced five new alias groups on both Cardboard
configurations with all previous 75 passing; pinned official Paper build 249
passed all 56 shared groups. Separate disposable JVM cases proved the API,
async Spigot API and command paths did not stop the old server before the fix.
Shared contracts check startup loading, order/defensive copies, macro expansion,
replacement and failed-reload return/file behavior. A Cardboard-only oracle
checks native dispatch, command ownership/restoration and staged failure behavior.

The dedicated restart runner requires a script-created second JVM with a new PID,
exact literal arguments, one launch despite duplicate requests, primary-thread
plugin shutdown and all three saved-data groups. The missing-script command case
uses a separate supervisor launch. Cleanup signals only verified owned processes
through pidfds. Actual clients observe alias add/replace/remove command trees and
a non-operator LAN guest is denied restart.

Validation targets: all 13 checks, 80 Cardboard API groups before/after full restart,
56 shared Paper groups, three persistence groups, both 1,800-second workloads,
three dedicated restart cases and both integrated alias/restart lifecycles.
Exact-head validation results are recorded in PR #58.


## Native command argument provider

The missing VanillaArgumentProvider service is implemented against the pinned
Paper 26.3 provider contract. Factories cover all 37 native types and public
overloads. Native parsing converts entities, positions, block snapshots/NBT, item
components, predicates, components/styles, ranges and registry values into API
results. Registry-dependent factories use the active server registries/features
without a static world/build-context cache.

Native command nodes keep their real wire/signing type while parsing into API
values. Per-registration graph copies preserve shared nodes, redirects, cycles,
forks, requirements and explicit/custom suggestions. The native dispatcher's
root also normalizes directly inserted API arguments. Ordinary API builders keep
ordinary roots; the dispatcher's result consumer remains installed by vanilla.
Unknown legacy arguments retain the previous client string fallback. Declared
custom native-type cycles/nulls are rejected without recursion overflow.

Command-source getters preserve the initiating output when the executor changes.
The player output uses its captured owner; console output takes priority over the
new executor. Reply/success/failure delegate to native delivery and feedback rules.
Signed message resolution adapts only a private context's named argument back to
the native Message, retaining the original signing context, source and input.
The Adventure view preserves native time, salt, identity, signature and decoration.

Fixture-only fa275556 passes all 64 shared groups on pinned official Paper while
both Cardboard configurations reproduce exactly eight new failures and retain all
previous 80 passing groups. The control established unsigned message resolution
as asynchronous and hex-color grammar as six digits without a leading hash.

The production target is 88 groups before/after process restart on both stacks,
64 shared Paper groups and all existing persistence/workload/restart checks.
Actual owner/LAN clients decode every public factory type and time/selector
properties, execute UUID/custom/player/world/component/message commands, request
server-backed suggestions and receive reply/success/failure packets. These scopes
repeat after native resource reload in each successive world, with fresh lifecycle
registration and packet-observer cleanup. Real disposable clients have offline
profiles: their unsigned fallback is tested over the connection. A separate
synthetic native signing context checks metadata/signature binding through the
vanilla resolver; it does not establish authenticated-client cryptographic proof.
Exact-head validation results for this stage are recorded in PR #58.

## Game dialogs and click callbacks

Adventure's callback provider is registered through its service file. Both callback
queues are activated on the native server tick and retain their use/expiry behavior.
The common game listener preserves vanilla handling and thread transfer, emits
`PlayerCustomClickEvent` for every custom action, then consumes matching callbacks.
Unknown identifiers and absent/non-compound payloads still produce events;
only compounds expose `DialogResponseView`. Event payloads use the generic NBT
codec so scalar/list tags are retained. The game connection resolves its actual
native player without depending on an injected `getPlayer` overload.

Malformed callback UUID fields are decoded through the native UUID codec's
non-logging result path. Missing, wrongly typed and incorrectly sized identifiers
are ignored for both callback managers without consuming a use or emitting a server
ERROR. The strict production console gate also runs directly after API probes and
compatibility checks, before starting the 30-minute workload.

Inline dialog conversion reads the active server's registries on every request.
It no longer holds the first integrated world's registry access in a static
context, including across resource reloads. Native server stop releases both
active callback maps and pending registration queues before a dedicated restart
launcher runs or an integrated world is replaced.

Five shared groups add dialog types/inputs/actions, validation/defensive lists,
registered-template copying, callback uses/expiry/reentrancy, typed/invalid responses
and native tick activation. They run unchanged on checksum-pinned official Paper
26.3 build 249. The target is 93 Cardboard groups before/after process restart and
69 Paper groups, plus the existing persistence, compatibility and workload gates.

The real owner's client renders and submits the native form six times per world,
split before/after a native resource reload. Its independently loaded Bukkit plugin
checks edited Unicode text, boolean/number values, the actor, primary thread,
event-before-callback ordering, two-use limits and replay rejection. A received
chat component supplies another single-use callback action. Separate actual
client packets cover compound, scalar, list and absent payloads. Both successive
worlds must also prove that active/queued closures were populated before stop and
are empty afterward. UI and cleanup markers are mandatory CI gates. The LAN
fixture repeats the owner's scope and retains its separate guest/LuckPerms checks.

This stage covers game connections; configuration-stage dialogs and reentry remain
a separate existing API gap. Exact-head results belong in PR #58.
