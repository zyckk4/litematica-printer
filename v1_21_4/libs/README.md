# Pinned 1.21.4 dependencies

The native build uses this exact release pair. `prepareNativeDependencies`
downloads the originals, checks SHA256 and SHA512, and remaps them for
development. Compilation, `check` and `build` invoke the task automatically.
Dependency JARs are excluded from the source repository and printer distribution.

| Component | Version | Release metadata | Download |
| --- | --- | --- | --- |
| Litematica | 0.21.7 | [Z7jnVo9V](https://api.modrinth.com/v2/version/Z7jnVo9V) | [litematica-fabric-1.21.4-0.21.7.jar](https://cdn.modrinth.com/data/bEpr0Arc/versions/Z7jnVo9V/litematica-fabric-1.21.4-0.21.7.jar) |
| MaLiLib | 0.23.5 | [ifi4uRpL](https://api.modrinth.com/v2/version/ifi4uRpL) | [malilib-fabric-1.21.4-0.23.5.jar](https://cdn.modrinth.com/data/GcWjdA9I/versions/ifi4uRpL/malilib-fabric-1.21.4-0.23.5.jar) |

Expected hashes:

```text
litematica-fabric-1.21.4-0.21.7.jar
SHA256 19fbf46ddc0776c2ff2a1be4cac627b3a236cc4682ce93d01c858e1437e93d4d
SHA512 a4931c649fb21d52be1f12abd1a31950b63429e0e083974347b2e7bab3da8edb19930906790de70ef3b2803ea17059d2ef188c5e0ac13dfdb8272f1a1bb16f4d

malilib-fabric-1.21.4-0.23.5.jar
SHA256 07e2c7569378e37059b35c0cf7a6530554f756737c8e341e1a8c2312dc2d4548
SHA512 03d0a554a2a6a069e9a1b624d9bb72c7e1f17a1e29f2c62e5c29284912829ba9a476cc34dec1a7ee8409ca08b46deb60527363659b4fa16d8a71d8110b67a18b
```

Litematica 0.21.7 requires MaLiLib `>=0.23.5-`; MaLiLib 0.23.5
is incompatible with Litematica `<=0.21.1`. Both releases require Minecraft
`1.21.4`. MaLiLib includes the Fabric API base, networking and resource-loader
modules it needs. The remapper processes those nested modules and preserves
dependency constraints and license resources.

With JDK 21, run `./gradlew check build` from the repository root, or
`.\gradlew.bat check build` on Windows. Generated files are stored in:

- `v1_21_4/build/native-dependencies/original/`: unmodified, verified releases.
- `v1_21_4/build/native-dependencies/named/`: development-only remapped inputs.

To provide local originals, use `-PlitematicaJar=<absolute-path>` and
`-PmalilibJar=<absolute-path>`. Quote the entire property argument when it
contains spaces. Relative paths resolve from `v1_21_4`.

`--offline` needs cached Gradle/Minecraft/tool dependencies and either existing
originals or supplied local files. `clean` removes generated directories; an
offline clean build therefore needs local originals outside those directories.

Install the original releases in the game's `mods/` directory. For offline build
examples and standalone remapping, see [tool instructions](../tools/README.md).
