package me.aleksilassila.litematica.printer.v1_21_11;

import net.minecraft.block.*;
import net.minecraft.item.Item;
import net.minecraft.item.Items;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

abstract public class BlockHelper {
    /** The original setting protects still water/lava, while flowing fluids remain replaceable. */
    public static boolean canReplaceFluid(BlockState current) {
        return !(current.getBlock() instanceof FluidBlock) || !current.getFluidState().isStill()
                || LitematicaMixinMod.REPLACE_FLUIDS_SOURCE_BLOCKS.getBooleanValue();
    }

    public static List<Class<?>> interactiveBlocks = new ArrayList<>(Arrays.asList(
            AbstractChestBlock.class, AbstractFurnaceBlock.class, CraftingTableBlock.class, LeverBlock.class,
            DoorBlock.class, TrapdoorBlock.class, BedBlock.class, RedstoneWireBlock.class, ScaffoldingBlock.class,
            HopperBlock.class, EnchantingTableBlock.class, NoteBlock.class, JukeboxBlock.class, CakeBlock.class,
            FenceGateBlock.class, BrewingStandBlock.class, DragonEggBlock.class, CommandBlock.class,
            BeaconBlock.class, AnvilBlock.class, ComparatorBlock.class, RepeaterBlock.class,
            DropperBlock.class, DispenserBlock.class, ShulkerBoxBlock.class, LecternBlock.class,
            FlowerPotBlock.class, BarrelBlock.class, BellBlock.class, SmithingTableBlock.class,
            LoomBlock.class, CartographyTableBlock.class, GrindstoneBlock.class,
            StonecutterBlock.class, AbstractSignBlock.class, AbstractCandleBlock.class));

    public static final Item[] SHOVEL_ITEMS = new Item[]{
            Items.NETHERITE_SHOVEL,
            Items.DIAMOND_SHOVEL,
            Items.GOLDEN_SHOVEL,
            Items.IRON_SHOVEL,
            Items.COPPER_SHOVEL,
            Items.STONE_SHOVEL,
            Items.WOODEN_SHOVEL
    };
}
