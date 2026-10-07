package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_11.actions.PostAction;
import me.aleksilassila.litematica.printer.v1_21_11.actions.PrepareLook;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.PrinterPlacementContext;
import me.aleksilassila.litematica.printer.v1_21_11.mixin.MixinAccessorClientPlayerEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.HopperBlock;
import net.minecraft.block.SlabBlock;
import net.minecraft.block.StairsBlock;
import net.minecraft.block.TrapdoorBlock;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.block.enums.BlockHalf;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.enums.ComparatorMode;
import net.minecraft.block.enums.DoorHinge;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.resource.featuretoggle.FeatureSet;
import net.minecraft.state.property.Properties;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.math.random.Random;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.world.border.WorldBorder;
import sun.misc.Unsafe;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Exercises public solver entrypoints using real 1.21.11 BlockItems and blocks.
 * Only world storage, player state and the network sink are headless fixtures;
 * candidate enumeration, context construction, placement and revalidation are
 * production code. This does not emulate a remote server or anti-cheat.
 */
public final class NativePlacementSolverChecks {
    private static int assertions;
    private static int solutions;
    private static int familySolutions;
    private static Method speedFactors;
    private static Method toVelocity;

    private NativePlacementSolverChecks() {}

    public static int run() throws Exception {
        assertions = 0;
        solutions = 0;
        familySolutions = 0;
        speedFactors = ClientPlayerEntity.class.getDeclaredMethod("applyDirectionalMovementSpeedFactors", Vec2f.class);
        speedFactors.setAccessible(true);
        toVelocity = Entity.class.getDeclaredMethod("movementInputToVelocity", Vec3d.class, float.class, float.class);
        toVelocity.setAccessible(true);
        MinecraftClient previous = MinecraftClient.getInstance();
        boolean raycast = PrinterConfig.RAYCAST.getBooleanValue();
        boolean ignoreRotation = PrinterConfig.PRINTER_IGNORE_ROTATION.getBooleanValue();
        try (Fixture fixture = new Fixture()) {
            PrinterConfig.RAYCAST.setBooleanValue(false);
            PrinterConfig.PRINTER_IGNORE_ROTATION.setBooleanValue(false);
            checkSixDirections(fixture);
            checkAllOctants(fixture);
            checkFaceAndHalfFamilies(fixture);
            checkOtherDirectionalFamilies(fixture);
            checkTorchAttachments(fixture);
            checkChestStages(fixture);
            checkSlabStages(fixture);
            checkHiddenSlabMerges(fixture);
            checkTrapdoorsAndLevers(fixture);
            checkDoorHinges(fixture);
            checkStandingSigns(fixture);
            checkRevalidationAndRejection(fixture);
            checkNativeCameraIsolation(fixture);
            checkAirborneAndJumpRotation(fixture);
            checkLegacyCameraAndPacketCache(fixture);
            System.out.println("Native solver checks passed: " + solutions
                    + " complete six-way solutions; " + familySolutions + " additional directional/attachment solutions; "
                    + assertions + " solver/movement assertions.");
        } finally {
            set(MinecraftClient.class, null, "instance", previous);
            PrinterConfig.RAYCAST.setBooleanValue(raycast);
            PrinterConfig.PRINTER_IGNORE_ROTATION.setBooleanValue(ignoreRotation);
            MovementHandler.clearRotation();
        }
        return assertions;
    }

    private static void checkSixDirections(Fixture fixture) throws Exception {
        Block[] blocks = {Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.DISPENSER, Blocks.OBSERVER};
        // These are six different target locations, independently of the six
        // desired block orientations. None intersects the player's body.
        BlockPos[] targets = {
                new BlockPos(0, 65, 3), new BlockPos(0, 65, -3),
                new BlockPos(3, 65, 0), new BlockPos(-3, 65, 0),
                new BlockPos(0, 68, 0), new BlockPos(0, 62, 0)
        };
        float[] yaws = {-179.0F, -91.0F, -37.0F, 0.0F, 23.0F, 89.0F, 175.0F};
        float[] pitches = {-82.0F, 13.0F, 78.0F};
        for (Block block : blocks) {
            for (BlockPos target : targets) {
                for (Direction facing : Direction.values()) {
                    BlockState desired = block.getDefaultState().with(Properties.FACING, facing);
                    for (float yaw : yaws) {
                        for (float pitch : pitches) {
                            for (Motion motion : Motion.values()) {
                                fixture.configure(block, yaw, pitch, motion);
                                verifySolution(fixture, target, desired, motion);
                            }
                        }
                    }
                }
            }
        }
    }

