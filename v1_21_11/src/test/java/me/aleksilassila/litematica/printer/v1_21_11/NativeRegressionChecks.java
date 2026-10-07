package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.actions.PrepareAction;
import me.aleksilassila.litematica.printer.v1_21_11.mixin.MixinAccessorClientPlayerEntity;
import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;
import java.lang.reflect.Field;
import sun.misc.Unsafe;

/** Headless checks against the actual mapped Minecraft dependency; no game client is constructed. */
public final class NativeRegressionChecks {
    private static final long WINDOW = 310_000_000L;
    private static int assertions;

    public static void main(String[] args) throws Exception {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        checkLatticeAgainstVanilla();
        checkRollingBudget();
        checkBurstAndReset();
        checkNanoTimeWrap();
        checkLegacyRotationHandoff();
        int placementChecks = NativePlacementContextChecks.run();
        int solverChecks = NativePlacementSolverChecks.run();
        int interactionChecks = InteractionRegressionChecks.run();
        System.out.println("Native regression checks passed: " + assertions
                + " movement/rate assertions; " + placementChecks + " placement-context assertions; "
                + solverChecks + " full-solver assertions; " + interactionChecks + " state/empty-hand assertions.");
    }

    private static void checkLatticeAgainstVanilla() throws Exception {
        Method rotateInput = MovementHandler.class.getDeclaredMethod("rotateInput", PlayerInput.class, int.class);
        rotateInput.setAccessible(true);
        Method speedFactors = ClientPlayerEntity.class.getDeclaredMethod("applyDirectionalMovementSpeedFactors", Vec2f.class);
        speedFactors.setAccessible(true);
        Method toVelocity = Entity.class.getDeclaredMethod("movementInputToVelocity", Vec3d.class, float.class, float.class);
        toVelocity.setAccessible(true);

        float[] cameraYaws = {-179.0F, -91.0F, -37.0F, 0.0F, 23.0F, 89.0F, 175.0F};
        int combinations = 0;
        for (float cameraYaw : cameraYaws) {
            for (int sideways = -1; sideways <= 1; sideways++) {
                for (int forward = -1; forward <= 1; forward++) {
                    if (sideways == 0 && forward == 0) continue;
                    PlayerInput original = new PlayerInput(forward > 0, forward < 0, sideways > 0, sideways < 0,
                            sideways != 0, forward != 0, sideways == forward);
                    Vec2f localInput = applyVanillaFactors(speedFactors, original, 1.0F);
                    for (int steps = -4; steps <= 4; steps++) {
                        PlayerInput declared = (PlayerInput) rotateInput.invoke(null, original, steps);
                        float serverYaw = cameraYaw - steps * 45.0F;
                        float scale = MovementHandler.movementSpeedScaleForInput(original, steps);
                        Vec2f serverInput = applyVanillaFactors(speedFactors, declared, 1.0F);
                        Vec3d actual = vanillaVelocity(toVelocity, localInput, scale, cameraYaw);
                        Vec3d predicted = vanillaVelocity(toVelocity, serverInput, 1.0F, serverYaw);
                        checkVector(actual, predicted, "World acceleration differs for yaw=" + cameraYaw + ", steps=" + steps);
                        check(declared.jump() == original.jump() && declared.sneak() == original.sneak()
                                && declared.sprint() == original.sprint(), "Rotation changed non-direction keys");
                        check(!declared.forward() || !declared.backward(), "Opposing forward/backward keys");
                        check(!declared.left() || !declared.right(), "Opposing left/right keys");
                        check(original.equals(rotateInput.invoke(null, declared, -steps)), "Input rotation failed inverse check");

                        // Every lattice step must preserve the pre-clamp
                        // slowdown multiplier, including odd 45-degree turns.
                        for (float slowdown : new float[]{0.2F, 0.3F}) {
                            Vec2f slowLocal = applyVanillaFactors(speedFactors, original, slowdown);
                            Vec2f slowServer = applyVanillaFactors(speedFactors, declared, slowdown);
                            float slowScale = MovementHandler.movementSpeedScaleForInput(original, steps, slowdown);
                            checkVector(vanillaVelocity(toVelocity, slowLocal, slowScale, cameraYaw),
                                    vanillaVelocity(toVelocity, slowServer, 1.0F, serverYaw), "Slow movement mismatch");
                        }
                        combinations++;
                    }
                }
            }
        }
        check(combinations == 504, "The complete 504-case lattice was not exercised");
        for (int steps = -8; steps <= 8; steps++) {
            check(rotateInput.invoke(null, PlayerInput.DEFAULT, steps).equals(PlayerInput.DEFAULT), "Idle keys became movement");
            check(MovementHandler.movementSpeedScaleForInput(PlayerInput.DEFAULT, steps) == 1.0F, "Idle input speed changed");
        }
    }

