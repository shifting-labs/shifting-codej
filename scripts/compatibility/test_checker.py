"""Small positive/negative controls for the compatibility gate; no checkout/build writes."""

import importlib.util
import os
from pathlib import Path
import sys
import tempfile
import unittest

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

    def test_unshaded_or_missing_dependencies_are_rejected(self):
        relocated = [CHECK.PREFIX + "internal/jackson/databind/ObjectMapper.class",
                     CHECK.PREFIX + "internal/google/common/base/Preconditions.class"]
        CHECK.check_shading(relocated)
        for sentinel in relocated:
            with self.assertRaisesRegex(RuntimeError, "Missing relocated"):
                CHECK.check_shading([n for n in relocated if n != sentinel])
        for prefix in ("", "META-INF/versions/25/"):
            for dependency in ("com/google/common/base/Preconditions.class", "com/fasterxml/jackson/databind/ObjectMapper.class"):
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
