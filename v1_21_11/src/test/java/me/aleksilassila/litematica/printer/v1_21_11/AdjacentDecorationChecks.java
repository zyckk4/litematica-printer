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
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

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
            for (var mount : List.of(WALL_SIGN, Blocks.TORCH.getDefaultState(),
                    Blocks.WALL_TORCH.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.NORTH),
                    Blocks.STONE_BUTTON.getDefaultState().with(WallMountedBlock.FACE, BlockFace.WALL)
                            .with(Properties.HORIZONTAL_FACING, Direction.NORTH))) {
                checkMixedBatch(f, mount, false);
            }
            checkMixedBatch(f, WALL_SIGN, true);
            checkPartialBudget(f, 1, false);
            checkPartialBudget(f, 2, false);
            checkPartialBudget(f, 2, true);
            checkPartialBudget(f, 3, true);
        }
    }

    private static void checkPartialBudget(StateInteractionTestFixture f, int left, boolean rollingOnly) throws Exception {
        f.reset();
        f.setVelocity(Vec3d.ZERO);
        f.configure(PrinterConfig.NATIVE_BURST, 9);
        f.configure(PrinterConfig.INTERACTION_WINDOW_LIMIT, rollingOnly ? 9 : 64);
        var mount = StateInteractionTestFixture.TARGET;
        var foundation = mount.east();
        var other = foundation.north();
        var dependent = (left == 1 ? foundation : other).up();
        f.world.blocks.put(mount.south(), Blocks.STONE.getDefaultState());
        var states = List.of(Blocks.ANDESITE.getDefaultState(), WALL_SIGN, Blocks.GRANITE.getDefaultState());
        var positions = List.of(foundation, mount, other);
        for (int i = 0; i < positions.size(); i++) {
            var stack = new ItemStack(states.get(i).getBlock(), 12);
            f.player.inventory.main.set(2 + i, stack);
            var solved = NativePlacementSolver.solveStationary(positions.get(i), states.get(i), stack, 4.5, true);
            if (solved == null) throw new AssertionError("Missing budget fixture");
            f.printer.addNativePlacement(positions.get(i), states.get(i), stack, solved);
        }
        var torch = Blocks.TORCH.getDefaultState();
        var torches = new ItemStack(Blocks.TORCH, 12);
        f.player.inventory.main.set(5, torches);
        if (NativePlacementSolver.solveStationary(dependent, torch, torches, 4.5, true) != null) {
            throw new AssertionError("Fourth target was placeable without its foundation");
        }
        // Intervening uses occur after planning. In one variant they only occupy the
        // rolling window because the player has advanced to the next tick.
        for (int i = left; i < 9; i++) {
            if (!Printer.tryAcquirePlacementPacket()) throw new AssertionError("Could not consume test budget");
        }
        if (rollingOnly) f.player.age++;
        var materials = new ArrayList<ItemStack>();
        f.network.packetSink = packet -> {
            if (packet instanceof PlayerInteractBlockC2SPacket) materials.add(f.player.getMainHandStack().copy());
        };
        MovementHandler.onMovementSent(f.player);
        f.printer.flushNativePlacements();
        var order = switch (left) {
            case 1 -> List.of(0);
            case 2 -> List.of(0, 2);
            default -> List.of(1, 0, 2);
        };
        if (materials.size() != left) throw new AssertionError("Wrong number of interactions for budget " + left);
        for (int i = 0; i < left; i++) {
            int planned = order.get(i);
            var packet = f.network.blockPackets.get(i);
            var stack = materials.get(i);
            var item = (BlockItem) stack.getItem();
            var context = item.getPlacementContext(new ItemPlacementContext(f.player, Hand.MAIN_HAND, stack, packet.getBlockHitResult()));
            var pos = positions.get(planned);
            if (context == null || !pos.equals(context.getBlockPos()) || item.getPlacementState(context) != states.get(planned)) {
                throw new AssertionError("Displaced foundation with budget " + left + " at packet " + i);
            }
            f.world.blocks.put(pos, states.get(planned));
            Printer.confirmNativePlacement(pos, states.get(planned));
            Printer.acknowledgeBlockActions(packet.getSequence());
        }
        f.configure(PrinterConfig.INTERACTION_WINDOW_LIMIT, 64);
        f.player.age++;
        f.schematic.blocks.put(dependent, torch);
        f.printer.onGameTick();
        MovementHandler.onMovementSent(f.player);
        f.printer.flushNativePlacements();
        if (materials.size() != left + 1 || materials.getLast().getItem() != torches.getItem()) {
            throw new AssertionError("Fourth target did not unlock after its foundation arrived");
        }
    }

    private static void checkMixedBatch(StateInteractionTestFixture f, BlockState mount, boolean loseSupport) throws Exception {
        f.reset();
        f.setVelocity(Vec3d.ZERO);
        var target = StateInteractionTestFixture.TARGET;
        var support = target.offset(NativeAttachment.decorationFace(mount).getOpposite());
        var neighbor = target.east();
        var stone = Blocks.ANDESITE.getDefaultState();
        f.world.blocks.put(support, Blocks.STONE.getDefaultState());
        var stones = new ItemStack(stone.getBlock(), 8);
        var mounts = new ItemStack(mount.getBlock(), 8);
        f.player.inventory.main.set(2, stones);
        f.player.inventory.main.set(3, mounts);
        var first = NativePlacementSolver.solveStationary(neighbor, stone, stones, 4.5, true);
        var second = NativePlacementSolver.solveStationary(target, mount, mounts, 4.5, true);
        if (first == null || second == null) throw new AssertionError("Missing mixed batch setup");
        f.printer.addNativePlacement(neighbor, stone, stones, first);
        f.printer.addNativePlacement(target, mount, mounts, second);
        if (loseSupport) f.world.blocks.remove(support);
        var wireItems = new ArrayList<ItemStack>();
        f.network.packetSink = packet -> {
            if (packet instanceof PlayerInteractBlockC2SPacket) wireItems.add(f.player.getMainHandStack().copy());
        };
        MovementHandler.onMovementSent(f.player);
        f.printer.flushNativePlacements();
        var expected = loseSupport ? List.of(stone) : List.of(mount, stone);
        if (wireItems.size() != expected.size()) throw new AssertionError("Mixed batch did not finish: " + wireItems);
        // Apply packets in server order. No block updates or ACKs were returned during the flush.
        for (int i = 0; i < expected.size(); i++) {
            var stack = wireItems.get(i);
            var item = (BlockItem) stack.getItem();
            var context = item.getPlacementContext(new ItemPlacementContext(f.player, Hand.MAIN_HAND, stack,
                    f.network.blockPackets.get(i).getBlockHitResult()));
            var pos = expected.get(i) == stone ? neighbor : target;
            if (context == null || !context.getBlockPos().equals(pos) || item.getPlacementState(context) != expected.get(i)) {
                throw new AssertionError("Invalid reordered placement at " + pos + " with " + stack);
            }
            f.world.blocks.put(pos, expected.get(i));
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
