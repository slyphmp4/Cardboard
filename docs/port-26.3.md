# Minecraft 26.3 port

Status: experimental `26.3.0-alpha.1`; not a stable release.

## Baseline and target

The branch was fast-forwarded from 26.2.24 to the exact 26.2.27 source,
`fbddc358b8c58ba8e7654c53969a405160ef983d`, before porting. This preserves
PRs #47, #49, #50, #52 through #57 and the subsequent client passenger guard.
No old branch history or 26.2 release was replaced.

| Dependency | Target |
| --- | --- |
| Minecraft | 26.3 |
| Java | 25 |
| Gradle | 9.8.1 |
| Fabric Loom | 1.17.21 |
| Fabric Loader | 0.20.1 |
| Fabric API | 0.166.2+26.3 |
| Paper API | 26.3.build.249-stable |
| Adventure | 5.2.0, matching Paper's BOM |

Paper API coordinates are updated in both compilation and runtime library
loading, including the runtime SHA-256. Test plugins compile against the same API.
The bundled iCommonLib artifact retains its original 26.2 name and metadata;
its Minecraft requirement is >=26.2. Its startup and the exercised paths work
on 26.3. This is not evidence that every iCommon integration is compatible.

## Implemented changes

- Preserve vanilla's concurrent registry loading and wrap its lookup to expose
  Paper registry builders. Apply entry listeners during registry loading and
  compose listeners before freezing; include decorated-pot-pattern support.
- Remove hooks for the deleted PotionBrewing class. Use brewing recipes in the
  recipe manager, with add, remove and reset support. Preserve mutable recipe
  maps and last-added recipe priority.
- Migrate item component adapters, pot decorations, sign text, mob visibility,
  villager food, transmutation and the removed map-colour component. Preserve
  unknown modded component adapters and distinguish removed components from
  absent components through the public patch split API.
- Adapt immutable sign text and the update event, fire the player drop event
  before spawning its item, and keep cancellation authoritative. Update punch
  packets, item break callbacks, TNT priming and lectern drops.
- Correct 26.3's `SignText.Mutable` copy constructor, which initializes its
  filtered array from the raw messages. Preserve filtered components in native
  sign updates and Bukkit sign snapshots when a plugin edits another line.
- Adapt entity gravity, drag, invulnerability, conversion trackers, swings,
  post-processing effects, alliances and enchantment offers to native 26.3 APIs.
- Add Bukkit wrappers and spawn routing for cushions and poplar boats.
- Adapt feature registries, biome search and datapack loading. Implement new
  particle packet parameters and chunk generation-state queries; a query does
  not load or generate a chunk.
- Remove stale access-widener entries and obsolete mixins. Point CI at 26.3,
  adapt the client fixtures, and add a dedicated 26.3 API runtime probe.

## Local validation

On Windows with a private Temurin 25 toolchain (the system Java was not changed):

- Root `build`: 75 tests passed, access widener validation passed.
- Integrated-client and LAN fixture sources compiled against 26.3.
- Torture helper `build` passed; Python tooling: 44 torture tests and 9
  reproducibility-verifier tests passed.
- Static inspection against the real 26.3 merged JAR found no missing active
  injection method signatures, shadow members or native call targets.
- Development server created all three dimensions, reached `Done`, saved and
  stopped normally.
- Canonical `Cardboard-26.3.0-alpha.1.jar` also passed a clean Fabric Loader
  server launch, independent of Loom's development classpath.
- On the production JAR, LuckPerms-Bukkit 5.5.53, PlaceholderAPI 2.12.3,
  EssentialsX 2.22.0 (core, chat, spawn), NightCore 2.16.7, ExcellentEnchants
  5.4.3, CommandAPI 12.1.0 and ProtocolLib 5.4.0 enabled. NightCore registered
  81 enchantments during registry compose; a CommandAPI command executed.
  This checks startup and that command, not full gameplay plugin compatibility.
