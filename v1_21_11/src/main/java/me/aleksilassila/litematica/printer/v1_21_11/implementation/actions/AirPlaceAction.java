package me.aleksilassila.litematica.printer.v1_21_11.implementation.actions;

import me.aleksilassila.litematica.printer.v1_21_11.Printer;
import me.aleksilassila.litematica.printer.v1_21_11.actions.InteractAction;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.PrinterPlacementContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** The guide fallback uses the same native UseOn wire format as the solver. */
public class AirPlaceAction extends InteractAction {
    public AirPlaceAction(PrinterPlacementContext context) {
        super(context);
    }

    @Override
    protected ActionResult interact(MinecraftClient client, ClientPlayerEntity player, Hand ignored, BlockHitResult hit) {
        if (client.world == null || client.interactionManager == null || client.getNetworkHandler() == null
                || !context.canPlace()) return ActionResult.FAIL;
        BlockPos target = context.getBlockPos();
        ItemStack held = player.getMainHandStack();
        if (!player.getAbilities().creativeMode && held.getCount() <= Printer.pendingItemCount(held)) return ActionResult.FAIL;
        if (!(held.getItem() instanceof BlockItem item)
                || !ItemStack.areItemsAndComponentsEqual(held, context.getStack())
                || Printer.hasPendingPlacement(target)
                || player.getEyePos().squaredDistanceTo(hit.getPos()) > player.getBlockInteractionRange() * player.getBlockInteractionRange()) {
            return ActionResult.FAIL;
        }
        var predicted = item.getPlacementState(context);
        if (predicted == null || !Printer.tryAcquirePlacementPacket()) return ActionResult.FAIL;
        boolean swap = PrinterConfig.PRINTER_NATIVE_GRIM_AIRPLACE.getBooleanValue();
        Hand hand = swap ? Hand.OFF_HAND : Hand.MAIN_HAND;
        BlockHitResult wire = new BlockHitResult(hit.getPos(), hit.getSide(), target, false);
        if (swap) swapHands(client);
        try {
            client.interactionManager.sendSequencedPacket(client.world, sequence -> {
                Printer.trackPlacement(target, predicted, held, sequence);
                return new PlayerInteractBlockC2SPacket(hand, wire, sequence);
            });
        } finally {
            if (swap) swapHands(client);
        }
        return ActionResult.SUCCESS;
    }

    private static void swapHands(MinecraftClient client) {
        client.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
    }
}
