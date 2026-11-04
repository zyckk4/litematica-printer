package me.aleksilassila.litematica.printer.v1_21_4;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import me.aleksilassila.litematica.printer.v1_21_4.actions.Action;
import me.aleksilassila.litematica.printer.v1_21_4.actions.NativePlacementAction;
import me.aleksilassila.litematica.printer.v1_21_4.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_4.guides.interaction.CycleStateGuide;
import me.aleksilassila.litematica.printer.v1_21_4.guides.interaction.WaterLogGuide;
import net.minecraft.block.*;
import net.minecraft.block.enums.RailShape;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BiPredicate;

/** One specialized interaction, independent of the legacy pre/post-tick rotation and sneak chain. */
final class MovingPrinterAction extends Action {
    private final SchematicBlockState state;
    private final BiPredicate<MinecraftClient, ClientPlayerEntity> use;

    private MovingPrinterAction(SchematicBlockState state, BiPredicate<MinecraftClient, ClientPlayerEntity> use) {
        this.state = state;
        this.use = use;
    }

    @Nullable
    static MovingPrinterAction prepare(SchematicBlockState state, ClientPlayerEntity player, double reach) {
        if (state.targetState.getBlock() instanceof AbstractRailBlock rail && state.currentState.isReplaceable()) {
            return prepareRail(state, rail, player, reach);
        }
        if (tuningProperty(state.targetState) != null && CycleStateGuide.needsInteraction(state.currentState, state.targetState)) {
            if (!new CycleStateGuide(state).canExecute(player)) return null;
            return new MovingPrinterAction(state, (client, currentPlayer) -> CycleStateGuide.tune(client, currentPlayer, state));
        }
        WaterLogGuide water = new WaterLogGuide(state);
        if (!water.canExecute(player)) return null;
        List<Action> actions = water.execute(player);
        return actions.isEmpty() ? null : new MovingPrinterAction(state, actions.getFirst()::send);
    }

    @Override
    public boolean send(MinecraftClient client, ClientPlayerEntity player) {
        return PrinterConfig.PRINTER_NATIVE_MOVING.getBooleanValue() && client.world == state.world
                && client.interactionManager != null && SchematicWorldHandler.getSchematicWorld() == state.schematic
                && state.schematic.getBlockState(state.blockPos) == state.targetState
                && DataManager.getRenderLayerRange().isPositionWithinRange(state.blockPos)
                && !Printer.hasPendingPlacement(state.blockPos) && use.test(client, player);
    }

    @Nullable
    static Property<?> tuningProperty(BlockState state) {
        return CycleStateGuide.tuningProperty(state);
    }

    @Nullable
    private static MovingPrinterAction prepareRail(SchematicBlockState state, AbstractRailBlock rail,
                                                    ClientPlayerEntity player, double reach) {
        BlockState seed = railPlacementSeed(state, rail);
        if (seed == null) return null;
        ItemStack stack = new ItemStack(rail);
        if (!Printer.inventoryManager.hasMaterial(stack)) return null;
        boolean allowAir = PrinterConfig.PRINTER_NATIVE_OMNI.getBooleanValue();
        boolean rotate = PrinterConfig.PRINTER_GRIM_ROTATION.getBooleanValue();
        NativePlacementSolver.Solution solution = rotate
                ? NativePlacementSolver.solveMoving(state.blockPos, seed, stack, reach, allowAir, player.isSprinting())
                : NativePlacementSolver.solveMovingAtRotation(state.blockPos, seed, stack, reach, allowAir,
                        player.isSprinting(), player.getYaw(), player.getPitch());
        if (solution == null || !Printer.inventoryManager.select(stack)) return null;
        if (rotate && !solution.anyRotation() && !MovementHandler.requestRotation(solution.yaw(), solution.pitch())) return null;
        NativePlacementAction placement = new NativePlacementAction(state.blockPos, seed, stack, solution);
        return new MovingPrinterAction(state, (client, currentPlayer) ->
                railPlacementSeed(state, rail) == seed && placement.send(client, currentPlayer));
    }

    /** The BlockItem first places a straight rail. Vanilla then selects curves and slopes from live neighbors. */
    @Nullable
    static BlockState railPlacementSeed(SchematicBlockState state, AbstractRailBlock rail) {
        BlockPos pos = state.blockPos;
        BlockState actual = state.world.getBlockState(pos);
        if (!actual.isReplaceable() || !BlockHelper.canReplaceFluid(actual) || !Block.hasTopRim(state.world, pos.down())) return null;
        var plan = new RailPlacementHelper(state.world, pos, state.targetState);
        List<BlockPos> ends = plan.getNeighbors();
        RailShape shape = state.targetState.get(rail.getShapeProperty());
        boolean curve = !shape.isAscending() && shape != RailShape.NORTH_SOUTH && shape != RailShape.EAST_WEST;
        Direction.Axis seedAxis = ends.getFirst().getX() == pos.getX() ? Direction.Axis.Z : Direction.Axis.X;
        for (Direction direction : Direction.Type.HORIZONTAL) {
            BlockPos column = pos.offset(direction);
            if (Printer.hasPendingPlacement(column) || Printer.hasPendingPlacement(column.up())
                    || Printer.hasPendingPlacement(column.down())) return null;
            BlockPos end = null;
            for (BlockPos intended : ends) {
                if (intended.getX() == column.getX() && intended.getZ() == column.getZ()) end = intended;
            }
            if (end != null && end.getY() > pos.getY() && !Block.hasTopRim(state.world, column)) return null;
            // An upper rail can convert a straight placement into a slope even at a junction.
            if (AbstractRailBlock.isRail(state.world, column.up()) && end != null && end.getY() != pos.getY() + 1) return null;
            if (!plan.canConnect(column)) {
                if (end != null && (curve || end.getY() > pos.getY()) && !hasFutureConnection(state, end)) return null;
                continue;
            }
            if (end == null) return null; // Wait for a nearby branch to stabilize instead of connecting across it.
            BlockPos neighbor = AbstractRailBlock.isRail(state.world, column) ? column
                    : AbstractRailBlock.isRail(state.world, column.up()) ? column.up() : column.down();
            BlockState real = state.world.getBlockState(neighbor);
            if (end.getY() > pos.getY() && !neighbor.equals(end)) return null;
            BlockState expected = state.schematic.getBlockState(neighbor);
            if (!expected.isOf(real.getBlock())) expected = real; // Preserve rails outside the schematic.
            BlockPos reciprocal = new BlockPos(pos.getX(), end.getY(), pos.getZ());
            if (!new RailPlacementHelper(state.world, neighbor, expected).getNeighbors().contains(reciprocal)) return null;
            seedAxis = direction.getAxis();
        }
        return state.targetState.with(rail.getShapeProperty(),
                seedAxis == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH);
    }

    private static boolean hasFutureConnection(SchematicBlockState state, BlockPos end) {
        BlockPos reciprocal = new BlockPos(state.blockPos.getX(), end.getY(), state.blockPos.getZ());
        for (BlockPos neighbor : List.of(end, end.down())) {
            if (end.getY() > state.blockPos.getY() && !neighbor.equals(end)) continue;
            BlockState expected = state.schematic.getBlockState(neighbor);
            if (expected.getBlock() instanceof AbstractRailBlock && state.world.getBlockState(neighbor).isReplaceable()
                    && new RailPlacementHelper(state.world, neighbor, expected).getNeighbors().contains(reciprocal)) return true;
        }
        return false;
    }
}
