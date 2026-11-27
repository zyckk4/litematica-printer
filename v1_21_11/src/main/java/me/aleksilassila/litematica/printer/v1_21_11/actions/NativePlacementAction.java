package me.aleksilassila.litematica.printer.v1_21_11.actions;

import me.aleksilassila.litematica.printer.v1_21_11.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.v1_21_11.MovementHandler;
import me.aleksilassila.litematica.printer.v1_21_11.NativeHeadRotation;
import me.aleksilassila.litematica.printer.v1_21_11.NativePlacementSolver;
import me.aleksilassila.litematica.printer.v1_21_11.Printer;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

/** Prepared before movement; revalidated and sent after vanilla movement. */
public final class NativePlacementAction extends Action {
    private final BlockPos target;
    private final BlockState desired;
    private final ItemStack stack;
    private final NativePlacementSolver.Solution solution;
    private final boolean selectMaterial;

    public NativePlacementAction(BlockPos target, BlockState desired, ItemStack stack,
                                 NativePlacementSolver.Solution solution) {
        this(target, desired, stack, solution, false);
    }

    public NativePlacementAction(BlockPos target, BlockState desired, ItemStack stack,
                                 NativePlacementSolver.Solution solution, boolean selectMaterial) {
        this.target = target.toImmutable();
        this.desired = desired;
        this.stack = stack.copyWithCount(1);
        this.solution = solution;
        this.selectMaterial = selectMaterial;
    }

    @Override
    public boolean send(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null || client.interactionManager == null || client.getNetworkHandler() == null
                || Printer.hasPendingPlacement(target)) return false;
        if (selectMaterial && !Printer.inventoryManager.selectReadyNativeMaterial(stack)) return false;
        if (!Printer.inventoryManager.canUseSelectedSlot(player)) return false;
        ItemStack held = player.getMainHandStack();
        if (!ItemStack.areItemsAndComponentsEqual(held, stack)) return false;
        if (!solution.anyRotation() && !MovementHandler.sentRotationMatches(solution.yaw(), solution.pitch())) return false;
        double reach = Math.min(LitematicaMixinMod.PRINTING_RANGE.getDoubleValue(), player.getBlockInteractionRange());
        NativePlacementSolver.Solution checked = NativePlacementSolver.revalidate(target, desired, held, solution,
                reach, MovementHandler.sentYaw(player.getYaw()), MovementHandler.sentPitch(player.getPitch()));
        if (checked == null || Printer.hasUnsettledAttachmentNeighbor(target, desired, checked.hit(), held)) return false;
        if (!NativePlacementSolver.independentOfHeadYaw(target, held, checked)
                && !NativeHeadRotation.ready(player, target, checked.yaw())) return false;
        if (!Printer.tryAcquirePlacementPacket()) return false;

        boolean swap = checked.airPlace() && PrinterConfig.PRINTER_NATIVE_GRIM_AIRPLACE.getBooleanValue();
        Hand hand = swap ? Hand.OFF_HAND : Hand.MAIN_HAND;
        if (swap) swapHands(client);
        try {
            client.interactionManager.sendSequencedPacket(client.world, sequence -> {
                Printer.trackPlacement(target, checked.predicted(), stack, sequence, checked.hit());
                return new PlayerInteractBlockC2SPacket(hand, checked.hit(), sequence);
            });
        } finally {
            if (swap) swapHands(client);
        }
        Printer.addTimeout(target);
        NativeHeadRotation.placed(target);
        return true;
    }

    private static void swapHands(MinecraftClient client) {
        client.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(
                PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND, BlockPos.ORIGIN, Direction.DOWN));
    }
}
