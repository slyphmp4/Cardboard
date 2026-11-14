
<img align="right" alt="logo" width="130" src="https://cardboardpowered.org/assets/cardboard-box.png">

# Cardboard [![Join Discord](https://img.shields.io/badge/Discord-Join-7289DA?logo=discord&style=flat-square)](https://discord.gg/tddTWXZtaP) <img alt="Fabric" src="https://img.shields.io/badge/Fabric%20-0.20.1%2B-%23dacfa4">

Cardboard is an implementation of the popular Bukkit/Spigot/Paper Modding API for FabricMC. This mod lets you use plugins that are made for Bukkit and it's derivatives (Spigot & Paper) on a Fabric modded server.

Fabric version chart:
| Support  | Minecraft        | Git Branch  | Dev Status |
|----------|------------------|-------------|------------|
| Experimental | Fabric 26.3 | ver/26.3 | Port in progress |
| &#x2705; | Fabric 26.2 | ver/26.2 | Stable 26.2.27 |
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
(iCommonLib is bundled); Bukkit plugins then go in `plugins/`.

## Reproducible builds

The canonical release artifact is `build/libs/Cardboard-<version>.jar` from the
normal root build above. Cardboard supports reproducible builds with the
checked-in Gradle Wrapper 9.8.0 and Temurin JDK 25: two clean, full-history Git
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
the `ver/26.3` branch. It uses Temurin 25, Python 3.11, and
the checked-in Gradle Wrapper. Jobs run the root regression suite, torture
harness tests, reproducibility checks, dedicated-server plugin checks, and
integrated-client and two-client LAN lifecycle checks. The 26.3 API smoke
checks the new entity wrappers, brewing recipes, particles and chunk queries.

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

### 26.3 port status

This branch is experimental and targets Minecraft 26.3, Fabric Loader 0.20.1,
Fabric API 0.166.2+26.3, Paper API 26.3.build.249-stable and Adventure 5.2.0.
The 26.2.27 release and its previous fixes form the port's baseline.
See [`docs/port-26.3.md`](docs/port-26.3.md) for implemented changes,
validation evidence and remaining release gates. Historical 26.2 compatibility
reports do not establish compatibility with 26.3.

CI boots disposable Minecraft instances; it does not contact the deployment VPS
or run the six-hour soak from the torture procedure. The legacy DirTNT workflow
still targets 26.2 because its upstream has no 26.3 branch.

## About this fork
This repository is derived from [CardboardPowered/cardboard](https://github.com/CardboardPowered/cardboard)
and carries its history and license. The `ver/26.3` branch develops Minecraft 26.3 support on top of the maintained 26.2 fork.

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