    private static Vec2f applyVanillaFactors(Method method, PlayerInput input, float slowdown) throws Exception {
        int sideways = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
        int forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        // These are vanilla KeyboardInput.tick and applyMovementSpeedFactors'
        // inputs; the directional clamp itself is the real Minecraft method.
        Vec2f normalized = new Vec2f(sideways, forward).normalize().multiply(0.98F * slowdown);
        return (Vec2f) method.invoke(null, normalized);
    }

    private static Vec3d vanillaVelocity(Method method, Vec2f input, float speed, float yaw) throws Exception {
        return (Vec3d) method.invoke(null, new Vec3d(input.x, 0.0, input.y), speed, yaw);
    }

    private static void checkRollingBudget() {
        PlacementRateLimiter limiter = new PlacementRateLimiter();
        for (int tick = 0; tick < 3; tick++) {
            long now = tick * 50_000_000L;
            for (int probe = 0; probe < 4; probe++) check(limiter.available(now, tick, 3), "Availability query consumed budget");
            for (int place = 0; place < 3; place++) check(limiter.tryAcquire(now, tick, 3), "Burst unexpectedly denied");
            check(!limiter.tryAcquire(now, tick, 3), "Fourth placement exceeded per-tick burst");
        }
        check(!limiter.tryAcquire(150_000_000L, 3, 3), "Tick advance reset rolling window");
        check(!limiter.tryAcquire(WINDOW - 1, 4, 3), "Token expired before 310 ms");
        for (int place = 0; place < 3; place++) check(limiter.tryAcquire(WINDOW, 5, 3), "Exact-boundary token not released");
        check(!limiter.tryAcquire(WINDOW, 6, 3), "Boundary expired newer tokens too early");
        check(!limiter.tryAcquire(WINDOW + 50_000_000L - 1, 7, 3), "Staggered token expired early");
        for (int place = 0; place < 3; place++) check(limiter.tryAcquire(WINDOW + 50_000_000L, 8, 3), "Staggered token not released");
    }

    private static void checkBurstAndReset() {
        PlacementRateLimiter limiter = new PlacementRateLimiter();
        check(!limiter.tryAcquire(0, 0, 0), "Disabled burst accepted a placement");
        for (int tick = 0; tick < 9; tick++) {
            check(limiter.tryAcquire(tick * 1_000_000L, tick, 1), "Single-placement burst denied");
            check(!limiter.tryAcquire(tick * 1_000_000L, tick, 1), "Same-tick burst exceeded");
        }
        check(!limiter.tryAcquire(10_000_000L, 10, 9), "Large burst bypassed nine-packet window");
        limiter.reset();
        for (int place = 0; place < 9; place++) check(limiter.tryAcquire(10_000_000L, 10, 9), "Reset retained stale budget");
        check(!limiter.tryAcquire(10_000_000L, 10, 9), "Ten-packet burst bypassed window");
    }

