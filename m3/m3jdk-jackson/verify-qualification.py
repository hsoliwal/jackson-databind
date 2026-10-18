#!/usr/bin/env python3
# Copyright 2026 Hitesh Soliwal and contributors.
# SPDX-License-Identifier: Apache-2.0
"""Verify the executed runtime, compiler and upstream Jackson qualification receipts."""
import argparse
import csv
import hashlib
import importlib.util
import json
import io
from pathlib import Path
import re
import subprocess
import sys


MODULES = {"com.fasterxml.jackson.annotation", "com.fasterxml.jackson.core",
           "com.fasterxml.jackson.databind", "jdk.m3.jackson"}
CONTRACTS = {"JacksonContract": "JACKSON_CONTRACT_PASS",
             "M3FileContract": "M3_FILE_CONTRACT_PASS",
             "JacksonStreamingContract": "JACKSON_STREAMING_PASS"}
UPSTREAM = {"jackson-annotations": "com.fasterxml.jackson.annotation",
            "jackson-core": "com.fasterxml.jackson.core",
            "jackson-databind": "com.fasterxml.jackson.databind"}
COMPILER_OWNERS = {
    "src/jdk.compiler/share/classes/com/sun/source/util/M3AST.java",
    "src/jdk.compiler/share/classes/com/sun/tools/javac/m3/M3ASTPC.java",
    "src/jdk.compiler/share/classes/com/sun/tools/javac/m3/M3PrimitiveImageCompiler.java",
    "src/jdk.compiler/share/classes/com/sun/tools/javac/main/JavaCompiler.java",
}


def require(value, message):
    if not value:
        raise ValueError(message)


def content(path):
    require(path.is_file() and path.stat().st_size > 0, "Missing evidence: " + str(path))
    return path.read_bytes()


def load(path):
    return json.loads(content(path))


def digest(path):
    return hashlib.sha256(content(path)).hexdigest()


def import_local(path, name):
    """Load an explicitly selected local policy owner without writing bytecode."""
    content(path)
    specification = importlib.util.spec_from_file_location(name, path)
    require(specification is not None and specification.loader is not None,
            "Cannot load qualification policy: " + str(path))
    module = importlib.util.module_from_spec(specification)
    previous = sys.dont_write_bytecode
    sys.dont_write_bytecode = True
    try:
        specification.loader.exec_module(module)
    finally:
        sys.dont_write_bytecode = previous
    return module


def snapshot(root, recorded, expected):
    require(set(recorded) == set(expected), "Source receipt has an incomplete or unexpected file set")
    for name, expected_hash in recorded.items():
        path = (root / name).resolve(strict=True)
        require(path.is_relative_to(root), "Source receipt escapes its owner: " + name)
        require(digest(path) == expected_hash, "Source identity mismatch: " + name)


def one_marker(text, pattern, name):
    found = re.findall(pattern, text, re.MULTILINE)
    require(len(found) == 1, "One nonzero executed marker required: " + name)
    value = found[0]
    return [int(item) for item in value] if isinstance(value, tuple) else int(value)


def verify_jmod_selection(pack, modules):
    expected = {name + ".jmod" for name in modules}
    require({path.name for path in (pack / "jmods").glob("*.jmod")} == expected,
            "Actual JMOD file closure differs from the selected modules: " + str(pack))
    rows = list(csv.DictReader(io.StringIO(content(pack / "MODULES.tsv").decode()), delimiter="\t"))
    selected = [row for row in rows if row.get("module") in modules]
    require(len(selected) == len(modules) and {row["module"] for row in selected} == set(modules),
            "Complete unique selected JMOD hashes required: " + str(pack))
    hashes = {}
    for row in selected:
        name = row["module"] + ".jmod"
        actual = digest(pack / "jmods" / name)
        require(row.get("kind") == "JMOD" and row.get("sha256") == actual,
                "Packaged JMOD differs from shared selection evidence: " + name)
        hashes[name] = actual
    return hashes


