package me.aleksilassila.litematica.printer.v1_21_4.guides.interaction;

import me.aleksilassila.litematica.printer.v1_21_4.SchematicBlockState;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import me.aleksilassila.litematica.printer.v1_21_4.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.v1_21_4.Printer;
import me.aleksilassila.litematica.printer.v1_21_4.actions.Action;
import me.aleksilassila.litematica.printer.v1_21_4.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_4.mixin.MixinAccessorClientPlayerEntity;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.state.property.Property;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
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
    public @NotNull List<Action> execute(ClientPlayerEntity player) {
        if (!(targetState.getBlock() instanceof DaylightDetectorBlock)) return super.execute(player);
        // Vanilla toggles this block only on the server. Await that update even
        // while stationary so delayed replies cannot produce a second toggle.
        return List.of(new Action() {
            @Override
            public boolean send(MinecraftClient client, ClientPlayerEntity currentPlayer) {
                return client.world == state.world && client.interactionManager != null
                        && SchematicWorldHandler.getSchematicWorld() == state.schematic
                        && state.schematic.getBlockState(state.blockPos) == targetState
                        && DataManager.getRenderLayerRange().isPositionWithinRange(state.blockPos)
                        && !Printer.hasPendingPlacement(state.blockPos) && tune(client, currentPlayer, state);
            }
        });
    }

    public static Property<?> tuningProperty(BlockState state) {
        if (state.getBlock() instanceof RepeaterBlock) return RepeaterBlock.DELAY;
        if (state.getBlock() instanceof ComparatorBlock) return ComparatorBlock.MODE;
        if (state.getBlock() instanceof NoteBlock) return NoteBlock.NOTE;
        if (state.getBlock() instanceof DaylightDetectorBlock) return DaylightDetectorBlock.INVERTED;
        return null;
    }

    /** Send one dial/toggle step and wait for the corresponding server state. */
    public static boolean tune(MinecraftClient client, ClientPlayerEntity player, SchematicBlockState state) {
        BlockPos pos = state.blockPos;
        BlockState actual = state.world.getBlockState(pos);
        Property<?> property = tuningProperty(actual);
        if (property == null || !needsInteraction(actual, state.targetState) || player.isSneaking()
                || ((MixinAccessorClientPlayerEntity) player).getLastSneaking() || client.options.sneakKey.isPressed()
                || !client.world.getWorldBorder().contains(pos)) return false;
        Vec3d eye = player.getEyePos();
        Vec3d center = actual.getOutlineShape(client.world, pos).getBoundingBox().getCenter().add(Vec3d.of(pos));
        double reach = Math.min(player.getBlockInteractionRange(), LitematicaMixinMod.PRINTING_RANGE.getDoubleValue());
        BlockHitResult hit = new BlockHitResult(center, Direction.UP, pos, false);
        if (PrinterConfig.RAYCAST.getBooleanValue()) {
            hit = client.world.raycast(new RaycastContext(eye, center, RaycastContext.ShapeType.OUTLINE,
                    RaycastContext.FluidHandling.NONE, player));
            if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) return false;
        }
        if (eye.squaredDistanceTo(hit.getPos()) > reach * reach || !Printer.inventoryManager.select(ItemStack.EMPTY)
                || !player.getMainHandStack().isEmpty() || !Printer.tryAcquirePlacementPacket()) return false;
        // Do not locally predict a dial step: a neighbor update already in
        // flight must not cause the next scan to send that step twice.
        BlockHitResult checkedHit = hit;
        client.interactionManager.sendSequencedPacket(client.world, sequence -> {
            Printer.trackPlacement(pos, actual.cycle(property), ItemStack.EMPTY, sequence);
            return new PlayerInteractBlockC2SPacket(Hand.MAIN_HAND, checkedHit, sequence);
        });
        Printer.addTimeout(pos);
        player.swingHand(Hand.MAIN_HAND);
        return true;
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
        if (block instanceof DaylightDetectorBlock) return current.get(DaylightDetectorBlock.INVERTED) != target.get(DaylightDetectorBlock.INVERTED);
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
