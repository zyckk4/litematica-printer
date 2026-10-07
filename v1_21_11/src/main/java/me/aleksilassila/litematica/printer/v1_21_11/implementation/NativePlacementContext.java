package me.aleksilassila.litematica.printer.v1_21_11.implementation;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

/**
 * Placement context whose look direction is supplied by the printer solver.
 * It lets us evaluate a block's vanilla placement state without changing the
 * local camera or relying on the user's crosshair.
 */
public final class NativePlacementContext extends ItemPlacementContext {
    private final float yaw;
    private final float pitch;
    private final Direction[] lookDirections;

    public NativePlacementContext(PlayerEntity player, Hand hand, ItemStack stack, BlockHitResult hit,
                                  float yaw, float pitch) {
        super(player, hand, stack, hit);
        this.yaw = yaw;
        this.pitch = pitch;
        this.lookDirections = orderedDirections(yaw, pitch);
        // ItemPlacementContext's constructor may call BlockState.canReplace
        // before the simulated look fields are initialized (e.g. layering a
        // slab). Re-evaluate replacement after initializing this context.
        this.canReplaceExisting = true;
        this.canReplaceExisting = getWorld().getBlockState(hit.getBlockPos()).canReplace(this);
    }

    @Override
    public Direction getPlayerLookDirection() {
        return lookDirections == null ? super.getPlayerLookDirection() : lookDirections[0];
    }

    @Override
    public Direction getVerticalPlayerLookDirection() {
        return lookDirections == null ? super.getVerticalPlayerLookDirection() : pitch < 0.0F ? Direction.UP : Direction.DOWN;
    }

    @Override
    public Direction getHorizontalPlayerFacing() {
        return lookDirections == null ? super.getHorizontalPlayerFacing() : Direction.fromHorizontalDegrees(yaw);
    }

    @Override
    public float getPlayerYaw() {
        // Signs, skulls and banners read this scalar rather than a Direction.
        return lookDirections == null ? super.getPlayerYaw() : yaw;
    }

    /**
     * ItemPlacementContext uses this order for stairs, observers, pistons and
     * other blocks whose placement depends on the nearest look direction.
     * The old port only overrode the single-direction accessors, so its local
     * simulation could disagree with vanilla even when yaw/pitch looked right.
     */
    @Override
    public Direction[] getPlacementDirections() {
        if (lookDirections == null) return super.getPlacementDirections();
        Direction[] directions = lookDirections.clone();

        if (!canReplaceExisting()) {
            Direction opposite = getSide().getOpposite();
            int index = 0;
            while (index < directions.length && directions[index] != opposite) index++;
            if (index > 0 && index < directions.length) {
                System.arraycopy(directions, 0, directions, 1, index);
                directions[0] = opposite;
            }
        }
        return directions;
    }

    private static Direction[] orderedDirections(float yaw, float pitch) {
        // Direction.getEntityFacingOrder in 1.21.11, using the simulated
        // angles. A general dot-product sort differs at axis ties; vanilla's
        // tie order matters for wall attachments with multiple supports.
        float pitchRadians = pitch * 0.017453292F;
        float yawRadians = -yaw * 0.017453292F;
        float sinPitch = MathHelper.sin(pitchRadians);
        float cosPitch = MathHelper.cos(pitchRadians);
        float sinYaw = MathHelper.sin(yawRadians);
        float cosYaw = MathHelper.cos(yawRadians);
        boolean east = sinYaw > 0.0F;
        boolean up = sinPitch < 0.0F;
        boolean south = cosYaw > 0.0F;
        float absX = east ? sinYaw : -sinYaw;
        float absY = up ? -sinPitch : sinPitch;
        float absZ = south ? cosYaw : -cosYaw;
        float horizontalX = absX * cosPitch;
        float horizontalZ = absZ * cosPitch;
        Direction x = east ? Direction.EAST : Direction.WEST;
        Direction y = up ? Direction.UP : Direction.DOWN;
        Direction z = south ? Direction.SOUTH : Direction.NORTH;
        if (absX > absZ) {
            if (absY > horizontalX) return directions(y, x, z);
            if (horizontalZ > absY) return directions(x, z, y);
            return directions(x, y, z);
        }
        if (absY > horizontalZ) return directions(y, z, x);
        if (horizontalX > absY) return directions(z, x, y);
        return directions(z, y, x);
    }

    private static Direction[] directions(Direction first, Direction second, Direction third) {
        return new Direction[]{first, second, third, third.getOpposite(), second.getOpposite(), first.getOpposite()};
    }

    public float getSimulatedYaw() {
        return yaw;
    }

    public float getSimulatedPitch() {
        return pitch;
    }
}