def verify_donors(repo, pack, base, source, result):
    owner = pack / "build.py"
    require(result.get("qualification_tool_sha256") == digest(owner),
            "Runtime build tool source identity mismatch")
    build = import_local(owner, "m3_jackson_source_build_policy")
    lock = load(pack / "SOURCE_LOCK.json")
    original = load(base / "SOURCE_RECEIPT.json")
    require(original.get("status") == "PASS"
            and original.get("lock_sha256") == digest(pack / "SOURCE_LOCK.json"),
            "Source lock differs from the executed source receipt")
    current = build.check_donors(repo, lock)
    require(original.get("donors") == current,
            "Current donor source snapshot differs from the executed receipt")
    owners = lock.get("packaging_owners", [])
    require(owners and len({item["path"] for item in owners}) == len(owners),
            "Unique packaging source owners are required")
    snapshot(repo, {item["path"]: item["sha256"] for item in owners},
             {item["path"] for item in owners})
    built = source.get("donors", [])
    require(len(built) == len(current)
            and len({item["directory"] for item in built}) == len(current),
            "Exactly one source build per selected donor is required")
    by_directory = {item["directory"]: item for item in built}
    require(set(by_directory) == {item["directory"] for item in current},
            "Source build donor selection differs from current source owners")
    for item in current:
        recorded = by_directory[item["directory"]]
        require(all(recorded.get(key) == value for key, value in item.items())
                and recorded.get("maven_source_build") == "PASS",
                "Donor source build identity mismatch: " + item["directory"])
        name = Path(item["directory"]).name
        content(base / "logs" / (name + "-maven.log"))
        require(load(base / "logs" / (name + "-maven.result.json")).get("exit_code") == 0,
                "Donor source build process failed: " + name)
        if item["module"] is not None:
            require(recorded.get("built_jar_sha256")
                    == result["jars"][item["module"]]["sha256"],
                    "Selected runtime JAR differs from its donor source build: " + name)


