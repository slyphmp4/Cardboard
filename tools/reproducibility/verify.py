#!/usr/bin/env python3
"""Fail-closed, byte-for-byte verification of the Cardboard release JAR."""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import sys
import tempfile
from typing import Iterable, Mapping, Sequence
import zipfile


GRADLE_TASK = "reproducibleJar"
GRADLE_ARGUMENTS = (
    "clean",
    GRADLE_TASK,
    "--no-daemon",
    "--stacktrace",
    "--no-build-cache",
)
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


class VerificationError(RuntimeError):
    """A fail-closed precondition or build verification failure."""


@dataclass(frozen=True)
class CheckoutState:
    root: Path
    head: str


@dataclass(frozen=True)
class ZipEntrySnapshot:
    filename: str
    occurrence: int
    content_sha256: str
    file_size: int
    compress_size: int
    crc: int
    date_time: tuple[int, int, int, int, int, int]
    extra_hex: str
    external_attr: int
    internal_attr: int
    compress_type: int
    flag_bits: int
    create_system: int
    create_version: int
    extract_version: int
    comment_hex: str
    is_directory: bool

    @property
    def key(self) -> tuple[str, int]:
        return self.filename, self.occurrence


@dataclass(frozen=True)
class ZipArchiveSnapshot:
    comment_hex: str
    entries: tuple[ZipEntrySnapshot, ...]


@dataclass(frozen=True)
class FieldDifference:
    field: str
    value_a: str
    value_b: str


@dataclass(frozen=True)
class EntryDifference:
    filename: str
    occurrence: int
    fields: tuple[FieldDifference, ...]


@dataclass(frozen=True)
class OrderDifference:
    index: int
    entry_a: str
    entry_b: str


@dataclass(frozen=True)
class ArtifactComparison:
    path_a: Path
    path_b: Path
    size_a: int
    size_b: int
    sha256_a: str
    sha256_b: str
    archive_a: ZipArchiveSnapshot | None
    archive_b: ZipArchiveSnapshot | None
    inspection_error_a: str | None
    inspection_error_b: str | None
    archive_differences: tuple[FieldDifference, ...]
    order_differences: tuple[OrderDifference, ...]
    only_a: tuple[str, ...]
    only_b: tuple[str, ...]
    entry_differences: tuple[EntryDifference, ...]

    @property
    def bytes_equal(self) -> bool:
        # The raw artifact hash is authoritative. ZIP-level comparison is only
        # diagnostic and can never turn differing bytes into a passing result.
        return self.size_a == self.size_b and self.sha256_a == self.sha256_b


def parse_gradle_properties(text: str) -> dict[str, str]:
    """Parse the simple key/value subset used by this repository."""

    properties: dict[str, str] = {}
    for raw_line in text.splitlines():
        line = raw_line.strip()
        if not line or line.startswith(("#", "!")):
            continue

        separator = next((item for item in ("=", ":") if item in line), None)
        if separator is not None:
            key, value = line.split(separator, 1)
        else:
            parts = line.split(None, 1)
            if len(parts) != 2:
                continue
            key, value = parts

        key = key.strip()
        if key:
            properties[key] = value.strip()

    return properties


def canonical_artifact_name(properties: Mapping[str, str]) -> str:
    """Return the exact archive name configured by Gradle project properties."""

    missing = [
        key
        for key in ("archives_base_name", "mod_version")
        if not properties.get(key, "").strip()
    ]
    if missing:
        raise VerificationError(
            "gradle.properties is missing required value(s): " + ", ".join(missing)
        )

    base_name = properties["archives_base_name"].strip()
    version = properties["mod_version"].strip()
    artifact_name = f"{base_name}-{version}.jar"
    if Path(artifact_name).name != artifact_name or any(
        separator in artifact_name for separator in ("/", "\\")
    ):
        raise VerificationError(
            f"Configured archive name is not a plain file name: {artifact_name!r}"
        )
    return artifact_name


