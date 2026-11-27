package me.aleksilassila.litematica.printer.v1_21_4;

import io.netty.channel.embedded.EmbeddedChannel;
import me.aleksilassila.litematica.printer.v1_21_4.mixin.MixinAccessorClientPlayerEntity;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkSide;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.Hand;

final class NativePacketLookChecks {
    static void run() throws Exception {
        try (var f = new StateInteractionTestFixture()) {
            var connection = new ClientConnection(NetworkSide.CLIENTBOUND);
            var channel = new EmbeddedChannel(connection);
            try {
                StateInteractionTestFixture.set(ClientCommonNetworkHandler.class, f.network, "connection", connection);
                f.setYaw(0);
                var cache = (MixinAccessorClientPlayerEntity) (Object) f.player;
                connection.send(new PlayerMoveC2SPacket.LookAndOnGround(0, 0, true, false));
                MovementHandler.onMovementSent(f.player);
                require(MovementHandler.sentRotationMatches(0, 0), "An accepted turn should be usable immediately");

                connection.send(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 7, 90, -30));
                connection.send(new PlayerMoveC2SPacket.OnGroundOnly(true, false));
                require(!MovementHandler.sentRotationMatches(0, 0) && !MovementHandler.sentRotationMatches(90, -30)
                                && !MovementHandler.placementLookKnown(f.player),
                        "UseItem can be rejected before Folia applies its angles");
                require(MovementHandler.requestRotation(90, -30), "Failed to reserve the turn");
                MovementHandler.validateMovementInput(f.player);
                MovementHandler.prepareMovementLook(f.player);
                require(cache.getLastYaw() != 90, "Vanilla must send the turn even when the item carried its angles");
                require(f.player.getYaw() == 0 && f.player.getPitch() == 0, "Updated the local camera");

                connection.send(new PlayerMoveC2SPacket.LookAndOnGround(90, -30, true, false));
                MovementHandler.onMovementSent(f.player);
                require(MovementHandler.sentRotationMatches(90, -30), "Restored turn cannot be used in the same tick");
                connection.send(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 8, -270, -30));
                require(MovementHandler.sentRotationMatches(90, -30), "A matching UseItem invalidated a known turn");
                connection.send(new PlayerInteractItemC2SPacket(Hand.MAIN_HAND, 9, 90, 30));
                require(!MovementHandler.sentRotationMatches(90, -30), "A pitch-only change was lost");
            } finally {
                MovementHandler.clearRotation();
                channel.finishAndReleaseAll();
            }
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) throw new AssertionError(message);
    }
}
