package me.aleksilassila.litematica.printer.v1_21_11;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_11.guides.placement.GeneralPlacementGuide;
import me.aleksilassila.litematica.printer.v1_21_11.guides.placement.PropertySpecificGuesserGuide;
import me.aleksilassila.litematica.printer.v1_21_11.guides.placement.TorchGuide;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.PrinterPlacementContext;
import me.aleksilassila.litematica.printer.v1_21_11.mixin.MixinAccessorClientPlayerEntity;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Replays the actual item, rather than the desired block, for all five torch mounts. */
final class TorchPlacementChecks {
    private static final BlockPos TARGET = new BlockPos(0, 64, 2);
    private static int assertions;

    static int run(MinecraftClient client, Map<BlockPos, BlockState> blocks, Unsafe unsafe) throws Exception {
        assertions = 0;
        Field inventoryField = PlayerEntity.class.getDeclaredField("inventory");
        inventoryField.setAccessible(true);
        Object previousInventory = inventoryField.get(client.player);
        Field heldField = client.player.getClass().getDeclaredField("heldStack");
        heldField.setAccessible(true);
        Object previousHeld = heldField.get(client.player);
        PlayerInventory inventory = new PlayerInventory(client.player, new net.minecraft.entity.EntityEquipment());
        boolean airplace = PrinterConfig.PRINTER_AIRPLACE.getBooleanValue();
        boolean ignoreRotation = PrinterConfig.PRINTER_IGNORE_ROTATION.getBooleanValue();
        boolean raycast = PrinterConfig.RAYCAST.getBooleanValue();
        boolean strictFace = PrinterConfig.STRICT_BLOCK_FACE_CHECK.getBooleanValue();
        int inactivity = Printer.inactivityCounter;
        float yaw = client.player.getYaw();
        float pitch = client.player.getPitch();
        MixinAccessorClientPlayerEntity sent = (MixinAccessorClientPlayerEntity) client.player;
        float sentYaw = sent.getLastYaw();
        float sentPitch = sent.getLastPitch();
        try {
            inventoryField.set(client.player, inventory);
            PrinterConfig.RAYCAST.setBooleanValue(false);
            PrinterConfig.STRICT_BLOCK_FACE_CHECK.setBooleanValue(false);
            Printer.inactivityCounter = PrinterConfig.PRINTER_MIN_INACTIVE_TIME_AIR_PLACE.getIntegerValue() + 1;
            Schematic schematic = (Schematic) unsafe.allocateInstance(Schematic.class);
            Block[][] families = {{Blocks.TORCH, Blocks.WALL_TORCH},
                    {Blocks.SOUL_TORCH, Blocks.SOUL_WALL_TORCH},
                    {Blocks.REDSTONE_TORCH, Blocks.REDSTONE_WALL_TORCH}};
            for (Block[] family : families) {
                ItemStack material = new ItemStack(family[0]);
                inventory.main.set(0, material);
                heldField.set(client.player, material);
                List<BlockState> variants = new ArrayList<>();
                variants.add(family[0].getDefaultState());
                for (Direction facing : Direction.Type.HORIZONTAL) {
                    variants.add(family[1].getDefaultState().with(WallTorchBlock.FACING, facing));
                }
                for (boolean ignore : new boolean[]{false, true}) {
                    PrinterConfig.PRINTER_IGNORE_ROTATION.setBooleanValue(ignore);
                    for (BlockState desired : variants) {
                        schematic.desired = desired;
                        for (boolean air : new boolean[]{false, true}) {
                            PrinterConfig.PRINTER_AIRPLACE.setBooleanValue(air);
                            for (boolean fallback : new boolean[]{false, true}) {
                                for (float[] camera : new float[][]{{23, -51}, {-90, 75}, {179, -30}}) {
                                    fillSupports(blocks);
                                    client.player.setYaw(camera[0]);
                                    client.player.setPitch(camera[1]);
                                    SchematicBlockState state = new SchematicBlockState(client.world, schematic, TARGET);
                                    GeneralPlacementGuide guide = fallback ? new PropertySpecificGuesserGuide(state) : new TorchGuide(state);
                                    check(guide.canExecute(client.player), "Torch guide cannot place " + desired
                                            + " air=" + air + " fallback=" + fallback + " ignore=" + ignore);
                                    PrinterPlacementContext context = guide.getPlacementContext(client.player);
                                    check(context != null && context.getBlockPos().equals(TARGET), "Torch guide changed destination");
                                    applySentRotation(client, context);
                                    BlockHitResult hit = context.isAirPlace ? new BlockHitResult(context.hitResult.getPos(),
                                            context.hitResult.getSide(), context.getBlockPos(), false) : context.hitResult;
                                    BlockItem item = (BlockItem) material.getItem();
                                    ItemPlacementContext wire = item.getPlacementContext(new ItemPlacementContext(
                                            client.player, Hand.MAIN_HAND, material, hit));
                                    BlockState placed = wire == null ? null : item.getPlacementState(wire);
                                    check(exactAttachment(desired, placed), "Actual torch item chose a different mount: wanted "
                                            + desired + ", got " + placed + ", air=" + air + " fallback=" + fallback);
                                    check(context.canSendPlacement(client.player, Hand.MAIN_HAND, hit),
                                            "Unchanged torch context was rejected at send time");
                                    float preparedYaw = sent.getLastYaw();
                                    float preparedPitch = sent.getLastPitch();
                                    client.player.setYaw(preparedYaw + 180);
                                    client.player.setPitch(-preparedPitch);
                                    check(context.canSendPlacement(client.player, Hand.MAIN_HAND, hit),
                                            "Local camera replaced the actual sent torch angle");
                                    if (air) {
                                        sent.setLastYaw(0);
                                        sent.setLastPitch(desired.contains(WallTorchBlock.FACING) ? 90 : 0);
                                        check(!context.canSendPlacement(client.player, Hand.MAIN_HAND, hit),
                                                "Queued torch accepted a changed server angle that selects another mount");
                                        sent.setLastYaw(preparedYaw);
                                        sent.setLastPitch(preparedPitch);
                                    }
                                    blocks.remove(TARGET.offset(supportDirection(desired)));
                                    check(!context.canSendPlacement(client.player, Hand.MAIN_HAND, hit),
                                            "Queued torch placement fell back to another support after its support disappeared: " + desired);
                                }
                            }
                        }
                        fillSupports(blocks);
                        client.player.setYaw(23);
                        client.player.setPitch(-51);
                        var stationary = NativePlacementSolver.solveStationary(TARGET, desired, material, 4.5, true);
                        check(stationary != null && exactAttachment(desired, stationary.predicted()),
                                "Native torch solver changed attachment with ignoreRotation=" + ignore + ": " + desired);
                        var moving = NativePlacementSolver.solveMoving(TARGET, desired, material, 4.5, true, false);
                        check(moving != null && exactAttachment(desired, moving.predicted()),
                                "Moving torch solver changed attachment with ignoreRotation=" + ignore + ": " + desired);
                        blocks.remove(TARGET.offset(supportDirection(desired)));
                        check(NativePlacementSolver.revalidate(TARGET, desired, material, stationary, 4.5) == null,
                                "Native torch revalidation fell back to another support: " + desired);
                    }
                }
            }
        } finally {
            blocks.clear();
            inventoryField.set(client.player, previousInventory);
            heldField.set(client.player, previousHeld);
            PrinterConfig.PRINTER_AIRPLACE.setBooleanValue(airplace);
            PrinterConfig.PRINTER_IGNORE_ROTATION.setBooleanValue(ignoreRotation);
            PrinterConfig.RAYCAST.setBooleanValue(raycast);
            PrinterConfig.STRICT_BLOCK_FACE_CHECK.setBooleanValue(strictFace);
            Printer.inactivityCounter = inactivity;
            client.player.setYaw(yaw);
            client.player.setPitch(pitch);
            sent.setLastYaw(sentYaw);
            sent.setLastPitch(sentPitch);
        }
        return assertions;
    }

