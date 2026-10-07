package me.aleksilassila.litematica.printer.v1_21_11.implementation.actions;

import me.aleksilassila.litematica.printer.v1_21_11.actions.InteractAction;
import me.aleksilassila.litematica.printer.v1_21_11.Printer;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.PrinterPlacementContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
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
    private final MinecraftClient mc = MinecraftClient.getInstance();
    @Override
    protected ActionResult interact(MinecraftClient client, ClientPlayerEntity player, Hand hand, BlockHitResult hitResult) {
        if (!Printer.tryAcquirePlacementPacket()) {
            return ActionResult.FAIL;
        }
        ActionResult result = client.interactionManager.interactBlock(player, hand, hitResult);
        if (!result.isAccepted()) {
            if (PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) System.out.println("Failed to interact with block got " + result);
        }
        player.swingHand(hand);
        return result;
    }
}
