package me.aleksilassila.litematica.printer.v1_21_11.actions;

import me.aleksilassila.litematica.printer.v1_21_11.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.v1_21_11.Printer;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.PrinterPlacementContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;

abstract public class InteractAction extends Action {
    public final PrinterPlacementContext context;

    public InteractAction(PrinterPlacementContext context) {
        this.context = context;
    }

    protected abstract ActionResult interact(MinecraftClient client, ClientPlayerEntity player, Hand hand, BlockHitResult hitResult);

    @Override
    public boolean send(MinecraftClient client, ClientPlayerEntity player) {
        ActionResult result = interact(client, player, Hand.MAIN_HAND, context.hitResult);

        // Keep the action queued when the client rejected the interaction.  A
        // failed prediction must not be recorded as a completed placement or
        // hidden by the block timeout, otherwise movement/latency can leave a
        // permanent hole in the schematic.
        if (result == null || !result.isAccepted()) {
            return false;
        }

        if (LitematicaMixinMod.DEBUG)
            System.out.println("InteractAction.send: Blockpos: " + context.getBlockPos() + " Side: " + context.getSide() + " HitPos: " + context.getHitPos());
        Printer.addTimeout(getTimeoutPos());
        return true;
    }

    protected BlockPos getTimeoutPos() {
        return context.hitResult.isInsideBlock() ? context.hitResult.getBlockPos()
                : context.hitResult.getBlockPos().offset(context.hitResult.getSide());
    }

    @Override
    public String toString() {
        return "InteractAction{" +
                "context=" + context +
                '}';
    }
}
