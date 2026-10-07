# Native build tools

## Normal build

Use JDK 21 and the repository's Gradle wrapper:

```sh
./gradlew clean check build --no-daemon --console=plain
```

On Windows, use `.\gradlew.bat` in place of `./gradlew`.

The build runs `prepareNativeDependencies`, downloads the exact releases listed
in [the dependency manifest](../libs/README.md), verifies SHA256 and SHA512, and
invokes `RemapRealMods.java`. Gradle resolves TinyRemapper 0.12.2, mapping-io 0.8.0
and ASM 9.10.1; Loom supplies Minecraft 1.21.11 and its mappings. The remapper
also processes nested mods and access wideners.

Generated dependencies are under:

- `v1_21_11/build/native-dependencies/original/`: unmodified release JARs.
- `v1_21_11/build/native-dependencies/named/`: development-only remapped JARs.

Only original dependency releases belong in a game's `mods/` directory.
Generated inputs and Gradle caches are excluded from version control.

## Local inputs and offline builds

To use downloaded originals, pass both properties to Gradle:

```powershell
.\gradlew.bat check build `
  '-PlitematicaJar=C:\path\to\litematica-fabric-1.21.11-0.26.16.jar' `
  '-PmalilibJar=C:\path\to\malilib-fabric-1.21.11-0.27.20.jar'
```

Hashes are checked for local inputs too. Prefer absolute paths and quote the
whole argument if it contains spaces. Relative paths resolve from `v1_21_11`.

Add `--offline` once the wrapper, Gradle dependencies, Minecraft, mappings and
tools have been cached. Offline preparation uses existing originals or the two
local files; it fails when an input is missing or mismatched. `clean` removes
generated dependencies, so supply local originals for an offline clean build.

Run `:v1_21_11:prepareNativeDependencies` separately to prepare only these inputs.

## Standalone dependency remapping

`remap-runtime-dependencies.ps1` remaps local original releases without running
Gradle or downloading dependencies. It requires:

- JDK 21, selected by `JAVA_HOME`, `-JavaHome` or `java` on `PATH`.
- A Loom cache for Minecraft 1.21.11 and Yarn 1.21.11+build.3.
- Cached TinyRemapper 0.12.2, mapping-io 0.8.0 and ASM 9.10.1 (`asm`,
  `asm-commons`, `asm-tree`, `asm-analysis`).
- The two original releases listed in the dependency manifest.

From the repository root:

```powershell
.\v1_21_11\tools\remap-runtime-dependencies.ps1 `
  -LitematicaJar 'C:\path\to\litematica-fabric-1.21.11-0.26.16.jar' `
  -MalilibJar 'C:\path\to\malilib-fabric-1.21.11-0.27.20.jar'
```

Use `-GradleUserHome` and `-JavaHome` to select caches and Java, or
`-OutputDirectory` to choose an output location. `-AsmVersion` selects another
installed ASM version explicitly. Missing or ambiguous cache artifacts stop
the script.

The default output, `v1_21_11/libs/named/`, is for inspection and is not consumed
by normal builds. Temporary files are under `v1_21_11/build/remap-runtime/`.
The script verifies both input hashes, and the Java helper verifies SHA256 when
invoked directly. Remapping preserves dependency versions, entrypoints and
license resources. Generated dependency archives can differ in ZIP timestamps
or ordering; their input versions and hashes are pinned.

## Production Mixin loading check

After building, run the smoke tool with the original dependencies:

```powershell
.\v1_21_11\tools\production-mixin-smoke.ps1 `
  -LitematicaJar 'C:\path\to\litematica-fabric-1.21.11-0.26.16.jar' `
  -MalilibJar 'C:\path\to\malilib-fabric-1.21.11-0.27.20.jar' `
  -JavaHome 'C:\path\to\jdk-21'
```

You can use the originals in `v1_21_11/build/native-dependencies/original/`.
`-PrinterJar` selects a release artifact, and `-GradleUserHome` selects the cache.
The tool checks dependency hashes and uses cached Minecraft 1.21.11, Fabric
Loader 0.18.2, ASM 9.9 and Mixin; it does not download dependencies or run Gradle.

The smoke check loads the remapped printer in Fabric's production `intermediary`
namespace, checks its Mixin targets and key injection handlers, and writes input
copies, the printer SHA256 and `smoke.log` to a new
`v1_21_11/build/production-smoke/` directory. It does not launch a game or connect
to a server, so gameplay still needs client verification.
