from __future__ import annotations

import hashlib
from pathlib import Path
import sys
import tempfile
import unittest
import warnings
import zipfile


sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import verify  # noqa: E402


FIXED_TIME = (2026, 8, 20, 12, 0, 0)


def write_zip(
    path: Path,
    entries: list[dict[str, object]],
    *,
    archive_comment: bytes = b"",
) -> None:
    with zipfile.ZipFile(path, "w") as archive:
        archive.comment = archive_comment
        for entry in entries:
            info = zipfile.ZipInfo(str(entry["name"]), entry.get("time", FIXED_TIME))
            info.compress_type = int(entry.get("compression", zipfile.ZIP_DEFLATED))
            info.extra = bytes(entry.get("extra", b""))
            info.external_attr = int(entry.get("external_attr", 0o100644 << 16))
            info.internal_attr = int(entry.get("internal_attr", 0))
            info.comment = bytes(entry.get("comment", b""))
            archive.writestr(info, bytes(entry.get("data", b"")))


class GradlePropertiesTests(unittest.TestCase):
    def test_parses_repository_style_properties(self) -> None:
        properties = verify.parse_gradle_properties(
            """
            # comment
              mod_version = 26.2
            archives_base_name: Cardboard
            ignored_without_value
            ! another comment
            """
        )

        self.assertEqual("26.2", properties["mod_version"])
        self.assertEqual("Cardboard", properties["archives_base_name"])
        self.assertNotIn("ignored_without_value", properties)
        self.assertEqual(
            "Cardboard-26.2.jar", verify.canonical_artifact_name(properties)
        )

    def test_artifact_name_requires_both_values(self) -> None:
        with self.assertRaises(verify.VerificationError):
            verify.canonical_artifact_name({"mod_version": "26.2"})

    def test_artifact_name_rejects_path_components(self) -> None:
        with self.assertRaises(verify.VerificationError):
            verify.canonical_artifact_name(
                {"archives_base_name": "../Cardboard", "mod_version": "26.2"}
            )


class ZipComparisonTests(unittest.TestCase):
    def test_identical_archives_pass_exact_byte_comparison(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact_a = root / "a.jar"
            artifact_b = root / "b.jar"
            entries = [
                {"name": "META-INF/MANIFEST.MF", "data": b"Manifest-Version: 1.0\n"},
                {"name": "org/cardboardpowered/Test.class", "data": b"class-bytes"},
            ]
            write_zip(artifact_a, entries)
            write_zip(artifact_b, entries)

            comparison = verify.compare_artifacts(artifact_a, artifact_b)

            self.assertTrue(comparison.bytes_equal)
            self.assertEqual((), comparison.order_differences)
            self.assertEqual((), comparison.entry_differences)
            self.assertEqual(hashlib.sha256(artifact_a.read_bytes()).hexdigest(), comparison.sha256_a)

    def test_entry_order_difference_is_reported_and_fails(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact_a = root / "a.jar"
            artifact_b = root / "b.jar"
            first = {"name": "a.txt", "data": b"a"}
            second = {"name": "b.txt", "data": b"b"}
            write_zip(artifact_a, [first, second])
            write_zip(artifact_b, [second, first])

            comparison = verify.compare_artifacts(artifact_a, artifact_b)

            self.assertFalse(comparison.bytes_equal)
            self.assertEqual(2, len(comparison.order_differences))
            self.assertEqual((), comparison.entry_differences)

    def test_content_and_zip_metadata_differences_are_reported(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact_a = root / "a.jar"
            artifact_b = root / "b.jar"
            valid_extra_a = b"\xca\xfe\x01\x00A"
            valid_extra_b = b"\xca\xfe\x01\x00B"
            write_zip(
                artifact_a,
                [
                    {
                        "name": "entry.bin",
                        "data": b"content-a" * 100,
                        "time": FIXED_TIME,
                        "compression": zipfile.ZIP_DEFLATED,
                        "extra": valid_extra_a,
                        "external_attr": 0o100644 << 16,
                        "internal_attr": 0,
                        "comment": b"A",
                    }
                ],
            )
            write_zip(
                artifact_b,
                [
                    {
                        "name": "entry.bin",
                        "data": b"content-b" * 100,
                        "time": (2026, 8, 20, 12, 0, 2),
                        "compression": zipfile.ZIP_STORED,
                        "extra": valid_extra_b,
                        "external_attr": 0o100755 << 16,
                        "internal_attr": 1,
                        "comment": b"B",
                    }
                ],
            )

            comparison = verify.compare_artifacts(artifact_a, artifact_b)

            self.assertFalse(comparison.bytes_equal)
            self.assertEqual(1, len(comparison.entry_differences))
            fields = {
                difference.field
                for difference in comparison.entry_differences[0].fields
            }
            self.assertTrue(
                {
                    "content_sha256",
                    "crc",
                    "date_time",
                    "extra_hex",
                    "external_attr",
                    "internal_attr",
                    "compress_type",
                    "comment_hex",
                }.issubset(fields)
            )
            report = verify.format_comparison(comparison)
            self.assertIn("Raw byte result: FAIL", report)
            self.assertIn("Entry: entry.bin", report)

    def test_archive_comment_difference_cannot_be_ignored(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact_a = root / "a.jar"
            artifact_b = root / "b.jar"
            entries = [{"name": "same.txt", "data": b"same"}]
            write_zip(artifact_a, entries, archive_comment=b"comment-a")
            write_zip(artifact_b, entries, archive_comment=b"comment-b")

            comparison = verify.compare_artifacts(artifact_a, artifact_b)

            self.assertFalse(comparison.bytes_equal)
            self.assertEqual(1, len(comparison.archive_differences))
            self.assertEqual(
                "archive_comment_hex", comparison.archive_differences[0].field
            )

    def test_unmodeled_raw_byte_difference_cannot_pass(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact_a = root / "a.jar"
            artifact_b = root / "b.jar"
            entries = [{"name": "same.txt", "data": b"same"}]
            write_zip(artifact_a, entries)
            write_zip(artifact_b, entries)
            with artifact_b.open("ab") as destination:
                destination.write(b"trailing-container-byte")

            comparison = verify.compare_artifacts(artifact_a, artifact_b)

            self.assertFalse(comparison.bytes_equal)
            self.assertEqual((), comparison.archive_differences)
            self.assertEqual((), comparison.order_differences)
            self.assertEqual((), comparison.entry_differences)
            self.assertIn(
                "inspected ZIP entry model is identical",
                verify.format_comparison(comparison),
            )

    def test_duplicate_entry_occurrences_are_compared_independently(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifact_a = root / "a.jar"
            artifact_b = root / "b.jar"
            entries_a = [
                {"name": "duplicate.txt", "data": b"first"},
                {"name": "duplicate.txt", "data": b"second-a"},
            ]
            entries_b = [
                {"name": "duplicate.txt", "data": b"first"},
                {"name": "duplicate.txt", "data": b"second-b"},
            ]
            with warnings.catch_warnings():
                warnings.filterwarnings(
                    "ignore", message="Duplicate name:.*", category=UserWarning
                )
                write_zip(artifact_a, entries_a)
                write_zip(artifact_b, entries_b)

            comparison = verify.compare_artifacts(artifact_a, artifact_b)

            self.assertFalse(comparison.bytes_equal)
            self.assertEqual(1, len(comparison.entry_differences))
            self.assertEqual(1, comparison.entry_differences[0].occurrence)


if __name__ == "__main__":
    unittest.main()
