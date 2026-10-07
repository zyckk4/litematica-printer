# Pinned 1.21.11 dependencies

The native build uses this exact release pair. `prepareNativeDependencies`
downloads the originals, checks SHA256 and SHA512, and remaps them for
development. Compilation, `check` and `build` invoke the task automatically.
Dependency JARs are excluded from the source repository and printer distribution.

| Component | Version | Release metadata | Download |
| --- | --- | --- | --- |
| Litematica | 0.26.16 | [mengBR9D](https://api.modrinth.com/v2/version/mengBR9D) | [litematica-fabric-1.21.11-0.26.16.jar](https://cdn.modrinth.com/data/bEpr0Arc/versions/mengBR9D/litematica-fabric-1.21.11-0.26.16.jar) |
| MaLiLib | 0.27.20 | [loabWpyU](https://api.modrinth.com/v2/version/loabWpyU) | [malilib-fabric-1.21.11-0.27.20.jar](https://cdn.modrinth.com/data/GcWjdA9I/versions/loabWpyU/malilib-fabric-1.21.11-0.27.20.jar) |

Expected hashes:

```text
litematica-fabric-1.21.11-0.26.16.jar
SHA256 69df83a3d229a1fe77ddbeb54071a5ac07c2284ed4d623ea075d04e5f0a6d328
SHA512 c7d8e4ee3a1a4eacb825b19d389a06e17f6c4b78c3074fd929eb1bb1f5eabdbd77bd09545229b042e50f47aa78deac4005809a4c4bf554537e816e40e2885005

malilib-fabric-1.21.11-0.27.20.jar
SHA256 48461c24a560c68afc4042545b654d8d5ea3796a9339681485aed76a326f6ef3
SHA512 c6a0cbc407962b43316e52b15d928aa2137efa86963cdcea29699f43efa6442519206bdbfac23e4c6c36237931006ecd12b9c6450a4efd1a9a20689e50b5622a
```

Litematica 0.26.16 requires MaLiLib `>=0.27.19- <0.28.0-`; MaLiLib 0.27.20
is incompatible with Litematica `<0.26.13-`. Remapping preserves these
constraints and the dependencies' license resources.

With JDK 21, run `./gradlew check build` from the repository root, or
`.\gradlew.bat check build` on Windows. Generated files are stored in:

- `v1_21_11/build/native-dependencies/original/`: unmodified, verified releases.
- `v1_21_11/build/native-dependencies/named/`: development-only remapped inputs.

To provide local originals, use `-PlitematicaJar=<absolute-path>` and
`-PmalilibJar=<absolute-path>`. Quote the entire property argument when it
contains spaces. Relative paths resolve from `v1_21_11`.

`--offline` needs cached Gradle/Minecraft/tool dependencies and either existing
originals or supplied local files. `clean` removes generated directories; an
offline clean build therefore needs local originals outside those directories.

Install the original releases in the game's `mods/` directory. For offline build
examples and standalone remapping, see [tool instructions](../tools/README.md).
