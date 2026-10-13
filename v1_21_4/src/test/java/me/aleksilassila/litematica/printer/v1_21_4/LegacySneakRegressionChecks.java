package me.aleksilassila.litematica.printer.v1_21_4;

import me.aleksilassila.litematica.printer.v1_21_4.actions.Action;
import me.aleksilassila.litematica.printer.v1_21_4.actions.ActionChain;
import me.aleksilassila.litematica.printer.v1_21_4.actions.PrepareAction;
import me.aleksilassila.litematica.printer.v1_21_4.actions.PrepareLook;
import me.aleksilassila.litematica.printer.v1_21_4.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_4.guides.placement.PlacementGuide;
import me.aleksilassila.litematica.printer.v1_21_4.implementation.PrinterPlacementContext;
import me.aleksilassila.litematica.printer.v1_21_4.implementation.actions.InteractActionImpl;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Captures real legacy actions at the interaction/network boundary. */
public final class LegacySneakRegressionChecks {
    private static int assertions;

    public static int run() throws Exception {
        assertions = 0;
        MinecraftClient previous = MinecraftClient.getInstance();
        boolean airOnly = PrinterConfig.PRINTER_AIRPLACE_ONLY.getBooleanValue();
        try {
            Fixture fixture = new Fixture();
            for (ActionResult result : new ActionResult[]{ActionResult.SUCCESS, ActionResult.FAIL}) {
                fixture.reset(false, false);
                fixture.interaction.result = result;
                check(fixture.action(true).send(fixture.client, fixture.player) == result.isAccepted(),
                        "Legacy action did not retain its accepted/rejected result");
                fixture.checkRestored();
                check(fixture.network.events.equals(List.of("PRESS_SHIFT_KEY", "USE", "RELEASE_SHIFT_KEY")),
                        "1.21.4 interaction did not bracket UseOn with sneak commands");
                check(fixture.interaction.observedSneaking && fixture.interaction.observedServerSneaking,
                        "Local prediction and server interaction disagree about sneak");
                check(Printer.blockPosTimeout.size() == (result.isAccepted() ? 1 : 0),
                        "Rejected interaction was hidden by a block timeout");
            }

            fixture.reset(false, false);
            fixture.interaction.throwAfterUse = true;
            expectFailure(() -> fixture.action(true).send(fixture.client, fixture.player), "interaction");
            fixture.checkRestored();
            check(fixture.network.events.equals(List.of("PRESS_SHIFT_KEY", "USE", "RELEASE_SHIFT_KEY")),
                    "Interaction exception left a server sneak lease");
            check(Printer.blockPosTimeout.isEmpty(), "Throwing interaction created a block timeout");

            for (ClientCommandC2SPacket.Mode failure : new ClientCommandC2SPacket.Mode[]{
                    ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY, ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY}) {
                fixture.reset(false, false);
                fixture.network.throwOn = failure;
                expectFailure(() -> fixture.action(true).send(fixture.client, fixture.player), "network");
                fixture.checkRestored();
                check(fixture.network.events.getLast().equals("RELEASE_SHIFT_KEY"),
                        "Failed command did not attempt release after restoring local input");
                check(fixture.interaction.calls == (failure == ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY ? 0 : 1),
                        "Interaction ran despite a failed sneak acquisition");
            }

            fixture.reset(true, true);
            check(fixture.action(true).send(fixture.client, fixture.player), "User-sneaking placement rejected");
            fixture.checkRestored();
            check(fixture.network.events.equals(List.of("USE")) && fixture.network.serverSneaking,
                    "Printer released the user's own sneak state");
            check(fixture.interaction.observedSneaking, "User sneak missing from local prediction");

            fixture.reset(false, false);
            check(fixture.action(false).send(fixture.client, fixture.player), "Ordinary interaction rejected");
            fixture.checkRestored();
            check(fixture.network.events.equals(List.of("USE")) && !fixture.interaction.observedSneaking,
                    "Ordinary interaction acquired an unnecessary sneak lease");

            fixture.reset(false, true);
            check(!fixture.action(true).send(fixture.client, fixture.player), "Fresh user sneak key was temporarily released");
            fixture.checkRestored();
            check(fixture.network.events.isEmpty() && fixture.interaction.calls == 0,
                    "Unsampled user sneak input still sent an interaction");

            fixture.reset(false, false);
            while (Printer.tryAcquirePlacementPacket()) {}
            check(!fixture.action(true).send(fixture.client, fixture.player), "Exhausted budget accepted interaction");
            fixture.checkRestored();
            check(fixture.network.events.isEmpty() && fixture.interaction.calls == 0,
                    "Budget denial changed sneak state or sent a packet");

            fixture.reset(false, false);
            PrinterConfig.PRINTER_AIRPLACE_ONLY.setBooleanValue(false);
            SchematicBlockState state = (SchematicBlockState) fixture.unsafe.allocateInstance(SchematicBlockState.class);
            set(SchematicBlockState.class, state, "currentState", Blocks.AIR.getDefaultState());
            set(SchematicBlockState.class, state, "targetState", Blocks.STONE.getDefaultState());
            PlacementGuide guide = new ProbePlacementGuide(state, fixture.context(true));
            List<Action> actions = guide.execute(fixture.player);
            check(actions.size() == 1 && actions.getFirst() instanceof ActionChain, "Placement guide lost its chain");
            ActionChain chain = (ActionChain) actions.getFirst();
            check(chain.getActionsCurrentTick().size() == 1
                            && chain.getActionsCurrentTick().getFirst() instanceof PrepareLook,
                    "Placement guide acquired sneak before the interaction tick");
            check(chain.getActionsNextTick().size() == 2
                            && chain.getActionsNextTick().getFirst() instanceof PrepareAction
                            && chain.getActionsNextTick().getLast() instanceof InteractActionImpl,
                    "Placement guide still relies on a queued sneak release");
            ActionHandler handler = new ActionHandler(fixture.client, fixture.player);
            handler.addActions(actions.toArray(Action[]::new));
            handler.clear();
            check(handler.acceptsActions() && fixture.network.events.isEmpty(),
                    "Cancelling an unstarted placement retained network or queued state");
            fixture.checkRestored();
        } finally {
            set(MinecraftClient.class, null, "instance", previous);
            PrinterConfig.PRINTER_AIRPLACE_ONLY.setBooleanValue(airOnly);
            rateLimiter().reset();
            Printer.blockPosTimeout.clear();
        }
        return assertions;
    }