    private static void checkNanoTimeWrap() {
        for (long origin : new long[]{-1_000_000_000L, Long.MAX_VALUE - 100_000_000L}) {
            PlacementRateLimiter limiter = new PlacementRateLimiter();
            for (int place = 0; place < 9; place++) check(limiter.tryAcquire(origin, 0, 9), "Negative/wrapped clock denied token");
            check(!limiter.tryAcquire(origin + WINDOW - 1, 1, 9), "Wrapped clock expired early");
            check(limiter.tryAcquire(origin + WINDOW, 1, 9), "Wrapped clock failed exact expiry");
        }
    }

    private static void checkLegacyRotationHandoff() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        ClientPlayerEntity player = (ClientPlayerEntity) unsafe.allocateInstance(ClientPlayerEntity.class);
        player.setYaw(25.0F);
        player.setPitch(12.0F);
        MixinAccessorClientPlayerEntity cache = (MixinAccessorClientPlayerEntity) player;
        cache.setLastYaw(player.getYaw());
        cache.setLastPitch(player.getPitch());
        Printer previousPrinter = LitematicaMixinMod.printer;
        try {
            // Exercise the production packet-constructor mixin: the requested
            // angle is deliberately different from the angle actually sent.
            Printer fixture = (Printer) unsafe.allocateInstance(Printer.class);
            ActionHandler actions = new ActionHandler(null, player);
            Field actionHandler = Printer.class.getDeclaredField("actionHandler");
            actionHandler.setAccessible(true);
            actionHandler.set(fixture, actions);
            PrepareAction legacyLook = (PrepareAction) unsafe.allocateInstance(PrepareAction.class);
            legacyLook.modifyYaw = true;
            legacyLook.modifyPitch = true;
            legacyLook.yaw = -90.0F;
            legacyLook.pitch = 75.0F;
            actions.lookAction = legacyLook;
            LitematicaMixinMod.printer = fixture;

            PlayerMoveC2SPacket legacy = new PlayerMoveC2SPacket.Full(0, 64, 0, 110.0F, -20.0F, true, false);
            check(legacy.getYaw(0) == -90.0F && legacy.getPitch(0) == 75.0F,
                    "Production constructor did not rewrite the legacy packet");
            MovementHandler.recordSentRotation(player, legacy);
            check(cache.getLastYaw() == legacy.getYaw(0) && cache.getLastPitch() == legacy.getPitch(0),
                    "Last-sent cache records requested angles rather than actual packet angles");
            check(player.getYaw() == 25.0F && player.getPitch() == 12.0F,
                    "Recording a legacy packet changed the local camera");
            check(player.getYaw() != cache.getLastYaw() || player.getPitch() != cache.getLastPitch(),
                    "Vanilla would skip the restoring native rotation after a legacy packet");

            actions.clearLookAction();
            PlayerMoveC2SPacket nativeLook = new PlayerMoveC2SPacket.LookAndOnGround(
                    player.getYaw(), player.getPitch(), true, false);
            MovementHandler.recordSentRotation(player, nativeLook);
            check(cache.getLastYaw() == player.getYaw() && cache.getLastPitch() == player.getPitch(),
                    "Look-only restoration did not update both cached angles");
            MovementHandler.recordSentRotation(player, new PlayerMoveC2SPacket.OnGroundOnly(true, false));
            check(cache.getLastYaw() == player.getYaw() && cache.getLastPitch() == player.getPitch(),
                    "A packet without rotation overwrote the last-sent cache");
        } finally {
            LitematicaMixinMod.printer = previousPrinter;
            MovementHandler.clearRotation();
        }
    }

    private static void checkVector(Vec3d actual, Vec3d expected, String message) {
        // Minecraft's trigonometric lookup table and float angle conversion
        // introduce <1e-4 drift at equivalent angles crossing a full turn.
        check(actual.distanceTo(expected) < 0.0003, message + ": " + actual + " vs " + expected);
    }

    private static void check(boolean value, String message) {
        assertions++;
        if (!value) throw new AssertionError(message);
    }
}
