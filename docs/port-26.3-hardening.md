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

## Limits still requiring work

Cardboard contains inherited API stubs independent of this version migration.
The source audit found 939 TODO/unsupported markers; that count is **not** a count
of confirmed defects. Examples requiring separate implementation/verification
include structure/raid-location Bukkit methods, portions of DragonBattle, and
the full native Brigadier argument provider. Existing dialog/click callback
implementations also need end-to-end client verification; the presence of a
builder/provider alone does not establish working callbacks.

No six-hour soak, arbitrary modpack validation, client FPS benchmark or visual
artifact inspection is implied by the bounded server gates. Existing CI also
checks integrated two-world lifecycle, two-client LAN, LuckPerms, common plugin
startup and reproducible builds. Keep the PR in draft until remaining release
criteria and the intended real-world mod/plugin pack have been reviewed.
