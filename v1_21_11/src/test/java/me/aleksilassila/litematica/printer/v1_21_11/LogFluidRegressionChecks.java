package me.aleksilassila.litematica.printer.v1_21_11;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.v1_21_11.guides.interaction.LogStrippingGuide;
import me.aleksilassila.litematica.printer.v1_21_11.actions.ActionChain;
import me.aleksilassila.litematica.printer.v1_21_11.guides.placement.PropertySpecificGuesserGuide;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.PrinterPlacementContext;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.actions.InteractActionImpl;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.FluidBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;

import java.util.List;
import java.util.Map;

final class LogFluidRegressionChecks {
    private static int assertions;

    static int run(MinecraftClient client, ClientPlayerEntity player, WorldSchematic schematic,
                   Map<BlockPos, BlockState> world, BlockPos target) {
        assertions = 0;
        var inventory = player.getInventory();
        int selected = inventory.selectedSlot;
        ItemStack axeSlot = inventory.main.get(6);
        ItemStack logSlot = inventory.main.get(7);
        ItemStack finishedSlot = inventory.main.get(8);
        BlockState oldTarget = world.get(target);
        BlockPos support = target.down();
        BlockState oldSupport = world.get(support);
        boolean strip = LitematicaMixinMod.STRIP_LOGS.getBooleanValue();
        boolean replace = LitematicaMixinMod.REPLACE_FLUIDS_SOURCE_BLOCKS.getBooleanValue();
        try {
            LitematicaMixinMod.STRIP_LOGS.setBooleanValue(true);
            ItemStack axe = new ItemStack(Items.COPPER_AXE);
            axe.setDamage(4);
            inventory.main.set(6, axe);
            inventory.main.set(7, new ItemStack(Items.SPRUCE_LOG));
            inventory.main.set(8, ItemStack.EMPTY);
            for (Direction.Axis axis : Direction.Axis.values()) {
                BlockState desired = Blocks.STRIPPED_SPRUCE_LOG.getDefaultState().with(Properties.AXIS, axis);
                BlockState raw = Blocks.SPRUCE_LOG.getDefaultState().with(Properties.AXIS, axis);
                check(LogStrippingGuide.placementTarget(player, desired) == raw, "Wrong first-stage log or axis");
                world.remove(target);
                BlockPos logSupport = switch (axis) {
                    case X -> target.west();
                    case Y -> target.down();
                    case Z -> target.south();
                };
                world.put(logSupport, Blocks.STONE.getDefaultState());
                var staged = new SchematicBlockState(client.world, schematic, target)
                        .withTarget(LogStrippingGuide.placementTarget(player, desired));
                var placement = new PropertySpecificGuesserGuide(staged);
                check(placement.canExecute(player), "Legacy supported placement did not solve staged log axis " + axis);
                check(Blocks.SPRUCE_LOG.getPlacementState(placement.getPlacementContext(player)) == raw,
                        "Legacy placement would use the wrong log axis");
                LitematicaMixinMod.REPLACE_FLUIDS_SOURCE_BLOCKS.setBooleanValue(true);
                ActionChain chain = (ActionChain) placement.execute(player).getFirst();
                check(chain.getActionsNextTick().getFirst().send(client, player), "Could not select staged raw log");
                var context = placement.getPlacementContext(player);
                check(context.canSendPlacement(player, net.minecraft.util.Hand.MAIN_HAND, context.hitResult),
                        "Rejected a valid supported raw-log placement");
                world.remove(logSupport);
                check(!chain.getActionsNextTick().getLast().send(client, player),
                        "Vanished support redirected a staged log to the wrong coordinate");
                world.put(target, raw);
                var state = new SchematicBlockState(client.world, schematic, target).withTarget(desired);
                check(new LogStrippingGuide(state).canExecute(player), "Existing correctly oriented log was not strippable");
                world.put(target, Blocks.BIRCH_LOG.getDefaultState().with(Properties.AXIS, axis));
                state = new SchematicBlockState(client.world, schematic, target).withTarget(desired);
                check(!new LogStrippingGuide(state).canExecute(player), "Stripped unrelated wood");
            }
            BlockState desired = Blocks.STRIPPED_SPRUCE_LOG.getDefaultState();
            inventory.main.set(8, new ItemStack(Items.STRIPPED_SPRUCE_LOG));
            check(LogStrippingGuide.placementTarget(player, desired) == desired, "Did not prefer available finished material");
            inventory.main.set(8, ItemStack.EMPTY);
            LitematicaMixinMod.STRIP_LOGS.setBooleanValue(false);
            check(LogStrippingGuide.placementTarget(player, desired) == desired, "Strip-logs off used a substitute log");
            world.put(target, Blocks.SPRUCE_LOG.getDefaultState());
            var state = new SchematicBlockState(client.world, schematic, target).withTarget(desired);
            check(!new LogStrippingGuide(state).canExecute(player), "Strip-logs off allowed stripping");
            LitematicaMixinMod.STRIP_LOGS.setBooleanValue(true);
            inventory.main.set(6, ItemStack.EMPTY);
            check(LogStrippingGuide.placementTarget(player, desired) == desired, "Started an unfinishable log without an axe");

            inventory.main.set(7, new ItemStack(Items.SAND));
            inventory.selectedSlot = 7;
            world.put(support, Blocks.STONE.getDefaultState());
            var supportHit = new BlockHitResult(support.toCenterPos(), Direction.UP, support, false);
            var airHit = new BlockHitResult(target.toCenterPos(), Direction.UP, target, false);
            for (Block fluid : List.of(Blocks.WATER, Blocks.LAVA)) {
                BlockState source = fluid.getDefaultState();
                LitematicaMixinMod.REPLACE_FLUIDS_SOURCE_BLOCKS.setBooleanValue(false);
                for (int level = 0; level <= 15; level++) {
                    world.put(target, source.with(FluidBlock.LEVEL, level));
                    var supported = new PrinterPlacementContext(player, supportHit, player.getMainHandStack(), 7);
                    var air = new PrinterPlacementContext(player, airHit, player.getMainHandStack(), 7);
                    air.isAirPlace = true;
                    check(supported.canPlace() == (level > 0), "Wrong fluid level policy for supporting click: " + level);
                    check(air.canPlace() == (level > 0), "Wrong fluid level policy for airplace: " + level);
                }
                var queued = new PrinterPlacementContext(player, supportHit, player.getMainHandStack(), 7);
                check(queued.canPlace(), "Could not prepare placement over flowing fluid");
                world.put(target, source);
                check(!new InteractActionImpl(queued).send(client, player), "Queued support click consumed new fluid source");
                LitematicaMixinMod.REPLACE_FLUIDS_SOURCE_BLOCKS.setBooleanValue(true);
                check(queued.canPlace(), "Enabled fluid replacement rejected source");
            }
            LitematicaMixinMod.REPLACE_FLUIDS_SOURCE_BLOCKS.setBooleanValue(false);
            // Scaffolding can extend through an existing tower to a different coordinate.
            world.put(target, Blocks.SCAFFOLDING.getDefaultState());
            world.put(target.up(), Blocks.WATER.getDefaultState());
            inventory.main.set(7, new ItemStack(Items.SCAFFOLDING));
            var side = new BlockHitResult(target.toCenterPos(), Direction.EAST, target, false);
            var tower = new PrinterPlacementContext(player, side, player.getMainHandStack(), 7);
            check(Items.SCAFFOLDING instanceof net.minecraft.item.BlockItem item
                            && item.getPlacementContext(tower).getBlockPos().equals(target.up()),
                    "Scaffolding fixture did not redirect into the water source");
            check(!tower.canSendPlacement(player, net.minecraft.util.Hand.MAIN_HAND, side),
                    "Redirected scaffolding consumed a protected source");
            world.remove(target.up());
            check(BlockHelper.canReplaceFluid(Blocks.OAK_SLAB.getDefaultState().with(Properties.WATERLOGGED, true)),
                    "Waterlogged block was mistaken for a standalone source");
        } finally {
            if (oldTarget == null) world.remove(target); else world.put(target, oldTarget);
            if (oldSupport == null) world.remove(support); else world.put(support, oldSupport);
            inventory.selectedSlot = selected;
            inventory.main.set(6, axeSlot);
            inventory.main.set(7, logSlot);
            inventory.main.set(8, finishedSlot);
            LitematicaMixinMod.STRIP_LOGS.setBooleanValue(strip);
            LitematicaMixinMod.REPLACE_FLUIDS_SOURCE_BLOCKS.setBooleanValue(replace);
        }
        return assertions;
    }

    private static void check(boolean result, String message) {
        assertions++;
        if (!result) throw new AssertionError(message);
    }
}
