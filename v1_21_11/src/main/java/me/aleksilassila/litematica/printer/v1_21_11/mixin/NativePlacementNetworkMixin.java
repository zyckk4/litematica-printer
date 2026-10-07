package me.aleksilassila.litematica.printer.v1_21_11.mixin;

import me.aleksilassila.litematica.printer.v1_21_11.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.v1_21_11.Printer;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla has already switched to the client thread at these method tails. */
@Mixin(ClientPlayNetworkHandler.class)
public class NativePlacementNetworkMixin {
    @Inject(method = "onBlockUpdate", at = @At("TAIL"))
    private void printer$blockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        Printer.confirmNativePlacement(packet.getPos(), packet.getState());
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("TAIL"))
    private void printer$chunkDelta(ChunkDeltaUpdateS2CPacket packet, CallbackInfo ci) {
        packet.visitUpdates(Printer::confirmNativePlacement);
    }

    @Inject(method = "onPlayerPositionLook", at = @At("TAIL"))
    private void printer$correction(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        if (LitematicaMixinMod.printer != null) LitematicaMixinMod.printer.onServerCorrection();
    }
}
