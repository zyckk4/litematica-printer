package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import net.minecraft.block.*;
import net.minecraft.block.enums.Attachment;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.block.enums.WallShape;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;

final class NativeMountSolverChecks {
    private static final BlockPos POS = StateInteractionTestFixture.TARGET;

    static void run() throws Exception {
        try (var fixture = new StateInteractionTestFixture()) {
            fixture.configure(PrinterConfig.PRINTER_GRIM_ROTATION, true);
            for (boolean ignoreRotation : List.of(false, true)) {
                fixture.configure(PrinterConfig.PRINTER_IGNORE_ROTATION, ignoreRotation);
                checkFacing(ignoreRotation);
                checkExplicitSignSupport(fixture);
                checkVines(fixture);
                checkPaleMoss(fixture);
            }
        }
    }

    private static void checkFacing(boolean ignoreRotation) {
        for (Block wall : List.of(Blocks.OAK_WALL_SIGN, Blocks.OAK_WALL_HANGING_SIGN, Blocks.WHITE_WALL_BANNER,
                Blocks.PLAYER_WALL_HEAD, Blocks.TUBE_CORAL_WALL_FAN, Blocks.COPPER_WALL_TORCH,
                Blocks.LADDER, Blocks.COCOA, Blocks.TRIPWIRE_HOOK)) {
            checkHorizontalFacing(wall.getDefaultState(), false);
        }
        var cluster = Blocks.AMETHYST_CLUSTER.getDefaultState();
        check(!NativePlacementSolver.statesMatch(cluster, cluster.cycle(Properties.FACING)), "Wrong amethyst support");
        for (Block mounted : List.of(Blocks.STONE_BUTTON, Blocks.LEVER, Blocks.GRINDSTONE)) {
            checkHorizontalFacing(mounted.getDefaultState().with(WallMountedBlock.FACE, BlockFace.WALL), false);
            checkHorizontalFacing(mounted.getDefaultState().with(WallMountedBlock.FACE, BlockFace.FLOOR), ignoreRotation);
        }
        checkHorizontalFacing(Blocks.BELL.getDefaultState().with(BellBlock.ATTACHMENT, Attachment.SINGLE_WALL), false);
        checkHorizontalFacing(Blocks.BELL.getDefaultState().with(BellBlock.ATTACHMENT, Attachment.CEILING), ignoreRotation);
        var standingSign = Blocks.OAK_SIGN.getDefaultState();
        check(NativePlacementSolver.statesMatch(standingSign, standingSign.cycle(Properties.ROTATION)) == ignoreRotation,
                "Standing sign lost its rotation setting");
    }

    private static void checkHorizontalFacing(BlockState state, boolean expected) {
        check(NativePlacementSolver.statesMatch(state.with(Properties.HORIZONTAL_FACING, Direction.NORTH),
                state.with(Properties.HORIZONTAL_FACING, Direction.EAST)) == expected, "Wrong facing policy for " + state);
    }

    private static void checkExplicitSignSupport(StateInteractionTestFixture f) {
        for (Block anchor : List.of(Blocks.STONE, Blocks.OAK_LOG)) {
            f.world.blocks.clear();
            for (Direction side : Direction.values()) f.world.blocks.put(POS.offset(side), anchor.getDefaultState());
            var sign = new ItemStack(Blocks.OAK_SIGN);
            f.fill(sign);
            for (Direction side : Direction.Type.HORIZONTAL) {
                var wall = Blocks.OAK_WALL_SIGN.getDefaultState().with(Properties.HORIZONTAL_FACING, side);
                checkHit(wall, NativePlacementSolver.solveStationary(POS, wall, sign, 4.5, true), side);
                checkHit(wall, NativePlacementSolver.solveAnyRotation(POS, wall, sign, 4.5), side);
                checkHit(wall, solve(wall, sign), side);
            }
            var standing = Blocks.OAK_SIGN.getDefaultState().with(Properties.ROTATION, 8);
            checkHit(standing, solve(standing, sign), Direction.UP);
        }
    }

    private static void checkHit(BlockState desired, NativePlacementSolver.Solution solution, Direction face) {
        check(solution != null && !solution.airPlace() && solution.hit().getSide() == face
                        && solution.hit().getBlockPos().equals(POS.offset(face.getOpposite()))
                        && solution.predicted().equals(desired),
                "Expected explicit schematic support for " + desired + " face=" + face + ", got " + solution);
    }

    private static void checkVines(StateInteractionTestFixture f) {
        f.world.blocks.clear();
        var stack = new ItemStack(Blocks.VINE);
        f.fill(stack);
        var north = Blocks.VINE.getDefaultState().with(VineBlock.NORTH, true);
        var northAndWest = north.with(VineBlock.WEST, true);
        f.world.blocks.put(POS.east(), Blocks.STONE.getDefaultState());
        check(solve(north, stack) == null, "Vine attached to the only available, unrequested wall");
        f.world.blocks.put(POS.north(), Blocks.STONE.getDefaultState());
        var first = solve(northAndWest, stack);
        check(first != null && first.predicted().equals(north), "Vine did not start with the available planned face");
        f.world.blocks.put(POS, north);
        f.world.blocks.put(POS.west(), Blocks.STONE.getDefaultState());
        check(NativePlacementSolver.canAddFaces(north, northAndWest), "Vine cannot add its second face");
        var second = solve(northAndWest, stack);
        check(second != null && second.predicted().equals(northAndWest), "Vine did not extend its planned faces");
        check(NativePlacementSolver.revalidate(POS, northAndWest, stack, second, 4.5) != null,
                "Missing vine face was rejected before send");
        f.world.blocks.put(POS, northAndWest);
        check(NativePlacementSolver.revalidate(POS, northAndWest, stack, second, 4.5) == null,
                "Completed vine was extended twice");
        check(!NativePlacementSolver.canAddFaces(north.with(VineBlock.EAST, true), northAndWest),
                "An existing unwanted vine attachment was accepted");
    }

    private static void checkPaleMoss(StateInteractionTestFixture f) {
        f.world.blocks.clear();
        var stack = new ItemStack(Blocks.PALE_MOSS_CARPET);
        f.fill(stack);
        var lower = Blocks.PALE_MOSS_CARPET.getDefaultState().with(PaleMossCarpetBlock.NORTH, WallShape.LOW);
        f.world.blocks.put(POS.down(), lower);
        f.world.blocks.put(POS.north(), Blocks.STONE.getDefaultState());
        var upper = lower.with(PaleMossCarpetBlock.BOTTOM, false);
        check(upper.canPlaceAt(f.world, POS) && solve(upper, stack) == null, "Moss item used a base for an upper frond");
        var correct = solve(lower, stack);
        check(correct != null && correct.predicted().equals(lower), "Legal moss base rejected");
        check(solve(lower.with(PaleMossCarpetBlock.NORTH, WallShape.TALL), stack) != null,
                "A requested upper frond prevented planting its supporting base");
        f.world.blocks.put(POS.east(), Blocks.STONE.getDefaultState());
        check(solve(lower, stack) == null, "Moss accepted an extra east frond");
    }

    private static NativePlacementSolver.Solution solve(BlockState desired, ItemStack stack) {
        return NativePlacementSolver.solveMovingAtRotation(POS, desired, stack, 4.5, true, false, 0, 0);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
