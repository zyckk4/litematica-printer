package me.aleksilassila.litematica.printer.v1_21_11;

import net.minecraft.block.AbstractCauldronBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CandleCakeBlock;
import net.minecraft.block.CaveVinesBodyBlock;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.block.FlowerPotBlock;

/** The ordinary block item needed before a separate transformation or interaction. */
public final class PlacementStages {
    private PlacementStages() {}

    /** Only use this seed when the target cell is still replaceable. */
    public static BlockState initialTarget(BlockState desired) {
        if (desired.getBlock() instanceof FarmlandBlock) return Blocks.DIRT.getDefaultState();
        if (desired.getBlock() instanceof FlowerPotBlock && !desired.isOf(Blocks.FLOWER_POT)) {
            return Blocks.FLOWER_POT.getDefaultState();
        }
        if (desired.getBlock() instanceof AbstractCauldronBlock && !desired.isOf(Blocks.CAULDRON)) {
            return Blocks.CAULDRON.getDefaultState();
        }
        if (desired.getBlock() instanceof CandleCakeBlock) return Blocks.CAKE.getDefaultState();
        if (desired.getBlock() instanceof CaveVinesBodyBlock) return Blocks.CAVE_VINES.getStateWithProperties(desired);
        return desired;
    }
}