    private static final class Fixture {
        private final Unsafe unsafe;
        private final MinecraftClient client;
        private final FixturePlayer player;
        private final FixtureNetwork network;
        private final FixtureInteraction interaction;
        private PlayerInput original;
        private boolean originalKey;

        private Fixture() throws Exception {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            unsafe = (Unsafe) field.get(null);
            client = (MinecraftClient) unsafe.allocateInstance(MinecraftClient.class);
            player = (FixturePlayer) unsafe.allocateInstance(FixturePlayer.class);
            network = (FixtureNetwork) unsafe.allocateInstance(FixtureNetwork.class);
            interaction = (FixtureInteraction) unsafe.allocateInstance(FixtureInteraction.class);
            network.events = new ArrayList<>();
            interaction.network = network;
            player.input = new Input();
            set(ClientPlayerEntity.class, player, "networkHandler", network);
            client.player = player;
            client.interactionManager = interaction;
            GameOptions options = (GameOptions) unsafe.allocateInstance(GameOptions.class);
            set(GameOptions.class, options, "sneakKey", unsafe.allocateInstance(KeyBinding.class));
            set(MinecraftClient.class, client, "options", options);
            set(MinecraftClient.class, null, "instance", client);
        }

        private void reset(boolean sneaking, boolean key) throws Exception {
            original = new PlayerInput(true, false, true, false, true, sneaking, true);
            player.input.playerInput = original;
            player.input.movementSideways = 0.3F;
            player.input.movementForward = 0.3F;
            originalKey = key;
            client.options.sneakKey.setPressed(key);
            network.events.clear();
            network.serverSneaking = sneaking;
            network.throwOn = null;
            interaction.result = ActionResult.SUCCESS;
            interaction.throwAfterUse = false;
            interaction.calls = 0;
            interaction.observedSneaking = false;
            interaction.observedServerSneaking = false;
            rateLimiter().reset();
            Printer.blockPosTimeout.clear();
        }