def verify_upstream(upstream, qualification, base, result, policy_path):
    policy_hash = digest(policy_path)
    require(qualification.get("status") == "PASS"
            and qualification.get("qualification_tool_sha256") == policy_hash
            and qualification.get("harness", {}).get("sha256") == policy_hash,
            "Full upstream qualification or its exact policy identity did not pass")
    policy = import_local(policy_path, "m3_jackson_upstream_qualification_policy")
    require(policy.MODULES == UPSTREAM and set(policy.PINS) == set(UPSTREAM),
            "Unexpected upstream qualification component selection")
    forks = qualification.get("forks", {})
    require(set(forks) == set(UPSTREAM), "Three pinned upstream fork snapshots required")
    for component, pin in policy.PINS.items():
        recorded = forks[component]
        require(recorded.get("upstream_commit") == pin
                and recorded.get("upstream_files_unchanged") is True,
                "Upstream fork selection differs from the qualification policy: " + component)
        current = policy.inspect_fork(Path(recorded["path"]), component, upstream)
        require(current == recorded, "Upstream fork source snapshot changed: " + component)

    runtimes = qualification.get("runtime_jars", {})
    require(set(runtimes) == set(UPSTREAM), "Three exact upstream Jackson JARs required")
    tested_receipt = qualification.get("runtime_receipt", {})
    tested_path = Path(tested_receipt.get("path", ""))
    require(tested_path.is_absolute()
            and digest(tested_path) == tested_receipt.get("sha256"),
            "Upstream source-build receipt is missing or changed")
    tested_build = load(tested_path)
    require(tested_build.get("status") == "PASS" and tested_build.get("source_build") == "PASS",
            "Upstream libraries were not selected from a successful source build")
    verified_runtimes = {}
    for component, module in UPSTREAM.items():
        item = runtimes[component]
        current_jar = base / "jars" / (module + ".jar")
        require(item.get("module") == module
                and item.get("sha256") == result["jars"][module]["sha256"]
                == tested_build.get("jars", {}).get(module, {}).get("sha256")
                == digest(current_jar) and Path(item["path"]).is_absolute(),
                "Upstream suite tested different library bytes: " + component)
        # The replay may precede a byte-identical generated receiver build. Its
        # recorded class-loading URL stays authoritative; inspect these verified
        # bytes when reconstructing the set of production classes.
        verified_runtimes[component] = dict(item, verification_path=str(current_jar))

    lanes = qualification.get("lanes", {})
    expected_lanes = {prefix + "/" + component for component in UPSTREAM
                      for prefix in ("upstream", "generated")}
    expected_lanes |= {prefix + "/jackson-databind" for prefix in
                       ("upstream-typepollution", "generated-typepollution")}
    require(set(lanes) == expected_lanes, "All eight upstream and generated test lanes required")
    inspected = {}
    test_snapshots = {}
    for name, item in lanes.items():
        path = Path(item["receipt"]).resolve(strict=True)
        require(path == (upstream / name / "RESULT.json").resolve(strict=True)
                and path.is_relative_to(upstream) and digest(path) == item.get("sha256"),
                "Upstream lane receipt identity mismatch: " + name)
        lane = load(path)
        folder = path.parent
        require(item.get("status") == lane.get("status") == "PASS"
                and lane.get("exit_code") == 0,
                "Upstream lane process failed: " + name)
        require(digest(folder / "maven.log") == lane.get("maven_log_sha256"),
                "Upstream Maven output identity mismatch: " + name)
        component = name.split("/")[1]
        suite = "typepollution" if "typepollution" in name else "default"
        require(lane.get("suite") == suite, "Upstream lane suite mismatch: " + name)
        actual = policy.reports(folder / "surefire-reports")
        policy.check_tests(actual, component, suite)
        for field in ("totals", "testcases", "skips", "problems", "files", "skip_policy"):
            require(actual[field] == lane.get(field),
                    "Upstream XML evidence differs from its receipt: " + name + "/" + field)
        require(actual["totals"] == item.get("totals"),
                "Upstream summary totals differ from executed XML: " + name)
        inspected[name] = lane
        snapshot_path = upstream / "upstream" / component / "test-classes"
        require(snapshot_path.is_dir(), "Missing upstream test-class snapshot: " + component)
        if component not in test_snapshots:
            test_snapshots[component] = policy.tree_hash(snapshot_path)
        if name.startswith("upstream"):
            require(lane.get("fork") == forks[component],
                    "Lane fork snapshot differs from the qualification: " + name)
        if suite == "default" or name.startswith("generated"):
            require(test_snapshots[component] == lane.get("test_classes")
                    and lane.get("test_classes", {}).get("files", 0) > 0,
                    "Compiled upstream test snapshot changed: " + name)
        if name.startswith("upstream"):
            # The isolated type-pollution execution reuses the default Maven
            # compilation and its dependency snapshot; the runner deliberately
            # records that snapshot once, in the default baseline receipt.
            if suite == "default":
                dependencies = lane.get("test_classpath_artifacts", {})
                require(dependencies, "Upstream test classpath snapshot required: " + name)
                for artifact, expected_hash in dependencies.items():
                    require(digest(Path(artifact)) == expected_hash,
                            "Upstream test dependency changed: " + artifact)
        else:
            names = set(UPSTREAM) if component == "jackson-databind" else {component}
            require(lane.get("runtime_jars") == {key: runtimes[key] for key in names},
                    "Generated lane library selection differs from qualified bytes: " + name)
            dependencies = lane.get("dependencies", [])
            require(dependencies, "Generated test dependency snapshot required: " + name)
            for dependency in dependencies:
                production = next((key for key in names
                                   if dependency["path"] == runtimes[key]["path"]), None)
                artifact = (Path(verified_runtimes[production]["verification_path"])
                            if production is not None else Path(dependency["path"]))
                require(digest(artifact) == dependency.get("sha256"),
                        "Generated test dependency changed: " + dependency["path"])
            logs = [{"name": logfile.name, "sha256": digest(logfile)}
                    for logfile in sorted(folder.glob("classload-*.log"))]
            require(logs and logs == lane.get("classload_logs"),
                    "Generated class-loading evidence is missing or changed: " + name)
            origins = policy.inspect_origins(folder, component, suite,
                    {key: verified_runtimes[key] for key in names}, dependencies)
            require(origins == lane.get("origin_check") and origins.get("status") == "PASS"
                    and origins.get("production_class_count", 0) > 0,
                    "Generated loaded-library origin evidence mismatch: " + name)
    for name, lane in inspected.items():
        if name.startswith("generated"):
            baseline = "upstream" + name[len("generated"):]
            require(lane.get("testcase_selection_matches_upstream") is True
                    and lane["testcases"] == inspected[baseline]["testcases"],
                    "Generated replay changed the executed upstream testcase multiset: " + name)
    return lanes


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("runtime", "compiler", "upstream", "repo-root", "out"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--source-root", type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument("--upstream-tool", type=Path,
                        help="Explicit local upstream policy owner; defaults to the source pack's runner")
    parser.add_argument("--expected-head", help="Require this exact Git revision in CI")
    args = parser.parse_args()
    base, compiler, upstream, repo, pack = (path.resolve(strict=True) for path in
        (args.runtime, args.compiler, args.upstream, args.repo_root, args.source_root))
    require(not args.out.exists(), "Use a new receipt path; existing evidence is retained")
    result = load(base / "RESULT.json")
    source = load(base / "SOURCE_BUILD.json")
    receipt = load(compiler / "COMPILER_RECEIPT.json")
    qualification = load(upstream / "RESULT.json")
    require(result.get("status") == "PASS" and result.get("source_build") == "PASS",
            "Runtime source/JMOD proof did not pass")
    require(source.get("status") == "PASS"
            and source.get("runtime_dependency_policy") == "VENDORED_SOURCE_ONLY",
            "Runtime inputs were not qualified from the selected sources")
    donors = source.get("donors", [])
    require(len(donors) == 4 and all(item.get("maven_source_build") == "PASS" for item in donors),
            "Four successful donor source builds are required")
    require(set(result.get("jars", {})) == MODULES
            and sorted(result.get("jmods", [])) == sorted(name + ".jmod" for name in MODULES),
            "Runtime module closure must contain exactly four modules")
    require(receipt.get("status") == "PASS"
            and receipt.get("proof") == "ACTUAL_M3AST_COMPILER_PATCH_AND_FIVE_JMOD_IMAGE"
            and len(receipt.get("modules", [])) == 5
            and set(receipt.get("modules", [])) == MODULES | {"jdk.m3.jackson.compiler"},
            "Actual compiler proof must contain exactly five modules")
    runtime_jmods = verify_jmod_selection(base / "pack", MODULES)
    compiler_jmods = verify_jmod_selection(compiler / "pack", MODULES | {"jdk.m3.jackson.compiler"})
    require(receipt.get("runtime_result_sha256") == digest(base / "RESULT.json")
            and set(receipt.get("runtime_input_jars", {})) == MODULES,
            "Compiler proof does not refer to this runtime result")
    for module in sorted(MODULES):
        jar_hash = digest(base / "jars" / (module + ".jar"))
        require(jar_hash == result["jars"][module].get("sha256")
                == receipt["runtime_input_jars"][module].get("sha256"),
                "Runtime/compiler JAR identity mismatch: " + module)
        require(digest(base / "pack/jmods" / (module + ".jmod"))
                == result.get("jmod_sha256", {}).get(module + ".jmod"),
                "Runtime JMOD identity mismatch: " + module)
    require(digest(compiler / "jdk.m3.jackson.compiler.jar") == receipt.get("jar_sha256")
            and digest(compiler / "pack/jmods/jdk.m3.jackson.compiler.jmod") == receipt.get("jmod_sha256"),
            "Compiler output artifact identity mismatch")
    verify_donors(repo, pack, base, source, result)
    owners = receipt.get("compiler_source_inputs", [])
    require(len(owners) == 4, "Four actual M3 compiler source owners required")
    snapshot(repo, {item["path"]: item["sha256"] for item in owners}, COMPILER_OWNERS)
    java = sorted((pack / "src/main/java").rglob("*.java")) + sorted((pack / "src/test/java").rglob("*.java"))
    snapshot(pack, result.get("adapter_and_contract_source_inputs", {}),
             {path.relative_to(pack).as_posix() for path in java})
    for key, directory in (("bridge_source_inputs", "src/main/java"),
                           ("contract_source_inputs", "src/test/java")):
        root = pack / "compiler"
        rows = receipt.get(key, [])
        expected = {path.relative_to(root).as_posix() for path in (root / directory).rglob("*.java")}
        require(rows and len(rows) == len(expected), "Complete compiler source hashes required: " + key)
        snapshot(root, {item["path"]: item["sha256"] for item in rows}, expected)
    require(result.get("qualification_tool_sha256") == digest(pack / "build.py")
            and receipt.get("qualification_tool_sha256") == digest(pack / "compiler-build.py"),
            "Build tool source identity mismatch")
    counts = {}
    for lane, key in (("host", "host_contracts"), ("linked", "linked_contracts")):
        contracts = result.get(key, {})
        require(set(contracts) == {"jdk.m3.jackson." + name for name in CONTRACTS},
                "All three executed runtime contracts are required")
        for name, marker in CONTRACTS.items():
            contract = contracts["jdk.m3.jackson." + name]
            pattern = r"^" + marker + r" checks=([1-9][0-9]*)(?:[^\S\n].*)?$"
            logged = one_marker(content(base / "logs" / (lane + "-" + name + ".log")).decode(),
                                pattern, lane + "/" + name)
            recorded = one_marker(contract.get("stdout", ""), pattern, "receipt/" + name)
            require(contract.get("status") == "PASS" and logged == recorded == contract.get("checks"),
                    "Runtime contract receipt differs from executed output")
            require(load(base / "logs" / (lane + "-" + name + ".result.json")).get("exit_code") == 0,
                    "Runtime contract process failed")
            counts[lane + "/" + name] = logged
    for name in CONTRACTS:
        require(counts["host/" + name] == counts["linked/" + name], "Runtime contract lane counts differ")
    for lane, filename in (("host", "host-ast.log"), ("linked", "image-ast.log")):
        log = content(compiler / filename).decode()
        require(log.rstrip().endswith("EXIT 0"), "Compiler contract process failed")
        baseline = one_marker(log, r"^M3_AST_BINDINGS_PASS shapes=([1-9][0-9]*)"
                              r" projections=([1-9][0-9]*) hits=([1-9][0-9]*)$", filename)
        extended = one_marker(log, r"^M3_AST_BINDINGS_EXTENDED_PASS checks=([1-9][0-9]*)$", filename)
        require(receipt.get("contract_counts", {}).get(lane)
                == {"baseline": baseline, "extended": [extended]},
                "Compiler contract receipt differs from executed output")
        counts[lane + "/M3AstBindingsContract"] = {"baseline": baseline, "extended": extended}
    require(counts["host/M3AstBindingsContract"] == counts["linked/M3AstBindingsContract"],
            "Compiler contract lane counts differ")
    upstream_tool = (args.upstream_tool or pack / "upstream-qualification.py").resolve(strict=True)
    lanes = verify_upstream(upstream, qualification, base, result, upstream_tool)
    head = subprocess.check_output(["git", "-C", str(repo), "rev-parse", "HEAD"], text=True).strip()
    if args.expected_head:
        require(head == args.expected_head, "Evidence revision differs from selected head")
    evidence = {"status": "PASS", "head": head, "runtime_modules": sorted(MODULES),
                "compiler_modules": sorted(receipt["modules"]), "executed_counts": counts,
                "runtime_jmod_sha256": runtime_jmods, "compiler_jmod_sha256": compiler_jmods,
                "runtime_result_sha256": digest(base / "RESULT.json"),
                "source_receipt_sha256": digest(base / "SOURCE_RECEIPT.json"),
                "source_build_sha256": digest(base / "SOURCE_BUILD.json"),
                "source_lock_sha256": digest(pack / "SOURCE_LOCK.json"),
                "compiler_receipt_sha256": digest(compiler / "COMPILER_RECEIPT.json"),
                "upstream_receipt_sha256": digest(upstream / "RESULT.json"),
                "upstream_tool_sha256": digest(upstream_tool),
                "qualification_tool_sha256": digest(Path(__file__).resolve()),
                "upstream_lanes": sorted(lanes), "m3_vm_string_storage": result.get("m3_string_runtime", {}),
                "scope": "Executed source JMODs, actual compiler patch and full pinned upstream test selection. Full M3 VM qualification remains separate."}
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(evidence, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print("JACKSON_FULL_QUALIFICATION_PASS head=" + head + " runtimeModules=4 compilerModules=5 upstreamLanes=8")


if __name__ == "__main__":
    main()
