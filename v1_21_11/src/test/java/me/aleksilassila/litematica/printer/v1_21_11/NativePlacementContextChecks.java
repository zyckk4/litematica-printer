package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.implementation.NativePlacementContext;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import net.minecraft.SharedConstants;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.RailShape;
import net.minecraft.state.property.Properties;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.ItemUsageContext;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.Bootstrap;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Headless oracle checks against the actual Minecraft 1.21.11 methods. */
public final class NativePlacementContextChecks {
    private NativePlacementContextChecks() {}

    public static int run() throws Exception {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        // Only rotation accessors are exercised. No constructor, world, render,
        // inventory or network operation is invoked on this headless fixture.
        ClientPlayerEntity player = (ClientPlayerEntity) unsafe.allocateInstance(ClientPlayerEntity.class);
        Method order = NativePlacementContext.class.getDeclaredMethod("orderedDirections", float.class, float.class);
        order.setAccessible(true);
        List<Float> yaws = new ArrayList<>();
        for (int i = -16; i <= 16; i++) yaws.add(i * 11.25F);
        for (int i = -4; i <= 4; i++) {
            yaws.add(i * 45.0F - 0.001F);
            yaws.add(i * 45.0F + 0.001F);
        }
        float[] pitches = {-90, -75, -45.001F, -45, -44.999F, -30, -1, 0, 1, 30, 44.999F, 45, 45.001F, 75, 90};
        Block[] contextOnlyBlocks = {Blocks.STONE, Blocks.OAK_LOG, Blocks.DISPENSER,
                Blocks.OBSERVER, Blocks.BLUE_GLAZED_TERRACOTTA};
        BlockPos target = new BlockPos(8, 64, -3);
        int checks = 0;
        for (float yaw : yaws) {
            for (float pitch : pitches) {
                player.setYaw(yaw);
                player.setPitch(pitch);
                Direction[] actualOrder = (Direction[]) order.invoke(null, yaw, pitch);
                check(Arrays.equals(actualOrder, Direction.getEntityFacingOrder(player)),
                        "vanilla direction order differs at " + yaw + "," + pitch);
                checks++;

                for (Direction face : Direction.values()) {
                    BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(target), face, target, false);
                    ItemPlacementContext vanilla = (ItemPlacementContext) unsafe.allocateInstance(ItemPlacementContext.class);
                    NativePlacementContext simulated = (NativePlacementContext) unsafe.allocateInstance(NativePlacementContext.class);
                    initialize(vanilla, player, target, hit);
                    initialize(simulated, player, target, hit);
                    set(NativePlacementContext.class, simulated, "yaw", yaw);
                    set(NativePlacementContext.class, simulated, "pitch", pitch);
                    set(NativePlacementContext.class, simulated, "lookDirections", actualOrder);

                    // The oracle sees the simulated server angle; then move
                    // the local camera elsewhere. Context results must stay
                    // equal to the oracle without reading the user's camera.
                    Direction expectedLook = vanilla.getPlayerLookDirection();
                    Direction expectedVertical = vanilla.getVerticalPlayerLookDirection();
                    Direction expectedHorizontal = vanilla.getHorizontalPlayerFacing();
                    float expectedYaw = vanilla.getPlayerYaw();
                    Direction[] expectedReplace = vanilla.getPlacementDirections();
                    set(ItemPlacementContext.class, vanilla, "canReplaceExisting", false);
                    Direction[] expectedAdjacent = vanilla.getPlacementDirections();
                    set(ItemPlacementContext.class, vanilla, "canReplaceExisting", true);
                    BlockState[] expectedStates = new BlockState[contextOnlyBlocks.length];
                    for (int i = 0; i < contextOnlyBlocks.length; i++) expectedStates[i] = contextOnlyBlocks[i].getPlacementState(vanilla);

                    player.setYaw(yaw + 83.0F);
                    player.setPitch(-pitch);
                    check(simulated.getPlayerLookDirection() == expectedLook, "nearest look reads camera");
                    check(simulated.getVerticalPlayerLookDirection() == expectedVertical, "vertical look differs");
                    check(simulated.getHorizontalPlayerFacing() == expectedHorizontal, "horizontal facing reads camera");
                    check(simulated.getPlayerYaw() == expectedYaw, "scalar yaw reads camera");
                    check(Arrays.equals(simulated.getPlacementDirections(), expectedReplace), "replace order differs");
                    set(ItemPlacementContext.class, simulated, "canReplaceExisting", false);
                    check(Arrays.equals(simulated.getPlacementDirections(), expectedAdjacent), "adjacent order differs");
                    set(ItemPlacementContext.class, simulated, "canReplaceExisting", true);
                    check(!simulated.hitsInsideBlock() && simulated.getBlockPos().equals(target), "wire hit target differs");
                    for (int i = 0; i < contextOnlyBlocks.length; i++) {
                        check(contextOnlyBlocks[i].getPlacementState(simulated) == expectedStates[i],
                                "actual vanilla block placement differs for " + contextOnlyBlocks[i] + " at " + yaw + "," + pitch);
                    }
                    checks += 7 + contextOnlyBlocks.length;
                    player.setYaw(yaw);
                    player.setPitch(pitch);
                }
            }
        }
        boolean ignoreRotation = PrinterConfig.PRINTER_IGNORE_ROTATION.getBooleanValue();
        try {
            PrinterConfig.PRINTER_IGNORE_ROTATION.setBooleanValue(false);
            BlockState stairs = Blocks.OAK_STAIRS.getDefaultState();
            check(NativePlacementSolver.statesMatch(stairs, stairs.with(Properties.WATERLOGGED, true)),
                    "environment water must not prevent initial block placement");
            check(!NativePlacementSolver.statesMatch(stairs.with(Properties.HORIZONTAL_FACING, Direction.EAST),
                    stairs.with(Properties.HORIZONTAL_FACING, Direction.WEST)), "stair orientation must remain strict");
            BlockState rail = Blocks.RAIL.getDefaultState();
            check(!NativePlacementSolver.statesMatch(rail.with(Properties.RAIL_SHAPE, RailShape.NORTH_SOUTH),
                    rail.with(Properties.RAIL_SHAPE, RailShape.EAST_WEST)), "rail shape is placement orientation");
            BlockState lichen = Blocks.GLOW_LICHEN.getDefaultState();
            check(!NativePlacementSolver.statesMatch(lichen.with(Properties.NORTH, true),
                    lichen.with(Properties.NORTH, false)), "multiface attachment must remain strict");
            checks += 4;
        } finally {
            PrinterConfig.PRINTER_IGNORE_ROTATION.setBooleanValue(ignoreRotation);
        }
        return checks;
    }

    private static void initialize(ItemPlacementContext context, ClientPlayerEntity player, BlockPos target,
                                     BlockHitResult hit) throws Exception {
        set(ItemUsageContext.class, context, "player", player);
        set(ItemUsageContext.class, context, "hand", Hand.MAIN_HAND);
        set(ItemUsageContext.class, context, "stack", ItemStack.EMPTY);
        set(ItemUsageContext.class, context, "hit", hit);
        set(ItemPlacementContext.class, context, "placementPos", target.offset(hit.getSide()));
        set(ItemPlacementContext.class, context, "canReplaceExisting", true);
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
