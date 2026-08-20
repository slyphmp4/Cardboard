# Cardboard reproducible-build verifier

This directory contains the Point 3 regression guard for Cardboard's canonical
release artifact. It builds the root project twice and requires the resulting
`build/libs/Cardboard-<version>.jar` files to be identical byte for byte. It
does not accept matching extracted contents when the JAR bytes differ.

## Reproducibility contract

The supported contract is:

- the same clean Cardboard Git revision;
- full Git history in both checkouts;
- the checked-in Gradle Wrapper 9.5.1;
- Temurin JDK 25;
- the same resolved dependency artifacts;
- the canonical root JAR produced by the normal build.

Under those conditions, builds made at different times or in different checkout
directories must have the same SHA-256. The canonical developer build remains:

```bash
./gradlew clean build --no-daemon --stacktrace
```

The covered artifact is `build/libs/Cardboard-<version>.jar`, produced by the
`reproducibleJar` task and included in `assemble`/`build`. Loom's raw JAR is kept
under `build/intermediates/loomJar/`; it, `shadowJar`, and other intermediate or
optional archives are not Cardboard release artifacts.

## Git revision metadata

The generated `org.cardboardpowered.GitVersion` class preserves useful,
source-derived diagnostics: Maven/project identity, project version, full Git
commit SHA, full-history commit count, commit timestamp, configured release
branch, and dirty state. The compatibility `BUILD_DATE` and `BUILD_UNIX_TIME`
fields contain that same commit timestamp, not the wall-clock build time. The
class does not contain a hostname, username, checkout path, or local branch.
The source is generated below `build/generated/` and is removed by `clean`;
builds do not generate Java source in the tracked `src/main/java` tree.

`DIRTY = 0` identifies a clean checkout and `DIRTY = 1` identifies working-tree
changes. Dirty builds remain useful for local debugging, but they are
intentionally not guaranteed to match the artifact from clean `HEAD`. Shallow
clones are outside the reproducibility contract because they do not contain the
full revision history.

## Local verification

First run the verifier's lightweight regression tests:

```bash
python3 -B -m unittest discover -s tools/reproducibility/tests -p 'test_*.py' -v
```

`-B` keeps the checkout clean by preventing local `__pycache__` files before
the verifier performs its fail-closed Git status check.

Then build the current checkout twice and compare the canonical JAR:

```bash
python3 tools/reproducibility/verify.py
```

The verifier invokes the platform-appropriate checked-in Gradle Wrapper with
`clean reproducibleJar --no-daemon --stacktrace --no-build-cache`. The normal
`build` task is still the release and correctness command; CI runs it
independently so the reproducibility job does not duplicate the full test suite.

For a checkout-path-independent validation, prepare two clean copies at the same
commit and pass the second one explicitly. For example, from a clean repository:

```bash
git clone --no-local . ../cardboard-repro-a
git clone --no-local . ../cardboard-repro-b
python3 ../cardboard-repro-a/tools/reproducibility/verify.py \
  --second-checkout ../cardboard-repro-b
```

Both local clones include full history and resolve to the same source revision.
Use disposable target directories and inspect them before removing them after
the comparison.

## Results and diagnostics

Success prints the exact artifact paths and their identical SHA-256. A mismatch
returns a non-zero status and reports archive-level and entry-level differences,
including entry order, missing entries, content hashes, timestamps, permissions,
ZIP extra fields, compression metadata, the manifest, `fabric.mod.json`, and
`GitVersion.class` where applicable.

Failure evidence is written below
`build/reports/reproducibility/` in the first checkout. It includes the two JARs
and a text comparison report when they are available. GitHub Actions uploads
that directory for 7 days on failure. The ordinary Cardboard CI job separately
retains the successful release JAR for 14 days.

## CI

The `reproducible-build` job in `.github/workflows/ci.yml` checks out the same
GitHub event SHA twice with full history, into `checkout-a` and `checkout-b`.
It uses Temurin 25, Python 3.11, the Gradle Wrapper, and the official Gradle
cache setup. It runs the verifier unit tests and then:

```bash
python3 checkout-a/tools/reproducibility/verify.py \
  --second-checkout checkout-b
```

No production server, VPS credentials, RCON secret, signing key, or external
runtime environment participates in this check.

## Scope boundary

This tooling covers Point 3 build determinism only. It does not run Minecraft,
the torture soak, or the Point 4 Paper/Bukkit real-plugin compatibility matrix.
