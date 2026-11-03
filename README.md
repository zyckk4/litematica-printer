# Litematica Printer — Minecraft 1.21.4

A Fabric printer extension for Litematica, optimized specifically for the current
2b2t server version and environment. This branch targets the **Minecraft 1.21.4 client**
connecting directly to 2b2t, with native placement and movement handling. No
client-side Via mod is required.

Version: **3.4.4**. The `1.21.4` branch ports the native printer logic from the
`1.21.11` branch, preserving its placement, movement and interaction behavior for
blocks supported by Minecraft 1.21.4. The earlier 1.21.4 implementation remains
available in Git history; the `1.21.11` branch targets the newer client separately.

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
- Share a configurable rolling budget for block and item interactions, defaulting
  to 9 interactions per 310 ms and up to 9 interactions per tick.

## Installation and use

Requirements:

- Minecraft **1.21.4** and Java **21**.
- Fabric Loader **0.18.2 or newer**.
- [Litematica 0.21.7](https://modrinth.com/mod/litematica/version/Z7jnVo9V).
- [MaLiLib 0.23.5](https://modrinth.com/mod/malilib/version/ifi4uRpL).

Place the original dependency JARs and
`litematica-printer-3.4.4-mc1.21.4.jar` in your instance's `mods/` directory.
Remove any older printer JAR first. Development `-named.jar` files are build
inputs and must not be installed in the game.

Load and position a schematic in Litematica. Press `CAPS_LOCK` to toggle printing,
or hold `V` to print. Open Litematica's configuration with `M + C`; printer
options are in **Generic**, and key bindings are in **Hotkeys**.

Automatic water-bucket use is disabled by default. Enable `printerWaterlogging`
to fill a dry block of the matching type where the schematic calls for waterlogging.
The printer checks line of sight from the player's eyes to that block, so you can
look elsewhere. Release sneak before waterlogging.

## Recommended settings

Existing configuration files retain their saved values. The two range defaults
are 4.5 blocks; set saved ranges to 4.5 manually if they were previously lowered.
The interaction defaults below apply to new configurations or reset options:

| Setting | Suggested value | Purpose |
| --- | --- | --- |
| `printingRange` | `4.5` | Printing range, capped by native interaction reach |
| `printerNativeMoving` | `true` | Enable native placement while moving |
| `printerNativeOmni` | `true` | Enable placement around the player |
| `printerGrimRotate` | `true` | Send the orientation required by directional blocks |
| `printerInteractionBurst` | `9` | Maximum interactions per tick (1–64), within the rolling budget |
| `printerInteractionWindowMs` | `310` | Rolling window in milliseconds (1–10000); 300 is also configurable |
| `printerInteractionWindowLimit` | `9` | Maximum interaction attempts in the window (1–64) |
| `printerTickDelay` | `0` | Attempt each tick within the shared interaction budget |
| `printerStopOnMovement` | `false` | Allow printing while moving |
| `printerRaycast` | `false` | Allow in-range targets behind obstructions |
| `printerStrictBlockFaceCheck` | `false` | Allow clicks on the far side of a support block |
| `printerIgnoreRotation` | `false` | Preserve schematic block orientations |
| `printerAirPlace` | `true` | Enable airplace for specialized placement handlers |
| `printerAirPlaceRange` | `4.5` | Range for specialized airplace handlers |
| `printerInventoryDelay` | `5` | Wait after replenishment; try `10` at high latency |
| `printerHotbarSlots` | `3,4,5,6,7,8,9` | Reserve the first two hotbar slots |
| `printerDisableInGuis` | `true` | Pause while a screen is open |

Keep `printerRotatePlayer`, `printerFreeLook` and `printerNativeGrimAirplace`
disabled for this setup. Native placement preserves the local camera. Actual
reach remains capped by the player's interaction range. Increasing the burst
does not raise the rolling budget or guarantee that every tick can fill a batch.

The shared budget includes waterlogging/item use and failed interaction attempts;
manual clicks and other mods are not counted. Paper normally shares a 9-interaction
budget across block and item use over 300 ms; the 310 ms default leaves a small
timing margin. Server configuration and network jitter can still affect acceptance.
Changing the window or limit preserves recent history instead of resetting the
budget. Lower the burst or increase the window if interactions are rejected.

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

## Open issues from the printer audit

These findings remain open after the waterlogging change.

| Area | Trigger and observed code behavior |
| --- | --- |
| Native solver | With `printerNativeMoving` enabled and both `printerNativeOmni` and `printerGrimRotate` disabled, ordinary blocks can be skipped even with solid support. |
| Fluid replacement | Disabling `replaceFluidSourceBlocks` also blocks flowing fluids on the native path. Legacy supported placement can still replace a source. |
| Stripped logs | `stripLogs` can strip a matching log already in the world, but the guide that first places a plain log is unregistered. |
| Keys and camera | `printerPreventDoubleTapSprinting` also affects play with printing off. Default `printerPickBlock` can consume middle click on a miss. Native mode disables legacy FreeLook and its inventory movement controls. In legacy mode FreeLook can stay active after printing stops. |
| Airplace and state matching | The legacy airplace switch, range, airplace-only mode and inactivity delay do not govern native omni placement. Legacy airplace can reuse a cached context with `printerNoPlacementCache` enabled. The explicit `printerAllowNoneExactStates` check is limited to the legacy glow lichen guide. |
| Inventory | `printerHotbarSlots` limits refill destinations. Existing materials in excluded slots can still be selected. A survival refill takes at least one tick even with zero inventory delay. Native material matching can skip a usable renamed block. |
| Redstone and signs | Clicking a repeater as a placement support can cycle its delay. Manually editing a sign at a schematic sign position while printing can submit schematic text and close the editor. |
| Stacked blocks | The guide for adding snow layers, candles or sea pickles rejects an existing block of the same type, leaving the count short. |
| Settings screen | Building the combined configuration list adds Litematica's Generic options twice. |

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
[Tool instructions](v1_21_4/tools/README.md) cover local dependency files,
offline builds and the production Mixin loading check.

Output is under `v1_21_4/build/libs/`. Install the remapped printer JAR; the
`-sources.jar` is for development. `check` and `build` run the native regression
harness under Fabric Knot. These checks verify code and loading behavior;
gameplay and server acceptance require a client test.

After committing the reviewed files and building from a clean working tree,
run `python scripts/prepare_release.py` with Python 3.11 or newer to create local
JAR, source and checksum assets under `dist/`. The packager includes only the
committed public files and checks that the JARs match the current build inputs.

GitHub Actions builds and packages the same source on Linux and Windows, with a
production Mixin loading check on Windows. Each successful run retains verified
release assets for 30 days. Public releases use assets from a successful run;
publishing remains a separate step.

## Repository layout

| Path | Purpose |
| --- | --- |
| `v1_21_4/src/main` | Native printer implementation |
| `v1_21_4/src/test` | Native regression harness |
| `v1_21_4/tools` | Dependency remapping and production smoke tools |
| `v1_21_4/libs/README.md` | Pinned dependency versions and checksums |
| `scripts` | Release packaging tools |

Report issues at [zyckk4/litematica-printer](https://github.com/zyckk4/litematica-printer/issues).

## License

[GNU Affero General Public License v3](LICENSE.md) (`AGPL-3.0-only`).
