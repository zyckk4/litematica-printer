#!/usr/bin/env python3
"""Package verified artifacts and committed source locally. Requires Python 3.11+."""

import hashlib
import json
import shutil
import subprocess
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def git(*args):
    return subprocess.check_output(["git", "-C", str(ROOT), *args])


def public_path(relative):
    path = Path(relative)
    if relative in {
        ".gitattributes", ".gitignore", "README.md", "LICENSE.md", "build.gradle.kts",
        "gradle.properties", "gradlew", "gradlew.bat", "settings.gradle",
        "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
        ".github/workflows/build.yml", "scripts/prepare_release.py",
        "v1_21_11/build.gradle.kts", "v1_21_11/gradle.properties",
        "v1_21_11/libs/README.md", "v1_21_11/tools/README.md",
        "v1_21_11/tools/RemapRealMods.java",
        "v1_21_11/tools/ProductionMixinSmoke.java",
        "v1_21_11/tools/remap-runtime-dependencies.ps1",
        "v1_21_11/tools/production-mixin-smoke.ps1",
    }:
        return True
    if relative.startswith(("v1_21_11/src/main/java/", "v1_21_11/src/test/java/")):
        return path.suffix == ".java"
    if relative.startswith("v1_21_11/src/main/resources/"):
        return path.suffix in {".json", ".png", ".accesswidener"}
    return False


def zip_entry(archive, name, content, executable=False):
    info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = (0o100755 if executable else 0o100644) << 16
    archive.writestr(info, content)


def main():
    if git("status", "--porcelain"):
        raise SystemExit("Commit the reviewed public files and build from a clean working tree first.")
    properties = {}
    for line in (ROOT / "v1_21_11/gradle.properties").read_text().splitlines():
        if "=" in line and not line.lstrip().startswith("#"):
            key, value = line.split("=", 1)
            properties[key.strip()] = value.strip()
    version = properties["mod_version"]
    minecraft = properties["minecraft_version"]
    stem = f"litematica-printer-{version}-mc{minecraft}"
    build = ROOT / "v1_21_11/build/libs"
    artifacts = [build / f"{stem}.jar", build / f"{stem}-sources.jar"]
    input_files = {
        "v1_21_11/build.gradle.kts", "v1_21_11/gradle.properties", "build.gradle.kts",
        "settings.gradle", "gradle.properties", "gradlew", "gradlew.bat",
        "gradle/wrapper/gradle-wrapper.jar", "gradle/wrapper/gradle-wrapper.properties",
        "LICENSE.md",
    }
    for directory in ("v1_21_11/src/main", "v1_21_11/src/test", "v1_21_11/tools"):
        input_files.update(path.relative_to(ROOT).as_posix()
                           for path in (ROOT / directory).rglob("*") if path.is_file())
    built_fingerprint = None
    for artifact in artifacts:
        if not artifact.is_file():
            raise SystemExit(f"Missing {artifact.name}; run check build first.")
        with zipfile.ZipFile(artifact) as jar:
            names = jar.namelist()
            if jar.read("LICENSE.md") != (ROOT / "LICENSE.md").read_bytes():
                raise SystemExit(f"License mismatch: {artifact.name}")
            if any(name.endswith(".jar") for name in names):
                raise SystemExit(f"Unexpected bundled JAR: {artifact.name}")
            if any("Regression" in name or name.startswith("fi/dy/masa/") for name in names):
                raise SystemExit(f"Unexpected test/dependency classes: {artifact.name}")
            fingerprint = json.loads(jar.read("printer-build-source.json"))
            if (fingerprint["schemaVersion"] != 1 or fingerprint["algorithm"] != "SHA-256"
                    or set(fingerprint["files"]) != input_files):
                raise SystemExit("Build input file set changed; rebuild before packaging.")
            if built_fingerprint is not None and fingerprint != built_fingerprint:
                raise SystemExit("Runtime and sources JARs came from different builds.")
            built_fingerprint = fingerprint
            for relative, expected in fingerprint["files"].items():
                source = ROOT / relative
                if not source.is_file() or sha256(source) != expected:
                    raise SystemExit(f"Stale build: {relative} changed; rebuild before packaging.")
    with zipfile.ZipFile(artifacts[0]) as jar:
        metadata = json.loads(jar.read("fabric.mod.json"))
        if (metadata["version"] != version or metadata["depends"]["minecraft"] != minecraft
                or metadata["license"] != "AGPL-3.0-only"):
            raise SystemExit("Built mod metadata does not match the release.")
        for name in jar.namelist():
            if name.endswith(".class") and int.from_bytes(jar.read(name)[6:8], "big") != 65:
                raise SystemExit(f"Unexpected Java bytecode version: {name}")

    source_files = sorted(git("ls-tree", "-r", "-z", "--name-only", "HEAD")
                          .decode().strip("\0").split("\0"))
    for relative in source_files:
        source = ROOT / relative
        if not public_path(relative):
            raise SystemExit(f"Unreviewed public path: {relative}")
        if source.is_symlink() or not source.is_file():
            raise SystemExit(f"Expected a regular source file: {relative}")
    if not input_files.issubset(source_files):
        raise SystemExit("Some build inputs are not in the committed public source.")

    output = (ROOT / "dist" / f"v{version}-mc{minecraft}").resolve()
    if not output.is_relative_to(ROOT / "dist"):
        raise SystemExit("Release output must remain inside dist/.")
    if output.exists():
        raise SystemExit(f"Output already exists: {output}. Archive it before packaging again.")
    output.parent.mkdir(parents=True, exist_ok=True)
    source_manifest = {
        "version": version,
        "minecraft": minecraft,
        "git_commit": git("rev-parse", "HEAD").decode().strip(),
        "git_branch": git("branch", "--show-current").decode().strip(),
        "source_files_sha256": {relative: sha256(ROOT / relative) for relative in source_files},
        "build_input_manifest_sha256": hashlib.sha256(
            json.dumps(built_fingerprint, sort_keys=True).encode()).hexdigest(),
    }
    # Build a fresh directory and rename it only after every asset succeeds.
    # Only this newly created temporary directory is removed on failure.
    with tempfile.TemporaryDirectory(prefix=".prepare-", dir=output.parent) as temporary:
        staging = Path(temporary) / output.name
        staging.mkdir()
        source_zip = staging / f"{stem}-source.zip"
        with zipfile.ZipFile(source_zip, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
            for relative in source_files:
                zip_entry(archive, f"{stem}-source/{relative}", (ROOT / relative).read_bytes(),
                          executable=Path(relative).name == "gradlew")
            zip_entry(archive, f"{stem}-source/source-manifest.json",
                      (json.dumps(source_manifest, indent=2, ensure_ascii=False) + "\n").encode())
        for artifact in artifacts:
            shutil.copyfile(artifact, staging / artifact.name)
        shutil.copyfile(ROOT / "LICENSE.md", staging / "LICENSE.md")
        (staging / "BUILD.json").write_text(
            json.dumps({key: value for key, value in source_manifest.items()
                        if key != "source_files_sha256"}, indent=2) + "\n", encoding="utf-8")
        asset_names = [artifact.name for artifact in artifacts] + [source_zip.name, "LICENSE.md", "BUILD.json"]
        (staging / "SHA256SUMS.txt").write_text(
            "".join(f"{sha256(staging / name)}  {name}\n" for name in asset_names), encoding="utf-8")
        staging.rename(output)
    print(f"Local release assets: {output}")
    print(f"Source commit: {source_manifest['git_commit']} ({len(source_files)} public files)")


if __name__ == "__main__":
    main()
