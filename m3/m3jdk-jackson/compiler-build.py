#!/usr/bin/env python3
# Copyright 2026 Hitesh Soliwal and contributors.
# SPDX-License-Identifier: Apache-2.0
"""Qualify the optional Jackson compiler JMOD with the actual four-source M3AST owner."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path

COMPILER_SOURCES = (
    "com/sun/source/util/M3AST.java",
    "com/sun/tools/javac/m3/M3ASTPC.java",
    "com/sun/tools/javac/m3/M3PrimitiveImageCompiler.java",
    "com/sun/tools/javac/main/JavaCompiler.java",
)
MODULES = ("com.fasterxml.jackson.annotation", "com.fasterxml.jackson.core",
           "com.fasterxml.jackson.databind", "jdk.m3.jackson", "jdk.m3.jackson.compiler")


def run(log, *args, expected=None):
    command = [str(arg) for arg in args]
    result = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                            text=True, encoding="utf-8", timeout=300)
    log.write_text(json.dumps(command) + "\n" + result.stdout + "\nEXIT "
                   + str(result.returncode) + "\n", encoding="utf-8")
    if result.returncode or (expected is not None and not any(
            line.startswith(expected) for line in result.stdout.splitlines())):
        raise RuntimeError("compiler module proof failed: " + str(log))
    return result.stdout


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def contract_counts(text):
    patterns = {
        "baseline": r"M3_AST_BINDINGS_PASS shapes=([1-9][0-9]*) projections=([1-9][0-9]*) hits=([1-9][0-9]*)",
        "extended": r"M3_AST_BINDINGS_EXTENDED_PASS checks=([1-9][0-9]*)",
    }
    counts = {}
    for name, pattern in patterns.items():
        matches = [match for line in text.splitlines() if (match := re.fullmatch(pattern, line))]
        if len(matches) != 1:
            raise ValueError("one nonzero M3AST " + name + " contract marker required")
        counts[name] = [int(value) for value in matches[0].groups()]
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jdk-home", type=Path, required=True)
    parser.add_argument("--repo-root", type=Path, required=True)
    parser.add_argument("--jars", type=Path, required=True,
                        help="directory containing the four source-built runtime modular jars")
    parser.add_argument("--source-root", type=Path,
                        help="candidate compiler source root; defaults to the receiver's compiler directory")
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    home = args.jdk_home.resolve(strict=True)
    repo = args.repo_root.resolve(strict=True)
    pack = Path(__file__).resolve().parent
    source = (args.source_root or pack / "compiler").resolve(strict=True)
    if not re.search(r'^JAVA_VERSION="21(?:[.+"-])', (home / "release").read_text(), re.MULTILINE):
        raise ValueError("Java 21 development toolchain required")
    output = args.out.resolve()
    if output.exists() or output.is_relative_to(home) or home.is_relative_to(output):
        raise ValueError("fresh output separate from the supplied JDK required")
    output.mkdir(parents=True)
    compiler_root = repo / "src/jdk.compiler/share/classes"
    stage = output / "compiler-sources"
    receipts = []
    for name in COMPILER_SOURCES:
        original = (compiler_root / name).resolve(strict=True)
        target = stage / name
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(original, target)
        receipts.append({"path": str(original.relative_to(repo)), "sha256": digest(original)})
    compiler_patch = output / "compiler-classes"
    run(output / "compiler-javac.log", home / "bin/javac", "-source", "21", "-target", "21",
        "-proc:none", "-implicit:none", "--patch-module", "jdk.compiler=" + str(stage),
        "-d", compiler_patch, *(stage / name for name in COMPILER_SOURCES))

    jars = args.jars.resolve(strict=True)
    runtime_receipt_path = jars.parent / "RESULT.json"
    runtime_receipt = json.loads(runtime_receipt_path.read_text(encoding="utf-8"))
    source_build = json.loads((jars.parent / "SOURCE_BUILD.json").read_text(encoding="utf-8"))
    if (runtime_receipt.get("status") != "PASS"
            or runtime_receipt.get("source_build") != "PASS"
            or source_build.get("status") != "PASS"
            or source_build.get("runtime_dependency_policy") != "VENDORED_SOURCE_ONLY"
            or set(runtime_receipt.get("jars", {})) != set(MODULES[:-1])):
        raise ValueError("successful source-built runtime JMOD receipt required")
    legal = (jars.parent / "legal").resolve(strict=True)
    if not legal.is_dir() or not (legal / "M3-SOURCE-LOCK.json").is_file():
        raise ValueError("complete runtime legal/source-lock bundle required")
    inputs = {}
    candidates = sorted(jars.glob("*.jar"))
    if len(candidates) != len(MODULES) - 1:
        raise ValueError("exactly four runtime modular JAR inputs required")
    input_receipts = {}
    for candidate in candidates:
        described = run(output / (candidate.stem + "-input-descriptor.log"),
                        home / "bin/jar", "--describe-module", "--file", candidate,
                        "--release", "21")
        for module in MODULES[:-1]:
            if re.search(r"(?m)^" + re.escape(module) + r"(?:@|\s)", described):
                if module in inputs:
                    raise ValueError("duplicate input module: " + module)
                actual = digest(candidate)
                if actual != runtime_receipt["jars"][module]["sha256"]:
                    raise ValueError("runtime source-build JAR hash drift: " + module)
                inputs[module] = candidate
                input_receipts[module] = {"jar": candidate.name, "sha256": actual}
    if set(inputs) != set(MODULES[:-1]):
        raise ValueError("exact source-built Jackson/runtime module closure required")
    module_path = os.pathsep.join(str(path) for path in inputs.values())
    sources = sorted((source / "src/main/java").rglob("*.java"))
    if not sources or not any(path.name == "module-info.java" for path in sources):
        raise ValueError("compiler bridge sources and descriptor required")
    tests = sorted((source / "src/test/java").rglob("*.java"))
    if not tests or not any(path.name == "M3AstBindingsContract.java" for path in tests):
        raise ValueError("actual compiler bridge contract sources required")
    bridge_hashes = [{"path": str(path.relative_to(source)), "sha256": digest(path)} for path in sources]
    test_hashes = [{"path": str(path.relative_to(source)), "sha256": digest(path)} for path in tests]
    classes = output / "classes"
    run(output / "bridge-javac.log", home / "bin/javac", "-source", "21", "-target", "21",
        "-Xlint:all,-module", "-Werror", "-proc:none", "--module-path", module_path,
        "--patch-module", "jdk.compiler=" + str(compiler_patch), "-d", classes, *sources)
    jar = output / "jdk.m3.jackson.compiler.jar"
    run(output / "bridge-jar.log", home / "bin/jar", "--create", "--file", jar,
        "--date", "2026-01-01T00:00:00Z", "-C", classes, ".")
    inputs[MODULES[-1]] = jar
    runtime_path = os.pathsep.join(str(path) for path in inputs.values())
    test_classes = output / "test-classes"
    common = ["--patch-module", "jdk.compiler=" + str(compiler_patch),
              "--patch-module", "jdk.m3.jackson.compiler=" + str(source / "src/test/java")]
    run(output / "tests-javac.log", home / "bin/javac", "-source", "21", "-target", "21",
        "-Xlint:all,-module", "-Werror", "-proc:none", "--module-path", runtime_path,
        "--add-modules", "jdk.m3.jackson.compiler", *common, "-d", test_classes, *tests)
    runtime = ["--patch-module", "jdk.compiler=" + str(compiler_patch),
               "--patch-module", "jdk.m3.jackson.compiler=" + str(test_classes),
               "--add-opens", "jdk.m3.jackson.compiler/jdk.m3.jackson.compiler=com.fasterxml.jackson.databind",
               "--add-opens", "jdk.m3.jackson.compiler/jdk.m3.jackson.compiler.fixture=com.fasterxml.jackson.databind",
               "-m", "jdk.m3.jackson.compiler/jdk.m3.jackson.compiler.M3AstBindingsContract"]
    host_counts = contract_counts(run(output / "host-ast.log", home / "bin/java", "--module-path", runtime_path,
        *runtime, expected="M3_AST_BINDINGS_PASS"))

    image_tsv = output / "IMAGE.tsv"
    image_tsv.write_text("artifact\tversion\tjar\tmodule\trequires\tdisposition\n"
        + "".join("\t".join((name, "1", str(path), name, "-", "JMOD")) + "\n"
                  for name, path in inputs.items()), encoding="utf-8")
    descriptors = output / "DESCRIPTORS.tsv"
    descriptors.write_text("module\tartifacts\tunit_jars\ttarget\tsha256\tgenerated\n", encoding="utf-8")
    run(output / "jmod-pack.log", sys.executable,
        repo / "m3/tooling/jmod-recipes/tools/jmod_pack.py", "--jdk-home", home,
        "--image-tsv", image_tsv, "--manifest", descriptors, "--layer-root", repo,
        "--out", output / "pack", "--legal-notices", legal,
        "--root-module", "jdk.m3.jackson.compiler",
        "--add-modules", "java.se,jdk.compiler,jdk.jlink,jdk.jartool,jdk.jdeps")
    image = output / "pack/image"
    linked_counts = contract_counts(run(output / "image-ast.log", image / "bin/java", *runtime,
                                      expected="M3_AST_BINDINGS_PASS"))
    if host_counts != linked_counts:
        raise ValueError("host and linked-image M3AST contract counts differ")
    for item in receipts:
        if digest(repo / item["path"]) != item["sha256"]:
            raise ValueError("M3 compiler source changed during qualification: " + item["path"])
    for item in [*bridge_hashes, *test_hashes]:
        if digest(source / item["path"]) != item["sha256"]:
            raise ValueError("compiler bridge/contract source changed during qualification: " + item["path"])
    receipt = {
        "status": "PASS", "proof": "ACTUAL_M3AST_COMPILER_PATCH_AND_FIVE_JMOD_IMAGE",
        "modules": list(inputs), "compiler_source_inputs": receipts,
        "runtime_input_jars": input_receipts,
        "runtime_result_sha256": digest(runtime_receipt_path),
        "bridge_source_inputs": bridge_hashes,
        "contract_source_inputs": test_hashes,
        "contract_counts": {"host": host_counts, "linked": linked_counts},
        "qualification_tool_sha256": digest(Path(__file__).resolve()),
        "patch_role": "Qualification only; use a qualified M3JDK compiler in production.",
        "runtime_dependency_on_synexia": False,
        "full_m3_vm_string_storage_flag_test": "NOT_EXECUTED_BY_THIS_COMPILER_HARNESS",
        "jar_sha256": digest(jar),
        "jmod_sha256": digest(output / "pack/jmods/jdk.m3.jackson.compiler.jmod"),
    }
    (output / "COMPILER_RECEIPT.json").write_text(json.dumps(receipt, indent=2) + "\n", encoding="utf-8")
    print("JACKSON_COMPILER_JMOD_PASS modules=5 realM3AST=true fullM3VM=false")


if __name__ == "__main__":
    main()
