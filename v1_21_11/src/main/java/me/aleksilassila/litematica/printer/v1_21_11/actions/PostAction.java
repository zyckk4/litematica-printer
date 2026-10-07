package me.aleksilassila.litematica.printer.v1_21_11.actions;

import me.aleksilassila.litematica.printer.v1_21_11.FreeLook;
import me.aleksilassila.litematica.printer.v1_21_11.MovementHandler;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.PrinterPlacementContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;

public class PostAction extends PrepareAction {
    public PostAction(PrinterPlacementContext context, ClientPlayerEntity player) {
        super(context);
        this.pitch = player.getPitch();
        this.yaw = player.getYaw();
    }

    @Override
    public boolean send(MinecraftClient client, ClientPlayerEntity player) {
        if (context.canStealth) {
            boolean nativeMode = FreeLook.nativeMode();
            PlayerMoveC2SPacket.LookAndOnGround packet = new PlayerMoveC2SPacket.LookAndOnGround(
                    nativeMode ? player.getYaw() : this.yaw, nativeMode ? player.getPitch() : this.pitch,
                    player.isOnGround(), player.horizontalCollision);

            if (!nativeMode && PrinterConfig.ROTATE_PLAYER.getBooleanValue()) {
                player.setYaw(this.yaw);
                player.setPitch(this.pitch);
            }

            MovementHandler.sendLegacyRotation(player, packet);
        }
        return true;
    }
}
