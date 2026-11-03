package me.aleksilassila.litematica.printer.v1_21_4;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.v1_21_4.actions.ActionChain;
import me.aleksilassila.litematica.printer.v1_21_4.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_4.guides.placement.BlockReplacementGuide;
import me.aleksilassila.litematica.printer.v1_21_4.guides.placement.PropertySpecificGuesserGuide;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.CandleBlock;
import net.minecraft.block.SeaPickleBlock;
import net.minecraft.block.SnowBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;

/** Normal candidate, guide and BlockItem simulation on a map-backed Minecraft world. */
final class StackedPlacementChecks {
    private static final BlockPos TARGET = new BlockPos(0, 64, 2);
    private static int assertions;

    static int run(MinecraftClient client, Map<BlockPos, BlockState> blocks, Unsafe unsafe) throws Exception {
        assertions = 0;
        PlayerInventory inventory = new PlayerInventory(client.player);
        Field playerInventory = PlayerEntity.class.getDeclaredField("inventory");
        playerInventory.setAccessible(true);
        Object previous = playerInventory.get(client.player);
        boolean raycast = PrinterConfig.RAYCAST.getBooleanValue();
        boolean strictRaycast = PrinterConfig.RAYCAST_STRICT_BLOCK_HIT.getBooleanValue();
        boolean strictFace = PrinterConfig.STRICT_BLOCK_FACE_CHECK.getBooleanValue();
        boolean airPlace = PrinterConfig.PRINTER_AIRPLACE.getBooleanValue();
        try {
            playerInventory.set(client.player, inventory);
            PrinterConfig.RAYCAST.setBooleanValue(true);
            PrinterConfig.RAYCAST_STRICT_BLOCK_HIT.setBooleanValue(true);
            PrinterConfig.STRICT_BLOCK_FACE_CHECK.setBooleanValue(true);
            PrinterConfig.PRINTER_AIRPLACE.setBooleanValue(false);
            Schematic schematic = (Schematic) unsafe.allocateInstance(Schematic.class);
            Printer printer = new Printer(client, client.player);
            Method candidate = Printer.class.getDeclaredMethod("isNativePlacementCandidate", SchematicBlockState.class, Set.class);
            candidate.setAccessible(true);
            checkStages(client, blocks, schematic, printer, candidate, Blocks.SNOW, SnowBlock.LAYERS, 8);
            checkStages(client, blocks, schematic, printer, candidate, Blocks.BLUE_CANDLE, CandleBlock.CANDLES, 4);
            checkStages(client, blocks, schematic, printer, candidate, Blocks.SEA_PICKLE, SeaPickleBlock.PICKLES, 4);
            PrinterConfig.RAYCAST.setBooleanValue(false);
            checkRepeaterSupport(blocks);
        } finally {
            blocks.clear();
            playerInventory.set(client.player, previous);
            PrinterConfig.RAYCAST.setBooleanValue(raycast);
            PrinterConfig.RAYCAST_STRICT_BLOCK_HIT.setBooleanValue(strictRaycast);
            PrinterConfig.STRICT_BLOCK_FACE_CHECK.setBooleanValue(strictFace);
            PrinterConfig.PRINTER_AIRPLACE.setBooleanValue(airPlace);
        }
        return assertions;
    }

