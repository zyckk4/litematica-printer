package me.aleksilassila.litematica.printer.v1_21_11;

import net.minecraft.block.*;
import net.minecraft.block.enums.Attachment;
import net.minecraft.block.enums.BlockFace;
import net.minecraft.item.VerticallyAttachableBlockItem;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

/** Mounts whose placement can change when another support becomes available. */
final class NativeAttachment {
    private NativeAttachment() {}

    /** These vanilla single-cell decorations take the clicked support first. */
    @Nullable
    static Direction decorationFace(BlockState state) {
        Class<?> block = state.getBlock().getClass();
        if ((block == WallSignBlock.class && !state.get(Properties.WATERLOGGED)) || block == WallTorchBlock.class
                || (state.isOf(Blocks.STONE_BUTTON) && state.get(WallMountedBlock.FACE) == BlockFace.WALL
                    && !state.get(ButtonBlock.POWERED))) {
            return state.get(Properties.HORIZONTAL_FACING);
        }
        return block == TorchBlock.class ? Direction.UP : null;
    }

    static boolean isSensitive(BlockState state) {
        Block block = state.getBlock();
        return block.asItem() instanceof VerticallyAttachableBlockItem
                || block instanceof WallMountedBlock || block instanceof BellBlock
                || block instanceof LadderBlock || block instanceof CocoaBlock || block instanceof TripwireHookBlock
                || block instanceof LanternBlock || block instanceof AmethystClusterBlock
                || block instanceof PointedDripstoneBlock || block instanceof PaleMossCarpetBlock
                || hasFaces(state);
    }

    /** Both block classes use the vanilla directional boolean properties for their faces. */
    static boolean hasFaces(BlockState state) {
        return state.getBlock() instanceof MultifaceBlock || state.getBlock() instanceof VineBlock;
    }

    /** Floor/ceiling-mounted buttons, levers, grindstones and bells can rotate on the same support. */
    static boolean facingDeterminesMount(BlockState state) {
        Block block = state.getBlock();
        if (block instanceof WallMountedBlock) return state.get(WallMountedBlock.FACE) == BlockFace.WALL;
        if (block instanceof BellBlock) {
            Attachment attachment = state.get(BellBlock.ATTACHMENT);
            return attachment == Attachment.SINGLE_WALL || attachment == Attachment.DOUBLE_WALL;
        }
        // Dual-form items use "facing" on the wall form and "rotation" on a standing or ceiling form.
        return block.asItem() instanceof VerticallyAttachableBlockItem || block instanceof LadderBlock
                || block instanceof CocoaBlock || block instanceof TripwireHookBlock || block instanceof AmethystClusterBlock;
    }
}