- On the production JAR, the Bukkit runtime probe passed cushion colour,
  poplar boat wrappers, brewing add/read/remove, both particle API shapes,
  loaded chunk state and an asynchronous absent-chunk query without loading it.

The automated runtime probe is `bash tools/ci/port-263-smoke.sh`; the broader
plugin matrix is `bash tools/ci/common-plugins-smoke.sh`. Both use disposable
worlds. Tests involving player login, sign edits, drops and packet-driven
behaviour still require the client fixtures, not merely the no-player probe.

## Follow-up from PR #58

The published head `a5750ea6b1fc07eae4b1b2a986f73eff3a9cebad` passed
seven checks, including the Linux build, reproducible JARs and server probes.
Its integrated-client test failed because a plugin edit erased an unchanged
line's filtered variant. Inspection of the actual 26.3 merged JAR confirmed
the incorrect field read in `SignText.Mutable`'s constructor; the constructor
mixin repairs that copy for both vanilla and Bukkit callers. The client fixture
also checks snapshot edits, both variants of the changed line, colour/glow and
that editing a snapshot does not mutate its source.

The same head's LAN host aborted with SIGBUS in the Linux native linker before
entering a world. Each LAN JVM now has a separate LWJGL extraction directory,
and CI preserves JVM fatal-error reports. Shared native extraction is a possible
cause, not a proven diagnosis: the old job did not upload its `hs_err` file.
Require a new successful LAN run; do not turn that native failure into a pass
by ignoring the exit code or dropping an assertion.

Fabric's official metadata still lists Loader 0.20.1 and API 0.166.2+26.3 as
the current versions for this target. Loom is updated within 1.17, and Gradle
within 9.8. Follow-up validation must use these updated pins.

On `e991a2e0`, both LAN worlds passed and the old native abort did not recur,
but the dedicated server rejected the guest through its whitelist. The native
26.3 properties now default `white-list` to true. The fixture explicitly retains
that setting and lists only its offline guest in `whitelist.json`. This is a
fixture configuration change, not a bypass of native login checks.

The follow-up audit also found Bukkit's whitelist getter/setter incorrectly
using the separate enforcement flag. They now use the native
`isUsingWhitelist`/`setUsingWhitelist` methods. Both integrated worlds and the
dedicated fixture independently toggle whitelist state and enforcement, compare
Bukkit with native state, and restore the original settings.

## Gates before a stable release

1. Require the complete CI suite on the exact proposed commit: root and helper
   builds, reproducible JARs, dedicated-server probes, common plugins, integrated
   two-world lifecycle, two-client LAN, and dedicated LuckPerms-Fabric.
2. Re-run 26.2's compatibility scenarios against 26.3, especially registry
   bootstrap, enchantments, item metadata, brewing, sign cancellation, player
   drops and world reload. Do not reuse old success reports as new results.
3. Require the stock and performance production-JAR gates with the independent
   26.3 entity/block/item/component fixture, including saved-world restart. See
   [the hardening procedure](port-26.3-hardening.md). Fourmisain/DirTNT's original
   issue-30 workflow remains scoped to 26.2 and supplies no 26.3 result for that mod.
4. Run the bounded workload gates, extended soak procedure and inherited TODO
   API audit before claiming broad Paper parity or recommending production deployment.

Go for port development and review. No-go for a stable release until these
checks provide actual 26.3 evidence. No release is published by this change.

## Primary references

- [Fabric 26.3 migration notes](https://fabricmc.net/2026/09/15/263.html)
- [26.3 migration primer](https://docs.neoforged.net/primer/docs/26.3/)
- [Paper build-249 source](https://github.com/PaperMC/Paper/tree/1a995d67e68af9a46eb5fde023b3309921311f70)
- [26.2.27 baseline](https://github.com/slyphmp4/Cardboard/commit/fbddc358b8c58ba8e7654c53969a405160ef983d)
