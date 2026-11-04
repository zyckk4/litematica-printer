# Litematica Printer — Minecraft 1.21.11

A Fabric printer extension for Litematica, optimized specifically for the current
2b2t server version and environment. This branch targets the **Minecraft 1.21.11 client** with
native placement and movement handling, without a protocol downgrade.

Version: **3.4.5**. The `1.21.11` branch contains the native implementation;
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
  note-block pitch, daylight-detector inversion and interactable open/powered states.
- Share a configurable rolling budget for block and item interactions, defaulting
  to 9 interactions per 310 ms and up to 9 interactions per tick.

## Installation and use

Requirements:

- Minecraft **1.21.11** and Java **21**.
- Fabric Loader **0.18.2 or newer**.
- [Litematica 0.26.16](https://modrinth.com/mod/litematica/version/mengBR9D).
- [MaLiLib 0.27.20](https://modrinth.com/mod/malilib/version/loabWpyU).

Place the original dependency JARs and
`litematica-printer-3.4.5-mc1.21.11.jar` in your instance's `mods/` directory.
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
| `printerHotbarSlots` | `3,4,5,6,7,8,9` | Use slots 3–9 as refill destinations |
| `printerDisableInGuis` | `true` | Pause while a screen is open |

Keep `printerRotatePlayer`, `printerFreeLook` and `printerNativeGrimAirplace`
disabled for this setup. Either native mode leaves the local camera in control.
The legacy FreeLook camera and its inventory movement controls require both
`printerNativeMoving` and `printerNativeOmni` to be off. Actual
reach remains capped by the player's interaction range. Increasing the burst
does not raise the rolling budget or guarantee that every tick can fill a batch.

The shared budget includes waterlogging/item use and failed interaction attempts;
manual clicks and other mods are not counted. Paper normally shares a 9-interaction
budget across block and item use over 300 ms; the 310 ms default leaves a small
timing margin. Server configuration and network jitter can still affect acceptance.
Changing the window or limit preserves recent history instead of resetting the
budget. Lower the burst or increase the window if interactions are rejected.

## Placement limits

With `printerNativeMoving` enabled, ordinary placements take priority. While moving,
the printer can also lay supported rails, adjust repeaters, comparators, note
blocks and daylight detectors, and waterlog eligible targets. Leave an empty inventory slot for tuning
and release sneak for tuning and bucket use. Rail connections depend on the
support and neighboring tracks already in the world. Stand still for other
specialized guides and precise sign rotations. Adjacent independent single
chests require actual sneaking to prevent automatic merging.

Disable `replaceFluidSourceBlocks` to protect water and lava sources at the
placement position. Flowing water and lava remain eligible for replacement.
With `stripLogs` enabled, the printer prefers the finished material. When only
the corresponding plain log and an axe are available, it places the log on the
schematic axis and strips it once placed.

Daylight detectors are placed before their inversion mode is adjusted. Output
power follows the actual light and does not block printing. Mode changes wait
for server confirmation, including while standing still.

Turtle eggs, pink petals, wildflowers and leaf litter are added one item at a
time; glow lichen, sculk veins and resin clumps are added one supported face at
a time. Bamboo may start as a shoot, and dripstone thickness and shelf connections
settle after placement. Their support and growth rules still apply.

Farmland and potted plants start with dirt and an empty flower pot, followed by
the existing stationary tilling/filling guides. Filled cauldrons and candle cakes
start with an empty cauldron and a plain cake. Content and transient states no
longer prevent placing supported block shells, but this does not restore their
contents: the remaining manual steps are listed below. Copper hoes and shovels
are accepted by the tilling and campfire-extinguishing guides.

Incorrect blocks and orientations are not automatically excavated and replaced.
Redstone-controlled states follow the surrounding circuit. Sign text is filled
when an editing screen opens; existing text, back-face text, dye, glow and wax
are not automatically repaired.

Server updates and configuration can affect placement acceptance. The 2b2t
optimization target does not guarantee compatibility with every server setup.

## Remaining issues in 3.4.5

| Area | Trigger and observed code behavior |
| --- | --- |
| Native solver | With `printerNativeMoving` enabled and both `printerNativeOmni` and `printerGrimRotate` disabled, ordinary blocks can be skipped even with solid support. |
| Content and transformations | The printer can start with the empty/basic block, but does not insert books, bottles or records; fill cauldrons; add candles to cakes; charge respawn anchors; fill composters; or recreate cake bites and honey levels. Restore these separately. Bamboo growth, berries and hydration also follow normal game rules. |
| Existing light state | An extinguished campfire is not automatically relit, and a lit candle is not automatically extinguished. The opposite operations are supported while stationary. |
| Vine attachments | Ordinary vines can accept a different supported face from the schematic and do not repair existing faces. Multiface blocks use the separate staged-face path. |
| Strict raycast and slabs | With both `printerRaycast` and `printerRaycastStrictBlockHit` enabled, completing a bottom slab from directly above can fail because hit samples use full-block faces. |
| Keys and camera | Default `printerPickBlock` can consume middle click on a miss. In legacy mode FreeLook can stay active after printing stops. |
| Airplace and state matching | The legacy airplace switch, range, airplace-only mode and inactivity delay do not govern native omni placement. Legacy airplace can reuse a cached context with `printerNoPlacementCache` enabled. The explicit `printerAllowNoneExactStates` check is limited to the legacy glow lichen guide. |
| Inventory | `printerHotbarSlots` limits refill destinations. Existing materials in excluded slots can still be selected. A survival refill takes at least one tick even with zero inventory delay. Native material matching can skip a usable renamed block. |
| Signs | Manually editing a sign at a schematic sign position while printing can submit schematic text and close the editor. |
| Settings screen | Building the combined configuration list adds Litematica's Generic options twice. |

Standalone water/lava source placement and arbitrary block-entity inventories/NBT
are not supported. Natural-only flags such as a shrieker's `can_summon` or a
creaking heart's `natural` remain strict; ordinary placement cannot recreate
them. Transient redstone/sculk/animation states are not kept frozen to the
schematic. These limits are separate from successfully placing the base block.

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

GitHub Actions builds and packages the same source on Linux and Windows, with a
production Mixin loading check on Windows. Each successful run retains verified
release assets for 30 days. Public releases use assets from a successful run;
publishing remains a separate step.

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
