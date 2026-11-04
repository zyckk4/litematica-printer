package me.aleksilassila.litematica.printer.v1_21_4.guides.placement;

import me.aleksilassila.litematica.printer.v1_21_4.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.v1_21_4.NativePlacementSolver;
import me.aleksilassila.litematica.printer.v1_21_4.SchematicBlockState;
import me.aleksilassila.litematica.printer.v1_21_4.actions.Action;
import me.aleksilassila.litematica.printer.v1_21_4.actions.ActionChain;
import me.aleksilassila.litematica.printer.v1_21_4.actions.NativePlacementAction;
import me.aleksilassila.litematica.printer.v1_21_4.actions.PrepareAction;
import me.aleksilassila.litematica.printer.v1_21_4.guides.Guide;
import me.aleksilassila.litematica.printer.v1_21_4.implementation.PrinterPlacementContext;
import net.minecraft.block.BlockState;
import net.minecraft.block.MultifaceBlock;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** Adds one item to an existing stack or half slab using vanilla placement rules. */
public class BlockReplacementGuide extends Guide {
    private List<Action> prepared;

    public BlockReplacementGuide(SchematicBlockState state) {
        super(state);
    }

    @Override
    protected @NotNull List<ItemStack> getRequiredItems() {
        return List.of(new ItemStack(targetState.getBlock()));
    }

    @Override
    public boolean canExecute(ClientPlayerEntity player) {
        prepared = prepare(player);
        return !prepared.isEmpty();
    }

    @Override
    public @NotNull List<Action> execute(ClientPlayerEntity player) {
        return prepared != null ? prepared : prepare(player);
    }

    private List<Action> prepare(ClientPlayerEntity player) {
        if (!NativePlacementSolver.canAddToStack(currentState, targetState)
                && !NativePlacementSolver.canCompleteSlab(currentState, targetState)
                && !NativePlacementSolver.canAddFaces(currentState, targetState)) return List.of();
        ItemStack stack = getRequiredItem(player).stream().findFirst().orElse(ItemStack.EMPTY);
        if (stack.isEmpty()) return List.of();
        BlockState next = NativePlacementSolver.placementTarget(state.world, state.schematic, state.blockPos, targetState);
        var solution = NativePlacementSolver.solveAnyRotation(state.blockPos, next, stack,
                LitematicaMixinMod.PRINTING_RANGE.getDoubleValue());
        if (solution == null) return List.of();
        var context = new PrinterPlacementContext(player, solution.hit(), stack, getRequiredItemStackSlot(player));
        ActionChain chain = new ActionChain();
        chain.addImmediateAction(new PrepareAction(context));
        BlockState actionTarget = next.getBlock() instanceof MultifaceBlock ? solution.predicted() : next;
        chain.addImmediateAction(new NativePlacementAction(state.blockPos, actionTarget, stack, solution));
        return List.of(chain);
    }
}
