#!/usr/bin/env python3
# Copyright 2026 Hitesh Soliwal and contributors.
# SPDX-License-Identifier: Apache-2.0
"""Build the existing Jackson sources, package named JMODs, and test a linked image.

This is an orchestrator for the existing Maven donor builds and jmod_pack.py.
It neither edits those sources nor downloads runtime library binaries. Maven is
offline by default; --resolve-build-tools permits initial build-plugin resolution.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
import zipfile


MODULE = "jdk.m3.jackson"
VERSION = "2.19.2"
OUTPUT_TIMESTAMP = "2026-01-01T00:00:00Z"
DONORS = (
    ("fastdoubleparser-1.0.90", "ch.randelshofer", "fastdoubleparser", "1.0.90-m3-source-1", None),
    ("jackson-annotations-2.19.2", "com.fasterxml.jackson.core", "jackson-annotations", "2.19.2-m3-source-1", "com.fasterxml.jackson.annotation"),
    ("jackson-core-2.19.2", "com.fasterxml.jackson.core", "jackson-core", "2.19.2-m3-source-1", "com.fasterxml.jackson.core"),
    ("jackson-databind-2.19.2", "com.fasterxml.jackson.core", "jackson-databind", "2.19.2-m3-source-1", "com.fasterxml.jackson.databind"),
)
TESTS = {
    "jdk.m3.jackson.JacksonContract": "JACKSON_CONTRACT_PASS checks=",
    "jdk.m3.jackson.M3FileContract": "M3_FILE_CONTRACT_PASS checks=",
    "jdk.m3.jackson.JacksonStreamingContract": "JACKSON_STREAMING_PASS checks=",
}
IGNORED = {"target", "dependency-reduced-pom.xml", "__pycache__", ".DS_Store"}


def digest(path: Path) -> str:
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def source_files(root: Path) -> list[Path]:
    return sorted(path for path in root.rglob("*")
                  if path.is_file() and not any(part in IGNORED for part in path.relative_to(root).parts))


def tree_digest(root: Path) -> tuple[str, int]:
    files = source_files(root)
    result = hashlib.sha256()
    for path in files:
        if path.is_symlink():
            raise ValueError("Source inputs must be regular files: " + str(path))
        result.update(path.relative_to(root).as_posix().encode("utf-8"))
        result.update(b"\0")
        result.update(digest(path).encode("ascii"))
        result.update(b"\n")
    return result.hexdigest(), len(files)


def write_json(path: Path, value: object) -> None:
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def command(args: list[object], log: Path, env: dict[str, str] | None = None,
            cwd: Path | None = None) -> str:
    rendered = [str(item) for item in args]
    started = time.monotonic()
    with log.open("w", encoding="utf-8") as output:
        result = subprocess.run(rendered, stdout=output, stderr=subprocess.STDOUT,
                                text=True, env=env, cwd=cwd, check=False)
    duration = round(time.monotonic() - started, 3)
    write_json(log.with_suffix(".result.json"), {
        "command": rendered, "exit_code": result.returncode,
        "elapsed_seconds": duration,
    })
    if result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}); see {log}")
    return log.read_text(encoding="utf-8", errors="replace")


def check_donors(repo: Path, lock: dict) -> list[dict]:
    allowed = {(group, artifact, version) for _, group, artifact, version, _ in DONORS}
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}
    receipts = []
    for name, group, artifact, version, module in DONORS:
        relative = "m3/vendor/third_party/" + name
        root = repo / relative
        if not root.is_dir():
            raise FileNotFoundError("Materialize the existing vendored source directory: " + str(root))
        expected = lock["donors"][name]
        actual, count = tree_digest(root)
        if actual != expected["tree_sha256"] or count != expected["file_count"]:
            raise ValueError("Pinned donor source/build/legal tree changed: " + name)
        provenance = json.loads((root / "PROVENANCE.json").read_text(encoding="utf-8"))
        for item in provenance["files"]:
            path = repo / item["repository"]
            if not path.is_relative_to(root) or not path.is_file() or digest(path) != item["sha256"]:
                raise ValueError("Upstream provenance mismatch: " + item["repository"])
        pom = ET.parse(root / "pom.xml").getroot()
        if tuple(pom.findtext("m:" + key, namespaces=ns)
                 for key in ("groupId", "artifactId", "version")) != (group, artifact, version):
            raise ValueError("Unexpected donor coordinates: " + name)
        for dependency in pom.findall("m:dependencies/m:dependency", ns):
            coordinate = tuple(dependency.findtext("m:" + key, namespaces=ns)
                               for key in ("groupId", "artifactId", "version"))
            if coordinate not in allowed:
                raise ValueError("Runtime dependency has no selected source build: " + ":".join(coordinate))
        receipts.append({"directory": relative, "coordinate": ":".join((group, artifact, version)),
                         "module": module, "tree_sha256": actual, "file_count": count,
                         "upstream_provenance_files": len(provenance["files"]),
                         "sources_jar_sha256": provenance["sources_jar_sha256"],
                         "license": provenance["license"]})
    return receipts


def verify_built_jar(jar: Path, source: Path, name: str) -> None:
    with zipfile.ZipFile(jar) as archive:
        entries = set(archive.namelist())
        # Keep the source provenance and original legal resources inside each JAR.
        retained = "META-INF/third-party/" + name + "/"
        for filename in ("PROVENANCE.json", "THIRD_PARTY_NOTICES.md"):
            if archive.read(retained + filename) != (source / filename).read_bytes():
                raise ValueError("Built provenance changed: " + name + "/" + filename)
        if name == "jackson-annotations-2.19.2":
            required = {"module-info.class", "META-INF/LICENSE", "META-INF/NOTICE"}
        elif name == "jackson-core-2.19.2":
            required = {
                "META-INF/versions/9/module-info.class",
                "com/fasterxml/jackson/core/internal/shaded/fdp/v2_19_2/JavaDoubleParser.class",
                "META-INF/LICENSE", "META-INF/NOTICE",
            }
            if any(entry.startswith("ch/randelshofer/fastdoubleparser/") for entry in entries):
                raise ValueError("Unshaded FastDoubleParser classes in Jackson core")
            if any(entry.startswith("META-INF/versions/22/") for entry in entries):
                raise ValueError("JDK 22 overlay leaked into the JDK 21 Jackson build")
        elif name == "jackson-databind-2.19.2":
            required = {"META-INF/versions/9/module-info.class", "META-INF/LICENSE", "META-INF/NOTICE",
                        "com/fasterxml/jackson/databind/ObjectMapper.class"}
        else:
            required = {"META-INF/versions/11/module-info.class",
                        "META-INF/versions/17/module-info.class", "META-INF/versions/21/module-info.class",
                        "META-INF/LICENSE", "META-INF/NOTICE", "META-INF/thirdparty-LICENSE"}
        if not required.issubset(entries):
            raise ValueError("Source build omitted required entries in " + name + ": "
                             + ", ".join(sorted(required - entries)))


def copy_legal(repo: Path, source: Path, out: Path) -> Path:
    legal = out / "legal"
    legal.mkdir()
    for path in (source / "legal").iterdir():
        if path.is_file():
            shutil.copyfile(path, legal / path.name)
    shutil.copyfile(source / "SOURCE_LOCK.json", legal / "M3-SOURCE-LOCK.json")
    for name, *_ in DONORS:
        donor = repo / "m3/vendor/third_party" / name
        for path in sorted((donor / "src/main/resources").rglob("*")):
            if path.is_file() and ("LICENSE" in path.name.upper() or "NOTICE" in path.name.upper()):
                suffix = path.relative_to(donor / "src/main/resources").as_posix().replace("/", "_")
                shutil.copyfile(path, legal / (name + "_" + suffix))
        shutil.copyfile(donor / "THIRD_PARTY_NOTICES.md", legal / (name + "_SOURCE-NOTICES.md"))
    return legal


def test_runtime(java: Path, patch: Path, jars: list[Path], logs: Path,
                 label: str, vm_options: list[str] | None = None) -> dict:
    result = {}
    for test in TESTS:
        args: list[object] = [java, *(vm_options or [])]
        if jars:
            args.extend(["--module-path", os.pathsep.join(str(jar) for jar in jars)])
        args.extend(["--patch-module", MODULE + "=" + str(patch),
                     "--add-opens", MODULE + "/jdk.m3.jackson=com.fasterxml.jackson.databind",
                     "-m", MODULE + "/" + test])
        text = command(args, logs / (label + "-" + test.rsplit(".", 1)[1] + ".log"))
        markers = [line for line in text.splitlines() if line.startswith(TESTS[test])]
        match = (re.fullmatch(re.escape(TESTS[test]) + r"([1-9][0-9]*)(?:\s.*)?", markers[0])
                 if len(markers) == 1 else None)
        if match is None:
            raise ValueError("One nonzero contract PASS marker is required: " + label + "/" + test)
        result[test] = {"status": "PASS", "checks": int(match.group(1)),
                        "stdout": text.strip()}
    return result


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--jdk-home", type=Path, default=Path(os.environ.get("JAVA_HOME", sys.prefix)))
    ap.add_argument("--maven", default="mvn")
    ap.add_argument("--maven-repo", type=Path, required=True)
    ap.add_argument("--maven-settings", type=Path)
    ap.add_argument("--maven-global-settings", type=Path)
    ap.add_argument("--maven-offline", choices=("true", "false"), default="true")
    ap.add_argument("--resolve-build-tools", action="store_const", dest="maven_offline", const="false")
    ap.add_argument("--repo-root", type=Path, default=Path(__file__).resolve().parents[4])
    ap.add_argument("--source-root", type=Path, default=Path(__file__).resolve().parent)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--require-m3-runtime", action="store_true")
    args = ap.parse_args(argv)
    jdk, repo, source, out = (path.resolve() for path in
                              (args.jdk_home, args.repo_root, args.source_root, args.out))
    if out.exists():
        raise FileExistsError("Use a fresh output directory; previous evidence is retained: " + str(out))
    if out.is_relative_to(jdk) or jdk.is_relative_to(out):
        raise ValueError("The output directory must be separate from the selected JDK")
    if any(out.is_relative_to(repo / "m3/vendor/third_party" / name) for name, *_ in DONORS):
        raise ValueError("Build outputs must be separate from the pinned donor trees")
    for tool in ("java", "javac", "jar", "jmod", "jlink"):
        if not (jdk / "bin" / tool).is_file():
            raise FileNotFoundError("A complete JDK 21 is required: " + str(jdk / "bin" / tool))
    release = (jdk / "release").read_text(encoding="utf-8")
    if not re.search(r'^JAVA_VERSION="21(?:[.+"-])', release, re.MULTILINE):
        raise ValueError("This source/image contract is pinned to a JDK 21 toolchain")
    lock = json.loads((source / "SOURCE_LOCK.json").read_text(encoding="utf-8"))
    receipts = check_donors(repo, lock)
    for item in lock["packaging_owners"]:
        if digest(repo / item["path"]) != item["sha256"]:
            raise ValueError("Existing packaging owner changed: " + item["path"])
    java_sources = sorted((source / "src/main/java").rglob("*.java"))
    test_sources = sorted((source / "src/test/java").rglob("*.java"))
    for name in (test.rsplit(".", 1)[1] + ".java" for test in TESTS):
        if not any(path.name == name for path in test_sources):
            raise FileNotFoundError("Required executable contract is absent: " + name)
    if not java_sources or not (source / "src/main/java/module-info.java").is_file():
        raise FileNotFoundError("Generated named adapter-module sources are required")
    source_hashes = {str(path.relative_to(source)): digest(path)
                     for path in [*java_sources, *test_sources]}
    out.mkdir(parents=True)
    logs = out / "logs"
    logs.mkdir()
    jar_output = out / "jars"
    jar_output.mkdir()
    write_json(out / "SOURCE_RECEIPT.json", {"status": "PASS", "donors": receipts,
                                             "lock_sha256": digest(source / "SOURCE_LOCK.json")})
    (out / "toolchain-release.txt").write_text(release, encoding="utf-8")
    env = dict(os.environ)
    env["JAVA_HOME"] = str(jdk)
    env["PATH"] = str(jdk / "bin") + os.pathsep + env.get("PATH", "")
    maven = [args.maven, "-B", "-ntp", "-C", "-Dmaven.repo.local=" + str(args.maven_repo.resolve()),
             "-Dproject.build.outputTimestamp=" + OUTPUT_TIMESTAMP]
    if args.maven_offline == "true":
        maven.append("-o")
    if args.maven_settings:
        maven.extend(["-s", str(args.maven_settings.resolve())])
    if args.maven_global_settings:
        maven.extend(["-gs", str(args.maven_global_settings.resolve())])
    jars: list[Path] = []
    named: list[tuple[str, Path, str]] = []
    for receipt, (name, _, artifact, version, module) in zip(receipts, DONORS):
        staged = out / "staged" / name
        shutil.copytree(repo / receipt["directory"], staged,
                        ignore=shutil.ignore_patterns(*IGNORED))
        print("Source build:", name, flush=True)
        command([*maven, "-f", staged / "pom.xml", "install"], logs / (name + "-maven.log"), env=env, cwd=staged)
        built = staged / "target" / (artifact + "-" + version + ".jar")
        verify_built_jar(built, staged, name)
        receipt["built_jar_sha256"] = digest(built)
        receipt["maven_source_build"] = "PASS"
        if module:
            selected = jar_output / (module + ".jar")
            shutil.copyfile(built, selected)
            jars.append(selected)
            named.append((module, selected, version))
    write_json(out / "SOURCE_BUILD.json", {"status": "PASS", "donors": receipts,
                                           "runtime_dependency_policy": "VENDORED_SOURCE_ONLY",
                                           "archive_output_timestamp": OUTPUT_TIMESTAMP,
                                           "maven_offline": args.maven_offline == "true"})
    classes = out / "classes"
    classes.mkdir()
    command([jdk / "bin/javac", "--release", "21", "-Xlint:all,-module", "-Werror",
             "-proc:none",
             "--module-path", os.pathsep.join(str(jar) for jar in jars), "-d", classes,
             *java_sources], logs / "adapter-compile.log")
    resource_root = source / "src/main/resources"
    if resource_root.exists():
        shutil.copytree(resource_root, classes, dirs_exist_ok=True)
    adapter = jar_output / (MODULE + ".jar")
    command([jdk / "bin/jar", "--create", "--file", adapter, "--module-version", VERSION,
             "--date", OUTPUT_TIMESTAMP,
             "-C", classes, "."], logs / "adapter-jar.log")
    jars.append(adapter)
    named.append((MODULE, adapter, VERSION))
    tests = out / "test-classes"
    tests.mkdir()
    command([jdk / "bin/javac", "--release", "21", "-Xlint:all,-module", "-Werror",
             "-proc:none",
             "--module-path", os.pathsep.join(str(jar) for jar in jars),
             "--patch-module", MODULE + "=" + str(source / "src/test/java"),
             "-d", tests, *test_sources], logs / "contract-compile.log")
    host = test_runtime(jdk / "bin/java", tests, jars, logs, "host")
    legal = copy_legal(repo, source, out)
    image_tsv = out / "JMOD_INPUT.tsv"
    with image_tsv.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.writer(handle, delimiter="\t", lineterminator="\n")
        writer.writerow(("module", "jar", "version", "disposition"))
        writer.writerows((module, str(jar), version, "MODULAR_JAR_SOURCE_BUILT")
                         for module, jar, version in named)
    manifest = out / "DESCRIPTOR_MANIFEST.tsv"
    manifest.write_text("module\tartifacts\tunit_jars\ttarget\tsha256\tgenerated\n", encoding="utf-8")
    pack = out / "pack"
    command([sys.executable, repo / "m3/tooling/jmod-recipes/tools/jmod_pack.py",
             "--jdk-home", jdk, "--image-tsv", image_tsv, "--manifest", manifest,
             "--layer-root", source, "--out", pack, "--root-module", MODULE,
             "--add-modules", "java.se", "--legal-notices", legal], logs / "jmod-pack.log", env=env)
    produced = sorted(path.name for path in (pack / "jmods").glob("*.jmod"))
    expected = sorted(module + ".jmod" for module, *_ in named)
    if produced != expected:
        raise ValueError("Selected JMOD closure differs from the four requested modules")
    linked = test_runtime(pack / "image/bin/java", tests, [], logs, "linked")
    flags = ["-XX:+UnlockExperimentalVMOptions", "-XX:+UseM3StringStorage"]
    support = subprocess.run([str(jdk / "bin/java"), *flags, "-version"],
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                             text=True, check=False)
    m3 = {"status": "NOT_AVAILABLE_ON_SELECTED_JDK", "flag_on_off": "NOT_RUN"}
    (logs / "m3-runtime-probe.log").write_text(support.stdout, encoding="utf-8")
    if support.returncode == 0:
        lanes = {}
        for mode in ("on", "off"):
            option = "-XX:" + ("+" if mode == "on" else "-") + "UseM3StringStorage"
            for label, java, path in (("host", jdk / "bin/java", jars),
                                      ("linked", pack / "image/bin/java", [])):
                lanes[label + "-" + mode] = test_runtime(
                    java, tests, path, logs, "m3-" + label + "-" + mode,
                    ["-XX:+UnlockExperimentalVMOptions", option])
        m3 = {"status": "PASS", "flag_on_off": "PASS", "lanes": lanes}
    result = {"status": "PASS", "source_build": "PASS", "jmods": produced,
              "jars": {module: {"path": str(jar), "sha256": digest(jar)}
                       for module, jar, _ in named},
              "jmod_sha256": {name: digest(pack / "jmods" / name) for name in produced},
              "upstream_version": VERSION, "host_contracts": host, "linked_contracts": linked,
              "adapter_and_contract_source_inputs": source_hashes,
              "qualification_tool_sha256": digest(Path(__file__).resolve()),
              "m3_string_runtime": m3, "native_code": "NO_NEW_JNI_ABI",
              "full_jdk_suite": "NOT_RUN",
              "jackson_upstream_suite": "SEPARATE_UPSTREAM_QUALIFICATION_RECEIPT",
              "packaging_owner": "m3/tooling/jmod-recipes/tools/jmod_pack.py"}
    # Build and package operations must not mutate the actual donor checkout.
    check_donors(repo, lock)
    for name, expected in source_hashes.items():
        if digest(source / name) != expected:
            raise ValueError("adapter/contract source changed during qualification: " + name)
    if args.require_m3_runtime and m3["status"] != "PASS":
        result["status"] = "REQUIRED_M3_RUNTIME_UNAVAILABLE"
        write_json(out / "RESULT.json", result)
        raise RuntimeError("Core JMOD contracts passed; the requested M3 runtime flag is unavailable")
    write_json(out / "RESULT.json", result)
    print(json.dumps({"status": "PASS", "jmods": produced,
                      "m3_string_runtime": m3["status"], "result": str(out / "RESULT.json")}, sort_keys=True))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, KeyError, zipfile.BadZipFile) as failure:
        print("JACKSON_PACK_REFUSED:", failure, file=sys.stderr)
        raise SystemExit(1) from failure
