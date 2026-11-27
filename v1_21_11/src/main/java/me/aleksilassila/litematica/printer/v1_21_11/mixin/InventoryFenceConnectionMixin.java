package me.aleksilassila.litematica.printer.v1_21_11.mixin;

import io.netty.channel.ChannelFutureListener;
import me.aleksilassila.litematica.printer.v1_21_11.InventoryFenceOrder;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public class InventoryFenceConnectionMixin {
    @Unique
    private static float printer$lookYaw(Packet<?> packet) {
        if (packet instanceof PlayerMoveC2SPacket move && move.changesLook()) return move.getYaw(0);
        if (packet instanceof PlayerInteractItemC2SPacket use) return use.getYaw();
        return Float.NaN;
    }

    @Inject(method = "sendImmediately", at = @At("HEAD"))
    private void printer$invalidateQueuedLook(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        float yaw = printer$lookYaw(packet);
        if (!Float.isNaN(yaw) && ((ClientConnection) (Object) this).getPacketListener() instanceof ClientPlayNetworkHandler play) {
            InventoryFenceOrder.get(play).invalidateDifferentYaw(yaw);
        }
    }

    @Inject(method = "sendInternal", at = @At("HEAD"))
    private void printer$recordStatisticsWrite(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (!(((ClientConnection) (Object) this).getPacketListener() instanceof ClientPlayNetworkHandler play)) return;
        if (packet instanceof ClientStatusC2SPacket status && status.getMode() == ClientStatusC2SPacket.Mode.REQUEST_STATS) {
            InventoryFenceOrder.get(play).sent(status);
        } else {
            float yaw = printer$lookYaw(packet);
            if (!Float.isNaN(yaw)) InventoryFenceOrder.get(play).sentYaw(yaw);
        }
    }
}