    private static void checkAllOctants(Fixture fixture) throws Exception {
        // All 26 adjacent spatial directions, including edge/corner targets,
        // remain independent of both piston facing and the user's camera.
        BlockPos origin = new BlockPos(0, 65, 0);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) continue;
                    BlockPos target = origin.add(dx * 2, dy * 2, dz * 2);
                    for (Direction facing : Direction.values()) {
                        for (Motion motion : Motion.values()) {
                            fixture.configure(Blocks.PISTON, 41.25F, -56.0F, motion);
                            verifySolution(fixture, target,
                                    Blocks.PISTON.getDefaultState().with(Properties.FACING, facing), motion);
                        }
                    }
                }
            }
        }
    }

    private static BlockPos[] familyTargets() {
        return new BlockPos[]{new BlockPos(0, 65, 3), new BlockPos(0, 65, -3),
                new BlockPos(3, 65, 0), new BlockPos(-3, 65, 0),
                new BlockPos(0, 68, 0), new BlockPos(0, 62, 0)};
    }

    private static void checkFaceAndHalfFamilies(Fixture fixture) throws Exception {
        for (BlockPos target : familyTargets()) {
            for (Motion motion : Motion.values()) {
                // Hopper output follows the clicked face, not yaw. UP is not
                // an allowed vanilla hopper state, so only its five values count.
                for (Direction facing : HopperBlock.FACING.getValues()) {
                    fixture.configure(Blocks.HOPPER, 23.0F, -51.0F, motion);
                    verifyFamilySolution(fixture, target,
                            Blocks.HOPPER.getDefaultState().with(HopperBlock.FACING, facing), motion);
                }
                for (SlabType type : new SlabType[]{SlabType.TOP, SlabType.BOTTOM}) {
                    fixture.configure(Blocks.STONE_SLAB, -37.0F, 78.0F, motion);
                    verifyFamilySolution(fixture, target,
                            Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, type), motion);
                }
                for (Direction facing : Direction.Type.HORIZONTAL) {
                    for (BlockHalf half : BlockHalf.values()) {
                        fixture.configure(Blocks.OAK_STAIRS, -37.0F, 78.0F, motion);
                        verifyFamilySolution(fixture, target, Blocks.OAK_STAIRS.getDefaultState()
                                .with(StairsBlock.FACING, facing).with(StairsBlock.HALF, half), motion);
                    }
                }
            }
        }
    }

    private static void checkTorchAttachments(Fixture fixture) throws Exception {
        Block[][] torches = {{Blocks.TORCH, Blocks.WALL_TORCH},
                {Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH},
                {Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH}};
        for (Block[] torch : torches) {
            for (BlockPos target : familyTargets()) {
                for (Motion motion : Motion.values()) {
                    fixture.configure(torch[0], 23.0F, -51.0F, motion);
                    fixture.world.blocks.put(target.down(), Blocks.STONE.getDefaultState());
                    NativePlacementSolver.Solution floor = verifyFamilySolution(fixture, target,
                            torch[0].getDefaultState(), motion);
                    fixture.world.blocks.remove(target.down());
                    check(NativePlacementSolver.revalidate(target, torch[0].getDefaultState(),
                                    fixture.player.heldStack, floor, 4.5) == null,
                            "Standing torch survived removal of its required floor support");
                    for (Direction facing : Direction.Type.HORIZONTAL) {
                        fixture.configure(torch[0], 23.0F, -51.0F, motion);
                        fixture.world.blocks.put(target.offset(facing.getOpposite()), Blocks.STONE.getDefaultState());
                        BlockState desired = torch[1].getDefaultState().with(WallTorchBlock.FACING, facing);
                        NativePlacementSolver.Solution wall = verifyFamilySolution(fixture, target, desired, motion);
                        fixture.world.blocks.clear();
                        check(NativePlacementSolver.revalidate(target, desired,
                                        fixture.player.heldStack, wall, 4.5) == null,
                                "Wall torch survived removal of its required wall support: " + desired);
                    }
                }
            }
        }
        // With multiple valid attachments present, the chosen floor/wall
        // variant and wall direction must still match the schematic exactly.
        BlockPos enclosedTarget = new BlockPos(2, 65, 0);
        for (Block[] torch : torches) {
            for (Motion motion : Motion.values()) {
                List<BlockState> variants = new ArrayList<>();
                variants.add(torch[0].getDefaultState());
                for (Direction facing : Direction.Type.HORIZONTAL) {
                    variants.add(torch[1].getDefaultState().with(WallTorchBlock.FACING, facing));
                }
                for (BlockState desired : variants) {
                    fixture.configure(torch[0], 23.0F, -51.0F, motion);
                    for (Direction side : Direction.values()) {
                        fixture.world.blocks.put(enclosedTarget.offset(side), Blocks.STONE.getDefaultState());
                    }
                    verifyFamilySolution(fixture, enclosedTarget, desired, motion);
                }
            }
        }
        // An airplace packet does not suspend vanilla support requirements.
        for (Block[] torch : torches) {
            fixture.configure(torch[0], 23.0F, -51.0F, Motion.STILL);
            BlockPos target = new BlockPos(2, 65, 0);
            check(NativePlacementSolver.solveStationary(target, torch[0].getDefaultState(),
                            fixture.player.heldStack, 4.5, true) == null,
                    "Unsupported floor torch was accepted");
            for (Direction facing : Direction.Type.HORIZONTAL) {
                check(NativePlacementSolver.solveStationary(target,
                                torch[1].getDefaultState().with(WallTorchBlock.FACING, facing),
                                fixture.player.heldStack, 4.5, true) == null,
                        "Unsupported wall torch was accepted: " + facing);
            }
        }
    }

    private static void checkOtherDirectionalFamilies(Fixture fixture) throws Exception {
        for (BlockPos target : familyTargets()) {
            for (Motion motion : Motion.values()) {
                for (Block pillar : new Block[]{Blocks.OAK_LOG, Blocks.IRON_CHAIN}) {
                    for (Direction.Axis axis : Direction.Axis.values()) {
                        fixture.configure(pillar, 23.0F, -51.0F, motion);
                        verifyFamilySolution(fixture, target,
                                pillar.getDefaultState().with(Properties.AXIS, axis), motion);
                    }
                }
                for (Direction facing : Direction.values()) {
                    fixture.configure(Blocks.SHULKER_BOX, 23.0F, -51.0F, motion);
                    verifyFamilySolution(fixture, target,
                            Blocks.SHULKER_BOX.getDefaultState().with(Properties.FACING, facing), motion);
                }
                for (Direction facing : Direction.Type.HORIZONTAL) {
                    fixture.configure(Blocks.FURNACE, 23.0F, -51.0F, motion);
                    verifyFamilySolution(fixture, target,
                            Blocks.FURNACE.getDefaultState().with(Properties.HORIZONTAL_FACING, facing), motion);
                }
            }
        }
    }

    private static void checkChestStages(Fixture fixture) throws Exception {
        BlockPos target = new BlockPos(0, 65, 2);
        FixtureWorld schematic = fixture.newWorld();
        for (Block block : new Block[]{Blocks.CHEST, Blocks.TRAPPED_CHEST}) {
            for (Direction facing : Direction.Type.HORIZONTAL) {
                for (Motion motion : Motion.values()) {
                    fixture.configure(block, 23.0F, -51.0F, motion);
                    BlockState single = block.getDefaultState().with(ChestBlock.FACING, facing);
                    verifyFamilySolution(fixture, target, single, motion);
                    // Either half can be placed first. The first real vanilla
                    // state is SINGLE; the second placement and vanilla neighbor
                    // update must then produce the exact two schematic halves.
                    for (ChestType firstType : new ChestType[]{ChestType.LEFT, ChestType.RIGHT}) {
                        fixture.configure(block, 23.0F, -51.0F, motion);
                        BlockState firstDesired = single.with(ChestBlock.CHEST_TYPE, firstType);
                        Direction partnerSide = ChestBlock.getFacing(firstDesired);
                        BlockPos partner = target.offset(partnerSide);
                        BlockState secondDesired = single.with(ChestBlock.CHEST_TYPE, firstType.getOpposite());
                        schematic.blocks.clear();
                        schematic.blocks.put(target, firstDesired);
                        schematic.blocks.put(partner, secondDesired);
                        BlockState firstStage = NativePlacementSolver.placementTarget(fixture.world, schematic, target, firstDesired);
                        check(firstStage.equals(single), "Double chest's first half did not stage as SINGLE");
                        NativePlacementSolver.Solution first = verifyFamilySolution(fixture, target, firstStage, motion);
                        fixture.world.blocks.put(target, first.predicted());
                        BlockState secondStage = NativePlacementSolver.placementTarget(fixture.world, schematic, partner, secondDesired);
                        check(secondStage.equals(secondDesired), "Second chest half lost its required LEFT/RIGHT type");
                        NativePlacementSolver.Solution second = verifyFamilySolution(fixture, partner, secondStage, motion);
                        fixture.world.blocks.put(partner, second.predicted());
                        BlockState updatedFirst = first.predicted().getStateForNeighborUpdate(fixture.world,
                                fixture.world, target, partnerSide, partner, second.predicted(), Random.create(1));
                        check(updatedFirst.equals(firstDesired), "Vanilla neighbor update did not finish first chest half");
                    }
                    // A mismatched or missing schematic partner is not a valid
                    // excuse to silently drop the requested chest type.
                    fixture.configure(block, 23.0F, -51.0F, motion);
                    BlockState left = single.with(ChestBlock.CHEST_TYPE, ChestType.LEFT);
                    schematic.blocks.clear();
                    schematic.blocks.put(target, left);
                    check(NativePlacementSolver.placementTarget(fixture.world, schematic, target, left).equals(left),
                            "Double chest staged without a matching schematic partner");
                    schematic.blocks.put(target.offset(ChestBlock.getFacing(left)),
                            single.with(ChestBlock.CHEST_TYPE, ChestType.RIGHT).with(ChestBlock.FACING, facing.getOpposite()));
                    check(NativePlacementSolver.placementTarget(fixture.world, schematic, target, left).equals(left),
                            "Double chest staged with an incompatible partner facing");
                }
                fixture.configure(block, 23.0F, -51.0F, Motion.STILL);
                BlockState adjacentSingle = block.getDefaultState().with(ChestBlock.FACING, facing);
                fixture.world.blocks.put(target.offset(facing.rotateYClockwise()), adjacentSingle);
                fixture.player.sneaking = true;
                ((FixtureInput) fixture.player.input).set(new PlayerInput(false, false, false, false, false, true, false));
                verifyFamilySolution(fixture, target, adjacentSingle, Motion.STILL);
            }
        }
    }

    private static void checkSlabStages(Fixture fixture) throws Exception {
        boolean strict = PrinterConfig.STRICT_BLOCK_FACE_CHECK.getBooleanValue();
        try {
            PrinterConfig.STRICT_BLOCK_FACE_CHECK.setBooleanValue(true);
            checkVisibleSlabStages(fixture);
        } finally {
            PrinterConfig.STRICT_BLOCK_FACE_CHECK.setBooleanValue(strict);
        }
    }

    private static void checkVisibleSlabStages(Fixture fixture) throws Exception {
        FixtureWorld schematic = fixture.newWorld();
        BlockState desired = Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.DOUBLE);
        // Existing slabs are solid targets, so strict face checking still
        // applies. These four positions expose a real horizontal face for
        // merging either half without requiring a hidden solid-face click.
        for (BlockPos target : new BlockPos[]{new BlockPos(0, 65, 3), new BlockPos(0, 65, -3),
                new BlockPos(3, 65, 0), new BlockPos(-3, 65, 0)}) {
            for (Motion motion : Motion.values()) {
                fixture.configure(Blocks.STONE_SLAB, -37.0F, 78.0F, motion);
                schematic.blocks.clear();
                schematic.blocks.put(target, desired);
                BlockState firstStage = NativePlacementSolver.placementTarget(fixture.world, schematic, target, desired);
                check(firstStage.get(SlabBlock.TYPE) == SlabType.BOTTOM,
                        "Empty double slab target did not stage a lower half");
                NativePlacementSolver.Solution first = verifyFamilySolution(fixture, target, firstStage, motion);
                fixture.world.blocks.put(target, first.predicted());
                check(NativePlacementSolver.placementTarget(fixture.world, schematic, target, desired).equals(desired),
                        "Existing half slab never advanced to the DOUBLE stage");
                verifyFamilySolution(fixture, target, desired, motion);
                fixture.world.blocks.put(target, desired.with(SlabBlock.TYPE, SlabType.TOP));
                verifyFamilySolution(fixture, target, desired, motion);
            }
        }
    }

    private static void checkTrapdoorsAndLevers(Fixture fixture) throws Exception {
        BlockPos target = new BlockPos(2, 65, 0);
        for (Motion motion : Motion.values()) {
            for (Direction facing : Direction.Type.HORIZONTAL) {
                for (BlockHalf half : BlockHalf.values()) {
                    fixture.configure(Blocks.OAK_TRAPDOOR, 23.0F, -51.0F, motion);
                    verifyFamilySolution(fixture, target, Blocks.OAK_TRAPDOOR.getDefaultState()
                            .with(TrapdoorBlock.FACING, facing).with(TrapdoorBlock.HALF, half), motion);
                    BlockState open = Blocks.OAK_TRAPDOOR.getDefaultState().with(TrapdoorBlock.FACING, facing)
                            .with(TrapdoorBlock.HALF, half).with(TrapdoorBlock.OPEN, true);
                    NativePlacementSolver.Solution openingStage = motion == Motion.STILL
                            ? NativePlacementSolver.solveStationary(target, open, fixture.player.heldStack, 4.5, true)
                            : NativePlacementSolver.solveMoving(target, open, fixture.player.heldStack, 4.5, true, fixture.player.isSprinting());
                    check(openingStage != null && openingStage.predicted().equals(open.with(TrapdoorBlock.OPEN, false)),
                            "An open trapdoor schematic prevented its initial correctly oriented placement");
                }
                for (BlockFace attachment : BlockFace.values()) {
                    fixture.configure(Blocks.LEVER, 23.0F, -51.0F, motion);
                    Direction support = switch (attachment) {
                        case FLOOR -> Direction.DOWN;
                        case CEILING -> Direction.UP;
                        case WALL -> facing.getOpposite();
                    };
                    fixture.world.blocks.put(target.offset(support), Blocks.STONE.getDefaultState());
                    verifyFamilySolution(fixture, target, Blocks.LEVER.getDefaultState()
                            .with(Properties.HORIZONTAL_FACING, facing).with(Properties.BLOCK_FACE, attachment), motion);
                }
                fixture.configure(Blocks.COMPARATOR, 23.0F, -51.0F, motion);
                fixture.world.blocks.put(target.down(), Blocks.STONE.getDefaultState());
                BlockState subtract = Blocks.COMPARATOR.getDefaultState()
                        .with(Properties.HORIZONTAL_FACING, facing).with(Properties.COMPARATOR_MODE, ComparatorMode.SUBTRACT);
                NativePlacementSolver.Solution comparator = motion == Motion.STILL
                        ? NativePlacementSolver.solveStationary(target, subtract, fixture.player.heldStack, 4.5, true)
                        : NativePlacementSolver.solveMoving(target, subtract, fixture.player.heldStack, 4.5, true, fixture.player.isSprinting());
                check(comparator != null && comparator.predicted().equals(subtract.with(Properties.COMPARATOR_MODE, ComparatorMode.COMPARE)),
                        "A subtract comparator schematic prevented its initial correctly oriented placement");
            }
        }
    }

    private static void checkHiddenSlabMerges(Fixture fixture) throws Exception {
        boolean strict = PrinterConfig.STRICT_BLOCK_FACE_CHECK.getBooleanValue();
        try {
            PrinterConfig.STRICT_BLOCK_FACE_CHECK.setBooleanValue(false);
            BlockState desired = Blocks.STONE_SLAB.getDefaultState().with(SlabBlock.TYPE, SlabType.DOUBLE);
            for (BlockPos target : familyTargets()) {
                for (Motion motion : Motion.values()) {
                    for (SlabType existing : new SlabType[]{SlabType.TOP, SlabType.BOTTOM}) {
                        fixture.configure(Blocks.STONE_SLAB, -37.0F, 78.0F, motion);
                        fixture.world.blocks.put(target, desired.with(SlabBlock.TYPE, existing));
                        verifyFamilySolution(fixture, target, desired, motion);
                    }
                }
            }
        } finally {
            PrinterConfig.STRICT_BLOCK_FACE_CHECK.setBooleanValue(strict);
        }
    }

    private static void checkStandingSigns(Fixture fixture) throws Exception {
        BlockPos target = new BlockPos(2, 65, 0);
        for (int rotation = 0; rotation < 16; rotation++) {
            fixture.configure(Blocks.OAK_SIGN, 23.0F, -51.0F, Motion.STILL);
            fixture.world.blocks.put(target.down(), Blocks.STONE.getDefaultState());
            verifyFamilySolution(fixture, target,
                    Blocks.OAK_SIGN.getDefaultState().with(Properties.ROTATION, rotation), Motion.STILL);
        }
        for (Direction facing : Direction.Type.HORIZONTAL) {
            for (Motion motion : Motion.values()) {
                fixture.configure(Blocks.OAK_SIGN, 23.0F, -51.0F, motion);
                fixture.world.blocks.put(target.offset(facing.getOpposite()), Blocks.STONE.getDefaultState());
                verifyFamilySolution(fixture, target, Blocks.OAK_WALL_SIGN.getDefaultState()
                        .with(Properties.HORIZONTAL_FACING, facing), motion);
            }
        }
    }

    private static void checkDoorHinges(Fixture fixture) throws Exception {
        BlockPos target = new BlockPos(2, 65, 0);
        for (Block door : new Block[]{Blocks.OAK_DOOR, Blocks.IRON_DOOR}) {
            for (Direction facing : Direction.Type.HORIZONTAL) {
                for (DoorHinge hinge : DoorHinge.values()) {
                    for (Motion motion : Motion.values()) {
                        fixture.configure(door, 23.0F, -51.0F, motion);
                        fixture.world.blocks.put(target.down(), Blocks.STONE.getDefaultState());
                        BlockState desired = door.getDefaultState().with(DoorBlock.FACING, facing).with(DoorBlock.HINGE, hinge);
                        NativePlacementSolver.Solution solution = verifyFamilySolution(fixture, target, desired, motion);
                        fixture.world.blocks.put(target.up(), Blocks.STONE.getDefaultState());
                        check(NativePlacementSolver.revalidate(target, desired, fixture.player.heldStack, solution, 4.5) == null,
                                "Door placement ignored an occupied upper half");
                    }
                }
            }
        }
    }

    private static NativePlacementSolver.Solution verifyFamilySolution(Fixture fixture, BlockPos target,
                                                                       BlockState desired, Motion motion) throws Exception {
        FixturePlayer player = fixture.player;
        float yaw = player.getYaw();
        float pitch = player.getPitch();
        PlayerInput input = player.input.playerInput;
        Vec2f movement = player.input.getMovementInput();
        NativePlacementSolver.Solution solution = motion == Motion.STILL
                ? NativePlacementSolver.solveStationary(target, desired, player.heldStack, 4.5, true)
                : NativePlacementSolver.solveMoving(target, desired, player.heldStack, 4.5, true, player.isSprinting());
        String context = desired + " target=" + target + " motion=" + motion;
        check(solution != null, "No directional/attachment solution: " + context);
        // Use exact equality here, not the solver's matcher; otherwise an
        // over-broad ignored-property list could make the regression pass.
        check(desired.equals(solution.predicted()), "Incorrect real vanilla placement state: " + context
                + " actual=" + solution.predicted());
        check(!solution.hit().isInsideBlock(), "Placement depended on a non-wire inside-block flag: " + context);
        check(player.getEyePos().squaredDistanceTo(solution.hit().getPos()) <= 4.5 * 4.5,
                "Family hit was outside real reach: " + context);
        check(MovementHandler.requestRotation(solution.yaw(), solution.pitch()),
                "Family solver chose an impossible movement rotation: " + context);
        MovementHandler.validateMovementInput(player);
        check(MovementHandler.hasRotation(), "Family rotation failed actual input validation: " + context);
        if (motion != Motion.STILL) {
            PlayerInput declared = MovementHandler.declaredInput(input);
            check(acceleration(input, yaw, MovementHandler.movementSpeedScale())
                            .distanceTo(acceleration(declared, solution.yaw(), 1.0F)) < 0.0003,
                    "Family placement changed movement acceleration: " + context);
        }
        NativePlacementSolver.Solution checked = NativePlacementSolver.revalidate(target, desired, player.heldStack,
                solution, 4.5, MovementHandler.serverYaw(Float.NaN), MovementHandler.serverPitch(Float.NaN));
        check(checked != null && desired.equals(checked.predicted()),
                "Family placement did not survive actual-angle revalidation: " + context);
        check(player.getYaw() == yaw && player.getPitch() == pitch, "Family placement moved the camera: " + context);
        check(player.input.playerInput.equals(input) && player.input.getMovementInput().equals(movement),
                "Family placement changed local input: " + context);
        familySolutions++;
        MovementHandler.clearRotation();
        return solution;
    }

    private static void verifySolution(Fixture fixture, BlockPos target, BlockState desired,
                                       Motion motion) throws Exception {
        FixturePlayer player = fixture.player;
        float originalYaw = player.getYaw();
        float originalPitch = player.getPitch();
        PlayerInput originalInput = player.input.playerInput;
        Vec2f originalMovement = player.input.getMovementInput();
        NativePlacementSolver.Solution solution = motion == Motion.STILL
                ? NativePlacementSolver.solveStationary(target, desired, player.heldStack, 4.5, true)
                : NativePlacementSolver.solveMoving(target, desired, player.heldStack, 4.5, true, player.isSprinting());
        String context = desired + " target=" + target + " camera=" + originalYaw + "," + originalPitch + " motion=" + motion;
        check(solution != null, "Public solver returned no solution: " + context);
        check(solution.predicted().get(Properties.FACING) == desired.get(Properties.FACING),
                "Solver returned the wrong facing: " + context);
        check(solution.hit().getBlockPos().equals(target) && solution.airPlace(),
                "Empty-world solution did not use target-position airplace: " + context);
        check(!solution.hit().isInsideBlock(), "Solver and wire disagree on inside-block flag: " + context);
        check(player.getEyePos().squaredDistanceTo(solution.hit().getPos()) <= 4.5 * 4.5,
                "Solver returned an out-of-range hit: " + context);
        check(!solution.anyRotation(), "Directional block incorrectly marked independent of rotation: " + context);
        check(MovementHandler.requestRotation(solution.yaw(), solution.pitch()),
                "Solver chose a rotation the movement handler cannot request: " + context);
        MovementHandler.validateMovementInput(player);
        check(MovementHandler.hasRotation(), "Movement validation discarded the chosen solution: " + context);
        check(sameYaw(MovementHandler.serverYaw(Float.NaN), solution.yaw())
                        && close(MovementHandler.serverPitch(Float.NaN), solution.pitch()),
                "Validated wire rotation differs from solver result: " + context);
        PlayerInput declared = MovementHandler.declaredInput(originalInput);
        if (motion != Motion.STILL) {
            check(declared.forward() || declared.backward() || declared.left() || declared.right(),
                    "Rotation cancelled movement input: " + context);
            // Use Minecraft's real input clamp and acceleration conversion on
            // the selected solution, not a second copy of printer math.
            Vec3d localAcceleration = acceleration(originalInput, originalYaw, MovementHandler.movementSpeedScale());
            Vec3d wireAcceleration = acceleration(declared, solution.yaw(), 1.0F);
            check(localAcceleration.distanceTo(wireAcceleration) < 0.0003,
                    "Selected rotation changes acceleration direction or speed: " + context);
            check(declared.forward() || !player.isSprinting(),
                    "Side/back server input retained an illegal sprint state: " + context);
        }
        NativePlacementSolver.Solution checked = NativePlacementSolver.revalidate(target, desired,
                player.heldStack, solution, 4.5, MovementHandler.serverYaw(Float.NaN),
                MovementHandler.serverPitch(Float.NaN));
        check(checked != null && checked.predicted().get(Properties.FACING) == desired.get(Properties.FACING),
                "Chosen solution does not survive actual-angle revalidation: " + context);
        check(player.getYaw() == originalYaw && player.getPitch() == originalPitch,
                "Solver or movement rotation changed the first-person camera: " + context);
        check(player.input.playerInput.equals(originalInput), "Printer rewrote local key state: " + context);
        check(player.input.getMovementInput().equals(originalMovement), "Printer rotated the local movement vector: " + context);
        solutions++;
        MovementHandler.clearRotation();
    }

    private static void checkRevalidationAndRejection(Fixture fixture) throws Exception {
        fixture.configure(Blocks.PISTON, 23.0F, -31.0F, Motion.WALK);
        BlockPos target = new BlockPos(2, 65, 0);
        BlockState desired = Blocks.PISTON.getDefaultState().with(Properties.FACING, Direction.WEST);
        NativePlacementSolver.Solution solution = NativePlacementSolver.solveMoving(target, desired,
                fixture.player.heldStack, 4.5, true, false);
        check(solution != null, "Initial moving fixture must be placeable");
        fixture.moveTo(new Vec3d(0.6, 64.0, 0.65));
        check(NativePlacementSolver.revalidate(target, desired, fixture.player.heldStack, solution, 4.5) != null,
                "Small movement needlessly invalidated a reachable hit");
        fixture.moveTo(new Vec3d(3.5, 64, 0.5));
        NativePlacementSolver.Solution oppositeFace = NativePlacementSolver.revalidate(target, desired,
                fixture.player.heldStack, solution, 4.5);
        check(oppositeFace != null && fixture.player.getEyePos().squaredDistanceTo(oppositeFace.hit().getPos()) <= 4.5 * 4.5,
                "Crossing an air target's face plane invalidated a still reachable placement");
        check(oppositeFace.predicted().get(Properties.FACING) == Direction.WEST
                        && sameYaw(oppositeFace.yaw(), solution.yaw()) && oppositeFace.pitch() == solution.pitch(),
                "Refreshing the hit changed block facing or already reserved rotation");
        fixture.moveTo(new Vec3d(-8, 64, 0.5));
        check(NativePlacementSolver.revalidate(target, desired, fixture.player.heldStack, solution, 4.5) == null,
                "Queued placement ignored movement beyond reach");
        fixture.moveTo(new Vec3d(0.5, 64, 0.5));
        fixture.world.blocks.put(target, Blocks.STONE.getDefaultState());
        check(NativePlacementSolver.revalidate(target, desired, fixture.player.heldStack, solution, 4.5) == null,
                "Queued placement ignored a target becoming occupied");
        fixture.world.blocks.clear();
        check(NativePlacementSolver.solveStationary(new BlockPos(0, 320, 0), desired,
                fixture.player.heldStack, 4.5, true) == null, "Solver ignored build height");
        check(NativePlacementSolver.solveStationary(new BlockPos(8, 65, 0), desired,
                fixture.player.heldStack, 4.5, true) == null, "Solver ignored reach");
        check(NativePlacementSolver.solveStationary(new BlockPos(0, 64, 0), desired,
                fixture.player.heldStack, 4.5, true) == null, "Solver ignored player collision");
        // An intervening wall must not accidentally turn camera raycasting on.
        fixture.world.blocks.put(new BlockPos(1, 65, 0), Blocks.STONE.getDefaultState());
        check(NativePlacementSolver.solveStationary(new BlockPos(3, 65, 0), desired,
                fixture.player.heldStack, 4.5, true) != null, "Raycast-disabled airplace was blocked by a wall");
    }

    private enum Motion { STILL, WALK, DIAGONAL, SPRINT }

    private static void checkAirborneAndJumpRotation(Fixture fixture) throws Exception {
        BlockPos target = new BlockPos(2, 65, 0);
        for (Motion motion : new Motion[]{Motion.WALK, Motion.SPRINT}) {
            for (Direction facing : Direction.values()) {
                fixture.configure(Blocks.PISTON, 23.0F, -31.0F, motion);
                fixture.player.onGround = false;
                verifySolution(fixture, target,
                        Blocks.PISTON.getDefaultState().with(Properties.FACING, facing), motion);
            }
        }

        fixture.configure(Blocks.PISTON, 0.0F, 13.0F, Motion.SPRINT);
        ((FixtureInput) fixture.player.input).set(new PlayerInput(true, false, false, false,
                true, false, true));
        check(!MovementHandler.canRequestRotation(45.0F, 0.0F),
                "Preflight accepted a yaw-dependent sprint takeoff with compensated forward input");
        check(!MovementHandler.requestRotation(45.0F, 0.0F),
                "Rotation reservation accepted an invalid sprint takeoff");
        check(fixture.player.isSprinting() && fixture.player.getYaw() == 0.0F,
                "Rejected sprint-jump preflight mutated sprint or camera");
        check(MovementHandler.canRequestRotation(180.0F, 0.0F),
                "Side/back compensation should permit a non-sprint takeoff");
        check(fixture.player.isSprinting(), "Read-only side/back preflight stopped sprint prematurely");
        check(MovementHandler.requestRotation(180.0F, 0.0F) && fixture.player.isSprinting(),
                "Rotation reservation changed sprint before the actual input sample");
        MovementHandler.validateMovementInput(fixture.player);
        check(MovementHandler.hasRotation() && !fixture.player.isSprinting(),
                "Validated side/back takeoff did not stop sprint before physics");
        PlayerInput declared = MovementHandler.declaredInput(fixture.player.input.playerInput);
        check(declared.backward() && !declared.forward() && declared.jump(),
                "Side/back takeoff did not preserve the jump while compensating direction");
        check(fixture.player.getYaw() == 0.0F && fixture.player.getPitch() == 13.0F,
                "Takeoff compensation changed the first-person camera");
        MovementHandler.clearRotation();
        fixture.player.onGround = false;
        fixture.player.sprinting = true;
        check(MovementHandler.canRequestRotation(45.0F, 0.0F),
                "Airborne sprint was incorrectly treated as a fresh sprint takeoff");
        check(MovementHandler.requestRotation(45.0F, 0.0F), "Airborne sprint reservation was rejected");
        MovementHandler.validateMovementInput(fixture.player);
        check(MovementHandler.hasRotation() && fixture.player.isSprinting(),
                "Airborne forward-input compensation unnecessarily stopped sprint");
        MovementHandler.clearRotation();

        fixture.configure(Blocks.PISTON, 0.0F, 13.0F, Motion.STILL);
        check(MovementHandler.requestRotation(90.0F, 0.0F), "Idle rotation reservation failed");
        set(Input.class, fixture.player.input, "movementVector", new Vec2f(1.0F, 0.0F));
        MovementHandler.validateMovementInput(fixture.player);
        check(!MovementHandler.hasRotation() && fixture.player.getYaw() == 0.0F,
                "A module-created movement vector with no declared keys bypassed rotation validation");
    }

    private static void checkLegacyCameraAndPacketCache(Fixture fixture) throws Exception {
        boolean moving = PrinterConfig.PRINTER_NATIVE_MOVING.getBooleanValue();
        boolean omni = PrinterConfig.PRINTER_NATIVE_OMNI.getBooleanValue();
        boolean rotatePlayer = PrinterConfig.ROTATE_PLAYER.getBooleanValue();
        boolean grimRotate = PrinterConfig.PRINTER_GRIM_ROTATION.getBooleanValue();
        Printer previousPrinter = LitematicaMixinMod.printer;
        try {
            PrinterConfig.PRINTER_NATIVE_MOVING.setBooleanValue(true);
            PrinterConfig.PRINTER_NATIVE_OMNI.setBooleanValue(true);
            PrinterConfig.ROTATE_PLAYER.setBooleanValue(true);
            PrinterConfig.PRINTER_GRIM_ROTATION.setBooleanValue(true);
            fixture.configure(Blocks.PISTON, 23.0F, -31.0F, Motion.STILL);
            Printer printer = (Printer) fixture.unsafe.allocateInstance(Printer.class);
            set(Printer.class, printer, "player", fixture.player);
            set(Printer.class, printer, "actionHandler", new ActionHandler(fixture.client, fixture.player));
            LitematicaMixinMod.printer = printer;
            FixtureNetwork network = (FixtureNetwork) fixture.player.networkHandler;
            network.packets.clear();
            MixinAccessorClientPlayerEntity cache = (MixinAccessorClientPlayerEntity) (Object) fixture.player;
            printer.rotate(-100.0F, 45.0F);
            check(fixture.player.getYaw() == 23.0F && fixture.player.getPitch() == -31.0F,
                    "Native Printer.rotate obeyed stale ROTATE_PLAYER and moved the camera");
            check(network.packets.size() == 1 && network.packets.getFirst() instanceof PlayerMoveC2SPacket,
                    "Native legacy rotate did not emit exactly one movement rotation");
            PlayerMoveC2SPacket rotatePacket = (PlayerMoveC2SPacket) network.packets.getFirst();
            check(sameYaw(rotatePacket.getYaw(Float.NaN), -100.0F) && close(rotatePacket.getPitch(Float.NaN), 45.0F),
                    "Native Printer.rotate emitted the wrong server angle");
            check(sameYaw(cache.getLastYaw(), rotatePacket.getYaw(Float.NaN))
                            && close(cache.getLastPitch(), rotatePacket.getPitch(Float.NaN)),
                    "Native Printer.rotate did not update the actual last-sent angle cache");

            BlockPos target = new BlockPos(2, 65, 0);
            PrinterPlacementContext context = new PrinterPlacementContext(fixture.player,
                    new BlockHitResult(Vec3d.ofCenter(target), Direction.WEST, target, false), fixture.player.heldStack, 0);
            context.canStealth = true;
            PostAction post = new PostAction(context, fixture.player);
            // The user moved the mouse since this action was queued. Native
            // restoration must follow that current view, not the captured view.
            fixture.player.setYaw(28.0F);
            fixture.player.setPitch(10.0F);
            check(post.send(fixture.client, fixture.player), "PostAction did not complete");
            check(fixture.player.getYaw() == 28.0F && fixture.player.getPitch() == 10.0F,
                    "Native PostAction restored a stale camera snapshot");
            check(network.packets.size() == 2 && network.packets.getLast() instanceof PlayerMoveC2SPacket,
                    "Native PostAction did not emit exactly one restoring look");
            PlayerMoveC2SPacket postPacket = (PlayerMoveC2SPacket) network.packets.getLast();
            check(sameYaw(postPacket.getYaw(Float.NaN), 28.0F) && close(postPacket.getPitch(Float.NaN), 10.0F),
                    "Native PostAction sent the captured angle instead of the current camera angle");
            check(sameYaw(cache.getLastYaw(), postPacket.getYaw(Float.NaN))
                            && close(cache.getLastPitch(), postPacket.getPitch(Float.NaN)),
                    "Native PostAction did not update the actual last-sent angle cache");

            PrepareLook prepare = new PrepareLook(context);
            int packetCount = network.packets.size();
            check(prepare.send(fixture.client, fixture.player), "Stealth PrepareLook did not complete");
            check(network.packets.size() == packetCount + 1
                            && network.packets.getLast() instanceof PlayerMoveC2SPacket.Full,
                    "Native stealth PrepareLook with ROTATE_PLAYER and GRIM duplicated its rotation packet");
            check(fixture.player.getYaw() == 28.0F && fixture.player.getPitch() == 10.0F,
                    "Native stealth PrepareLook changed the first-person camera");
            PlayerMoveC2SPacket preparePacket = (PlayerMoveC2SPacket) network.packets.getLast();
            check(sameYaw(preparePacket.getYaw(Float.NaN), prepare.yaw.orElse(Float.NaN))
                            && close(preparePacket.getPitch(Float.NaN), prepare.pitch.orElse(Float.NaN)),
                    "Native stealth PrepareLook sent a different rotation than it prepared");
            check(sameYaw(cache.getLastYaw(), preparePacket.getYaw(Float.NaN))
                            && close(cache.getLastPitch(), preparePacket.getPitch(Float.NaN)),
                    "Native stealth PrepareLook did not update the actual sent angle cache");
        } finally {
            LitematicaMixinMod.printer = previousPrinter;
            PrinterConfig.PRINTER_NATIVE_MOVING.setBooleanValue(moving);
            PrinterConfig.PRINTER_NATIVE_OMNI.setBooleanValue(omni);
            PrinterConfig.ROTATE_PLAYER.setBooleanValue(rotatePlayer);
            PrinterConfig.PRINTER_GRIM_ROTATION.setBooleanValue(grimRotate);
        }
    }

    private static void checkNativeCameraIsolation(Fixture fixture) throws Exception {
        boolean moving = PrinterConfig.PRINTER_NATIVE_MOVING.getBooleanValue();
        boolean omni = PrinterConfig.PRINTER_NATIVE_OMNI.getBooleanValue();
        boolean legacyFreeLook = PrinterConfig.FREE_LOOK.getBooleanValue();
        FreeLook freeLook = FreeLook.getInstance();
        float savedYaw = freeLook.cameraYaw;
        float savedPitch = freeLook.cameraPitch;
        int savedTicks = freeLook.ticksSinceLastRotation;
        boolean savedEnabled = freeLook.enabled;
        try {
            for (boolean[] nativeModes : new boolean[][]{{true, true}, {true, false}, {false, true}}) {
                PrinterConfig.PRINTER_NATIVE_MOVING.setBooleanValue(nativeModes[0]);
                PrinterConfig.PRINTER_NATIVE_OMNI.setBooleanValue(nativeModes[1]);
                PrinterConfig.FREE_LOOK.setBooleanValue(true);
                fixture.configure(Blocks.PISTON, 23.0F, -31.0F, Motion.WALK);
                PlayerInput originalInput = fixture.player.input.playerInput;
                Vec2f originalMovement = fixture.player.input.getMovementInput();
                // Seed an already active legacy session and a stale look-back
                // angle to cover old configuration files and mode transitions.
                freeLook.enabled = true;
                freeLook.cameraYaw = -130.0F;
                freeLook.cameraPitch = 72.0F;
                freeLook.ticksSinceLastRotation = 1000;
                check(!freeLook.isEnabled() && !freeLook.shouldRotate(),
                        "Legacy FreeLook still intercepts native mouse/camera controls");
                freeLook.onEnable();
                check(!freeLook.isEnabled(), "FreeLook onEnable reactivated in native mode");
                freeLook.enabled = true;
                freeLook.onGameTick();
                new MovementHandler().onGameTick();
                check(fixture.player.getYaw() == 23.0F && fixture.player.getPitch() == -31.0F,
                        "Stale FreeLook angle changed native first-person view");
                check(fixture.player.input.playerInput.equals(originalInput)
                                && fixture.player.input.getMovementInput().equals(originalMovement),
                        "Legacy FreeLook movement changed native controls");
                check(!freeLook.isEnabled() && !freeLook.shouldRotate(),
                        "Native tick did not release old FreeLook interception");
            }
        } finally {
            PrinterConfig.PRINTER_NATIVE_MOVING.setBooleanValue(moving);
            PrinterConfig.PRINTER_NATIVE_OMNI.setBooleanValue(omni);
            PrinterConfig.FREE_LOOK.setBooleanValue(legacyFreeLook);
            freeLook.cameraYaw = savedYaw;
            freeLook.cameraPitch = savedPitch;
            freeLook.ticksSinceLastRotation = savedTicks;
            freeLook.enabled = savedEnabled;
        }
    }

    private static Vec3d acceleration(PlayerInput input, float yaw, float speed) throws Exception {
        Vec2f keys = new Vec2f((input.left() ? 1 : 0) - (input.right() ? 1 : 0),
                (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0)).normalize().multiply(0.98F);
        Vec2f transformed = (Vec2f) speedFactors.invoke(null, keys);
        return (Vec3d) toVelocity.invoke(null, new Vec3d(transformed.x, 0, transformed.y), speed, yaw);
    }

    private static final class Fixture implements AutoCloseable {
        private final MinecraftClient client;
        private final FixtureWorld world;
        private final FixturePlayer player;
        private final Unsafe unsafe;

        private Fixture() throws Exception {
            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            unsafe = (Unsafe) unsafeField.get(null);
            client = (MinecraftClient) unsafe.allocateInstance(MinecraftClient.class);
            // Real Litematica's BlockItem mixin initializes its defaults via
            // MaLiLib FileUtils; give it the existing Gradle task directory.
            set(MinecraftClient.class, client, "runDirectory", new File(System.getProperty("user.dir")).getAbsoluteFile());
            world = (FixtureWorld) unsafe.allocateInstance(FixtureWorld.class);
            player = (FixturePlayer) unsafe.allocateInstance(FixturePlayer.class);
            world.blocks = new HashMap<>();
            world.border = new WorldBorder();
            world.player = player;
            player.abilities = new PlayerAbilities();
            player.input = new FixtureInput();
            FixtureNetwork network = (FixtureNetwork) unsafe.allocateInstance(FixtureNetwork.class);
            network.packets = new ArrayList<>();
            set(ClientPlayerEntity.class, player, "networkHandler", network);
            set(Entity.class, player, "world", world);
            set(Entity.class, player, "standingEyeHeight", 1.62F);
            client.world = world;
            client.player = player;
            set(MinecraftClient.class, client, "cameraEntity", player);
            set(MinecraftClient.class, null, "instance", client);
            moveTo(new Vec3d(0.5, 64, 0.5));
        }

        private void configure(Block block, float yaw, float pitch, Motion motion) throws Exception {
            MovementHandler.clearRotation();
            world.blocks.clear();
            moveTo(new Vec3d(0.5, 64, 0.5));
            player.setYaw(yaw);
            player.setPitch(pitch);
            player.heldStack = new ItemStack(block.asItem());
            player.sprinting = motion == Motion.SPRINT;
            player.onGround = true;
            player.sneaking = false;
            ((FixtureInput) player.input).set(new PlayerInput(motion != Motion.STILL, false,
                    motion == Motion.DIAGONAL, false, false, false, motion == Motion.SPRINT));
        }

        private FixtureWorld newWorld() throws Exception {
            FixtureWorld result = (FixtureWorld) unsafe.allocateInstance(FixtureWorld.class);
            result.blocks = new HashMap<>();
            result.border = new WorldBorder();
            result.player = player;
            return result;
        }

        private void moveTo(Vec3d pos) throws Exception {
            set(Entity.class, player, "pos", pos);
            set(Entity.class, player, "boundingBox", new Box(pos.x - 0.3, pos.y, pos.z - 0.3,
                    pos.x + 0.3, pos.y + 1.8, pos.z + 0.3));
        }

        @Override public void close() { MovementHandler.clearRotation(); }
    }

    private static final class FixtureInput extends Input {
        private void set(PlayerInput input) {
            playerInput = input;
            movementVector = new Vec2f((input.left() ? 1 : 0) - (input.right() ? 1 : 0),
                    (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0)).normalize();
        }
    }

    private static final class FixtureWorld extends ClientWorld {
        private Map<BlockPos, BlockState> blocks;
        private WorldBorder border;
        private FixturePlayer player;

        // Never invoked. Unsafe avoids the renderer/network constructor only.
        private FixtureWorld() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public boolean isReceivingRedstonePower(BlockPos pos) { return false; }
        @Override public int getBottomY() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public FeatureSet getEnabledFeatures() { return FeatureFlags.VANILLA_FEATURES; }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public boolean isInBuildLimit(BlockPos pos) { return pos.getY() >= -64 && pos.getY() < 320; }
        @Override public boolean doesNotIntersectEntities(Entity except, VoxelShape shape) {
            if (player == except) return true;
            return shape.getBoundingBoxes().stream().noneMatch(player.getBoundingBox()::intersects);
        }
    }

    private static final class FixturePlayer extends ClientPlayerEntity {
        private ItemStack heldStack;
        private PlayerAbilities abilities;
        private boolean sprinting;
        private boolean onGround;
        private boolean sneaking;

        // Never invoked. The tests retain Entity's real rotation and position accessors.
        private FixturePlayer() { super(null, null, null, null, null, PlayerInput.DEFAULT, false); }
        @Override public double getBlockInteractionRange() { return 4.5; }
        @Override public ItemStack getMainHandStack() { return heldStack; }
        @Override public boolean isDescending() { return sneaking; }
        @Override public boolean isSneaking() { return sneaking; }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isSleeping() { return false; }
        @Override public boolean hasVehicle() { return false; }
        @Override public boolean isGliding() { return false; }
        @Override public boolean isSwimming() { return false; }
        @Override public boolean isTouchingWater() { return false; }
        @Override public boolean isInLava() { return false; }
        @Override public boolean isClimbing() { return false; }
        @Override public PlayerAbilities getAbilities() { return abilities; }
        @Override public boolean isUsingItem() { return false; }
        @Override public boolean shouldSlowDown() { return false; }
        @Override public boolean isOnGround() { return onGround; }
        @Override public boolean isJumping() { return false; }
        @Override public boolean isSprinting() { return sprinting; }
        @Override public void setSprinting(boolean value) { sprinting = value; }
    }

    private static final class FixtureNetwork extends ClientPlayNetworkHandler {
        private List<Packet<?>> packets;
        private FixtureNetwork() { super(null, null, null); }
        @Override public void sendPacket(Packet<?> packet) { packets.add(packet); }
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static boolean close(float left, float right) { return Math.abs(left - right) < 0.001F; }

    private static boolean sameYaw(float left, float right) {
        return Float.isFinite(left) && Float.isFinite(right)
                && Math.abs(MathHelper.wrapDegrees(left - right)) < 0.001F;
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
