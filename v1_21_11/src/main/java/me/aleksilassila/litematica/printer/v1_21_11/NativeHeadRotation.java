package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.mixin.MixinAccessorClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;

/** ServerPlayerEntity copies movement yaw to its head during playerTick, after handling movement. */
public final class NativeHeadRotation {
    private static ClientPlayerEntity owner;
    private static World world;
    private static ClientPlayNetworkHandler connection;
    private static ClientStatusC2SPacket marker;
    private static float yaw;
    private static BlockPos waitingTarget;
    private static Long firstServerTime;
    private static boolean settled;

    private NativeHeadRotation() {}

    public static BlockPos preferredTarget(ClientPlayerEntity player) {
        return owner == player && world == player.getEntityWorld() ? waitingTarget : null;
    }

    static float heldYaw(ClientPlayerEntity player) {
        return owner == player && world == player.getEntityWorld() && player.networkHandler == connection
                && InventoryFenceOrder.get(connection).holdsRotation(marker) ? yaw : Float.NaN;
    }

    public static void holdBetweenScans(ClientPlayerEntity player) {
        if (preferredTarget(player) != null) {
            MovementHandler.requestRotation(yaw, player.getPitch());
        }
    }

    public static boolean ready(ClientPlayerEntity player, BlockPos target, float sentYaw) {
        ClientPlayNetworkHandler network = player.networkHandler;
        InventoryFenceOrder order = InventoryFenceOrder.get(network);
        if (owner != player || world != player.getEntityWorld() || connection != network
                || MathHelper.wrapDegrees(sentYaw - yaw) != 0 || !order.holdsRotation(marker)) {
            owner = player;
            world = player.getEntityWorld();
            connection = network;
            yaw = sentYaw;
            firstServerTime = null;
            settled = false;
            waitingTarget = target.toImmutable();
            float wireYaw = order.lastSentYaw();
            if (Float.isFinite(wireYaw) && MathHelper.wrapDegrees(wireYaw - yaw) != 0) {
                // UseItem can change server yaw without updating vanilla's last movement yaw.
                // Let the next vanilla movement send restore the requested angle.
                ((MixinAccessorClientPlayerEntity) player).setLastYaw(wireYaw);
                marker = null;
                return false;
            }
            marker = order.expectRotation(yaw, player.age);
            if (marker != null) network.sendPacket(marker);
        } else if (!settled && waitingTarget == null) {
            waitingTarget = target.toImmutable();
        }
        return settled || order.hasPlayerTick(marker);
    }

    static void onMovementSent(ClientPlayerEntity player, float sentYaw) {
        if (owner == null) return;
        if (owner != player || world != player.getEntityWorld() || player.networkHandler != connection
                || MathHelper.wrapDegrees(sentYaw - yaw) != 0) {
            clear();
        } else if (!settled && waitingTarget != null) {
            var probe = InventoryFenceOrder.get(connection).followUp(marker, player.age);
            if (probe != null) connection.sendPacket(probe);
        }
    }

    public static void placed(BlockPos target) {
        if (target.equals(waitingTarget)) waitingTarget = null;
    }

    /** A vanilla block action acknowledgement is written at the start of connection.tick(),
     * immediately before playerTick(). The server processes the next UseOn after that tick. */
    public static void onActionResponse(ClientPlayNetworkHandler network) {
        if (hasMarkerReply(network)) settled = true;
    }

    /** With no other interactions, two increasing authoritative game times also bracket a
     * server tick. Requiring an ordered reply first excludes updates already in flight. */
    public static void onWorldTime(ClientPlayNetworkHandler network, long time) {
        if (!hasMarkerReply(network)) return;
        if (firstServerTime == null || time < firstServerTime) firstServerTime = time;
        else if (time > firstServerTime) settled = true;
    }

    private static boolean hasMarkerReply(ClientPlayNetworkHandler network) {
        return !settled && network == connection && InventoryFenceOrder.get(network).receivedRotation(marker);
    }

    public static void clear() {
        owner = null;
        world = null;
        connection = null;
        waitingTarget = null;
        marker = null;
        firstServerTime = null;
        settled = false;
    }
}
