package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.actions.NativePlacementAction;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/** A batch reaches the server in order, while every solve/revalidation can still see the old world. */
final class PendingMountChecks {
    private static final BlockPos TARGET = new BlockPos(0, 65, 2);
    private static final BlockState WALL = Blocks.OAK_WALL_SIGN.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.EAST);
    private static final BlockState STANDING = Blocks.OAK_SIGN.getDefaultState().with(Properties.ROTATION, 8);

    static void run() throws Exception {
        try (var f = new StateInteractionTestFixture()) {
            f.configure(PrinterConfig.PRINTER_GRIM_ROTATION, true);
            f.configure(PrinterConfig.PRINTER_IGNORE_ROTATION, false);
            for (var anchor : new BlockState[]{Blocks.STONE.getDefaultState(), Blocks.OAK_LOG.getDefaultState(),
                    Blocks.BRICKS.getDefaultState()}) {
                existingSupport(f, anchor, false);
                existingSupport(f, anchor, true);
            }
            delayedUpdates(f);
            companion(f, false);
            companion(f, true);
            pendingRayObstruction(f);
        }
    }

    private static void existingSupport(StateInteractionTestFixture f, BlockState anchor, boolean floor) throws Exception {
        f.reset();
        f.setVelocity(Vec3d.ZERO);
        Direction face = floor ? Direction.UP : Direction.EAST;
        BlockPos support = TARGET.offset(face.getOpposite());
        BlockState desired = floor ? STANDING : WALL;
        f.world.blocks.put(support, anchor);
        var signs = new ItemStack(Items.OAK_SIGN, 8);
        var stones = new ItemStack(Items.STONE, 8);
        f.player.inventory.main.set(2, stones);
        f.player.inventory.main.set(3, signs);
        var first = NativePlacementSolver.solveMovingAtRotation(TARGET.south(), Blocks.STONE.getDefaultState(), stones, 4.5, true, false, 0, 0);
        var next = NativePlacementSolver.solveMovingAtRotation(TARGET, desired, signs, 4.5, true, false, 0, 0);
        require(first != null && next != null, "Missing native batch");
        MovementHandler.onMovementSent(f.player);
        require(new NativePlacementAction(TARGET.south(), first.predicted(), stones, first, true).send(f.client, f.player),
                "Neighbor did not send");
        require(new NativePlacementAction(TARGET, desired, signs, next, true).send(f.client, f.player),
                "Existing support incurred a server round trip on " + anchor);
        var wire = f.network.blockPackets.getLast().getBlockHitResult();
        require(f.network.blockPackets.size() == 2 && support.equals(wire.getBlockPos()) && wire.getSide() == face,
                "Batch failed to click the intended real support on " + anchor);
    }

    private static void delayedUpdates(StateInteractionTestFixture f) throws Exception {
        f.reset();
        var stone = Blocks.STONE.getDefaultState();
        var stones = new ItemStack(Items.STONE, 8);
        var signs = new ItemStack(Items.OAK_SIGN, 8);
        var airHit = hit(TARGET, Direction.DOWN);
        var realHit = hit(TARGET.west(), Direction.EAST);
        f.world.blocks.put(TARGET.west(), stone);
        Printer.trackPlacement(TARGET.south(), stone, stones, 4);
        require(Printer.dependsOnPendingBlock(TARGET, WALL, signs, airHit), "Competing support released an ambiguous air hit");
        require(!Printer.dependsOnPendingBlock(TARGET, WALL, signs, realHit), "Unrelated support delayed a fixed mount");
        Printer.confirmNativePlacement(TARGET.south(), Blocks.AIR.getDefaultState());
        Printer.acknowledgeBlockActions(3);
        require(Printer.dependsOnPendingBlock(TARGET, WALL, signs, airHit), "Stale world update and older ACK released a competing support");
        Printer.acknowledgeBlockActions(4);
        require(Printer.dependsOnPendingBlock(TARGET, WALL, signs, airHit), "Rejected/stale pre-ACK world update published the new stone");
        Printer.confirmNativePlacement(TARGET.south(), stone);
        require(!Printer.dependsOnPendingBlock(TARGET, WALL, signs, airHit), "Acknowledged and published support stayed pending");
        Printer.trackPlacement(TARGET.west(), stone, stones, 7);
        require(Printer.dependsOnPendingBlock(TARGET, WALL, signs, realHit), "Used an unpublished actual anchor");
        for (int tick = 0; tick < 16; tick++) f.printer.onGameTick();
        require(Printer.dependsOnPendingBlock(TARGET, WALL, signs, realHit), "Retry timer was mistaken for server processing");
        Printer.acknowledgeBlockActions(7);
        require(!Printer.dependsOnPendingBlock(TARGET, WALL, signs, realHit), "Processed expired anchor kept blocking retry");
        Printer.trackPlacement(TARGET.up(), stone, stones, 8);
        require(!Printer.dependsOnPendingBlock(TARGET, WALL, signs, airHit), "Overhead block delayed a floor-or-wall sign");
    }

    private static void companion(StateInteractionTestFixture f, boolean bed) throws Exception {
        f.reset();
        var signs = new ItemStack(Items.OAK_SIGN, 8);
        var part = TARGET.north();
        var first = bed ? part.west() : part.down();
        var base = bed ? Blocks.RED_BED.getDefaultState().with(BedBlock.FACING, Direction.EAST) : Blocks.OAK_DOOR.getDefaultState();
        var otherHalf = bed ? base.with(BedBlock.PART, BedPart.HEAD) : base.with(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        var material = new ItemStack(bed ? Items.RED_BED : Items.OAK_DOOR, 8);
        f.fill(material);
        f.world.blocks.put(TARGET.down(), Blocks.STONE.getDefaultState());
        Printer.trackPlacement(first, base, material, 5);
        require(Printer.pendingSlotCount(0, material) == 1, "Second cell spent another item");
        Printer.confirmNativePlacement(first, base);
        Printer.acknowledgeBlockActions(5);
        require(Printer.hasPendingPlacement(part), "Publishing the first cell released its companion");
        require(Printer.dependsOnPendingBlock(TARGET, STANDING, signs, hit(TARGET, Direction.DOWN)), "Companion could redirect the air click");
        require(!Printer.dependsOnPendingBlock(TARGET, STANDING, signs, hit(TARGET.down(), Direction.UP)), "Companion delayed an explicit floor click");
        Printer.confirmNativePlacement(part, otherHalf);
        require(!Printer.hasPendingPlacement(part), "Published companion stayed pending");
    }

    private static void pendingRayObstruction(StateInteractionTestFixture f) throws Exception {
        f.reset();
        BlockPos target = new BlockPos(0, 65, 4);
        BlockPos obstruction = target.north(2);
        BlockState stone = Blocks.STONE.getDefaultState();
        var signs = new ItemStack(Items.OAK_SIGN);
        var stones = new ItemStack(Items.STONE, 3);
        f.world.blocks.put(target.down(), stone);
        var floor = hit(target.down(), Direction.UP);
        Printer.trackPlacement(obstruction, stone, stones, 1);
        require(!Printer.dependsOnPendingBlock(target, STANDING, signs, floor), "Disabled raycast waited on an unrelated block");
        f.configure(PrinterConfig.RAYCAST, true);
        require(Printer.dependsOnPendingBlock(target, STANDING, signs, floor), "Missed a pending block two cells along the ray");
        Printer.acknowledgeBlockActions(1);
        Printer.confirmNativePlacement(obstruction, Blocks.AIR.getDefaultState());
        Printer.trackPlacement(obstruction.east(), stone, stones, 2);
        require(!Printer.dependsOnPendingBlock(target, STANDING, signs, floor), "Visible floor click waited on a block beside the ray");
    }

    private static BlockHitResult hit(BlockPos cell, Direction face) {
        return new BlockHitResult(Vec3d.ofCenter(cell).add(Vec3d.of(face.getVector()).multiply(0.5)), face, cell, false);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
