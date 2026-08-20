
<img align="right" alt="logo" width="130" src="https://cardboardpowered.org/assets/cardboard-box.png">

# Cardboard [![Join Discord](https://img.shields.io/badge/Discord-Join-7289DA?logo=discord&style=flat-square)](https://discord.gg/tddTWXZtaP) <img alt="Fabric" src="https://img.shields.io/badge/Fabric%20-0.16%2B-%23dacfa4">

Cardboard is an implementation of the popular Bukkit/Spigot/Paper Modding API for FabricMC. This mod lets you use plugins that are made for Bukkit and it's derivatives (Spigot & Paper) on a Fabric modded server.

Fabric version chart:
| Support  | Minecraft        | Git Branch  | Dev Status |
|----------|------------------|-------------|------------|
| &#x2705; | Fabric 26.2      | ver-26.2-release | Active |
| &#x2705; | Fabric 26.1.2    | ver/26.1    | Low        |
| &#x2705; | Fabric 1.21.11   | ver/1.21.11 | Low        |
| &#x2705; | Fabric 1.21.1    | ver/1.21    | Low        |
| &#x274C; | <= 1.20          |             |            |

See [Supported Versions](https://github.com/CardboardPowered/cardboard/wiki/Supported-Versions) for more details. & [View Downloads](https://cardboardpowered.org/download/)

## Building

Requires JDK 25 (auto-provisioned by the Gradle toolchain resolver) and Java 25 at runtime.

```bash
./gradlew clean build --no-daemon --stacktrace
```

The mod jar is produced at `build/libs/Cardboard-<version>.jar`. Drop it into a
Fabric server's `mods/` folder alongside [Fabric API](https://modrinth.com/mod/fabric-api)
and iCommonLib; Bukkit plugins then go in `plugins/`.

## Reproducible builds

The canonical release artifact is `build/libs/Cardboard-<version>.jar` from the
normal root build above. Cardboard supports reproducible builds with the
checked-in Gradle Wrapper 9.5.1 and Temurin JDK 25: two clean, full-history Git
checkouts of the same revision and dependency set must produce the same JAR
SHA-256, even when the checkout directories differ.

`GitVersion` contains source-derived diagnostics only: the project identity and
version, full Git commit SHA, commit count, commit timestamp, configured release
branch, and dirty state. Its compatibility `BUILD_DATE` and `BUILD_UNIX_TIME`
fields are derived from the commit timestamp rather than the wall clock. It does
not embed the hostname, username, absolute checkout path, or local branch.
Generated source lives below `build/`, so a normal build does not write into
`src/main/java` or dirty tracked sources.

The reproducibility contract applies to clean, full-history checkouts. A dirty
working tree is deliberately identified with `DIRTY = 1` and is not guaranteed
to match the clean artifact for its `HEAD`. A shallow checkout cannot supply the
same history-derived revision count and is outside this contract.

Run the verifier and its own regression tests from the repository root:

```bash
python3 -B -m unittest discover -s tools/reproducibility/tests -p 'test_*.py' -v
python3 tools/reproducibility/verify.py
```

The verifier performs two clean Wrapper builds of the `reproducibleJar` task,
compares the canonical JARs byte for byte, and fails with entry-level ZIP
diagnostics on any difference.
For the stronger different-directory procedure and report layout, see
[`tools/reproducibility/README.md`](tools/reproducibility/README.md).

## Continuous integration

The `Cardboard CI` GitHub Actions workflow runs for pull requests and pushes to
the maintained `ver-26.2-release` branch. It uses Temurin 25, Python 3.11, and
the checked-in Gradle Wrapper. Three independent jobs run the root Cardboard
build/regression suite, the isolated torture-harness build/regression suite,
and the reproducibility verifier against two full-history checkout paths.

Run the same verification locally from the repository root:

```bash
./gradlew clean build --no-daemon --stacktrace
./gradlew -p tools/torture/plugin clean build --no-daemon --stacktrace
python3 -m py_compile tools/torture/run.py tools/torture/tests/test_run.py
python3 -m unittest discover -s tools/torture/tests -p 'test_*.py' -v
python3 -B -m unittest discover -s tools/reproducibility/tests -p 'test_*.py' -v
python3 tools/reproducibility/verify.py
```

Successful jobs retain the Cardboard and torture-helper JARs for 14 days.
On build/test failure, any produced Gradle reports and Python test log are
retained for 7 days. A reproducibility mismatch additionally retains both
canonical JARs and its comparison report for 7 days.

Ordinary CI does not boot Minecraft, contact the deployment VPS, use RCON, or
run smoke/burst/soak scenarios. Those runtime checks require a disposable
server and remain manual under the procedure in `tools/torture/README.md`.
Point 1's six-hour soak is not repeated by pull-request verification.
Reproducibility verification is limited to Point 3 build identity; it does not
run or replace the real-plugin compatibility matrix from Point 4.

### 26.2 notes

Minecraft 26.2 moved the entity and block-entity constants out of their type
classes (`EntityType.X` -> `EntityTypes.X`, `BlockEntityType.X` ->
`BlockEntityTypes.X`), stripped the colour metadata from `ChatFormatting` in
favour of `world.scores.TeamColor`, and ships Adventure 5. Cardboard on this
branch targets Paper-API `26.2.build.110-stable` and Adventure `5.2.0`.

## About this fork
This repository is derived from [CardboardPowered/cardboard](https://github.com/CardboardPowered/cardboard)
and carries its history and license. The `ver-26.2-release` branch adds Minecraft 26.2 support.

## License
We inherit the license from Paper. See [Paper's License](https://github.com/PaperMC/Paper/blob/master/LICENSE.md) for full details.
SrgLib is also licensed under MIT.

<!--
## NMS Support
We do support using Spigot's ``net.minecraft.server`` classes. 
Classes and Fields will automatically remap to their intermediary counterparts.
However, the current system is far from perfect.

# Progress
There is a progress indicator in the Discord. Although, if you're not in the Discord:
Progress can be determined by the completeness of the to-do lists on the two pinned issues.
-->

## Credits
* [BukkitTeam](https://bukkit.org/), [Spigot](https://spigotmc.org/), and [Paper](https://papermc.io/) for their work on the API.
* [Glowstone](https://glowstone.net) for the library loader.
* [md_5's SpecialSource](https://github.com/md-5/SpecialSource), [SrgLib by Techcable & Orion](https://github.com/OrionMinecraft/SrgLib), [MinecraftMapping by Phase](https://github.com/phase/MinecraftMapping/)
* Contributors to Cardboard

# Apex Hosting 
This project is partnered with ApexHosting! Join our test server, `cardboardmod.apexmc.co:25666`, or get a Minecraft server by clicking on the banner below:

[![Apex Hosting](https://cdn.apexminecrafthosting.com/img/theme/apex-hosting-mobile.png)](https://billing.apexminecrafthosting.com/aff.php?aff=3548)