def canonical_artifact_path(repository: Path) -> Path:
    properties_path = repository / "gradle.properties"
    try:
        properties = parse_gradle_properties(properties_path.read_text(encoding="utf-8"))
    except OSError as exc:
        raise VerificationError(f"Cannot read {properties_path}: {exc}") from exc
    return repository / "build" / "libs" / canonical_artifact_name(properties)


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def inspect_zip(path: Path) -> ZipArchiveSnapshot:
    entries: list[ZipEntrySnapshot] = []
    occurrences: Counter[str] = Counter()
    with zipfile.ZipFile(path, "r") as archive:
        for info in archive.infolist():
            occurrence = occurrences[info.filename]
            occurrences[info.filename] += 1

            digest = hashlib.sha256()
            with archive.open(info, "r") as entry:
                for block in iter(lambda: entry.read(1024 * 1024), b""):
                    digest.update(block)

            entries.append(
                ZipEntrySnapshot(
                    filename=info.filename,
                    occurrence=occurrence,
                    content_sha256=digest.hexdigest(),
                    file_size=info.file_size,
                    compress_size=info.compress_size,
                    crc=info.CRC,
                    date_time=info.date_time,
                    extra_hex=info.extra.hex(),
                    external_attr=info.external_attr,
                    internal_attr=info.internal_attr,
                    compress_type=info.compress_type,
                    flag_bits=info.flag_bits,
                    create_system=info.create_system,
                    create_version=info.create_version,
                    extract_version=info.extract_version,
                    comment_hex=info.comment.hex(),
                    is_directory=info.is_dir(),
                )
            )

        return ZipArchiveSnapshot(
            comment_hex=archive.comment.hex(),
            entries=tuple(entries),
        )


def _display_key(key: tuple[str, int]) -> str:
    name, occurrence = key
    return name if occurrence == 0 else f"{name} (occurrence {occurrence + 1})"


def _field_value(value: object) -> str:
    if isinstance(value, tuple):
        return "-".join(f"{item:02d}" for item in value)
    return repr(value)


def compare_artifacts(path_a: Path, path_b: Path) -> ArtifactComparison:
    """Compare raw bytes and produce ZIP-level diagnostics without weakening SHA."""

    path_a = path_a.resolve()
    path_b = path_b.resolve()
    size_a = path_a.stat().st_size
    size_b = path_b.stat().st_size
    digest_a = sha256_file(path_a)
    digest_b = sha256_file(path_b)

    archive_a: ZipArchiveSnapshot | None = None
    archive_b: ZipArchiveSnapshot | None = None
    error_a: str | None = None
    error_b: str | None = None
    try:
        archive_a = inspect_zip(path_a)
    except (OSError, RuntimeError, zipfile.BadZipFile) as exc:
        error_a = f"{type(exc).__name__}: {exc}"
    try:
        archive_b = inspect_zip(path_b)
    except (OSError, RuntimeError, zipfile.BadZipFile) as exc:
        error_b = f"{type(exc).__name__}: {exc}"

    archive_differences: list[FieldDifference] = []
    order_differences: list[OrderDifference] = []
    only_a: list[str] = []
    only_b: list[str] = []
    entry_differences: list[EntryDifference] = []

    if archive_a is not None and archive_b is not None:
        if archive_a.comment_hex != archive_b.comment_hex:
            archive_differences.append(
                FieldDifference(
                    "archive_comment_hex",
                    archive_a.comment_hex,
                    archive_b.comment_hex,
                )
            )

        order_a = tuple(entry.key for entry in archive_a.entries)
        order_b = tuple(entry.key for entry in archive_b.entries)
        for index in range(max(len(order_a), len(order_b))):
            key_a = order_a[index] if index < len(order_a) else None
            key_b = order_b[index] if index < len(order_b) else None
            if key_a != key_b:
                order_differences.append(
                    OrderDifference(
                        index=index,
                        entry_a="<missing>" if key_a is None else _display_key(key_a),
                        entry_b="<missing>" if key_b is None else _display_key(key_b),
                    )
                )

        entries_a = {entry.key: entry for entry in archive_a.entries}
        entries_b = {entry.key: entry for entry in archive_b.entries}
        keys_a = set(entries_a)
        keys_b = set(entries_b)
        only_a.extend(_display_key(key) for key in sorted(keys_a - keys_b))
        only_b.extend(_display_key(key) for key in sorted(keys_b - keys_a))

        compared_fields = (
            "content_sha256",
            "file_size",
            "compress_size",
            "crc",
            "date_time",
            "extra_hex",
            "external_attr",
            "internal_attr",
            "compress_type",
            "flag_bits",
            "create_system",
            "create_version",
            "extract_version",
            "comment_hex",
            "is_directory",
        )
        for key in sorted(keys_a & keys_b):
            entry_a = entries_a[key]
            entry_b = entries_b[key]
            fields: list[FieldDifference] = []
            for field in compared_fields:
                value_a = getattr(entry_a, field)
                value_b = getattr(entry_b, field)
                if value_a != value_b:
                    fields.append(
                        FieldDifference(
                            field=field,
                            value_a=_field_value(value_a),
                            value_b=_field_value(value_b),
                        )
                    )
            if fields:
                entry_differences.append(
                    EntryDifference(
                        filename=key[0],
                        occurrence=key[1],
                        fields=tuple(fields),
                    )
                )

    return ArtifactComparison(
        path_a=path_a,
        path_b=path_b,
        size_a=size_a,
        size_b=size_b,
        sha256_a=digest_a,
        sha256_b=digest_b,
        archive_a=archive_a,
        archive_b=archive_b,
        inspection_error_a=error_a,
        inspection_error_b=error_b,
        archive_differences=tuple(archive_differences),
        order_differences=tuple(order_differences),
        only_a=tuple(only_a),
        only_b=tuple(only_b),
        entry_differences=tuple(entry_differences),
    )


