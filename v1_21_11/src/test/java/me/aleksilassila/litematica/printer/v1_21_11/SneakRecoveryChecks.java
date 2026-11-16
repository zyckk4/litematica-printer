package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.actions.Action;
import me.aleksilassila.litematica.printer.v1_21_11.actions.ActionChain;
import me.aleksilassila.litematica.printer.v1_21_11.actions.PresShift;
import me.aleksilassila.litematica.printer.v1_21_11.actions.ReleaseShiftAction;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_11.guides.placement.FallingBlockGuide;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec3d;

/** Checks synthetic sneak ownership using real action queues and inventory selection. */
final class SneakRecoveryChecks {
    private static int assertions;

    static int run() throws Exception {
        assertions = 0;
        try (StateInteractionTestFixture fixture = new StateInteractionTestFixture()) {
            fixture.configure(PrinterConfig.PRINTER_AIRPLACE, false);
            fixture.configure(PrinterConfig.ROTATE_PLAYER, false);
            fixture.configure(PrinterConfig.INVENTORY_DELAY, 10);
            checkInventoryDeferral(fixture);
            checkRejectedPlacement(fixture);
            checkClearAndUserInput(fixture);
            checkCancellationAndSuccess(fixture);
            checkImmediateFailure(fixture);
            checkExceptions(fixture);
            checkStaleOwners(fixture);
        }
        return assertions;
    }

    private static void resetStationary(StateInteractionTestFixture fixture) throws Exception {
        fixture.reset();
        fixture.setVelocity(Vec3d.ZERO);
    }

    private static void queueFallingPlacement(StateInteractionTestFixture fixture, int materialSlot) {
        fixture.target(Blocks.AIR.getDefaultState(), Blocks.SAND.getDefaultState());
        fixture.world.blocks.put(StateInteractionTestFixture.TARGET.down(), Blocks.CHEST.getDefaultState());
        fixture.player.inventory.main.set(materialSlot, new ItemStack(Items.SAND, 4));
        FallingBlockGuide guide = new FallingBlockGuide(fixture.state(StateInteractionTestFixture.TARGET));
        check(guide.canExecute(fixture.player), "Supported sand did not reach the legacy placement guide");
        var actions = guide.execute(fixture.player);
        check(actions.size() == 1 && actions.getFirst() instanceof ActionChain, "Missing real placement chain");
        ActionChain chain = (ActionChain) actions.getFirst();
        check(chain.getActionsCurrentTick().stream().anyMatch(PresShift.class::isInstance)
                        && chain.getActionsNextTick().getLast() instanceof ReleaseShiftAction,
                "Interactive support did not reserve a next-tick sneak release");
        fixture.printer.actionHandler.addActions(actions.toArray(Action[]::new));
    }

    private static void runPressThenRefreshInput(StateInteractionTestFixture fixture) {
        fixture.printer.actionHandler.processCurrentTickActions();
        check(fixture.network.inputs.size() == 1 && fixture.network.inputs.getFirst().sneak(),
                "Temporary sneak was not sent before placement");
        check(fixture.player.getLastPlayerInput().equals(PlayerInput.DEFAULT),
                "Temporary sneak changed vanilla's tracker and would release before next-tick use");
        // Vanilla samples the unchanged physical keys before its input equality
        // check. With the tracker still DEFAULT, no vanilla reset packet follows.
        fixture.player.input.playerInput = PlayerInput.DEFAULT;
    }

    private static void checkInventoryDeferral(StateInteractionTestFixture fixture) throws Exception {
        resetStationary(fixture);
        queueFallingPlacement(fixture, 12);
        runPressThenRefreshInput(fixture);
        fixture.printer.actionHandler.processPreviousTickActions();
        check(fixture.interaction.swaps == 1 && fixture.interaction.blockUses == 0,
                "Real inventory deferral was not reached before the block use");
        checkReleased(fixture, "Inventory deferral stranded server sneak");
    }

    private static void checkRejectedPlacement(StateInteractionTestFixture fixture) throws Exception {
        resetStationary(fixture);
        queueFallingPlacement(fixture, 2);
        runPressThenRefreshInput(fixture);
        fixture.interaction.rejectBlocks = true;
        fixture.printer.actionHandler.processPreviousTickActions();
        check(fixture.interaction.blockUses == 1, "Rejected real placement was not attempted");
        checkReleased(fixture, "Rejected block use stranded server sneak");
    }

    private static void queuePress(StateInteractionTestFixture fixture, Action afterPress, boolean sameTick) {
        ActionChain chain = new ActionChain();
        chain.addImmediateAction(new PresShift());
        if (afterPress != null) {
            if (sameTick) chain.addImmediateAction(afterPress);
            else chain.addNextTickAction(afterPress);
        }
        chain.addNextTickAction(new ReleaseShiftAction());
        fixture.printer.actionHandler.addActions(chain);
    }

