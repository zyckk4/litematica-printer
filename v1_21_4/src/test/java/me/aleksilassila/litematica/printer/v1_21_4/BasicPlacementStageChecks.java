package me.aleksilassila.litematica.printer.v1_21_4;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.v1_21_4.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_4.guides.placement.BlockIndifferentGuesserGuide;
import me.aleksilassila.litematica.printer.v1_21_4.guides.placement.FarmlandGuide;
import me.aleksilassila.litematica.printer.v1_21_4.guides.placement.FlowerPotGuide;
import me.aleksilassila.litematica.printer.v1_21_4.guides.placement.GeneralPlacementGuide;
import me.aleksilassila.litematica.printer.v1_21_4.guides.placement.PropertySpecificGuesserGuide;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FarmlandBlock;
import net.minecraft.block.FlowerPotBlock;
import net.minecraft.block.MultifaceBlock;
import net.minecraft.block.TurtleEggBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Regressions for placing the ordinary block before a later transformation. */
final class BasicPlacementStageChecks {
    private static final BlockPos TARGET = new BlockPos(0, 64, 2);
    private static int assertions;

    static int run(MinecraftClient client, Map<BlockPos, BlockState> blocks, Unsafe unsafe) throws Exception {
        assertions = 0;
        Field inventoryField = PlayerEntity.class.getDeclaredField("inventory");
        inventoryField.setAccessible(true);
        Object previousInventory = inventoryField.get(client.player);
        PlayerInventory inventory = new PlayerInventory(client.player);
        boolean raycast = PrinterConfig.RAYCAST.getBooleanValue();
        boolean airplace = PrinterConfig.PRINTER_AIRPLACE.getBooleanValue();
        int inactivity = Printer.inactivityCounter;
        try {
            inventoryField.set(client.player, inventory);
            PrinterConfig.RAYCAST.setBooleanValue(false);
            Printer.inactivityCounter = PrinterConfig.PRINTER_MIN_INACTIVE_TIME_AIR_PLACE.getIntegerValue() + 1;
            Schematic schematic = (Schematic) unsafe.allocateInstance(Schematic.class);
            Printer printer = new Printer(client, client.player);
            Method candidate = Printer.class.getDeclaredMethod("isNativePlacementCandidate", SchematicBlockState.class, Set.class);
            candidate.setAccessible(true);
            for (Case example : List.of(
                    new Case(Blocks.FARMLAND.getDefaultState().with(FarmlandBlock.MOISTURE, 7), Blocks.DIRT),
                    new Case(Blocks.POTTED_DANDELION.getDefaultState(), Blocks.FLOWER_POT),
                    new Case(Blocks.POTTED_BAMBOO.getDefaultState(), Blocks.FLOWER_POT),
                    new Case(Blocks.WATER_CAULDRON.getDefaultState().with(Properties.LEVEL_3, 3), Blocks.CAULDRON),
                    new Case(Blocks.LAVA_CAULDRON.getDefaultState(), Blocks.CAULDRON),
                    new Case(Blocks.POWDER_SNOW_CAULDRON.getDefaultState().with(Properties.LEVEL_3, 3), Blocks.CAULDRON),
                    new Case(Blocks.CANDLE_CAKE.getDefaultState(), Blocks.CAKE),
                    new Case(Blocks.BLUE_CANDLE_CAKE.getDefaultState().with(Properties.LIT, true), Blocks.CAKE))) {
                schematic.desired = example.desired;
                blocks.clear();
                blocks.put(TARGET.down(), Blocks.STONE.getDefaultState());
                ItemStack material = new ItemStack(example.base);
                inventory.main.set(0, material);
                SchematicBlockState empty = new SchematicBlockState(client.world, schematic, TARGET);
                check((boolean) candidate.invoke(printer, empty, Set.of()), "Initial stage not a native candidate: " + example.desired);
                BlockState stage = NativePlacementSolver.placementTarget(client.world, schematic, TARGET, example.desired);
                check(stage.isOf(example.base), "Wrong initial stage for " + example.desired);
                var solution = NativePlacementSolver.solveStationary(TARGET, stage, material, 4.5, false);
                check(solution != null && solution.predicted().isOf(example.base), "Native stage cannot be placed: " + example.desired);
                for (boolean air : new boolean[]{false, true}) {
                    PrinterConfig.PRINTER_AIRPLACE.setBooleanValue(air);
                    GeneralPlacementGuide guide = guide(empty);
                    check(guide.canExecute(client.player), "Legacy stage cannot start (air=" + air + "): " + example.desired);
                    var context = guide.getPlacementContext(client.player);
                    check(context != null && context.getBlockPos().equals(TARGET) && context.getStack().isOf(example.base.asItem()),
                            "Legacy stage chose another target or item: " + example.desired);
                }
                inventory.main.set(0, ItemStack.EMPTY);
                check(!guide(empty).canExecute(client.player), "Stage accepted unavailable material: " + example.desired);
                inventory.main.set(0, material);
                for (BlockState occupied : List.of(example.base.getDefaultState(), example.desired, Blocks.STONE.getDefaultState())) {
                    blocks.put(TARGET, occupied);
                    SchematicBlockState cell = new SchematicBlockState(client.world, schematic, TARGET);
                    check(!(boolean) candidate.invoke(printer, cell, Set.of()), "Native stage tried to overwrite " + occupied);
                    GeneralPlacementGuide guide = guide(cell);
                    check(!guide.canExecute(client.player) && guide.getPlacementContext(client.player) == null,
                            "Legacy stage tried to overwrite " + occupied);
                }
            }
            checkTillableAlternatives(client, blocks, schematic, inventory);
            checkDeferredLegacyState(client, blocks, schematic, inventory);
            checkLegacyIncrementalStarts(client, blocks, schematic, inventory);
        } finally {
            blocks.clear();
            inventoryField.set(client.player, previousInventory);
            PrinterConfig.RAYCAST.setBooleanValue(raycast);
            PrinterConfig.PRINTER_AIRPLACE.setBooleanValue(airplace);
            Printer.inactivityCounter = inactivity;
        }
        return assertions;
    }

