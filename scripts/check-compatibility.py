#!/usr/bin/env python3
"""Compare a published-style candidate jar with the 0.164.0-1 compatibility boundary."""

import argparse
import difflib
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile


PREFIX = "io/github/shiftinglabs/codej/"
CHECKSUMS = PREFIX + "sdk/impl/codex-checksums.properties"
BASE_VERSION = "0.164.0-1"


def run(command):
    result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    if result.returncode:
        raise RuntimeError(f"Command failed: {command}\n{result.stdout}{result.stderr}")
    return result.stdout


def jdk_tool(name):
    home = os.environ.get("JAVA_HOME")
    executable = str(Path(home) / "bin" / name) if home else shutil.which(name)
    if not executable or not Path(executable).is_file():
        raise RuntimeError(f"JDK tool not found: {name}; set JAVA_HOME to JDK 25 or newer")
    return executable


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def compare(output, name, before, after):
    (output / f"{name}-baseline.txt").write_text(before)
    (output / f"{name}-candidate.txt").write_text(after)
    delta = "".join(difflib.unified_diff(
        before.splitlines(True), after.splitlines(True),
        fromfile="published-" + BASE_VERSION, tofile="candidate"))
    (output / f"{name}.diff").write_text(delta)
    if delta:
        raise RuntimeError(f"Compatibility mismatch: {output / (name + '.diff')}")


# Explicit additive members only. Keep every published descriptor, generic declaration and modifier exact.
ADDITIVE_API_MEMBERS = {
    "public-api": (
        "  public static <A> io.github.shiftinglabs.codej.Tool withParameters(java.lang.String, java.lang.String, "
        "io.github.shiftinglabs.codej.tool.ToolParameters<A>, io.github.shiftinglabs.codej.Tool$Handler<A>);\n"
        "    descriptor: (Ljava/lang/String;Ljava/lang/String;Lio/github/shiftinglabs/codej/tool/ToolParameters;"
        "Lio/github/shiftinglabs/codej/Tool$Handler;)Lio/github/shiftinglabs/codej/Tool;\n\n",
    ),
}


def published_surface(group, text):
    for member in ADDITIVE_API_MEMBERS.get(group, ()):
        if text.count(member) > 1:
            raise RuntimeError("Duplicate allowed additive member")
        text = text.replace(member, "")
    return text


def check_shading(names):
    for sentinel in ("internal/jackson/databind/ObjectMapper.class", "internal/google/common/base/Preconditions.class"):
        if PREFIX + sentinel not in names:
            raise RuntimeError(f"Missing relocated dependency class: {PREFIX + sentinel}")
    unrelocated = sorted(n for n in names if re.match(r"^(?:META-INF/versions/\d+/)?com/(google|fasterxml)/.*\.class$", n))
    if unrelocated:
        raise RuntimeError(f"Unrelocated dependency classes in candidate: {unrelocated[:5]}")