    private static void checkClearAndUserInput(StateInteractionTestFixture fixture) throws Exception {
        resetStationary(fixture);
        fixture.printer.actionHandler.clear();
        check(fixture.network.inputs.isEmpty(), "Clearing without an owned press sent user input");
        queuePress(fixture, null, false);
        runPressThenRefreshInput(fixture);
        PlayerInput userInput = new PlayerInput(true, false, false, true, true, false, true);
        fixture.player.input.playerInput = userInput;
        fixture.client.options.sneakKey.setPressed(true);
        fixture.printer.actionHandler.clear();
        PlayerInput restored = fixture.network.inputs.getLast();
        check(restored.equals(new PlayerInput(true, false, false, true, true, true, true)),
                "Cleanup changed other input bits or released the user's held sneak key");
        check(fixture.player.input.playerInput.equals(restored), "Cleanup did not restore local input consistently");
        int sent = fixture.network.inputs.size();
        fixture.printer.actionHandler.clear();
        check(fixture.network.inputs.size() == sent, "Repeated clear emitted another release");
    }

    private static void checkCancellationAndSuccess(StateInteractionTestFixture fixture) throws Exception {
        resetStationary(fixture);
        queuePress(fixture, null, false);
        runPressThenRefreshInput(fixture);
        fixture.configure(LitematicaMixinMod.PRINT_MODE, false);
        fixture.printer.actionHandler.processPreviousTickActions();
        checkReleased(fixture, "Cancelling printing stranded server sneak");
        fixture.configure(LitematicaMixinMod.PRINT_MODE, true);

        resetStationary(fixture);
        queuePress(fixture, null, false);
        runPressThenRefreshInput(fixture);
        fixture.printer.onServerCorrection();
        checkReleased(fixture, "Server correction stranded server sneak");

        resetStationary(fixture);
        queuePress(fixture, null, false);
        runPressThenRefreshInput(fixture);
        fixture.printer.actionHandler.processPreviousTickActions();
        checkReleased(fixture, "Successful chain did not release sneak");
    }

    private static void checkExceptions(StateInteractionTestFixture fixture) throws Exception {
        for (boolean sameTick : new boolean[]{false, true}) {
            resetStationary(fixture);
            RuntimeException original = new IllegalStateException("Expected action failure");
            Action failure = new Action() {
                @Override public boolean send(MinecraftClient client, ClientPlayerEntity player) { throw original; }
            };
            queuePress(fixture, failure, sameTick);
            if (!sameTick) runPressThenRefreshInput(fixture);
            try {
                if (sameTick) fixture.printer.actionHandler.processCurrentTickActions();
                else fixture.printer.actionHandler.processPreviousTickActions();
                throw new AssertionError("Action exception was swallowed");
            } catch (RuntimeException actual) {
                check(actual == original, "Cleanup masked the original action exception");
            }
            checkReleased(fixture, "Exception stranded server sneak");
        }

        resetStationary(fixture);
        RuntimeException original = new IllegalStateException("Expected primary failure");
        RuntimeException cleanup = new IllegalStateException("Expected disconnected recovery failure");
        queuePress(fixture, new Action() {
            @Override public boolean send(MinecraftClient client, ClientPlayerEntity player) { throw original; }
        }, false);
        runPressThenRefreshInput(fixture);
        fixture.network.inputFailure = cleanup;
        try {
            fixture.printer.actionHandler.processPreviousTickActions();
            throw new AssertionError("Primary exception was swallowed during failed recovery");
        } catch (RuntimeException actual) {
            check(actual == original && actual.getSuppressed().length == 1
                            && actual.getSuppressed()[0] == cleanup,
                    "Failed recovery masked the primary exception or lost its suppressed cause");
        } finally {
            fixture.network.inputFailure = null;
        }
        fixture.printer.actionHandler.clear();
        check(fixture.printer.actionHandler.acceptsActions() && fixture.network.inputs.size() == 1,
                "Failed recovery retained an old connection lease");
    }

    private static void checkImmediateFailure(StateInteractionTestFixture fixture) throws Exception {
        resetStationary(fixture);
        queuePress(fixture, new Action() {
            @Override public boolean send(MinecraftClient client, ClientPlayerEntity player) { return false; }
        }, true);
        fixture.printer.actionHandler.processCurrentTickActions();
        checkReleased(fixture, "Same-tick failure stranded server sneak");
    }

    private static void checkStaleOwners(StateInteractionTestFixture fixture) throws Exception {
        for (int changedOwner = 0; changedOwner < 3; changedOwner++) {
            resetStationary(fixture);
            queuePress(fixture, null, false);
            runPressThenRefreshInput(fixture);
            try {
                if (changedOwner == 0) fixture.client.world = null;
                else if (changedOwner == 1) fixture.client.player = null;
                else StateInteractionTestFixture.set(ClientPlayerEntity.class, fixture.player, "networkHandler", null);
                fixture.printer.actionHandler.clear();
                check(fixture.network.inputs.size() == 1, "Cleanup sent to a stale player, world or connection");
            } finally {
                fixture.client.world = fixture.world;
                fixture.client.player = fixture.player;
                StateInteractionTestFixture.set(ClientPlayerEntity.class, fixture.player, "networkHandler", fixture.network);
            }
            fixture.printer.actionHandler.clear();
            check(fixture.network.inputs.size() == 1, "Dropped stale lease was restored on a later clear");
        }
    }

    private static void checkReleased(StateInteractionTestFixture fixture, String message) {
        check(fixture.network.inputs.size() == 2 && !fixture.network.inputs.getLast().sneak(), message);
        check(fixture.printer.actionHandler.acceptsActions(), "Cancelled chain retained queued actions");
        fixture.printer.actionHandler.clear();
        check(fixture.network.inputs.size() == 2, "Completed cleanup retained synthetic sneak ownership");
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