        private PrinterPlacementContext context(boolean sneak) throws Exception {
            PrinterPlacementContext context = (PrinterPlacementContext) unsafe.allocateInstance(PrinterPlacementContext.class);
            set(PrinterPlacementContext.class, context, "shouldSneak", sneak);
            set(PrinterPlacementContext.class, context, "hitResult", new BlockHitResult(
                    new Vec3d(0.5, 64.5, 1), Direction.UP, new BlockPos(0, 64, 1), false));
            return context;
        }

        private InteractActionImpl action(boolean sneak) throws Exception { return new InteractActionImpl(context(sneak)); }

        private void checkRestored() {
            check(player.input.playerInput == original, "Temporary action did not restore the original input record");
            check(player.input.movementSideways == 0.3F && player.input.movementForward == 0.3F,
                    "Temporary action changed local movement axes");
            check(client.options.sneakKey.isPressed() == originalKey, "Temporary action changed the user's key");
            check(network.serverSneaking == original.sneak(), "Temporary action left server sneak changed");
        }
    }

    private static final class FixturePlayer extends ClientPlayerEntity {
        private FixturePlayer() { super(null, null, null, null, null, false, false); }
        // isSneaking deliberately remains the real 1.21.4 method.
        @Override public void swingHand(Hand hand) {}
    }

    private static final class FixtureNetwork extends ClientPlayNetworkHandler {
        private List<String> events;
        private boolean serverSneaking;
        private ClientCommandC2SPacket.Mode throwOn;
        private FixtureNetwork() { super(null, null, null); }
        @Override public void sendPacket(Packet<?> packet) {
            if (packet instanceof ClientCommandC2SPacket command) {
                events.add(command.getMode().name());
                if (command.getMode() == ClientCommandC2SPacket.Mode.PRESS_SHIFT_KEY) serverSneaking = true;
                else if (command.getMode() == ClientCommandC2SPacket.Mode.RELEASE_SHIFT_KEY) serverSneaking = false;
                if (command.getMode() == throwOn) throw new IllegalStateException("network");
            } else if (packet instanceof PlayerInteractBlockC2SPacket) events.add("USE");
            else throw new AssertionError("Unexpected legacy sneak packet: " + packet);
        }
    }

    private static final class FixtureInteraction extends ClientPlayerInteractionManager {
        private FixtureNetwork network;
        private ActionResult result;
        private boolean throwAfterUse;
        private int calls;
        private boolean observedSneaking;
        private boolean observedServerSneaking;
        private FixtureInteraction() { super(null, null); }
        @Override public ActionResult interactBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hit) {
            calls++;
            observedSneaking = player.isSneaking();
            observedServerSneaking = network.serverSneaking;
            network.sendPacket(new PlayerInteractBlockC2SPacket(hand, hit, 1));
            if (throwAfterUse) throw new IllegalStateException("interaction");
            return result;
        }
    }

    private static final class ProbePlacementGuide extends PlacementGuide {
        private final PrinterPlacementContext context;
        private ProbePlacementGuide(SchematicBlockState state, PrinterPlacementContext context) { super(state); this.context = context; }
        @Override protected boolean getUseShift(SchematicBlockState state) { return context.shouldSneak; }
        @Override public PrinterPlacementContext getPlacementContext(ClientPlayerEntity player) { return context; }
    }

    private static PlacementRateLimiter rateLimiter() throws Exception {
        Field field = Printer.class.getDeclaredField("placementRate");
        field.setAccessible(true);
        return (PlacementRateLimiter) field.get(null);
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void expectFailure(CheckedAction action, String message) throws Exception {
        try {
            action.run();
            throw new AssertionError("Expected " + message + " exception");
        } catch (IllegalStateException expected) {
            check(message.equals(expected.getMessage()), "Unexpected exception source: " + expected);
        }
    }

    @FunctionalInterface private interface CheckedAction { void run() throws Exception; }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
