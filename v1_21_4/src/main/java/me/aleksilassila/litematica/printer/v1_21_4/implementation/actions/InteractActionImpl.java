package me.aleksilassila.litematica.printer.v1_21_4.implementation.actions;

import me.aleksilassila.litematica.printer.v1_21_4.actions.InteractAction;
import me.aleksilassila.litematica.printer.v1_21_4.Printer;
import me.aleksilassila.litematica.printer.v1_21_4.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_4.implementation.PrinterPlacementContext;
import me.aleksilassila.litematica.printer.v1_21_4.mixin.MixinAccessorClientPlayerEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

public class InteractActionImpl extends InteractAction {
    private final boolean changesClickedBlock;

    public InteractActionImpl(PrinterPlacementContext context) {
        this(context, false);
    }

    public InteractActionImpl(PrinterPlacementContext context, boolean changesClickedBlock) {
        super(context);
        this.changesClickedBlock = changesClickedBlock;
    }

    @Override
    protected BlockPos getTimeoutPos() {
        return changesClickedBlock ? context.hitResult.getBlockPos() : super.getTimeoutPos();
    }
    @Override
    protected ActionResult interact(MinecraftClient client, ClientPlayerEntity player, Hand hand, BlockHitResult hitResult) {
        if (client.interactionManager == null || player.input == null || player.networkHandler == null) {
            return ActionResult.FAIL;
        }
        PlayerInput original = player.input.playerInput;
        boolean temporarySneak = context.shouldSneak && !original.sneak();
        // A newly pressed user key is sampled later in tickMovement. Let that
        // tick establish the user's sneak state rather than releasing it here.
        if (temporarySneak && client.options != null && client.options.sneakKey.isPressed()) {
            return ActionResult.FAIL;
        }
        if (!changesClickedBlock && !context.canSendPlacement(player, hand, hitResult)) return ActionResult.FAIL;
        if (!Printer.tryAcquirePlacementPacket()) return ActionResult.FAIL;
        ClientWorld world = client.world;
        ClientPlayNetworkHandler connection = player.networkHandler;
        Throwable interactionFailure = null;
        try {
            if (temporarySneak) {
                // 1.21.4 still uses ClientCommand for authoritative sneak state.
                // PlayerInput alone does not suppress the server's container UI.
                connection.sendPacket(new ClientCommandC2SPacket(player,
                        ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY));
                // Vanilla compares this value with the next sampled input. It
                // must send a release if the user takes over and lets go again.
                ((MixinAccessorClientPlayerEntity) player).setLastSneaking(true);
                player.input.playerInput = new PlayerInput(original.forward(), original.backward(),
                        original.left(), original.right(), original.jump(), true, original.sprint());
            }
            ActionResult result = client.interactionManager.interactBlock(player, hand, hitResult);
            if (!result.isAccepted()) {
                if (PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) System.out.println("Failed to interact with block got " + result);
            }
            player.swingHand(hand);
            return result;
        } catch (RuntimeException | Error failure) {
            interactionFailure = failure;
            throw failure;
        } finally {
            if (temporarySneak) {
                // This must not be queued for another tick: a rejected action,
                // disabled printer or correction may discard the entire queue.
                player.input.playerInput = original;
                // The user's key may have changed while the interaction ran.
                if (client.player == player && client.world == world && player.getWorld() == world
                        && player.networkHandler == connection
                        && (client.options == null || !client.options.sneakKey.isPressed())) {
                    try {
                        connection.sendPacket(new ClientCommandC2SPacket(player,
                                ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY));
                        ((MixinAccessorClientPlayerEntity) player).setLastSneaking(false);
                    } catch (RuntimeException | Error cleanupFailure) {
                        if (interactionFailure == null) throw cleanupFailure;
                        if (interactionFailure != cleanupFailure) interactionFailure.addSuppressed(cleanupFailure);
                    }
                }
            }
        }
    }
}
