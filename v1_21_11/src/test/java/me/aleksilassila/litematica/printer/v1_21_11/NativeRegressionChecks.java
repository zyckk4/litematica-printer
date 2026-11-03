package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.actions.PrepareAction;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
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
    private static int assertions;

    public static void main(String[] args) throws Exception {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        checkLatticeAgainstVanilla();
        checkBudgetDefaults();
        checkRollingBudget();
        checkBurstAndReset();
        checkLiveBudgetConfiguration();
        checkInvalidBudgets();
        checkNanoTimeWrap();
        checkLegacyRotationHandoff();
        int placementChecks = NativePlacementContextChecks.run();
        int solverChecks = NativePlacementSolverChecks.run();
        int interactionChecks = InteractionRegressionChecks.run();
        int conversionChecks = SchematicConversionRegressionChecks.run();
        System.out.println("Native regression checks passed: " + assertions
                + " movement/rate assertions; " + placementChecks + " placement-context assertions; "
                + solverChecks + " full-solver assertions; " + interactionChecks + " state/inventory/interaction assertions; "
                + conversionChecks + " schematic-conversion assertions.");
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

    private static void checkBudgetDefaults() {
        check(PrinterConfig.NATIVE_BURST.getDefaultIntegerValue() == 9, "Default burst must allow nine interactions");
        check(PrinterConfig.NATIVE_BURST.getMinIntegerValue() == 1
                && PrinterConfig.NATIVE_BURST.getMaxIntegerValue() == 64, "Burst configuration bounds");
        check(PrinterConfig.INTERACTION_WINDOW_MS.getDefaultIntegerValue() == 310, "Default window lost timing margin");
        check(PrinterConfig.INTERACTION_WINDOW_MS.getMinIntegerValue() == 1
                && PrinterConfig.INTERACTION_WINDOW_MS.getMaxIntegerValue() == 10_000, "Window configuration bounds");
        check(PrinterConfig.INTERACTION_WINDOW_LIMIT.getDefaultIntegerValue() == 9, "Default window limit");
        check(PrinterConfig.INTERACTION_WINDOW_LIMIT.getMinIntegerValue() == 1
                && PrinterConfig.INTERACTION_WINDOW_LIMIT.getMaxIntegerValue() == 64, "Window limit configuration bounds");
        check(LitematicaMixinMod.PRINTING_RANGE.getDefaultDoubleValue() == 4.5, "Default printing range changed");
        check(PrinterConfig.PRINTER_AIRPLACE_RANGE.getDefaultDoubleValue() == 4.5, "Default airplace range changed");
    }

    private static void checkRollingBudget() {
        for (int windowMs : new int[]{300, 310}) {
            long window = windowMs * 1_000_000L;
            PlacementRateLimiter limiter = new PlacementRateLimiter();
            for (int tick = 0; tick < 3; tick++) {
                long now = tick * 50_000_000L;
                for (int probe = 0; probe < 4; probe++) {
                    check(limiter.available(now, tick, 3, windowMs, 9), "Availability query consumed budget");
                }
                for (int place = 0; place < 3; place++) {
                    check(limiter.tryAcquire(now, tick, 3, windowMs, 9), "Burst unexpectedly denied");
                }
                check(!limiter.tryAcquire(now, tick, 3, windowMs, 9), "Fourth placement exceeded per-tick burst");
            }
            check(!limiter.tryAcquire(150_000_000L, 3, 3, windowMs, 9), "Tick advance reset rolling window");
            check(!limiter.tryAcquire(window - 1, 4, 3, windowMs, 9), "Token expired before configured window");
            for (int place = 0; place < 3; place++) {
                check(limiter.tryAcquire(window, 5, 3, windowMs, 9), "Exact-boundary token not released");
            }
            check(!limiter.tryAcquire(window, 6, 3, windowMs, 9), "Boundary expired newer tokens too early");
            check(!limiter.tryAcquire(window + 50_000_000L - 1, 7, 3, windowMs, 9), "Staggered token expired early");
            for (int place = 0; place < 3; place++) {
                check(limiter.tryAcquire(window + 50_000_000L, 8, 3, windowMs, 9), "Staggered token not released");
            }
        }
    }

    private static void checkBurstAndReset() {
        PlacementRateLimiter limiter = new PlacementRateLimiter();
        for (int tick = 0; tick < 9; tick++) {
            check(limiter.tryAcquire(tick * 1_000_000L, tick, 1, 310, 9), "Single-placement burst denied");
            check(!limiter.tryAcquire(tick * 1_000_000L, tick, 1, 310, 9), "Same-tick burst exceeded");
        }
        check(!limiter.tryAcquire(10_000_000L, 10, 64, 310, 9), "Large burst bypassed nine-packet window");
        limiter.reset();
        for (int place = 0; place < 9; place++) {
            check(limiter.tryAcquire(10_000_000L, 10, 9, 310, 9), "Nine-packet burst or reset denied");
        }
        check(!limiter.tryAcquire(10_000_000L, 10, 9, 310, 9), "Ten-packet burst bypassed window");
        check(!limiter.tryAcquire(20_000_000L, 11, 9, 310, 9), "Next tick granted a fresh window");
        limiter.reset();
        for (int place = 0; place < 64; place++) {
            check(limiter.tryAcquire(0, 0, 64, 310, 64), "Configurable limit retained the old nine-packet cap");
        }
        check(!limiter.tryAcquire(0, 1, 64, 310, 64), "Configured 64-packet window exceeded");
    }

    private static void checkLiveBudgetConfiguration() {
        PlacementRateLimiter limiter = new PlacementRateLimiter();
        for (int place = 0; place < 3; place++) {
            check(limiter.tryAcquire(0, 0, 9, 310, 3), "Initial three-packet budget denied");
        }
        check(!limiter.tryAcquire(100_000_000L, 1, 9, 310, 2), "Lowering limit cleared recent usage");
        for (int place = 0; place < 2; place++) {
            check(limiter.tryAcquire(100_000_000L, 1, 9, 310, 5), "Raising limit failed to expose remaining quota");
        }
        check(!limiter.tryAcquire(100_000_000L, 1, 9, 310, 5), "Raising limit reset recent usage");

        limiter.reset();
        for (int place = 0; place < 9; place++) {
            check(limiter.tryAcquire(0, 0, 9, 1_000, 9), "Initial long-window budget denied");
        }
        check(limiter.available(310_000_000L, 1, 9, 300, 9), "Shorter window retained expired active usage");
        check(!limiter.available(310_000_000L, 1, 9, 1_000, 9), "Shrinking then expanding forgot history");
        check(limiter.tryAcquire(310_000_000L, 1, 9, 300, 9), "Shrunk window did not permit a new interaction");
        check(!limiter.tryAcquire(310_000_000L, 1, 9, 1_000, 9), "New short-window interaction erased older history");
        check(!limiter.tryAcquire(999_999_999L, 2, 9, 1_000, 9), "Expanded window expired early");
        for (int place = 0; place < 8; place++) {
            check(limiter.tryAcquire(1_000_000_000L, 3, 9, 1_000, 9), "Expanded-window boundary failed to release old usage");
        }
        check(!limiter.tryAcquire(1_000_000_000L, 4, 9, 1_000, 9), "Newer short-window interaction was not retained");
        check(!limiter.available(9_999_999_999L, 5, 9, 10_000, 9), "History vanished inside maximum configurable window");
        check(!limiter.available(10_000_000_000L, 5, 9, 10_000, 9), "Maximum window expired newer sends with the oldest");
        check(limiter.available(10_310_000_000L, 6, 9, 10_000, 9), "Maximum window failed exact expiration");

        limiter.reset();
        for (int place = 0; place < 5; place++) {
            check(limiter.tryAcquire(0, 0, 9, 310, 64), "Initial burst denied");
        }
        check(!limiter.tryAcquire(0, 0, 2, 310, 64), "Lowering burst cleared same-tick usage");
        check(limiter.tryAcquire(0, 0, 6, 310, 64), "Raising burst did not expose the remaining interaction");
        check(!limiter.tryAcquire(0, 0, 6, 310, 64), "Changing burst reset same-tick usage");
    }

    private static void checkInvalidBudgets() {
        PlacementRateLimiter limiter = new PlacementRateLimiter();
        for (int[] config : new int[][]{{0, 310, 9}, {-1, 310, 9}, {9, 0, 9}, {9, -1, 9},
                {9, 10_001, 9}, {9, 310, 0}, {9, 310, -1}}) {
            check(!limiter.available(0, 0, config[0], config[1], config[2]), "Invalid configuration advertised availability");
            check(!limiter.tryAcquire(0, 0, config[0], config[1], config[2]), "Invalid configuration accepted interaction");
        }
        check(limiter.tryAcquire(0, 0, 1, 1, 1), "Invalid probes consumed budget or minimum window was rejected");
        check(!limiter.tryAcquire(999_999L, 1, 1, 1, 1), "Minimum window expired early");
        check(limiter.tryAcquire(1_000_000L, 1, 1, 1, 1), "Minimum window failed exact expiration");
    }

    private static void checkNanoTimeWrap() {
        for (long origin : new long[]{-1_000_000_000L, Long.MAX_VALUE - 100_000_000L}) {
            for (int windowMs : new int[]{300, 310, 10_000}) {
                long window = windowMs * 1_000_000L;
                PlacementRateLimiter limiter = new PlacementRateLimiter();
                for (int place = 0; place < 9; place++) {
                    check(limiter.tryAcquire(origin, 0, 9, windowMs, 9), "Negative/wrapped clock denied token");
                }
                check(!limiter.tryAcquire(origin + window - 1, 1, 9, windowMs, 9), "Wrapped clock expired early");
                check(limiter.tryAcquire(origin + window, 1, 9, windowMs, 9), "Wrapped clock failed exact expiry");
                if (windowMs < 10_000) {
                    check(!limiter.available(origin + window, 1, 9, 10_000, 9), "Window expansion lost pre-wrap history");
                }
            }
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
