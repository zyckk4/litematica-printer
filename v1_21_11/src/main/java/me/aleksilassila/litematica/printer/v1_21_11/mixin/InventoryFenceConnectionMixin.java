package me.aleksilassila.litematica.printer.v1_21_11.mixin;

import io.netty.channel.ChannelFutureListener;
import me.aleksilassila.litematica.printer.v1_21_11.InventoryFenceOrder;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public class InventoryFenceConnectionMixin {
    @Inject(method = "sendInternal", at = @At("HEAD"))
    private void printer$recordStatisticsWrite(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (packet instanceof ClientStatusC2SPacket status && status.getMode() == ClientStatusC2SPacket.Mode.REQUEST_STATS
                && ((ClientConnection) (Object) this).getPacketListener() instanceof ClientPlayNetworkHandler play) {
            InventoryFenceOrder.get(play).sent(status);
        }
    }
}
