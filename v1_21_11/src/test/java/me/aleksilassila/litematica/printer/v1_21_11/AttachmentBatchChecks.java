package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.actions.NativePlacementAction;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.PaleMossCarpetBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.WallShape;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionTypes;

final class AttachmentBatchChecks {
    static void run() throws Exception {
        try (var f = new StateInteractionTestFixture()) {
            f.setVelocity(Vec3d.ZERO);
            f.configure(PrinterConfig.PRINTER_GRIM_ROTATION, true);
            f.configure(PrinterConfig.PRINTER_IGNORE_ROTATION, false);
            var target = new BlockPos(0, 65, 2);
            var newWall = target.south();
            var stone = Blocks.STONE.getDefaultState();
            var sign = Blocks.OAK_WALL_SIGN.getDefaultState().with(Properties.HORIZONTAL_FACING, Direction.EAST);
            var stones = new ItemStack(Items.STONE, 12);
            var signs = new ItemStack(Items.OAK_SIGN, 12);
            f.world.blocks.put(target.west(), stone);
            f.player.inventory.main.set(2, stones);
            f.player.inventory.main.set(3, signs);
            // Both actions are solved against the same world, exactly as in a batch.
            var first = NativePlacementSolver.solveStationary(newWall, stone, stones, 4.5, true);
            var second = NativePlacementSolver.solveStationary(target, sign, signs, 4.5, true);
            if (first == null || second == null) throw new AssertionError("Missing batch setup");
            if (!new NativePlacementAction(newWall, stone, stones, first, true).send(f.client, f.player)) {
                throw new AssertionError("The support was never sent");
            }
            var queuedSign = new NativePlacementAction(target, sign, signs, second, true);
            if (queuedSign.send(f.client, f.player) || f.network.blockPackets.size() != 1) {
                throw new AssertionError("Sent the wall sign before the preceding support was published");
            }
            if (Printer.hasUnsettledAttachmentNeighbor(target, stone)
                    || Printer.hasUnsettledAttachmentNeighbor(target.west(2), sign)) {
                throw new AssertionError("An adjacent ordinary block or an independent sign was blocked");
            }
            // A delayed update for an earlier click at the same position is
            // insufficient. Release the neighbor only after the current action.
            int sequence = f.network.blockPackets.getFirst().getSequence();
            Printer.confirmNativePlacement(newWall, Blocks.AIR.getDefaultState());
            Printer.acknowledgeBlockActions(sequence - 1);
            if (!Printer.hasUnsettledAttachmentNeighbor(target, sign)) throw new AssertionError("Stale response released the support");
            f.world.blocks.put(newWall, stone);
            Printer.confirmNativePlacement(newWall, stone);
            Printer.acknowledgeBlockActions(sequence);
            if (Printer.hasUnsettledAttachmentNeighbor(target, sign)) throw new AssertionError("Published support stayed pending");
            var after = NativePlacementSolver.solveStationary(target, sign, signs, 4.5, true);
            if (after == null || !new NativePlacementAction(target, sign, signs, after, true).send(f.client, f.player)) {
                throw new AssertionError("The wall sign did not resume on confirmed supports");
            }
            var hit = f.network.blockPackets.getLast().getBlockHitResult();
            BlockItem item = (BlockItem) signs.getItem();
            var placement = item.getPlacementContext(new ItemPlacementContext(f.player, Hand.MAIN_HAND, signs, hit));
            if (placement == null || !placement.getBlockPos().equals(target) || !sign.equals(item.getPlacementState(placement))) {
                throw new AssertionError("The wire click did not place the sign facing east");
            }
            Printer.trackPlacement(newWall, stone, stones, sequence + 3);
            Printer.acknowledgeBlockActions(sequence + 3);
            if (!Printer.hasUnsettledAttachmentNeighbor(target, sign)) throw new AssertionError("An ACK alone published the support");
            Printer.confirmNativePlacement(newWall, stone);
            if (Printer.hasUnsettledAttachmentNeighbor(target, sign)) throw new AssertionError("Support not released after publication");

            Printer.trackPlacement(newWall, stone, stones, sequence + 4);
            for (int tick = 0; tick < 16; tick++) f.printer.onGameTick();
            if (Printer.hasPendingPlacement(newWall) || !Printer.hasUnsettledAttachmentNeighbor(target, sign)) {
                throw new AssertionError("Position retry timeout was mistaken for a server response");
            }
            Printer.acknowledgeBlockActions(sequence + 4);
            if (Printer.hasUnsettledAttachmentNeighbor(target, sign)) throw new AssertionError("Timed-out neighbor stayed blocked after ACK");

            var dimension = BuiltinRegistries.createWrapperLookup().getOrThrow(RegistryKeys.DIMENSION_TYPE).getOrThrow(DimensionTypes.OVERWORLD);
            StateInteractionTestFixture.set(World.class, f.world, "dimensionEntry", dimension);
            checkCompoundSupport(f, false);
            checkCompoundSupport(f, true);
            checkPlantCompanions(f);
        }
    }

