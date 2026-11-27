package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.implementation.NativePlacementContext;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.HashSet;
import java.util.List;

final class NativeHeadRotationChecks {
    static void run() throws Exception {
        try (var f = new StateInteractionTestFixture()) {
            markerOrdering(f);
            playTimeProof(f);
            staleAndMissingStatistics(f);
            directionOrders(f);
            multipleSupports(f);
        } finally {
            NativeHeadRotation.clear();
        }
    }

    private static void markerOrdering(StateInteractionTestFixture f) {
        BlockPos target = StateInteractionTestFixture.TARGET;
        InventoryFenceOrder order = InventoryFenceOrder.get(f.network);
        f.network.sendPacket(new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS));
        var inventoryMarker = order.expect();
        f.network.sendPacket(inventoryMarker);
        check(!NativeHeadRotation.ready(f.player, target, 90), "first snapped yaw is unproven");
        int count = f.network.packets.size();
        order.received(-1);
        NativeHeadRotation.onWorldTime(f.network, 20);
        check(order.received(-1) == inventoryMarker, "inventory and rotation retain distinct reply IDs");
        NativeHeadRotation.onActionResponse(f.network);
        NativeHeadRotation.onWorldTime(f.network, 40);
        check(!NativeHeadRotation.ready(f.player, target, 90), "earlier replies cannot settle yaw");
        check(f.network.packets.size() == count, "repeated attempts share the outstanding marker");
        check(target.equals(NativeHeadRotation.preferredTarget(f.player)), "waiting target has scan priority");
        NativeHeadRotation.holdBetweenScans(f.player);
        check(MovementHandler.hasRotation(), "skipped print scans still reserve the waiting yaw");
        MovementHandler.clearRotation();
        order.received(-1);
        NativeHeadRotation.onWorldTime(f.network, 60);
        NativeHeadRotation.onWorldTime(f.network, 60);
        check(!NativeHeadRotation.ready(f.player, target, 90), "two updates in one server tick are insufficient");
        NativeHeadRotation.onWorldTime(f.network, 80);
        check(NativeHeadRotation.ready(f.player, target, 90), "distinct server ticks settle the yaw");
        NativeHeadRotation.placed(target);
        check(NativeHeadRotation.preferredTarget(f.player) == null, "sent target releases scan priority");
        check(NativeHeadRotation.ready(f.player, target.east(), 450), "unchanged yaw can be reused");
        order.sentYaw(-90);
        check(!NativeHeadRotation.ready(f.player, target.east(), 90), "intervening look invalidates the old proof");
        order.sentYaw(90);
        f.player.age += 2;
        check(!NativeHeadRotation.ready(f.player, target.east(), 90), "restoring the movement yaw starts a new marker");
        NativeHeadRotation.onActionResponse(f.network);
        check(!NativeHeadRotation.ready(f.player, target.east(), 90), "old server tick cannot unlock new look");
        order.received(-1);
        NativeHeadRotation.onActionResponse(f.network);
        check(NativeHeadRotation.ready(f.player, target.east(), 90), "post-marker action response brackets playerTick");
        NativeHeadRotation.onMovementSent(f.player, 0);
        check(NativeHeadRotation.preferredTarget(f.player) == null, "restored camera yaw releases priority");
    }

    private static void playTimeProof(StateInteractionTestFixture f) {
        BlockPos target = StateInteractionTestFixture.TARGET;
        InventoryFenceOrder order = InventoryFenceOrder.get(f.network);
        f.player.age += 2;
        order.sentYaw(45);
        f.network.sendPacket(order.expect()); // inventory read submitted before the turn
        check(!NativeHeadRotation.ready(f.player, target, 45), "new yaw starts unconfirmed");
        int marked = f.network.packets.size();
        Printer.inventoryManager.onInventoryReadbackBarrier(f.network, 800);
        Printer.inventoryManager.onInventoryReadbackBarrier(f.network, 801);
        check(!NativeHeadRotation.ready(f.player, target, 45), "a pre-turn stat is not the baseline");
        f.player.age++;
        NativeHeadRotation.onMovementSent(f.player, 45);
        check(f.network.packets.size() == marked, "extra read is spaced from the marker");
        f.player.age++;
        NativeHeadRotation.onMovementSent(f.player, 45);
        check(f.network.packets.size() == marked + 1, "one follow-up without a placement scan");
        Printer.inventoryManager.onInventoryReadbackBarrier(f.network, 801);
        check(!NativeHeadRotation.ready(f.player, target, 45), "same-tick statistics cannot prove head yaw");
        for (int i = 0; i < 12; i++) {
            f.player.age++;
            NativeHeadRotation.onMovementSent(f.player, 45);
        }
        check(f.network.packets.size() == marked + 1, "inconclusive statistics are not repeatedly polled");
        Printer.inventoryManager.onInventoryReadbackBarrier(f.network, 999);
        check(!NativeHeadRotation.ready(f.player, target, 45), "an unmatched response cannot prove the turn");
        f.network.sendPacket(order.expect()); // a later inventory reply can also prove the tick
        Printer.inventoryManager.onInventoryReadbackBarrier(f.network, 802);
        check(NativeHeadRotation.ready(f.player, target, 45), "post-turn player tick confirms yaw");
        order.invalidateDifferentYaw(405);
        check(NativeHeadRotation.heldYaw(f.player) == 45, "equivalent queued look retains the proof");
        order.invalidateDifferentYaw(-45);
        check(Float.isNaN(NativeHeadRotation.heldYaw(f.player)) && order.lastSentYaw() == 45,
                "a queued turn invalidates the proof before Netty writes the actual yaw");
    }

    private static void staleAndMissingStatistics(StateInteractionTestFixture f) {
        BlockPos target = StateInteractionTestFixture.TARGET;
        InventoryFenceOrder order = InventoryFenceOrder.get(f.network);
        f.player.age += 2;
        order.sentYaw(-45);
        check(!NativeHeadRotation.ready(f.player, target, -45), "new angle discards proved play time");
        order.received(810);
        f.player.age += 2;
        NativeHeadRotation.onMovementSent(f.player, -45);
        int inFlight = f.network.packets.size();
        for (int yaw : new int[]{0, 90, -90, 0}) {
            f.player.age++;
            order.sentYaw(yaw);
            check(!NativeHeadRotation.ready(f.player, target, yaw), "changing yaw invalidates old reads");
        }
        check(f.network.packets.size() == inFlight, "churn did not queue markers behind an outstanding read");
        order.received(820);
        check(!NativeHeadRotation.ready(f.player, target, 0), "late follow-up cannot settle a newer look");
        order.received(-1);
        f.player.age += 2;
        NativeHeadRotation.onMovementSent(f.player, 0);
        check(f.network.packets.size() == inFlight + 1, "no polling when PLAY_TIME is unavailable");
        check(!NativeHeadRotation.ready(f.player, target, 0), "missing statistics are not zero");
        NativeHeadRotation.onWorldTime(f.network, 120);
        NativeHeadRotation.onWorldTime(f.network, 140);
        check(NativeHeadRotation.ready(f.player, target, 0), "missing statistics retain clock fallback");
        NativeHeadRotation.clear();
        check(Float.isNaN(NativeHeadRotation.heldYaw(f.player)), "context reset releases held yaw");
    }

    private static void directionOrders(StateInteractionTestFixture f) {
        for (float pitch : new float[]{-90, -45.1F, -45, -35.3F, -35.26F, -30, -0.0001F, 0, 30, 35.26F, 35.3F, 45, 45.1F, 90}) {
            var orders = new HashSet<List<Direction>>();
            NativePlacementContext.forEveryHeadOrder(pitch, order -> { orders.add(List.of(order)); return true; });
            f.player.setPitch(pitch);
            for (int yaw = -180; yaw <= 180; yaw++) {
                f.player.setYaw(yaw);
                f.player.setHeadYaw(yaw);
                check(orders.contains(List.of(Direction.getEntityFacingOrder(f.player))), "missing vanilla yaw/pitch order " + yaw + "/" + pitch);
            }
        }
    }

    private static void multipleSupports(StateInteractionTestFixture f) {
        BlockPos target = StateInteractionTestFixture.TARGET;
        for (Direction side : Direction.Type.HORIZONTAL) f.world.blocks.put(target.offset(side), Blocks.STONE.getDefaultState());
        var sign = Blocks.OAK_WALL_SIGN.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.EAST);
        var supportHit = new BlockHitResult(Vec3d.ofCenter(target.west()).add(0.5, 0, 0), Direction.EAST, target.west(), false);
        var direct = new NativePlacementSolver.Solution(supportHit, sign, 90, 30, 0, false, false);
        check(NativePlacementSolver.independentOfHeadYaw(target, new ItemStack(Items.OAK_SIGN), direct),
                "clicking the desired wall fixes a regular sign mount for every head yaw");
        var hanging = Blocks.OAK_WALL_HANGING_SIGN.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.EAST);
        var airHit = new BlockHitResult(Vec3d.ofCenter(target).add(0, -0.5, 0), Direction.DOWN, target, false);
        var ambiguous = new NativePlacementSolver.Solution(airHit, hanging, 90, 30, 0, true, false);
        check(!NativePlacementSolver.independentOfHeadYaw(target, new ItemStack(Items.OAK_HANGING_SIGN), ambiguous),
                "four supports allow old server head yaw to select a different wall hanging sign");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