    private static void checkTillableAlternatives(MinecraftClient client, Map<BlockPos, BlockState> blocks,
                                                 Schematic schematic, PlayerInventory inventory) {
        schematic.desired = Blocks.FARMLAND.getDefaultState();
        PrinterConfig.PRINTER_AIRPLACE.setBooleanValue(false);
        for (Block material : FarmlandGuide.TILLABLE_BLOCKS) {
            blocks.clear();
            blocks.put(TARGET.down(), Blocks.STONE.getDefaultState());
            inventory.main.set(0, new ItemStack(material));
            GeneralPlacementGuide guide = guide(new SchematicBlockState(client.world, schematic, TARGET));
            check(guide.canExecute(client.player), "Legacy lost a supported tillable material: " + material);
            check(guide.getPlacementContext(client.player).getStack().isOf(material.asItem()), "Wrong tillable material selected");
        }
    }

    private static void checkDeferredLegacyState(MinecraftClient client, Map<BlockPos, BlockState> blocks,
                                                 Schematic schematic, PlayerInventory inventory) {
        blocks.clear();
        blocks.put(TARGET.down(), Blocks.STONE.getDefaultState());
        schematic.desired = Blocks.CAKE.getDefaultState().with(Properties.BITES, 4);
        inventory.main.set(0, new ItemStack(Blocks.CAKE));
        check(guide(new SchematicBlockState(client.world, schematic, TARGET)).canExecute(client.player),
                "Legacy demanded an already-eaten cake before placing it");
        schematic.desired = Blocks.DAYLIGHT_DETECTOR.getDefaultState().with(Properties.INVERTED, true).with(Properties.POWER, 13);
        inventory.main.set(0, new ItemStack(Blocks.DAYLIGHT_DETECTOR));
        check(guide(new SchematicBlockState(client.world, schematic, TARGET)).canExecute(client.player),
                "Legacy demanded final detector mode/power before placing it");
    }