    private static void checkCompoundSupport(StateInteractionTestFixture f, boolean bed) throws Exception {
        f.reset();
        f.configure(PrinterConfig.STRICT_BLOCK_FACE_CHECK, true);
        // An ignored bed rotation may put the head in a different cell from the schematic.
        f.configure(PrinterConfig.PRINTER_IGNORE_ROTATION, bed);
        BlockPos target = StateInteractionTestFixture.TARGET;
        BlockPos companion = target.north();
        BlockPos primary = bed ? companion.north() : companion.down();
        f.world.blocks.put(target.down(), Blocks.STONE.getDefaultState());
        f.world.blocks.put(primary.down(), Blocks.STONE.getDefaultState());
        ItemStack material = new ItemStack(bed ? Items.WHITE_BED : Items.OAK_DOOR, 12);
        f.fill(material);
        BlockState primaryState = replay(f, primary, material, new BlockHitResult(Vec3d.ofCenter(primary), Direction.UP, primary, false));
        if (primaryState == null) throw new AssertionError("Missing compound block");
        BlockState blueprint = bed ? primaryState.with(BedBlock.FACING, Direction.EAST) : primaryState;
        var first = NativePlacementSolver.solveMovingAtRotation(primary, blueprint, material, 4.5, true, false, 0, 0);
        ItemStack signs = new ItemStack(Items.OAK_SIGN, 12);
        f.fill(signs);
        BlockState standing = Blocks.OAK_SIGN.getDefaultState().with(Properties.ROTATION, 8);
        // 3.4.9 clicked the air cell's DOWN face in the original door/sign batch.
        var oldHit = new BlockHitResult(Vec3d.of(target).add(0.5, 0, 0.95), Direction.DOWN, target, false);
        var second = NativePlacementSolver.solveMovingAtRotation(target, standing, signs, 4.5, true, false, 0, 0);
        if (first == null || first.predicted() != primaryState || second == null
                || replay(f, target, signs, oldHit) != standing
                || replay(f, target, signs, second.hit()) != standing) throw new AssertionError("Missing native compound batch");
        f.fill(material);
        MovementHandler.onMovementSent(f.player);
        if (!new NativePlacementAction(primary, blueprint, material, first).send(f.client, f.player)) {
            throw new AssertionError("Compound support was never sent");
        }
        if (Printer.pendingSlotCount(0, material) != 1) throw new AssertionError("Compound block did not reserve one item");
        f.fill(signs);
        if (new NativePlacementAction(target, standing, signs, second).send(f.client, f.player)
                || f.network.blockPackets.size() != 1) throw new AssertionError("Sign sent before secondary cell was known");
        int sequence = f.network.blockPackets.getFirst().getSequence();
        f.world.blocks.put(primary, primaryState);
        Printer.confirmNativePlacement(primary, primaryState);
        Printer.acknowledgeBlockActions(sequence);
        if (!Printer.hasUnsettledAttachmentNeighbor(target, standing)) throw new AssertionError("Primary publication released secondary cell");
        BlockState secondary = bed ? primaryState.with(BedBlock.PART, BedPart.HEAD) : primaryState.with(DoorBlock.HALF, DoubleBlockHalf.UPPER);
        f.world.blocks.put(companion, secondary);
        BlockState wrong = replay(f, target, signs, oldHit);
        if (wrong == null || !wrong.isOf(Blocks.OAK_WALL_SIGN) || wrong.get(Properties.HORIZONTAL_FACING) != Direction.SOUTH) {
            throw new AssertionError("Expected the stale click to attach to the new door upper or bed head");
        }
        Printer.confirmNativePlacement(companion, secondary);
        if (Printer.hasUnsettledAttachmentNeighbor(target, standing)) throw new AssertionError("Published compound support stayed pending");
        var checked = NativePlacementSolver.revalidate(target, standing, signs, second, 4.5, 0, 0);
        if (checked != null && !NativePlacementSolver.statesMatch(standing, replay(f, target, signs, checked.hit()))) {
            throw new AssertionError("Stale sign click survived revalidation");
        }
    }

