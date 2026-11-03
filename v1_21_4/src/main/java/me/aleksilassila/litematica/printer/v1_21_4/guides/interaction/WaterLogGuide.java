package me.aleksilassila.litematica.printer.v1_21_4.guides.interaction;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import me.aleksilassila.litematica.printer.v1_21_4.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.v1_21_4.MovementHandler;
import me.aleksilassila.litematica.printer.v1_21_4.Printer;
import me.aleksilassila.litematica.printer.v1_21_4.SchematicBlockState;
import me.aleksilassila.litematica.printer.v1_21_4.actions.Action;
import me.aleksilassila.litematica.printer.v1_21_4.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_4.guides.Guide;
import me.aleksilassila.litematica.printer.v1_21_4.mixin.MixinAccessorClientPlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.ShapeContext;
import net.minecraft.block.Waterloggable;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.fluid.Fluids;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class WaterLogGuide extends Guide {
    public WaterLogGuide(SchematicBlockState state) {
        super(state);
    }

    @Override
    protected @NotNull List<ItemStack> getRequiredItems() {
        return List.of(new ItemStack(Items.WATER_BUCKET));
    }

    private boolean canWaterlog(MinecraftClient client, ClientPlayerEntity player) {
        if (!PrinterConfig.WATERLOGGING.getBooleanValue() || !targetState.getOrEmpty(Properties.WATERLOGGED).orElse(false)
                || client.world == null || client.world != state.world || SchematicWorldHandler.getSchematicWorld() != state.schematic
                || state.schematic.getBlockState(state.blockPos) != targetState
                || !DataManager.getRenderLayerRange().isPositionWithinRange(state.blockPos)
                || Printer.hasPendingPlacement(state.blockPos)) return false;
        BlockState actual = client.world.getBlockState(state.blockPos);
        if (!actual.isOf(targetState.getBlock()) || actual.get(Properties.WATERLOGGED)
                || !(actual.getBlock() instanceof Waterloggable block)) return false;
        // Respect both the physical key and the last 1.21.4 sneak command while input is changing.
        return !player.hasVehicle() && !player.isSneaking() && !((MixinAccessorClientPlayerEntity) player).getLastSneaking()
                && !client.options.sneakKey.isPressed()
                && block.canFillWithFluid(player, client.world, state.blockPos, actual, Fluids.WATER)
                && !client.world.getDimension().ultrawarm();
    }

    @Override
    public boolean canExecute(ClientPlayerEntity player) {
        return canWaterlog(MinecraftClient.getInstance(), player) && playerHasRightItem(player)
                && (!player.getMainHandStack().isOf(Items.WATER_BUCKET) || findAim(player) != null);
    }

    @Override
    public @NotNull List<Action> execute(ClientPlayerEntity player) {
        return canWaterlog(MinecraftClient.getInstance(), player) ? List.of(new UseWaterBucket()) : List.of();
    }

    private record Aim(float yaw, float pitch) {}

    @Nullable
    private Aim findAim(ClientPlayerEntity player) {
        Vec3d eye = player.getEyePos();
        double reach = player.getBlockInteractionRange();
        double limit = Math.min(reach, LitematicaMixinMod.PRINTING_RANGE.getDoubleValue());
        BlockState actual = state.world.getBlockState(state.blockPos);
        for (Box local : actual.getOutlineShape(state.world, state.blockPos, ShapeContext.of(player)).getBoundingBoxes()) {
            Box box = local.offset(state.blockPos);
            // Try the middle first, then points inside the corners for partly obstructed shapes.
            Aim center = aimAt(player, eye, box.getCenter(), reach, limit);
            if (center != null) return center;
            for (int corner = 0; corner < 8; corner++) {
                Vec3d point = new Vec3d(box.minX + box.getLengthX() * ((corner & 1) == 0 ? 0.02 : 0.98),
                        box.minY + box.getLengthY() * ((corner & 2) == 0 ? 0.02 : 0.98),
                        box.minZ + box.getLengthZ() * ((corner & 4) == 0 ? 0.02 : 0.98));
                Aim aim = aimAt(player, eye, point, reach, limit);
                if (aim != null) return aim;
            }
        }
        return null;
    }

    @Nullable
    private Aim aimAt(ClientPlayerEntity player, Vec3d eye, Vec3d point, double reach, double limit) {
        Vec3d delta = point.subtract(eye);
        float yaw = MathHelper.wrapDegrees((float) Math.toDegrees(Math.atan2(-delta.x, delta.z)));
        float pitch = (float) Math.toDegrees(Math.atan2(-delta.y, delta.horizontalLength()));
        // Match Item.raycast exactly, including the float angles serialized by PlayerInteractItemC2SPacket.
        Vec3d end = eye.add(player.getRotationVector(pitch, yaw).multiply(reach));
        BlockHitResult hit = state.world.raycast(new RaycastContext(eye, end,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));
        return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(state.blockPos)
                && hit.getPos().squaredDistanceTo(eye) <= limit * limit ? new Aim(yaw, pitch) : null;
    }

    private final class UseWaterBucket extends Action {
        @Override
        public boolean send(MinecraftClient client, ClientPlayerEntity player) {
            if (client.player != player || client.interactionManager == null || !canWaterlog(client, player)) return false;
            ItemStack bucket = getRequiredItem(player).stream().findFirst().orElse(ItemStack.EMPTY);
            if (bucket.isEmpty() || !Printer.inventoryManager.select(bucket)
                    || !player.getMainHandStack().isOf(Items.WATER_BUCKET) || !canWaterlog(client, player)) return false;
            // Scaffolding, for example, changes its outline when the previously held item is put away.
            Aim aim = findAim(player);
            if (aim == null || !Printer.tryAcquirePlacementPacket()) return false;

            float yaw = player.getYaw();
            float pitch = player.getPitch();
            ActionResult result;
            try {
                if (PrinterConfig.PRINTER_GRIM_ROTATION.getBooleanValue()) {
                    MovementHandler.grimRotate(player, aim.yaw, aim.pitch);
                }
                player.setYaw(aim.yaw);
                player.setPitch(aim.pitch);
                result = client.interactionManager.interactItem(player, Hand.MAIN_HAND);
                // The server applies the item packet's angles. Let vanilla restore them on its next move.
                MixinAccessorClientPlayerEntity sent = (MixinAccessorClientPlayerEntity) player;
                sent.setLastYaw(aim.yaw);
                sent.setLastPitch(aim.pitch);
            } finally {
                player.setYaw(yaw);
                player.setPitch(pitch);
            }
            if (!result.isAccepted()) return false;
            // The normal item-use path owns the sequence and inventory prediction. Wait for the block update.
            Printer.trackPlacement(state.blockPos, targetState, bucket, -1);
            Printer.addTimeout(state.blockPos);
            player.swingHand(Hand.MAIN_HAND);
            return true;
        }
    }
}
