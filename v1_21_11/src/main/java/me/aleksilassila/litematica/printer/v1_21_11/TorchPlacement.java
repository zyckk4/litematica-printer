package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.implementation.NativePlacementContext;
import net.minecraft.block.AbstractTorchBlock;
import net.minecraft.block.BlockState;
import net.minecraft.block.WallTorchBlock;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BlockStateComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.Nullable;

/** A torch's support face is geometry, even when ordinary rotations are ignored. */
public final class TorchPlacement {
    private TorchPlacement() {}

    public static boolean isTorch(BlockState state) {
        return state.getBlock() instanceof AbstractTorchBlock;
    }

    public static boolean matches(BlockState desired, @Nullable BlockState actual) {
        if (actual == null || !actual.isOf(desired.getBlock())) return false;
        // Redstone LIT follows the circuit; wall FACING identifies the support.
        return !desired.contains(WallTorchBlock.FACING)
                || actual.get(WallTorchBlock.FACING) == desired.get(WallTorchBlock.FACING);
    }

    public static Direction supportDirection(BlockState state) {
        return state.contains(WallTorchBlock.FACING) ? state.get(WallTorchBlock.FACING).getOpposite() : Direction.DOWN;
    }

    @Nullable
    public static BlockState predict(PlayerEntity player, Hand hand, ItemStack stack,
                                     BlockHitResult hit, BlockPos target, float yaw, float pitch) {
        if (!(stack.getItem() instanceof BlockItem item)) return null;
        NativePlacementContext context = new NativePlacementContext(player, hand, stack, hit, yaw, pitch);
        if (!context.canPlace() || !BlockHelper.canReplaceFluid(context.getWorld().getBlockState(target))
                || !item.getBlock().isEnabled(context.getWorld().getEnabledFeatures())) return null;
        ItemPlacementContext placement = item.getPlacementContext(context);
        if (placement == null || !placement.getBlockPos().equals(target)) return null;
        // The item chooses standing/wall form and support; the block alone does not.
        BlockState result = item.getPlacementState(placement);
        if (result == null) return null;
        result = stack.getOrDefault(DataComponentTypes.BLOCK_STATE, BlockStateComponent.DEFAULT).applyToState(result);
        return result.canPlaceAt(placement.getWorld(), target) ? result : null;
    }
}