    private static GeneralPlacementGuide guide(SchematicBlockState state) {
        if (state.targetState.getBlock() instanceof FarmlandBlock) return new FarmlandGuide(state);
        if (state.targetState.getBlock() instanceof FlowerPotBlock) return new FlowerPotGuide(state);
        return new PropertySpecificGuesserGuide(state);
    }

    private static void checkLegacyIncrementalStarts(MinecraftClient client, Map<BlockPos, BlockState> blocks,
                                                     Schematic schematic, PlayerInventory inventory) {
        boolean exact = PrinterConfig.PRINTER_ALLOW_NONE_EXACT_STATES.getBooleanValue();
        try {
            PrinterConfig.PRINTER_ALLOW_NONE_EXACT_STATES.setBooleanValue(false);
            for (boolean air : new boolean[]{false, true}) {
                PrinterConfig.PRINTER_AIRPLACE.setBooleanValue(air);
                for (int count = 2; count <= 4; count++) {
                    blocks.clear();
                    blocks.put(TARGET.down(), Blocks.STONE.getDefaultState());
                    schematic.desired = Blocks.TURTLE_EGG.getDefaultState().with(TurtleEggBlock.EGGS, count);
                    inventory.main.set(0, new ItemStack(Blocks.TURTLE_EGG));
                    GeneralPlacementGuide guide = guide(new SchematicBlockState(client.world, schematic, TARGET));
                    check(guide.canExecute(client.player), "Legacy could not start " + count + " turtle eggs (air=" + air + ")");
                    var context = guide.getPlacementContext(client.player);
                    BlockState placed = Blocks.TURTLE_EGG.getPlacementState(context);
                    check(placed != null && placed.get(TurtleEggBlock.EGGS) == 1,
                            "Legacy egg stage did not represent one use");
                }
                blocks.clear();
                blocks.put(TARGET.north(), Blocks.STONE.getDefaultState());
                blocks.put(TARGET.east(), Blocks.STONE.getDefaultState());
                schematic.desired = Blocks.GLOW_LICHEN.getDefaultState()
                        .with(MultifaceBlock.getProperty(Direction.NORTH), true)
                        .with(MultifaceBlock.getProperty(Direction.EAST), true);
                inventory.main.set(0, new ItemStack(Blocks.GLOW_LICHEN));
                GeneralPlacementGuide guide = new BlockIndifferentGuesserGuide(new SchematicBlockState(client.world, schematic, TARGET));
                check(guide.canExecute(client.player), "Legacy could not start a two-face lichen (air=" + air + ")");
                BlockState first = Blocks.GLOW_LICHEN.getPlacementState(guide.getPlacementContext(client.player));
                check(first != null && NativePlacementSolver.canAddFaces(first, schematic.desired),
                        "Legacy lichen stage introduced an unplanned face or completed more than one use");
                int faces = 0;
                for (Direction direction : Direction.values()) {
                    if (first.get(MultifaceBlock.getProperty(direction))) faces++;
                }
                check(faces == 1, "Legacy lichen stage must add exactly one planned face");
                if (!air) {
                    check(first.get(MultifaceBlock.getProperty(Direction.EAST)),
                            "Legacy did not use the only player-facing support at the first stage");
                }
            }
        } finally {
            PrinterConfig.PRINTER_ALLOW_NONE_EXACT_STATES.setBooleanValue(exact);
        }
    }

    private record Case(BlockState desired, Block base) {}

    private static final class Schematic extends WorldSchematic {
        private BlockState desired;
        private Schematic() { super(null, null, null, null); }
        @Override public BlockState getBlockState(BlockPos pos) {
            return TARGET.equals(pos) ? desired : Blocks.AIR.getDefaultState();
        }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
