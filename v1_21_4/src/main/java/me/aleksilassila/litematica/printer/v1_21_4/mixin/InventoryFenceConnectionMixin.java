package me.aleksilassila.litematica.printer.v1_21_4.mixin;

import me.aleksilassila.litematica.printer.v1_21_4.InventoryFenceOrder;
import me.aleksilassila.litematica.printer.v1_21_4.NativePacketLook;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.PacketCallbacks;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.math.MathHelper;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public class InventoryFenceConnectionMixin implements NativePacketLook {
    @Unique private volatile NativePacketLook.Angles printer$look;

    @Override
    public NativePacketLook.Angles printer$lastLook() {
        return printer$look;
    }

    @Inject(method = "sendImmediately", at = @At("HEAD"))
    private void printer$recordQueuedLook(Packet<?> packet, PacketCallbacks callbacks, boolean flush, CallbackInfo ci) {
        if (packet instanceof PlayerMoveC2SPacket move && move.changesLook()) {
            printer$look = new NativePacketLook.Angles(move.getYaw(0), move.getPitch(0), false);
        } else if (packet instanceof PlayerInteractItemC2SPacket use) {
            var last = printer$look;
            // Folia may reject UseItem before applying its angles (for example, an empty hand).
            if (last == null || (!last.uncertain() && (MathHelper.wrapDegrees(last.yaw() - use.getYaw()) != 0
                    || last.pitch() != use.getPitch()))) {
                printer$look = new NativePacketLook.Angles(use.getYaw(), use.getPitch(), true);
            }
        }
    }

    @Inject(method = "sendInternal", at = @At("HEAD"))
    private void printer$recordStatisticsWrite(Packet<?> packet, PacketCallbacks callbacks, boolean flush, CallbackInfo ci) {
        if (packet instanceof ClientStatusC2SPacket status && status.getMode() == ClientStatusC2SPacket.Mode.REQUEST_STATS
                && ((ClientConnection) (Object) this).getPacketListener() instanceof ClientPlayNetworkHandler play) {
            InventoryFenceOrder.get(play).sent(status);
        }
    }
}