def verify(args, report):
    baseline, candidate, output = args.baseline.resolve(), args.candidate.resolve(), args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    report.update(baseline=str(baseline), candidate=str(candidate),
                  baseline_sha256=sha256(baseline), candidate_sha256=sha256(candidate))
    with zipfile.ZipFile(baseline) as old, zipfile.ZipFile(candidate) as new:
        check_shading(new.namelist())
        report["passed"].append("relocated Jackson/Guava sentinels; no unrelocated dependency classes")
        names = old.namelist()
        groups = {
            "public-api": sorted(n[:-6].replace("/", ".") for n in names
                                 if n.startswith(PREFIX) and "/" not in n[len(PREFIX):] and n.endswith(".class")),
            "legacy-driver-spi": sorted(n[:-6].replace("/", ".") for n in names
                                        if n.startswith(PREFIX + "cli/driver/") and n.endswith(".class")),
            "builder-tool-server": ["io.github.shiftinglabs.codej.cli.mcp.ToolServer"],
        }
        if not all(groups.values()):
            raise RuntimeError("Baseline is missing the expected compatibility surface")
        # Bytequay reads this exact path directly from the shaded artifact.
        if old.read(CHECKSUMS) != new.read(CHECKSUMS):
            raise RuntimeError("Codex checksum resource changed or moved")
        report["passed"].append("exact Codex checksum resource bytes/path")
        manifest = new.read("META-INF/MANIFEST.MF").decode().replace("\r\n ", "")
        version = re.search(r"^Implementation-Version: (.+)$", manifest, re.MULTILINE)
        if not version:
            raise RuntimeError("Candidate has no Implementation-Version")
        report["candidate_version"] = version.group(1).strip()
        if not re.search(r"^Implementation-Version: " + re.escape(BASE_VERSION) + r"\s*$",
                         old.read("META-INF/MANIFEST.MF").decode(), re.MULTILINE):
            raise RuntimeError("Baseline is not the published 0.164.0-1 version")
        for group, classes in groups.items():
            options = [jdk_tool("javap"), "-protected", "-s", "-constants"]
            before = run(options + ["-classpath", str(baseline), *classes])
            after = run(options + ["-classpath", str(candidate), *classes])
            (output / f"{group}-candidate-full.txt").write_text(after)
            compare(output, group, before, published_surface(group, after))
            report["passed"].append(f"{group}: {len(classes)} class signatures/constructors/constants")

    fixture = Path(__file__).parent / "compatibility" / "LegacyConsumer.java"
    with tempfile.TemporaryDirectory(prefix="codej-old-consumer-") as folder:
        classes = Path(folder) / "classes"
        classes.mkdir()
        # Compile ONCE with only the published artifact. Never compile against the candidate.
        run([jdk_tool("javac"), "--release", "25", "-cp", str(baseline), "-d", str(classes), str(fixture)])
        bytecode = {str(p.relative_to(classes)): sha256(p) for p in classes.rglob("*.class")}

        def consumer(jar, *arguments):
            return run([jdk_tool("java"), "-cp", os.pathsep.join((str(classes), str(jar))),
                        "LegacyConsumer", *arguments])

        inventory = sorted({name for group in groups.values() for name in group})
        compare(output, "records-enums-runtime-constants",
                consumer(baseline, "surface", *inventory), consumer(candidate, "surface", *inventory))
        report["passed"].append("record components, enum order, sealed status/permitted subclasses, runtime constants")
        for label, jar in (("baseline", baseline), ("candidate", candidate)):
            state = Path(folder) / label
            state.mkdir()
            result = consumer(jar, "smoke", str(state),
                              BASE_VERSION if label == "baseline" else report["candidate_version"])
            (output / f"old-consumer-{label}.txt").write_text(result)
            report["passed"].append(f"unchanged baseline-compiled consumer/legacy connector on {label}; JDK + jar only")
        if bytecode != {str(p.relative_to(classes)): sha256(p) for p in classes.rglob("*.class")}:
            raise RuntimeError("Old consumer bytecode changed during execution")
        report["old_consumer_class_sha256"] = bytecode

        # Kotlin reflection metadata is not rewritten by Shade; exercise the real SDK after relocation.
        with zipfile.ZipFile(candidate) as artifact:
            has_claude_api = PREFIX + "ClaudeApi.class" in artifact.namelist()
        if has_claude_api:
            direct_fixture = Path(__file__).parent / "compatibility" / "ClaudeApiConsumer.java"
            run([jdk_tool("javac"), "--release", "25", "-cp", str(candidate), "-d", str(classes), str(direct_fixture)])
            result = run([jdk_tool("java"), "-cp", os.pathsep.join((str(classes), str(candidate))),
                          "ClaudeApiConsumer", str(Path(folder) / "claude-api")])
            (output / "claude-api-consumer.txt").write_text(result)
            report["passed"].append("ClaudeApi fake HTTP: shaded SDK, signed tool replay, durable resume and key rotation; JDK + jar only")
            if sys.platform == "darwin":
                result = run([jdk_tool("java"), "-cp", os.pathsep.join((str(classes), str(candidate))),
                              "ClaudeApiConsumer", str(Path(folder) / "coding-api"), "coding"])
                (output / "coding-api-consumer.txt").write_text(result)
                report["passed"].append("Coding tools: shaded record binding, file reads, native sandbox lifecycle and resume; JDK + jar only")

        with zipfile.ZipFile(candidate) as artifact:
            has_deepseek_api = PREFIX + "DeepSeekApi.class" in artifact.namelist()
        if has_deepseek_api:
            direct_fixture = Path(__file__).parent / "compatibility" / "DeepSeekApiConsumer.java"
            run([jdk_tool("javac"), "--release", "25", "-cp", str(candidate), "-d", str(classes), str(direct_fixture)])
            result = run([jdk_tool("java"), "-cp", os.pathsep.join((str(classes), str(candidate))),
                          "DeepSeekApiConsumer", str(Path(folder) / "deepseek-api")])
            (output / "deepseek-api-consumer.txt").write_text(result)
            report["passed"].append("DeepSeekApi fake HTTP: private reasoning, host tools, durable resume and key rotation; JDK + jar only")

        with zipfile.ZipFile(candidate) as artifact:
            has_tool_spi = PREFIX + "tool/ToolParameters.class" in artifact.namelist()
        if has_tool_spi:
            tool_fixture = Path(__file__).parent / "compatibility" / "ToolSpiConsumer.java"
            run([jdk_tool("javac"), "--release", "25", "-cp", str(candidate), "-d", str(classes), str(tool_fixture)])
            result = run([jdk_tool("java"), "-cp", os.pathsep.join((str(classes), str(candidate))), "ToolSpiConsumer"])
            (output / "tool-spi-consumer.txt").write_text(result)
            report["passed"].append("Tool SPI: interface-valued parameters, provider installation, typed results and verified shutdown; JDK + jar only")

        with zipfile.ZipFile(candidate) as artifact:
            has_compaction = PREFIX + "cli/spi/context/ContextCompactor.class" in artifact.namelist()
        if has_compaction:
            context_fixture = Path(__file__).parent / "compatibility" / "ContextCompactionConsumer.java"
            run([jdk_tool("javac"), "--release", "25", "-cp", str(candidate), "-d", str(classes), str(context_fixture)])
            result = run([jdk_tool("java"), "-cp", os.pathsep.join((str(classes), str(candidate))),
                          "ContextCompactionConsumer", str(Path(folder) / "context-api")])
            (output / "context-compaction-consumer.txt").write_text(result)
            report["passed"].append("Context compaction: local Claude/DeepSeek replay rewrite, full audit history and durable resume; JDK + jar only")

        with zipfile.ZipFile(candidate) as artifact:
            has_summary = PREFIX + "cli/spi/context/ContextSummarizationPolicy.class" in artifact.namelist()
        if has_summary:
            summary_fixture = Path(__file__).parent / "compatibility" / "ContextSummarizationConsumer.java"
            classpath = os.pathsep.join((str(classes), str(candidate)))
            run([jdk_tool("javac"), "--release", "25", "-cp", classpath, "-d", str(classes), str(summary_fixture)])
            result = run([jdk_tool("java"), "-cp", classpath, "ContextSummarizationConsumer", str(Path(folder) / "summary-api")])
            (output / "context-summarization-consumer.txt").write_text(result)
            report["passed"].append("LLM summarization: budgeted Claude/DeepSeek summary calls and durable replay; JDK + jar only")

        with zipfile.ZipFile(candidate) as artifact:
            has_incremental = PREFIX + "cli/spi/model/ModelCompactionMemory.class" in artifact.namelist()
        if has_incremental:
            fixture = Path(__file__).parent / "compatibility" / "IncrementalSummaryConsumer.java"
            classpath = os.pathsep.join((str(classes), str(candidate)))
            run([jdk_tool("javac"), "--release", "25", "-cp", classpath, "-d", str(classes), str(fixture)])
            result = run([jdk_tool("java"), "-cp", classpath, "IncrementalSummaryConsumer", str(Path(folder) / "incremental-api")])
            (output / "incremental-summary-consumer.txt").write_text(result)
            report["passed"].append("Incremental summaries: bounded Claude/DeepSeek chunks and durable memory; JDK + jar only")

        with zipfile.ZipFile(candidate) as artifact:
            has_retention = PREFIX + "cli/spi/retention/AuditRetentionPolicy.class" in artifact.namelist()
        if has_retention:
            fixture = Path(__file__).parent / "compatibility" / "AuditRetentionConsumer.java"
            classpath = os.pathsep.join((str(classes), str(candidate)))
            run([jdk_tool("javac"), "--release", "25", "-cp", classpath, "-d", str(classes), str(fixture)])
            fixture_root = Path(folder) / "retention-api"
            result = run([jdk_tool("java"), "-cp", classpath, "AuditRetentionConsumer", str(fixture_root)])
            (output / "audit-retention-consumer.txt").write_text(result)
            (output / "retained-checkpoint.json").write_bytes((fixture_root / "claude-retained-checkpoint.json").read_bytes())
            report["passed"].append("Audit retention: Claude/DeepSeek pruned format-3 history and durable resume; JDK + jar only")

        with zipfile.ZipFile(candidate) as artifact:
            has_context_budget = PREFIX + "cli/spi/context/ContextBudget.class" in artifact.namelist()
        if has_context_budget:
            fixture = Path(__file__).parent / "compatibility" / "ContextBudgetConsumer.java"
            classpath = os.pathsep.join((str(classes), str(candidate)))
            run([jdk_tool("javac"), "--release", "25", "-cp", classpath, "-d", str(classes), str(fixture)])
            result = run([jdk_tool("java"), "-cp", classpath, "ContextBudgetConsumer", str(Path(folder) / "context-budget-api")])
            (output / "context-budget-consumer.txt").write_text(result)
            report["passed"].append("Context budgets: provider sizing, output caps, pressured compaction and resume; JDK + jar only")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--baseline", type=Path, default=Path.home() / ".m2/repository/io/github/shifting-labs/shifting-codej/0.164.0-1/shifting-codej-0.164.0-1.jar")
    parser.add_argument("--candidate", type=Path, required=True, help="Fresh jar built with -Ppublish, not reactor classes")
    parser.add_argument("--output", type=Path, required=True, help="Evidence directory outside the source checkout")
    args = parser.parse_args()
    report = {"passed": [], "limitations": [
        "A fixture proves only the behaviors it exercises, not all downstream behavior.",
        "Rerun on the final coordinator-integrated shaded jar and run affected unmodified Bytequay tests.",
        "No live inference, Codex download, credentials, database, or default-home behavior is exercised.",
    ]}
    try:
        verify(args, report)
        report["success"] = True
    except (OSError, RuntimeError, KeyError, zipfile.BadZipFile, subprocess.TimeoutExpired) as failure:
        report.update(success=False, failure=str(failure))
    args.output.mkdir(parents=True, exist_ok=True)
    (args.output / "summary.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    return 0 if report["success"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