def format_comparison(comparison: ArtifactComparison) -> str:
    lines = [
        "Cardboard reproducible artifact comparison",
        "==========================================",
        f"Raw byte result: {'PASS' if comparison.bytes_equal else 'FAIL'}",
        f"Artifact A: {comparison.path_a}",
        f"Artifact B: {comparison.path_b}",
        f"Size A: {comparison.size_a}",
        f"Size B: {comparison.size_b}",
        f"SHA-256 A: {comparison.sha256_a}",
        f"SHA-256 B: {comparison.sha256_b}",
        "",
        "The raw SHA-256 comparison is authoritative; ZIP diagnostics cannot",
        "convert byte differences into a passing result.",
        "",
        "ZIP diagnostics",
        "---------------",
    ]

    if comparison.inspection_error_a:
        lines.append(f"Artifact A inspection error: {comparison.inspection_error_a}")
    if comparison.inspection_error_b:
        lines.append(f"Artifact B inspection error: {comparison.inspection_error_b}")

    if comparison.archive_a is not None and comparison.archive_b is not None:
        lines.extend(
            (
                f"Entry count A: {len(comparison.archive_a.entries)}",
                f"Entry count B: {len(comparison.archive_b.entries)}",
                f"Order differences: {len(comparison.order_differences)}",
                f"Entries only in A: {len(comparison.only_a)}",
                f"Entries only in B: {len(comparison.only_b)}",
                f"Entries with content/metadata differences: "
                f"{len(comparison.entry_differences)}",
            )
        )

        for difference in comparison.archive_differences:
            lines.append(
                f"Archive field {difference.field}: "
                f"A={difference.value_a!r}, B={difference.value_b!r}"
            )

        for difference in comparison.order_differences:
            lines.append(
                f"Order[{difference.index}]: "
                f"A={difference.entry_a!r}, B={difference.entry_b!r}"
            )

        for entry in comparison.only_a:
            lines.append(f"Only A: {entry}")
        for entry in comparison.only_b:
            lines.append(f"Only B: {entry}")

        for difference in comparison.entry_differences:
            key = _display_key((difference.filename, difference.occurrence))
            lines.append(f"Entry: {key}")
            for field in difference.fields:
                lines.append(
                    f"  {field.field}: A={field.value_a}, B={field.value_b}"
                )

        if (
            not comparison.bytes_equal
            and not comparison.archive_differences
            and not comparison.order_differences
            and not comparison.only_a
            and not comparison.only_b
            and not comparison.entry_differences
        ):
            lines.append(
                "Raw bytes differ, but the inspected ZIP entry model is identical; "
                "the artifact still FAILS."
            )

    return "\n".join(lines) + "\n"


