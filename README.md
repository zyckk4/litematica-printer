# Litematica Printer — Minecraft 1.21.11

A Fabric printer extension for Litematica, optimized specifically for the current
2b2t server version and environment. This branch targets the **Minecraft 1.21.11 client** with
native placement and movement handling, without a protocol downgrade.

Version: **3.4.2**. The `1.21.11` branch contains the native implementation;
the earlier implementation is maintained separately on `1.21.4`.

## Features

- Print while moving, with directional placement that preserves the local camera
  and world movement direction.
- Place throughout the configured range using native airplace and placement
  simulation, with support and collision checks.
- Handle directional blocks, attachments, staged double chests and double slabs.
- Replenish materials from the inventory and track placement results from server
  block updates.
- Adjust supported block states, including repeater delay, comparator mode,
  note-block pitch and interactable open/powered states.
- Share a 9-interaction/310 ms rolling budget, with a configurable per-tick burst.

## Installation and use

Requirements:

- Minecraft **1.21.11** and Java **21**.
- Fabric Loader **0.18.2 or newer**.
- [Litematica 0.26.16](https://modrinth.com/mod/litematica/version/mengBR9D).
- [MaLiLib 0.27.20](https://modrinth.com/mod/malilib/version/loabWpyU).

Place the original dependency JARs and
`litematica-printer-3.4.2-mc1.21.11.jar` in your instance's `mods/` directory.
Remove any older printer JAR first. Development `-named.jar` files are build
inputs and must not be installed in the game.

Load and position a schematic in Litematica. Press `CAPS_LOCK` to toggle printing,
or hold `V` to print. Open Litematica's configuration with `M + C`; printer
options are in **Generic**, and key bindings are in **Hotkeys**.

## Recommended settings

Existing configuration files retain their saved values. Start with these settings
and increase the range only after checking placement results:

| Setting | Suggested value | Purpose |
| --- | --- | --- |
| `printingRange` | `4.3` | Keep a margin within normal interaction reach |
| `printerNativeMoving` | `true` | Enable native placement while moving |
| `printerNativeOmni` | `true` | Enable placement around the player |
| `printerGrimRotate` | `true` | Send the orientation required by directional blocks |
| `printerInteractionBurst` | `2` | Maximum interactions per tick; reduce to `1` if rejected |
| `printerTickDelay` | `0` | Attempt each tick within the shared interaction budget |
| `printerStopOnMovement` | `false` | Allow printing while moving |
| `printerRaycast` | `false` | Allow in-range targets behind obstructions |
| `printerStrictBlockFaceCheck` | `false` | Allow clicks on the far side of a support block |
| `printerIgnoreRotation` | `false` | Preserve schematic block orientations |
| `printerAirPlace` | `true` | Enable airplace for specialized placement handlers |
| `printerAirPlaceRange` | `4.3` | Range for specialized airplace handlers |
| `printerInventoryDelay` | `5` | Wait after replenishment; try `10` at high latency |
| `printerHotbarSlots` | `3,4,5,6,7,8,9` | Reserve the first two hotbar slots |
| `printerDisableInGuis` | `true` | Pause while a screen is open |

Keep `printerRotatePlayer`, `printerFreeLook` and `printerNativeGrimAirplace`
disabled for this setup. Native placement preserves the local camera. Range can
be increased to `4.5` where reliable; actual reach remains capped by the player's
interaction range. Increasing the burst does not raise the rolling budget.

## Placement limits

State interactions require standing still, not sneaking, and at least one empty
inventory slot. Adjacent independent single chests require actual sneaking to
prevent automatic merging. Stand still to finish precise sign rotations and
specialized placement such as rails or gravity blocks.

Incorrect blocks and orientations are not automatically excavated and replaced.
Redstone-controlled states follow the surrounding circuit. Sign text is filled
when an editing screen opens; existing text, back-face text, dye, glow and wax
are not automatically repaired.

Server updates and configuration can affect placement acceptance. The 2b2t
optimization target does not guarantee compatibility with every server setup.

## Build and verification

Use JDK 21 and the included Gradle wrapper:

```sh
./gradlew clean check build --no-daemon --console=plain
```

In Windows PowerShell:

```powershell
.\gradlew.bat clean check build --no-daemon --console=plain
```

The build downloads the pinned dependencies, verifies their hashes and prepares
development inputs automatically. The first build needs network access.
[Tool instructions](v1_21_11/tools/README.md) cover local dependency files,
offline builds and the production Mixin loading check.

Output is under `v1_21_11/build/libs/`. Install the remapped printer JAR; the
`-sources.jar` is for development. `check` and `build` run the native regression
harness under Fabric Knot. These checks verify code and loading behavior;
gameplay and server acceptance require a client test.

After committing the reviewed files and building from a clean working tree,
run `python scripts/prepare_release.py` with Python 3.11 or newer to create local
JAR, source and checksum assets under `dist/`. The packager includes only the
committed public files and checks that the JARs match the current build inputs.

## Repository layout

| Path | Purpose |
| --- | --- |
| `v1_21_11/src/main` | Native printer implementation |
| `v1_21_11/src/test` | Native regression harness |
| `v1_21_11/tools` | Dependency remapping and production smoke tools |
| `v1_21_11/libs/README.md` | Pinned dependency versions and checksums |
| `scripts` | Release packaging tools |

Report issues at [zyckk4/litematica-printer](https://github.com/zyckk4/litematica-printer/issues).

## License

[GNU Affero General Public License v3](LICENSE.md) (`AGPL-3.0-only`).