    private static void checkPlantCompanions(StateInteractionTestFixture f) throws Exception {
        f.reset();
        var pos = StateInteractionTestFixture.TARGET;
        var sign = Blocks.OAK_SIGN.getDefaultState();
        var neighbor = pos.up().east();
        Printer.trackPlacement(pos, Blocks.SUNFLOWER.getDefaultState(), new ItemStack(Items.SUNFLOWER), 2);
        Printer.confirmNativePlacement(pos.up(), Blocks.AIR.getDefaultState());
        if (!Printer.hasUnsettledAttachmentNeighbor(neighbor, sign)) throw new AssertionError("Upper plant cell released before ACK");
        Printer.acknowledgeBlockActions(2);
        if (Printer.hasUnsettledAttachmentNeighbor(neighbor, sign)) throw new AssertionError("Upper plant cell stayed pending");
        Printer.trackPlacement(pos, Blocks.PITCHER_CROP.getDefaultState(), new ItemStack(Items.PITCHER_POD), 3);
        if (Printer.hasUnsettledAttachmentNeighbor(neighbor, sign)) throw new AssertionError("Fresh pitcher seed created a phantom upper cell");
        var carpet = Blocks.PALE_MOSS_CARPET.getDefaultState();
        var moss = new ItemStack(Items.PALE_MOSS_CARPET);
        Printer.trackPlacement(pos, carpet, moss, 4);
        if (Printer.hasUnsettledAttachmentNeighbor(neighbor, sign)) throw new AssertionError("Flat moss has no upper fronds");
        Printer.trackPlacement(pos, carpet.with(PaleMossCarpetBlock.NORTH, WallShape.LOW), moss, 5);
        Printer.acknowledgeBlockActions(5);
        if (!Printer.hasUnsettledAttachmentNeighbor(neighbor, sign)) throw new AssertionError("Possible moss frond released before publication");
        for (int i = 0; i < 16; i++) f.printer.onGameTick();
        if (Printer.hasUnsettledAttachmentNeighbor(neighbor, sign)) throw new AssertionError("Absent random moss frond never timed out");
    }

    private static BlockState replay(StateInteractionTestFixture f, BlockPos pos, ItemStack stack, BlockHitResult hit) {
        BlockItem item = (BlockItem) stack.getItem();
        var context = item.getPlacementContext(new ItemPlacementContext(f.player, Hand.MAIN_HAND, stack, hit));
        return context == null || !context.getBlockPos().equals(pos) ? null : item.getPlacementState(context);
    }
}