def _run_git(repository: Path, arguments: Sequence[str]) -> str:
    command = ("git", "-C", str(repository), *arguments)
    try:
        result = subprocess.run(
            command,
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            encoding="utf-8",
            errors="replace",
        )
    except OSError as exc:
        raise VerificationError(f"Cannot execute git: {exc}") from exc
    if result.returncode != 0:
        stderr = result.stderr.strip() or "no stderr"
        raise VerificationError(
            f"Git command failed ({_format_command(command)}): {stderr}"
        )
    return result.stdout


def _status_text(repository: Path) -> str:
    status = _run_git(
        repository,
        ("status", "--porcelain=v1", "--untracked-files=all", "-z"),
    )
    return status.replace("\0", "\n").strip()


def assert_clean_checkout(repository: Path, label: str) -> None:
    status = _status_text(repository)
    if status:
        raise VerificationError(
            f"{label} checkout is not clean; reproducibility verification is "
            f"fail-closed:\n{status}"
        )


def validate_checkout(repository: Path, label: str) -> CheckoutState:
    repository = repository.resolve()
    if not repository.is_dir():
        raise VerificationError(f"{label} checkout does not exist: {repository}")

    inside = _run_git(repository, ("rev-parse", "--is-inside-work-tree")).strip()
    if inside != "true":
        raise VerificationError(f"{label} is not a Git working tree: {repository}")

    top_level_text = _run_git(repository, ("rev-parse", "--show-toplevel")).strip()
    top_level = Path(top_level_text).resolve()
    if top_level != repository:
        raise VerificationError(
            f"{label} must point at the checkout root: {repository} "
            f"(Git root is {top_level})"
        )

    shallow = _run_git(
        repository, ("rev-parse", "--is-shallow-repository")
    ).strip()
    if shallow != "false":
        raise VerificationError(
            f"{label} checkout must contain full Git history (fetch-depth: 0)"
        )

    head = _run_git(repository, ("rev-parse", "--verify", "HEAD")).strip()
    if not head:
        raise VerificationError(f"{label} checkout has no resolvable HEAD")

    submodules = _run_git(repository, ("submodule", "status", "--recursive"))
    incomplete = [
        line
        for line in submodules.splitlines()
        if line and line[0] in ("-", "+", "U")
    ]
    if incomplete:
        raise VerificationError(
            f"{label} has missing or mismatched submodules:\n"
            + "\n".join(incomplete)
        )

    assert_clean_checkout(repository, label)
    return CheckoutState(root=repository, head=head)


def assert_checkout_unchanged(state: CheckoutState, label: str) -> None:
    current_head = _run_git(
        state.root, ("rev-parse", "--verify", "HEAD")
    ).strip()
    if current_head != state.head:
        raise VerificationError(
            f"{label} HEAD changed during verification: "
            f"{state.head} -> {current_head}"
        )
    assert_clean_checkout(state.root, label)


def gradle_command(repository: Path) -> tuple[str, ...]:
    if os.name == "nt":
        wrapper = repository / "gradlew.bat"
        if not wrapper.is_file():
            raise VerificationError(f"Gradle Wrapper is missing: {wrapper}")
        command_processor = os.environ.get("COMSPEC", "cmd.exe")
        return (command_processor, "/d", "/c", str(wrapper), *GRADLE_ARGUMENTS)

    wrapper = repository / "gradlew"
    if not wrapper.is_file():
        raise VerificationError(f"Gradle Wrapper is missing: {wrapper}")
    if not os.access(wrapper, os.X_OK):
        raise VerificationError(f"Gradle Wrapper is not executable: {wrapper}")
    return (str(wrapper), *GRADLE_ARGUMENTS)


def _format_command(command: Iterable[str]) -> str:
    values = tuple(command)
    return subprocess.list2cmdline(values) if os.name == "nt" else shlex.join(values)


def build_artifact(state: CheckoutState, label: str) -> tuple[Path, tuple[str, ...]]:
    command = gradle_command(state.root)
    print(f"[{label}] {_format_command(command)}", flush=True)
    try:
        result = subprocess.run(command, cwd=state.root, check=False)
    except OSError as exc:
        raise VerificationError(f"Cannot execute Gradle Wrapper: {exc}") from exc
    if result.returncode != 0:
        raise VerificationError(
            f"{label} Gradle build failed with exit code {result.returncode}"
        )

    artifact = canonical_artifact_path(state.root)
    if not artifact.is_file():
        raise VerificationError(
            f"{label} did not produce the configured canonical artifact: {artifact}"
        )
    return artifact, command


