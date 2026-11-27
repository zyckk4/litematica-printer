package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.actions.NativePlacementAction;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.WallMountedBlock;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

final class AdjacentDecorationChecks {
    private static final BlockPos START = new BlockPos(-1, 65, 2);
    private static final BlockState WALL_SIGN = Blocks.OAK_WALL_SIGN.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.NORTH);

    static void run() throws Exception {
        try (var f = new StateInteractionTestFixture()) {
            f.configure(PrinterConfig.PRINTER_GRIM_ROTATION, true);
            f.configure(PrinterConfig.PRINTER_IGNORE_ROTATION, false);
            row(f, WALL_SIGN, Direction.NORTH);
            row(f, Blocks.WALL_TORCH.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.NORTH), Direction.NORTH);
            row(f, Blocks.TORCH.getDefaultState(), Direction.UP);
            row(f, Blocks.STONE_BUTTON.getDefaultState().with(WallMountedBlock.FACE, BlockFace.WALL)
                    .with(Properties.HORIZONTAL_FACING, Direction.NORTH), Direction.NORTH);
            checkFallbacks(f);
        }
    }

    private static void row(StateInteractionTestFixture f, BlockState desired, Direction face) throws Exception {
        f.reset();
        f.setVelocity(Vec3d.ZERO);
        var stack = new ItemStack(desired.getBlock(), 12);
        f.fill(stack);
        for (int i = 0; i < 3; i++) {
            f.world.blocks.put(START.east(i).offset(face.getOpposite()), Blocks.STONE.getDefaultState());
        }
        // No server updates between sends: the middle cell has an outstanding neighbor.
        for (int i = 0; i < 3; i++) {
            var pos = START.east(i);
            var solution = NativePlacementSolver.solveStationary(pos, desired, stack, 4.5, true);
            if (solution == null || !new NativePlacementAction(pos, desired, stack, solution, true).send(f.client, f.player)) {
                throw new AssertionError("Fixed-support row stalled at " + pos + " for " + desired);
            }
        }
        // Replay the actual hits in wire order, with the server seeing the earlier cells.
        var item = (BlockItem) stack.getItem();
        for (int i = 0; i < 3; i++) {
            var pos = START.east(i);
            var hit = f.network.blockPackets.get(i).getBlockHitResult();
            for (int yaw = -180; yaw < 180; yaw += 45) {
                f.player.setHeadYaw(yaw);
                var context = item.getPlacementContext(new ItemPlacementContext(f.player, Hand.MAIN_HAND, stack, hit));
                if (context == null || !pos.equals(context.getBlockPos()) || !desired.equals(item.getPlacementState(context))) {
                    throw new AssertionError("Adjacent block changed the wire result for " + desired);
                }
            }
            f.world.blocks.put(pos, desired);
        }
    }

    private static void checkFallbacks(StateInteractionTestFixture f) throws Exception {
        f.reset();
        var next = START.east();
        var stack = new ItemStack(Blocks.OAK_SIGN, 12);
        f.world.blocks.put(START.south(), Blocks.STONE.getDefaultState());
        f.world.blocks.put(next.south(), Blocks.STONE.getDefaultState());
        var hit = new BlockHitResult(Vec3d.ofCenter(next.south()).add(0, 0, -0.5), Direction.NORTH, next.south(), false);
        var firstHit = new BlockHitResult(Vec3d.ofCenter(START.south()).add(0, 0, -0.5), Direction.NORTH, START.south(), false);
        Printer.trackPlacement(START, WALL_SIGN, stack, 19, firstHit);
        if (!Printer.hasUnsettledAttachmentNeighbor(next, WALL_SIGN, firstHit, stack)
                || !Printer.hasUnsettledAttachmentNeighbor(next, WALL_SIGN.with(Properties.WATERLOGGED, true), hit, stack)) {
            throw new AssertionError("Used an unrelated face or a waterlogged decoration as a stable mount");
        }
        var marked = stack.copy();
        marked.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Modified stack"));
        if (!Printer.hasUnsettledAttachmentNeighbor(next, WALL_SIGN, hit, marked)) throw new AssertionError("Nondefault item components accepted");
        for (var anchor : new BlockState[]{Blocks.AIR.getDefaultState(), Blocks.STONE_SLAB.getDefaultState(), Blocks.CRAFTING_TABLE.getDefaultState()}) {
            f.world.blocks.put(next.south(), anchor);
            if (!Printer.hasUnsettledAttachmentNeighbor(next, WALL_SIGN, hit, stack)) throw new AssertionError("Unstable anchor " + anchor);
        }
        f.world.blocks.put(next.south(), Blocks.STONE.getDefaultState());
        f.configure(PrinterConfig.RAYCAST, true);
        if (!Printer.hasUnsettledAttachmentNeighbor(next, WALL_SIGN, hit, stack)) throw new AssertionError("Unpublished outline ignored by raycast");
        f.configure(PrinterConfig.RAYCAST, false);
        Printer.confirmNativePlacement(START, Blocks.AIR.getDefaultState());
        if (!Printer.hasUnsettledAttachmentNeighbor(next, WALL_SIGN, hit, stack)) throw new AssertionError("Stale publication released an unknown click");
        Printer.acknowledgeBlockActions(19);
        Printer.trackPlacement(START, WALL_SIGN, stack, 20, firstHit);
        Printer.trackPlacement(next, WALL_SIGN, stack, 21, hit);
        Printer.confirmNativePlacement(next, Blocks.AIR.getDefaultState());
        if (!Printer.hasUnsettledAttachmentNeighbor(next, WALL_SIGN, hit, stack)) throw new AssertionError("Stale air at the target released a retry before ACK");
        Printer.acknowledgeBlockActions(21);
        if (Printer.hasUnsettledAttachmentNeighbor(next, WALL_SIGN, hit, stack)) throw new AssertionError("Fixed neighbor still blocked the acknowledged retry");
    }
}
