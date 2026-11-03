package me.aleksilassila.litematica.printer.v1_21_4.guides.interaction;

import me.aleksilassila.litematica.printer.v1_21_4.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.v1_21_4.Printer;
import me.aleksilassila.litematica.printer.v1_21_4.SchematicBlockState;
import me.aleksilassila.litematica.printer.v1_21_4.mixin.AxeItemAccessor;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.AxeItem;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Map;

public class LogStrippingGuide extends InteractionGuide {
    private static final Map<Block, Block> STRIPPED_BLOCKS = AxeItemAccessor.getStrippedBlocks();

    public LogStrippingGuide(SchematicBlockState state) {
        super(state);
    }

    /** Prefer the finished block. When it is missing, place the matching unstripped block on its final axis. */
    public static BlockState placementTarget(ClientPlayerEntity player, BlockState desired) {
        if (!LitematicaMixinMod.STRIP_LOGS.getBooleanValue() || !desired.contains(Properties.AXIS)
                || !STRIPPED_BLOCKS.containsValue(desired.getBlock())
                || Printer.inventoryManager.hasMaterial(new ItemStack(desired.getBlock()))
                || availableAxe(player).isEmpty()) return desired;
        for (var entry : STRIPPED_BLOCKS.entrySet()) {
            if (entry.getValue() == desired.getBlock() && Printer.inventoryManager.hasMaterial(new ItemStack(entry.getKey()))) {
                return entry.getKey().getStateWithProperties(desired);
            }
        }
        return desired;
    }

    private static ItemStack availableAxe(ClientPlayerEntity player) {
        // Vanilla gives a blocking offhand priority over a main-hand axe unless the player sneaks.
        if (player.getOffHandStack().isOf(Items.SHIELD) && !player.shouldCancelInteraction()) return ItemStack.EMPTY;
        for (ItemStack stack : player.getInventory().main) {
            if (stack.getItem() instanceof AxeItem && Printer.inventoryManager.hasMaterial(stack)) return stack;
        }
        return player.getAbilities().creativeMode ? Items.DIAMOND_AXE.getDefaultStack() : ItemStack.EMPTY;
    }

    @Override
    public boolean canExecute(ClientPlayerEntity player) {
        return LitematicaMixinMod.STRIP_LOGS.getBooleanValue()
                && STRIPPED_BLOCKS.get(currentState.getBlock()) == targetState.getBlock()
                && statesEqual(targetState.getBlock().getStateWithProperties(currentState), targetState)
                && playerHasRightItem(player);
    }

    @Override
    protected @NotNull List<ItemStack> getRequiredItems() {
        ItemStack axe = availableAxe(MinecraftClient.getInstance().player);
        return axe.isEmpty() ? List.of() : List.of(axe);
    }
}