    private static void checkStages(MinecraftClient client, Map<BlockPos, BlockState> blocks, Schematic schematic,
                                    Printer printer, Method candidate, Block block, IntProperty count, int limit) throws Exception {
        blocks.clear();
        blocks.put(TARGET.down(), Blocks.STONE.getDefaultState());
        ItemStack one = new ItemStack(block);
        client.player.getInventory().main.set(0, one);
        schematic.desired = block.getDefaultState().with(count, limit);
        if (block instanceof CandleBlock) schematic.desired = schematic.desired.with(Properties.LIT, true);
        if (block instanceof SeaPickleBlock) schematic.desired = schematic.desired.with(Properties.WATERLOGGED, true);
        for (int placed = 0; placed <= limit; placed++) {
            SchematicBlockState cell = new SchematicBlockState(client.world, schematic, TARGET);
            check((boolean) candidate.invoke(printer, cell, Set.of()) == (placed < limit),
                    "Wrong native candidate at " + cell);
            var legacy = new BlockReplacementGuide(cell);
            check(legacy.canExecute(client.player) == (placed > 0 && placed < limit),
                    "Wrong legacy stack candidate at " + cell);
            if (placed == limit) break;
            BlockState step = NativePlacementSolver.placementTarget(client.world, schematic, TARGET, schematic.desired);
            check(step.get(count) == placed + 1, "Expected a single extra item for " + cell);
            var plan = NativePlacementSolver.solveStationary(TARGET, step, one, 4.5, false);
            check(plan != null && plan.predicted().get(count) == placed + 1, "Vanilla step rejected " + cell);
            if (placed == 0) {
                check(new PropertySpecificGuesserGuide(cell).canExecute(client.player), "Legacy first placement failed for " + cell);
            } else {
                check(plan.hit().getBlockPos().equals(TARGET) && !plan.airPlace(), "Top-up would click a neighbor " + cell);
                client.player.getInventory().main.set(0, ItemStack.EMPTY);
                check(!new BlockReplacementGuide(cell).canExecute(client.player), "Missing material accepted " + cell);
                client.player.getInventory().main.set(0, one);
                var actions = legacy.execute(client.player);
                check(actions.size() == 1 && actions.getFirst() instanceof ActionChain chain
                                && chain.getActionsCurrentTick().size() == 2,
                        "Legacy did not prepare one item and one validated top-up for " + cell);
            }
            check(NativePlacementSolver.revalidate(TARGET, step, one, plan, 4.5) != null, "Valid hit dropped " + cell);
            blocks.put(TARGET, plan.predicted());
            check(NativePlacementSolver.revalidate(TARGET, step, one, plan, 4.5) == null, "Stale hit would overfill " + cell);
        }
        schematic.desired = schematic.desired.with(count, 1);
        SchematicBlockState excess = new SchematicBlockState(client.world, schematic, TARGET);
        check(!(boolean) candidate.invoke(printer, excess, Set.of()) && !new BlockReplacementGuide(excess).canExecute(client.player),
                "Stack above target was modified " + excess);
    }

    private static void checkRepeaterSupport(Map<BlockPos, BlockState> blocks) {
        blocks.clear();
        BlockState stone = Blocks.STONE.getDefaultState();
        ItemStack item = new ItemStack(Blocks.STONE);
        blocks.put(TARGET.down(), stone);
        var valid = NativePlacementSolver.solveStationary(TARGET, stone, item, 4.5, false);
        check(valid != null && valid.hit().getBlockPos().equals(TARGET.down()), "Expected a normal support hit");
        for (Block gate : new Block[]{Blocks.REPEATER, Blocks.COMPARATOR}) {
            blocks.put(TARGET.down(), gate.getDefaultState());
            check(NativePlacementSolver.solveStationary(TARGET, stone, item, 4.5, false) == null, "Would right-click " + gate);
            check(NativePlacementSolver.revalidate(TARGET, stone, item, valid, 4.5) == null, "Queued hit would change " + gate);
            blocks.put(TARGET.east(), stone);
            var alternative = NativePlacementSolver.revalidate(TARGET, stone, item, valid, 4.5);
            check(alternative != null && alternative.hit().getBlockPos().equals(TARGET.east()),
                    "Did not use the remaining safe support next to " + gate);
            blocks.remove(TARGET.east());
        }
    }

    private static final class Schematic extends WorldSchematic {
        private BlockState desired;
        private Schematic() { super(null, null, null, null); }
        @Override public BlockState getBlockState(BlockPos pos) {
            return TARGET.equals(pos) ? desired : Blocks.AIR.getDefaultState();
        }
    }

    private static void check(boolean ok, String error) {
        assertions++;
        if (!ok) throw new AssertionError(error);
    }
}
