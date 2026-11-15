"""Small positive/negative controls for the compatibility gate; no checkout/build writes."""

import hashlib
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).resolve().parents[1] / "check-compatibility.py"
SPEC = importlib.util.spec_from_file_location("compatibility", SCRIPT)
CHECK = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(CHECK)


class CheckerControls(unittest.TestCase):
    def test_descriptor_change_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            CHECK.compare(output, "same", "descriptor ()V\n", "descriptor ()V\n")
            with self.assertRaisesRegex(RuntimeError, "Compatibility mismatch"):
                CHECK.compare(output, "changed", "descriptor ()V\n", "descriptor (I)V\n")

    def test_only_reviewed_additive_member_preserves_the_published_surface(self):
        addition = CHECK.ADDITIVE_API_MEMBERS["public-api"][0]
        before = "public final class Tool {\n  public void call();\n    descriptor: ()V\n\n}\n"
        after = before.replace("}\n", addition + "}\n")
        with tempfile.TemporaryDirectory() as folder:
            output = Path(folder)
            CHECK.compare(output, "allowed", before, CHECK.published_surface("public-api", after))
            for incompatible in (after.replace("descriptor: ()V", "descriptor: (I)V"),
                                 after.replace("  public void call();\n    descriptor: ()V\n\n", ""),
                                 after.replace("}\n", "  public void unreviewed();\n    descriptor: ()V\n}\n")):
                with self.assertRaisesRegex(RuntimeError, "Compatibility mismatch"):
                    CHECK.compare(output, "changed", before, CHECK.published_surface("public-api", incompatible))
        self.assertEqual(after, CHECK.published_surface("legacy-driver-spi", after))

    def test_duplicate_reviewed_additive_member_is_rejected(self):
        addition = CHECK.ADDITIVE_API_MEMBERS["public-api"][0]
        with self.assertRaisesRegex(RuntimeError, "Duplicate allowed additive member"):
            CHECK.published_surface("public-api", addition + addition)

    def test_only_reviewed_codex_version_is_accepted(self):
        declaration = '  public static final java.lang.String VERSION = "0.164.0";\n'
        with patch.object(CHECK, "run", return_value=declaration):
            self.assertEqual(CHECK.BASE_CODEX_VERSION, CHECK.codex_version("fixture.jar"))
        for changed in (declaration.replace("0.164.0", "unreviewed"),
                        declaration.replace("public static final", "public static")):
            with patch.object(CHECK, "run", return_value=changed):
                with self.assertRaisesRegex(RuntimeError, "Unreviewed Codex"):
                    CHECK.codex_version("fixture.jar")

    def test_checksum_resource_requires_exact_reviewed_bytes(self):
        data = b"# reviewed checksum fixture\n"
        digest = hashlib.sha256(data).hexdigest()
        with patch.dict(CHECK.CODEX_CHECKSUMS, {"fixture": digest}):
            self.assertEqual(digest, CHECK.check_codex_checksums(data, "fixture"))
            for changed, version in ((data + b"\n", "fixture"), (data, "unreviewed")):
                with self.assertRaisesRegex(RuntimeError, "checksum resource does not match"):
                    CHECK.check_codex_checksums(changed, version)

    def test_codex_pin_normalization_preserves_every_other_declaration_and_constant(self):
        before = ('public final class io.github.shiftinglabs.codej.Codex {\n'
                  '  public static final java.lang.String VERSION = "0.164.0";\n'
                  '    descriptor: Ljava/lang/String;\n}\n'
                  'public final class Other {\n  public static final int VERSION = 1;\n}\n')
        after = before.replace('"0.164.0"', '"fixture"')
        runtime = "io.github.shiftinglabs.codej.Codex.VERSION=0.164.0\nOther.VERSION=1\n"
        with patch.dict(CHECK.CODEX_CHECKSUMS, {"fixture": "fixture"}):
            self.assertEqual(before, CHECK.normalize_codex_version("public-api", after, "fixture"))
            self.assertEqual(runtime, CHECK.normalize_codex_version("records-enums-runtime-constants",
                                                                  runtime.replace("0.164.0", "fixture"), "fixture"))
            self.assertEqual(after, CHECK.normalize_codex_version("legacy-driver-spi", after, "fixture"))
            with self.assertRaisesRegex(RuntimeError, "declaration changed or missing"):
                CHECK.normalize_codex_version("public-api", after.replace("public static final", "public static"), "fixture")
            with tempfile.TemporaryDirectory() as folder:
                for changed in (after.replace("descriptor: Ljava/lang/String;", "descriptor: I"),
                                after.replace("VERSION = 1", "VERSION = 2")):
                    with self.assertRaisesRegex(RuntimeError, "Compatibility mismatch"):
                        CHECK.compare(Path(folder), "changed", before, CHECK.normalize_codex_version("public-api", changed, "fixture"))
                with self.assertRaisesRegex(RuntimeError, "Compatibility mismatch"):
                    CHECK.compare(Path(folder), "runtime", runtime, CHECK.normalize_codex_version("records-enums-runtime-constants",
                                  runtime.replace("0.164.0", "fixture").replace("Other.VERSION=1", "Other.VERSION=2"), "fixture"))

    def test_unshaded_or_missing_dependencies_are_rejected(self):
        relocated = [CHECK.PREFIX + "internal/jackson/databind/ObjectMapper.class",
                     CHECK.PREFIX + "internal/google/common/base/Preconditions.class"]
        CHECK.check_shading(relocated)
        for sentinel in relocated:
            with self.assertRaisesRegex(RuntimeError, "Missing relocated"):
                CHECK.check_shading([n for n in relocated if n != sentinel])
        for prefix in ("", "META-INF/versions/25/"):
            for dependency in ("com/google/common/base/Preconditions.class", "com/fasterxml/jackson/databind/ObjectMapper.class",
                               "org/intellij/lang/annotations/Language.class", "Foreign.class"):
                with self.assertRaisesRegex(RuntimeError, "Unrelocated dependency"):
                    CHECK.check_shading(relocated + [prefix + dependency])

    def test_added_sealed_subtype_is_rejected_even_with_identical_javap(self):
        baseline = Path.home() / ".m2/repository/io/github/shifting-labs/shifting-codej/0.164.0-1/shifting-codej-0.164.0-1.jar"
        self.assertTrue(baseline.is_file(), "Cached published baseline is required")
        with tempfile.TemporaryDirectory(prefix="codej-sealed-control-") as folder:
            root = Path(folder)
            consumer = root / "consumer"
            consumer.mkdir()
            CHECK.run([CHECK.jdk_tool("javac"), "--release", "25", "-cp", str(baseline), "-d", str(consumer),
                       str(SCRIPT.parent / "compatibility/LegacyConsumer.java")])
            snapshots, descriptors = [], []
            for label, permits, extra in (("before", "First", ""), ("after", "First, Second", "final class Second implements Sample {}")):
                classes = root / label
                classes.mkdir()
                source = classes / "Sample.java"
                source.write_text(f"public sealed interface Sample permits {permits} {{}}\nfinal class First implements Sample {{}}\n{extra}\n")
                CHECK.run([CHECK.jdk_tool("javac"), "--release", "25", "-d", str(classes), str(source)])
                descriptors.append(CHECK.run([CHECK.jdk_tool("javap"), "-protected", "-s", "-constants", "-cp", str(classes), "Sample"]))
                snapshots.append(CHECK.run([CHECK.jdk_tool("java"), "-cp", os.pathsep.join(map(str, (consumer, classes, baseline))),
                                            "LegacyConsumer", "surface", "Sample"]))
            self.assertEqual(*descriptors)
            self.assertIn("sealed=true permits=[First]", snapshots[0])
            self.assertIn("sealed=true permits=[First, Second]", snapshots[1])
            with self.assertRaisesRegex(RuntimeError, "Compatibility mismatch"):
                CHECK.compare(root, "sealed-subtypes", *snapshots)


if __name__ == "__main__":
    unittest.main()
