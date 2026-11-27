package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_11.mixin.MixinAccessorClientPlayerEntity;
import me.aleksilassila.litematica.printer.v1_21_11.mixin.MixinAccessorKeyBinding;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.CraftingScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

public class MovementHandler {
    private static Float serverYaw;
    private static Float serverPitch;
    private static ClientPlayerEntity rotationOwner;
    private static int inputSteps;
    private static float movementSpeedScale = 1.0F;
    private static boolean inputValidated;
    private static boolean movementSent;
    private static float sentYaw;
    private static float sentPitch;
    private boolean ownsLegacyKeys;

    private static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }

    /** Read-only feasibility check, including keys pressed since the last player tick. */
    public static boolean canRequestRotation(float yaw, float pitch) {
        MinecraftClient client = mc();
        if (client == null || !Float.isFinite(yaw) || !Float.isFinite(pitch)
                || !supportsRotation(client.player)) return false;
        return movementPlan(client.player, anticipatedInput(client), yaw, false) != null;
    }

    private static PlayerInput anticipatedInput(MinecraftClient client) {
        if (client.options == null || client.currentScreen != null) return client.player.input.playerInput;
        var options = client.options;
        return new PlayerInput(options.forwardKey.isPressed(), options.backKey.isPressed(),
                options.leftKey.isPressed(), options.rightKey.isPressed(), options.jumpKey.isPressed(),
                options.sneakKey.isPressed(), options.sprintKey.isPressed());
    }

    /**
     * Reserve this tick's vanilla movement rotation. Call before movement, then
     * send placement only after sentRotationMatches succeeds. No extra movement
     * packet is sent and no camera or player rotation field is changed.
     */
    public static boolean requestRotation(float yaw, float pitch) {
        if (!canRequestRotation(yaw, pitch)) return false;
        clearRotation();
        serverYaw = MathHelper.wrapDegrees(yaw);
        serverPitch = Math.max(-90.0F, Math.min(90.0F, pitch));
        rotationOwner = mc().player;
        inputValidated = false;
        return true;
    }

    /** Called once at the beginning of the printer tick. */
    public static void beginPrinterTick() {
        clearRotation();
    }

    public static void clearRotation() {
        serverYaw = null;
        serverPitch = null;
        rotationOwner = null;
        inputSteps = 0;
        movementSpeedScale = 1.0F;
        inputValidated = false;
        movementSent = false;
    }

    public static boolean hasRotation() {
        return serverYaw != null && serverPitch != null && mc() != null && rotationOwner == mc().player;
    }

    public static float serverYaw(float fallback) {
        return hasRotation() && inputValidated ? serverYaw : fallback;
    }

    public static float serverPitch(float fallback) {
        return hasRotation() && inputValidated ? serverPitch : fallback;
    }

    /**
     * Validate after vanilla has sampled keys and applied auto-jump, but before
     * integrating movement. Revalidating after physics could select a different
     * input/rotation than the pair that produced this tick's displacement.
     */
    public static void validateMovementInput(ClientPlayerEntity player) {
        if (inputValidated || !hasRotation()) return;
        if (player != rotationOwner || !supportsRotation(player)) {
            clearRotation();
            return;
        }
        MovementPlan plan = movementPlan(player, player.input.playerInput, serverYaw, true);
        if (plan == null) {
            clearRotation();
            return;
        }
        inputSteps = plan.steps();
        movementSpeedScale = plan.speedScale();
        // ClientPlayerEntity has already decided sprinting for this tick. Its
        // superclass calls tickMovementInput before jump/travel, so this changes
        // both the actual physics and vanilla's later sprint-state packet.
        if (plan.stopSprinting() && player.isSprinting()) player.setSprinting(false);
        inputValidated = true;
    }

    private static MovementPlan movementPlan(ClientPlayerEntity player, PlayerInput original,
                                              float requestedYaw, boolean validateVector) {
        int sideways = (original.left() ? 1 : 0) - (original.right() ? 1 : 0);
        int forward = (original.forward() ? 1 : 0) - (original.backward() ? 1 : 0);
        float delta = MathHelper.wrapDegrees(player.getYaw() - requestedYaw);
        int steps = Math.round(delta / 45.0F);
        boolean hasInput = sideways != 0 || forward != 0;
        boolean changesYaw = Math.abs(delta) > 0.001F;
        if (hasInput && Math.abs(MathHelper.wrapDegrees(delta - steps * 45.0F)) > 0.001F) {
            return null;
        }
        if (validateVector && changesYaw) {
            Vec2f expected = new Vec2f(sideways, forward).normalize();
            Vec2f actual = player.input.getMovementInput();
            // A movement module may replace vanilla's vector without changing
            // its declared keys. Do not compensate a different input model.
            if (Math.abs(expected.x - actual.x) > 0.0001F || Math.abs(expected.y - actual.y) > 0.0001F) {
                return null;
            }
        }
        if (!hasInput) steps = 0;
        PlayerInput declared = rotateInput(original, steps);
        boolean stopSprinting = !declared.forward();
        // A sprint jump adds a yaw-dependent impulse, unlike ordinary airborne
        // acceleration. Reject only that takeoff, not the whole airborne arc.
        // A side/back placement stops sprinting before physics and has no impulse.
        boolean maySprint = player.isSprinting() || (!validateVector && original.sprint());
        if (changesYaw && player.isOnGround() && original.jump() && maySprint && !stopSprinting) return null;
        // Modern vanilla gives cardinal input length .98, diagonal length 1.
        // A 45-degree turn swaps these. Preserve direction and match the speed
        // implied by the declared keys; do not rotate the local movement vector.
        float slowdown = 1.0F;
        if ((steps & 1) != 0) {
            // The printer pauses during active item use; do not guess a modded
            // item's movement factor if another caller reserves a rotation.
            if (player.isUsingItem()) return null;
            if (player.shouldSlowDown()) slowdown *= (float) player.getAttributeValue(EntityAttributes.SNEAKING_SPEED);
        }
        return new MovementPlan(steps, movementSpeedScaleForInput(original, steps, slowdown), stopSprinting);
    }

    private record MovementPlan(int steps, float speedScale, boolean stopSprinting) {}

    private static boolean supportsRotation(ClientPlayerEntity player) {
        return player != null && player.input != null && player.isAlive() && !player.isSleeping()
                && !player.hasVehicle() && !player.isGliding() && !player.isSwimming()
                && !player.isTouchingWater() && !player.isInLava() && !player.isClimbing()
                && !player.getAbilities().flying;
    }

    /** Only the native PlayerInput packet is rewritten, never local key state. */
    public static PlayerInput declaredInput(PlayerInput original) {
        return hasRotation() && inputValidated ? rotateInput(original, inputSteps) : original;
    }

    private static PlayerInput rotateInput(PlayerInput original, int steps) {
        int sideways = (original.left() ? 1 : 0) - (original.right() ? 1 : 0);
        int forward = (original.forward() ? 1 : 0) - (original.backward() ? 1 : 0);
        if (steps == 0 || (sideways == 0 && forward == 0)) return original;
        int direction = Math.floorMod(Math.round((float) Math.toDegrees(Math.atan2(-sideways, forward)) / 45.0F) + steps, 8);
        boolean left = direction >= 5 && direction <= 7;
        boolean right = direction >= 1 && direction <= 3;
        boolean ahead = direction == 7 || direction <= 1;
        boolean behind = direction >= 3 && direction <= 5;
        return new PlayerInput(ahead, behind, left, right, original.jump(), original.sneak(), original.sprint());
    }

    static float movementSpeedScaleForInput(PlayerInput original, int steps) {
        return movementSpeedScaleForInput(original, steps, 1.0F);
    }

    static float movementSpeedScaleForInput(PlayerInput original, int steps, float slowdown) {
        if ((steps & 1) == 0) return 1.0F;
        int sideways = (original.left() ? 1 : 0) - (original.right() ? 1 : 0);
        int forward = (original.forward() ? 1 : 0) - (original.backward() ? 1 : 0);
        if (sideways == 0 && forward == 0) return 1.0F;
        float cardinal = Math.min(0.98F * slowdown, 1.0F);
        float diagonal = Math.min(0.98F * slowdown * MathHelper.SQUARE_ROOT_OF_TWO, 1.0F);
        if (cardinal <= 0.0F || diagonal <= 0.0F) return 1.0F;
        return sideways != 0 && forward != 0 ? cardinal / diagonal : diagonal / cardinal;
    }

    public static float movementSpeedScale() {
        return hasRotation() && inputValidated ? movementSpeedScale : 1.0F;
    }

    public static void onMovementSent(ClientPlayerEntity player) {
        MinecraftClient mc = mc();
        if (player != mc.player || mc.getCameraEntity() != player || player.hasVehicle()) return;
        MixinAccessorClientPlayerEntity accessor = (MixinAccessorClientPlayerEntity) player;
        sentYaw = accessor.getLastYaw();
        sentPitch = accessor.getLastPitch();
        movementSent = true;
    }

    public static boolean sentRotationMatches(float yaw, float pitch) {
        return movementSent && Math.abs(MathHelper.wrapDegrees(sentYaw - yaw)) < 0.001F
                && Math.abs(sentPitch - pitch) < 0.001F;
    }

    public static float sentYaw(float fallback) {
        return movementSent ? sentYaw : fallback;
    }

    public static float sentPitch(float fallback) {
        return movementSent ? sentPitch : fallback;
    }

    public void onGameTick() {
        MinecraftClient mc = mc();
        if (mc == null) return;
        if (FreeLook.nativeMode()) {
            releaseLegacyKeys();
            return;
        }
        if (mc.player == null) return;

        // Disabled check
        if (!PrinterConfig.FREE_LOOK.getBooleanValue() || !LitematicaMixinMod.PRINT_MODE.getBooleanValue()) {
            releaseLegacyKeys();
            return;
        }

        // Disable in inventories
        if (mc.currentScreen != null) {
            if (PrinterConfig.MOVE_WHILE_IN_INVENTORY.getBooleanValue()) {
                if (mc.currentScreen instanceof CraftingScreen || mc.currentScreen instanceof CreativeInventoryScreen) {
                    releaseLegacyKeys();
                    return;
                }
            } else {
                releaseLegacyKeys();
                return;
            }
        }

        // Get current inputs
        InputDirections currentInputDirection = InputDirections.getCurrentInput();

        float cameraYaw = mc.gameRenderer.getCamera().getYaw() % 360;
        if (currentInputDirection != InputDirections.NONE) {
            // lastPlayerInputDirection = currentInputDirection;
            // Calculate resulting control direction and apply them
            float inputYaw = currentInputDirection.getYaw();
            float playerYaw = (mc.player.getYaw() + 360) % 360;

            // Calculate the result yaw
            float playerRelativeYaw = inputYaw + playerYaw;
            float resultPlayerYaw = playerRelativeYaw - cameraYaw;
            InputDirections resultDirection = InputDirections.getDirection(resultPlayerYaw);
            if (resultDirection == null || resultDirection == InputDirections.NONE) {
                // ChatUtil.sendClientMessage("Result direction is null");
                Printer.logger.warn("Result direction is null / None");
                return;
            }

            InputDirections.apply(resultDirection);
            ownsLegacyKeys = true;
        } else /*if (overwriteKeys.getValue())*/ {
//            Printer.logger.info("No input direction");
            InputDirections.apply(InputDirections.NONE);
            ownsLegacyKeys = true;
        }
    }

    private void releaseLegacyKeys() {
        if (!ownsLegacyKeys) return;
        ownsLegacyKeys = false;
        MinecraftClient client = mc();
        if (client == null || client.options == null || client.getWindow() == null) return;
        for (KeyBinding key : new KeyBinding[]{client.options.forwardKey, client.options.backKey,
                client.options.leftKey, client.options.rightKey}) key.setPressed(isKeyPressed(key));
    }

    public static void grimRotate(ClientPlayerEntity player, float yaw, float pitch) {
        // Compatibility path for the legacy guide. Native placement must use
        // requestRotation and wait for the vanilla movement flush instead.
        Vec3d playerPos = player.getEntityPos();
        sendLegacyRotation(player, new PlayerMoveC2SPacket.Full(playerPos.x, playerPos.y, playerPos.z,
                yaw, pitch, player.isOnGround(), player.horizontalCollision));
    }

    public static void sendLegacyRotation(ClientPlayerEntity player, PlayerMoveC2SPacket packet) {
        player.networkHandler.sendPacket(packet);
        recordSentRotation(player, packet);
    }

    static void recordSentRotation(ClientPlayerEntity player, PlayerMoveC2SPacket packet) {
        if (!packet.changesLook()) return;
        // A packet-constructor mixin can change the requested angles. Record
        // the actual packet so vanilla cannot skip the next restoring look.
        MixinAccessorClientPlayerEntity accessor = (MixinAccessorClientPlayerEntity) player;
        accessor.setLastYaw(packet.getYaw(accessor.getLastYaw()));
        accessor.setLastPitch(packet.getPitch(accessor.getLastPitch()));
    }

    public void onDisable(ClientPlayerEntity player) {
        if (FreeLook.nativeMode()) {
            releaseLegacyKeys();
            return;
        }
        clearRotation();
        releaseLegacyKeys();
    }

    enum InputDirections {
        FORWARD(0),
        FORWARD_LEFT(45),
        FORWARD_RIGHT(315),
        LEFT(90),
        RIGHT(270),
        BACK(180),
        BACK_LEFT(135),
        BACK_RIGHT(225),
        NONE(-1);
        private final float yaw;

        InputDirections(float i) {
            this.yaw = i;
        }

        static InputDirections getDirection(float yaw) {
            while (yaw < 0) yaw += 360;
            yaw = yaw % 360;
            if (yaw < 22.5) return FORWARD;
            if (yaw < 67.5) return FORWARD_LEFT;
            if (yaw < 112.5) return LEFT;
            if (yaw < 157.5) return BACK_LEFT;
            if (yaw < 202.5) return BACK;
            if (yaw < 247.5) return BACK_RIGHT;
            if (yaw < 292.5) return RIGHT;
            if (yaw < 337.5) return FORWARD_RIGHT;
            return FORWARD;
        }

        /**
         * Returns a unit vector in the direction of the input direction
         *
         * @return a unit vector in the direction of the input direction
         */
        public Vec3d getVec3d() {
            return new Vec3d(Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
        }

        public float getYaw() {
            return yaw;
        }

        static InputDirections getCurrentInput() {
            MinecraftClient mc = mc();
            if (isKeyPressed(mc.options.forwardKey)) {
                if (isKeyPressed(mc.options.leftKey)) {
                    return FORWARD_LEFT;
                } else if (isKeyPressed(mc.options.rightKey)) {
                    return FORWARD_RIGHT;
                } else {
                    return FORWARD;
                }
            } else if (isKeyPressed(mc.options.backKey)) {
                if (isKeyPressed(mc.options.leftKey)) {
                    return BACK_LEFT;
                } else if (isKeyPressed(mc.options.rightKey)) {
                    return BACK_RIGHT;
                } else {
                    return BACK;
                }
            } else if (isKeyPressed(mc.options.leftKey)) {
                return LEFT;
            } else if (isKeyPressed(mc.options.rightKey)) {
                return RIGHT;
            } else {
                return NONE;
            }
        }

        public boolean isPressed() {
            MinecraftClient mc = mc();
            switch (this) {
                case FORWARD -> isKeyPressed(mc.options.forwardKey);
                case FORWARD_LEFT -> {
                    return isKeyPressed(mc.options.forwardKey) && isKeyPressed(mc.options.leftKey);
                }
                case FORWARD_RIGHT -> {
                    return isKeyPressed(mc.options.forwardKey) && isKeyPressed(mc.options.rightKey);
                }
                case LEFT -> isKeyPressed(mc.options.leftKey);
                case RIGHT -> isKeyPressed(mc.options.rightKey);
                case BACK -> isKeyPressed(mc.options.backKey);
                case BACK_LEFT -> {
                    return isKeyPressed(mc.options.backKey) && isKeyPressed(mc.options.leftKey);
                }
                case BACK_RIGHT -> {
                    return isKeyPressed(mc.options.backKey) && isKeyPressed(mc.options.rightKey);
                }
                default -> {
                    return false;
                }
            }
            return false;
        }

        static void apply(InputDirections direction) {
            MinecraftClient mc = mc();
            mc.options.forwardKey.setPressed(false);
            mc.options.leftKey.setPressed(false);
            mc.options.rightKey.setPressed(false);
            mc.options.backKey.setPressed(false);
            switch (direction) {
                case FORWARD -> mc.options.forwardKey.setPressed(true);
                case FORWARD_LEFT -> {
                    mc.options.leftKey.setPressed(true);
                    mc.options.forwardKey.setPressed(true);
                }
                case FORWARD_RIGHT -> {
                    mc.options.rightKey.setPressed(true);
                    mc.options.forwardKey.setPressed(true);
                }
                case LEFT -> mc.options.leftKey.setPressed(true);
                case RIGHT -> mc.options.rightKey.setPressed(true);
                case BACK -> mc.options.backKey.setPressed(true);
                case BACK_LEFT -> {
                    mc.options.backKey.setPressed(true);
                    mc.options.leftKey.setPressed(true);
                }
                case BACK_RIGHT -> {
                    mc.options.backKey.setPressed(true);
                    mc.options.rightKey.setPressed(true);
                }
                case NONE -> {

                }
            }
        }
    }

    static boolean isKeyPressed(KeyBinding keyBinding) {
        return InputUtil.isKeyPressed(MinecraftClient.getInstance().getWindow(), ((MixinAccessorKeyBinding) keyBinding).getBoundKey().getCode());
    }
}
