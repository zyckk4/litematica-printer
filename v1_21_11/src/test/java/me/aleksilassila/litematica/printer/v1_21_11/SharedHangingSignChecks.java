package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_11.mixin.MixinAccessorClientPlayerEntity;
import net.minecraft.block.Blocks;
import net.minecraft.block.WallHangingSignBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

final class SharedHangingSignChecks {
    static void run() throws Exception {
        try (var fixture = new StateInteractionTestFixture()) {
            for (Direction facing : Direction.Type.HORIZONTAL) {
                verifyPair(fixture, facing, facing.rotateYClockwise(), 9, 1);
            }
            verifyPair(fixture, Direction.SOUTH, Direction.WEST, 1, 1);
            verifyPair(fixture, Direction.EAST, Direction.WEST, 9, 2);
        }
    }

    private static void verifyPair(StateInteractionTestFixture f, Direction first, Direction second,
                                   int burst, int expectedTurns) throws Exception {
        f.reset();
        f.setVelocity(Vec3d.ZERO);
        f.configure(PrinterConfig.NATIVE_BURST, burst);
        f.configure(PrinterConfig.STRICT_BLOCK_FACE_CHECK, true);
        f.configure(PrinterConfig.PRINTER_GRIM_ROTATION, true);
        var signs = new ItemStack(Items.OAK_HANGING_SIGN, 8);
        f.fill(signs);
        var left = new BlockPos(0, 64, 2);
        var right = left.east(2);
        for (var pos : new BlockPos[]{left, right}) {
            for (Direction side : Direction.values()) f.world.blocks.put(pos.offset(side), Blocks.STONE.getDefaultState());
            f.schematic.blocks.put(pos, Blocks.OAK_WALL_HANGING_SIGN.getDefaultState()
                    .with(WallHangingSignBlock.FACING, pos.equals(left) ? first : second));
        }
        var movement = (MixinAccessorClientPlayerEntity) (Object) f.player;
        int turns = 0;
        float confirmedYaw = f.player.getYaw();
        for (int tick = 0; tick < 15 && f.network.blockPackets.size() < 2; tick++) {
            f.player.age++;
            int packetCount = f.network.packets.size();
            int uses = f.network.blockPackets.size();
            f.printer.onGameTick();
            MovementHandler.validateMovementInput(f.player);
            float yaw = MovementHandler.serverYaw(f.player.getYaw());
            movement.setLastYaw(yaw);
            movement.setLastPitch(MovementHandler.serverPitch(f.player.getPitch()));
            MovementHandler.onMovementSent(f.player);
            f.printer.flushNativePlacements();
            // A client player reads its camera in getYaw(1). Replay the server
            // player's confirmed look while vanilla decodes each captured hit.
            float cameraYaw = f.player.getYaw();
            float cameraPitch = f.player.getPitch();
            f.player.setYaw(confirmedYaw);
            f.player.setHeadYaw(confirmedYaw);
            f.player.setPitch(movement.getLastPitch());
            for (var packet : f.network.blockPackets.subList(uses, f.network.blockPackets.size())) {
                var item = (BlockItem) signs.getItem();
                var context = item.getPlacementContext(new ItemPlacementContext(f.player, Hand.MAIN_HAND, signs, packet.getBlockHitResult()));
                if (context == null) throw new AssertionError("Unusable hanging sign hit");
                var actual = item.getPlacementState(context);
                var pos = context.getBlockPos();
                if (actual == null || !actual.equals(f.schematic.getBlockState(pos)) || !f.world.getBlockState(pos).isAir()) {
                    throw new AssertionError("Wrong or duplicate hanging sign for " + first + "/" + second + " at " + pos + ": " + actual);
                }
                f.world.blocks.put(pos, actual);
                Printer.confirmNativePlacement(pos, actual);
                Printer.acknowledgeBlockActions(packet.getSequence());
            }
            f.player.setYaw(cameraYaw);
            f.player.setPitch(cameraPitch);
            if (f.network.packets.subList(packetCount, f.network.packets.size()).stream()
                    .anyMatch(p -> p instanceof ClientStatusC2SPacket)) {
                if (f.network.blockPackets.size() != uses) throw new AssertionError("Unconfirmed head turn used in the same flush");
                turns++;
                confirmedYaw = yaw;
                Printer.inventoryManager.onInventoryReadbackBarrier(f.network);
                NativeHeadRotation.onActionResponse(f.network);
            }
        }
        if (f.network.blockPackets.size() != 2 || turns != expectedTurns) {
            throw new AssertionError("Hanging sign pair " + first + "/" + second + " used "
                    + turns + " turns and " + f.network.blockPackets.size() + " clicks");
        }
    }
}