def _copy_build_artifact(source: Path, destination: Path) -> None:
    try:
        shutil.copyfile(source, destination)
    except OSError as exc:
        raise VerificationError(
            f"Cannot preserve build artifact {source} as {destination}: {exc}"
        ) from exc


def write_failure_report(
    first_checkout: CheckoutState,
    second_checkout: CheckoutState,
    artifact_a: Path,
    artifact_b: Path,
    command_a: Sequence[str],
    command_b: Sequence[str],
    comparison: ArtifactComparison,
) -> tuple[Path, Path, Path]:
    report_directory = (
        first_checkout.root / "build" / "reports" / "reproducibility"
    )
    report_directory.mkdir(parents=True, exist_ok=True)
    preserved_a = report_directory / "artifact-a.jar"
    preserved_b = report_directory / "artifact-b.jar"
    report_path = report_directory / "comparison.txt"
    shutil.copyfile(artifact_a, preserved_a)
    shutil.copyfile(artifact_b, preserved_b)

    context = [
        f"Checkout A: {first_checkout.root}",
        f"Checkout B: {second_checkout.root}",
        f"HEAD A: {first_checkout.head}",
        f"HEAD B: {second_checkout.head}",
        f"Command A: {_format_command(command_a)}",
        f"Command B: {_format_command(command_b)}",
        "",
    ]
    report_path.write_text(
        "\n".join(context) + format_comparison(comparison),
        encoding="utf-8",
        newline="\n",
    )
    return preserved_a, preserved_b, report_path


def verify(second_checkout_path: Path | None) -> int:
    first = validate_checkout(REPOSITORY_ROOT, "First")
    if second_checkout_path is None:
        second = first
    else:
        second = validate_checkout(second_checkout_path, "Second")
        if first.head != second.head:
            raise VerificationError(
                "Checkouts are not at the same HEAD: "
                f"{first.head} != {second.head}"
            )

    with tempfile.TemporaryDirectory(prefix="cardboard-reproducibility-") as temp:
        temporary = Path(temp)
        artifact_a_source, command_a = build_artifact(first, "Build A")
        artifact_a = temporary / "artifact-a.jar"
        _copy_build_artifact(artifact_a_source, artifact_a)
        assert_checkout_unchanged(first, "First")

        artifact_b_source, command_b = build_artifact(second, "Build B")
        artifact_b = temporary / "artifact-b.jar"
        _copy_build_artifact(artifact_b_source, artifact_b)
        assert_checkout_unchanged(second, "Second")
        if first is not second:
            assert_checkout_unchanged(first, "First")

        comparison = compare_artifacts(artifact_a, artifact_b)
        if comparison.bytes_equal:
            print(
                "Reproducibility PASS: "
                f"{comparison.sha256_a}  "
                f"{canonical_artifact_name(parse_gradle_properties((first.root / 'gradle.properties').read_text(encoding='utf-8')))}"
            )
            return 0

        try:
            preserved_a, preserved_b, report_path = write_failure_report(
                first,
                second,
                artifact_a,
                artifact_b,
                command_a,
                command_b,
                comparison,
            )
        except OSError as exc:
            raise VerificationError(
                "Artifacts differ and writing mismatch diagnostics also failed: "
                f"{exc}\n{format_comparison(comparison)}"
            ) from exc

        print(format_comparison(comparison), file=sys.stderr, end="")
        print(f"Preserved artifact A: {preserved_a}", file=sys.stderr)
        print(f"Preserved artifact B: {preserved_b}", file=sys.stderr)
        print(f"Detailed report: {report_path}", file=sys.stderr)
        return 1


def parse_arguments(arguments: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description=(
            "Build Cardboard twice from a clean full Git checkout and require "
            "an identical canonical JAR SHA-256."
        )
    )
    parser.add_argument(
        "--second-checkout",
        type=Path,
        help=(
            "clean full checkout at the same HEAD; build each checkout once "
            "instead of building the primary checkout twice"
        ),
    )
    return parser.parse_args(arguments)


def main(arguments: Sequence[str] | None = None) -> int:
    options = parse_arguments(arguments)
    try:
        return verify(options.second_checkout)
    except VerificationError as exc:
        print(f"Reproducibility verification ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
