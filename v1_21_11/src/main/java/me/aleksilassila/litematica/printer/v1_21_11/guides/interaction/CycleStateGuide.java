package me.aleksilassila.litematica.printer.v1_21_11.guides.interaction;

import me.aleksilassila.litematica.printer.v1_21_11.SchematicBlockState;
import me.aleksilassila.litematica.printer.v1_21_11.mixin.MixinAccessorClientPlayerEntity;
import net.minecraft.block.*;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Direction;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.List;

public class CycleStateGuide extends InteractionGuide {
    public CycleStateGuide(SchematicBlockState state) {
        super(state);
    }

    @Override
    public boolean canExecute(ClientPlayerEntity player) {
        // Respect the user's sneak key and offhand. Sneaking may suppress a
        // normal block interaction when the offhand holds an item.
        return !player.isSneaking() && playerHasRightItem(player)
                && needsInteraction(currentState, targetState)
                && canInteractWithoutChangingFacing(currentState, player.getYaw(),
                        ((MixinAccessorClientPlayerEntity) player).getLastYaw());
    }

    @Override
    protected boolean requiresShiftRelease() {
        // canExecute already requires non-sneaking. Do not rewrite or resend
        // the user's movement input for an empty-hand state interaction.
        return false;
    }

    public static boolean canInteractWithoutChangingFacing(BlockState current, float cameraYaw, float serverYaw) {
        if (!(current.getBlock() instanceof FenceGateBlock) || current.get(FenceGateBlock.OPEN)) return true;
        // Vanilla opening flips a gate when facing it from its opposite yaw.
        // Both client prediction and server interaction must preserve FACING;
        // defer this legacy action until they do rather than turning the view.
        Direction opposite = current.get(FenceGateBlock.FACING).getOpposite();
        return Float.isFinite(cameraYaw) && Float.isFinite(serverYaw)
                && Direction.fromHorizontalDegrees(cameraYaw) != opposite
                && Direction.fromHorizontalDegrees(serverYaw) != opposite;
    }

    public static boolean needsInteraction(BlockState current, BlockState target) {
        if (current.getBlock() != target.getBlock()) return false;
        Block block = current.getBlock();
        if (block instanceof LeverBlock) return current.get(LeverBlock.POWERED) != target.get(LeverBlock.POWERED);
        if (block instanceof RepeaterBlock) return !current.get(RepeaterBlock.DELAY).equals(target.get(RepeaterBlock.DELAY));
        if (block instanceof ComparatorBlock) return current.get(ComparatorBlock.MODE) != target.get(ComparatorBlock.MODE);
        if (block instanceof NoteBlock) return !current.get(NoteBlock.NOTE).equals(target.get(NoteBlock.NOTE));
        if (block == Blocks.IRON_DOOR || block == Blocks.IRON_TRAPDOOR) return false;
        if (block instanceof DoorBlock) return current.get(DoorBlock.OPEN) != target.get(DoorBlock.OPEN);
        if (block instanceof TrapdoorBlock) return current.get(TrapdoorBlock.OPEN) != target.get(TrapdoorBlock.OPEN);
        if (block instanceof FenceGateBlock) return current.get(FenceGateBlock.OPEN) != target.get(FenceGateBlock.OPEN);
        // Orientation, hinge, attachment and redstone-derived state cannot be
        // repaired by repeatedly right-clicking an otherwise correct block.
        return false;
    }

    @Override
    protected @NotNull List<ItemStack> getRequiredItems() {
        return Collections.singletonList(ItemStack.EMPTY);
    }
}
