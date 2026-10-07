package me.aleksilassila.litematica.printer.v1_21_11.actions;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.util.PlayerInput;

public class PresShift extends Action {
    @Override
    public boolean send(MinecraftClient client, ClientPlayerEntity player) {
        player.input.playerInput = new PlayerInput(player.input.playerInput.forward(),
                player.input.playerInput.backward(), player.input.playerInput.left(), player.input.playerInput.right(),
                player.input.playerInput.jump(), true, player.input.playerInput.sprint());
        // Since 1.21.11 sneak is carried by PlayerInputC2SPacket; the old
        // PRESS_SHIFT_KEY ClientCommand mode no longer exists.
        player.networkHandler.sendPacket(new PlayerInputC2SPacket(player.input.playerInput));
        return true;
    }
}