    private static void fillSupports(Map<BlockPos, BlockState> blocks) {
        blocks.clear();
        for (Direction side : Direction.values()) blocks.put(TARGET.offset(side), Blocks.STONE.getDefaultState());
    }

    private static Direction supportDirection(BlockState state) {
        return state.contains(WallTorchBlock.FACING) ? state.get(WallTorchBlock.FACING).getOpposite() : Direction.DOWN;
    }

    private static boolean exactAttachment(BlockState desired, BlockState actual) {
        return actual != null && actual.isOf(desired.getBlock())
                && supportDirection(desired) == supportDirection(actual);
    }

    private static void applySentRotation(MinecraftClient client, PrinterPlacementContext context) {
        if (context.isAirPlace && context.lookDirection != null) {
            client.player.setYaw(switch (context.lookDirection) {
                case NORTH -> 180; case SOUTH, UP, DOWN -> 0; case WEST -> 90; case EAST -> -90;
            });
            client.player.setPitch(context.lookDirection == Direction.UP ? -90
                    : context.lookDirection == Direction.DOWN ? 90 : 0);
        } else if (context.canStealth) {
            var delta = context.hitResult.getPos().subtract(client.player.getEyePos());
            client.player.setYaw((float) Math.toDegrees(-Math.atan2(delta.x, delta.z)));
            client.player.setPitch((float) Math.toDegrees(-Math.asin(delta.y / delta.length())));
        }
        MixinAccessorClientPlayerEntity sent = (MixinAccessorClientPlayerEntity) client.player;
        sent.setLastYaw(client.player.getYaw());
        sent.setLastPitch(client.player.getPitch());
    }

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
