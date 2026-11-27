package me.aleksilassila.litematica.printer.v1_21_4;

import net.minecraft.block.*;
import net.minecraft.block.enums.Attachment;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.item.VerticallyAttachableBlockItem;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

final class NativeAttachment {
    private NativeAttachment() {}

    /** Outward face on the supporting block. A wall hanging sign can hang from either end of its axis. */
    @Nullable
    static Direction preferredFace(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof WallHangingSignBlock) return null;
        if (block instanceof HangingSignBlock) return Direction.DOWN;
        if (block.asItem() instanceof VerticallyAttachableBlockItem) {
            return state.contains(Properties.HORIZONTAL_FACING) ? state.get(Properties.HORIZONTAL_FACING) : Direction.UP;
        }
        if (block instanceof WallMountedBlock) {
            return switch (state.get(WallMountedBlock.FACE)) {
                case FLOOR -> Direction.UP;
                case CEILING -> Direction.DOWN;
                case WALL -> state.get(Properties.HORIZONTAL_FACING);
            };
        }
        if (block instanceof LadderBlock || block instanceof TripwireHookBlock) return state.get(Properties.HORIZONTAL_FACING);
        if (block instanceof CocoaBlock) return state.get(Properties.HORIZONTAL_FACING).getOpposite();
        if (block instanceof AmethystClusterBlock) return state.get(Properties.FACING);
        return null;
    }

    static boolean isSensitive(BlockState state) {
        Block block = state.getBlock();
        return block.asItem() instanceof VerticallyAttachableBlockItem
                || block instanceof WallMountedBlock || block instanceof BellBlock
                || block instanceof LadderBlock || block instanceof CocoaBlock || block instanceof TripwireHookBlock
                || block instanceof LanternBlock || block instanceof AmethystClusterBlock
                || block instanceof PointedDripstoneBlock || block instanceof PaleMossCarpetBlock || hasFaces(state);
    }

    /** Directions read by the item when selecting a mount; the clicked cell is checked separately. */
    static boolean readsNeighbor(BlockState state, Direction side) {
        Block block = state.getBlock();
        if (block.asItem() instanceof VerticallyAttachableBlockItem) {
            return side != (block instanceof HangingSignBlock || block instanceof WallHangingSignBlock
                    ? Direction.DOWN : Direction.UP);
        }
        if (block instanceof LadderBlock || block instanceof CocoaBlock || block instanceof TripwireHookBlock) {
            return side.getAxis().isHorizontal();
        }
        if (block instanceof LanternBlock || block instanceof PointedDripstoneBlock) return side.getAxis().isVertical();
        return !hasFaces(state) || state.contains(MultifaceBlock.getProperty(side));
    }

    static boolean hasFaces(BlockState state) {
        return state.getBlock() instanceof MultifaceBlock || state.getBlock() instanceof VineBlock;
    }

    static boolean facingDeterminesMount(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof WallMountedBlock) return state.get(WallMountedBlock.FACE) == BlockFace.WALL;
        if (block instanceof BellBlock) {
            Attachment attachment = state.get(BellBlock.ATTACHMENT);
            return attachment == Attachment.SINGLE_WALL || attachment == Attachment.DOUBLE_WALL;
        }
        return block.asItem() instanceof VerticallyAttachableBlockItem || block instanceof LadderBlock
                || block instanceof CocoaBlock || block instanceof TripwireHookBlock || block instanceof AmethystClusterBlock;
    }
}
