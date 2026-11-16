package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.actions.Action;
import me.aleksilassila.litematica.printer.v1_21_11.actions.ActionChain;
import me.aleksilassila.litematica.printer.v1_21_11.actions.PrepareAction;
import me.aleksilassila.litematica.printer.v1_21_11.actions.PresShift;
import me.aleksilassila.litematica.printer.v1_21_11.actions.ReleaseShiftAction;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;

import java.util.LinkedList;
import java.util.Queue;

public class ActionHandler {
    private final MinecraftClient client;
    private final ClientPlayerEntity player;

    private final Queue<Action> currentTickActions = new LinkedList<>();
    private final Queue<Action> previousTickActions = new LinkedList<>();
    private ClientWorld ownedSneakWorld;
    private ClientPlayNetworkHandler ownedSneakConnection;
    public PrepareAction lookAction = null;

    public ActionHandler(MinecraftClient client, ClientPlayerEntity player) {
        this.client = client;
        this.player = player;
    }

    public void processCurrentTickActions() {
        if (!Printer.canRunLegacyActions(client, player)) {
            clear();
            return;
        }
        Action nextAction = currentTickActions.poll();
        boolean sentAny = false;
        while (nextAction != null) {
            if (LitematicaMixinMod.DEBUG) {
                System.out.println("Sending action " + nextAction);
            }
            boolean success = sendAction(nextAction);
            if (PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) {
                if (success) {
                    System.out.println("Action (Pre) success " + nextAction);
                } else {
                    System.out.println("Action (Pre) failed " + nextAction);
                }
            }
            if (!success) {
                clear();
                break;
            }
            sentAny = true;
            nextAction = currentTickActions.poll();
        }
        if (sentAny) {
            Printer.inactivityCounter = 0;
        }
    }

    public void processPreviousTickActions() {
        if (!Printer.canRunLegacyActions(client, player)) {
            clear();
            return;
        }
        // Hours spent figuring this out: 6
        Action nextAction = previousTickActions.poll();
        while (nextAction != null) {
            if (LitematicaMixinMod.DEBUG) {
                System.out.println("Sending action " + nextAction);
            }
            boolean success = sendAction(nextAction);
            if (PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) {
                if (success) {
                    System.out.println("Action (Post Tick) success " + nextAction);
                } else {
                    System.out.println("Action (Post Tick) failed " + nextAction);
                }
            }
            if (!success) {
                clear();
                break;
            }
            nextAction = previousTickActions.poll();
        }
    }

    public boolean acceptsActions() {
        return currentTickActions.isEmpty() && previousTickActions.isEmpty();
    }

    public void clear() {
        currentTickActions.clear();
        previousTickActions.clear();
        lookAction = null;
        releaseOwnedSneak();
    }

    private boolean sendAction(Action action) {
        if (action instanceof PresShift && ownedSneakWorld == null) {
            // Claim before sending so an exception after a partially sent
            // packet still takes the recovery path. Do not alter vanilla's
            // lastPlayerInput: the press must survive until next-tick use.
            ownedSneakWorld = client.world;
            ownedSneakConnection = player.networkHandler;
        }
        try {
            boolean success = action.send(client, player);
            if (success && action instanceof ReleaseShiftAction) forgetOwnedSneak();
            return success;
        } catch (RuntimeException | Error failure) {
            try {
                clear();
            } catch (RuntimeException | Error cleanupFailure) {
                if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    private void forgetOwnedSneak() {
        ownedSneakWorld = null;
        ownedSneakConnection = null;
    }

    private void releaseOwnedSneak() {
        ClientWorld world = ownedSneakWorld;
        ClientPlayNetworkHandler connection = ownedSneakConnection;
        // Clear the lease before sending: repeated cancellation is idempotent,
        // including a failed send while the connection is shutting down.
        forgetOwnedSneak();
        if (world == null || connection == null || client.player != player
                || client.world != world || player.getEntityWorld() != world
                || player.networkHandler != connection) return;
        // Restore the live sneak key while retaining every other input bit.
        // Cancelling printing must not release a key the user is holding.
        new ReleaseShiftAction().send(client, player);
    }

    public void addActions(Action... actions) {
        if (!acceptsActions()) return;

        for (Action action : actions) {
            if (action instanceof PrepareAction)
                lookAction = (PrepareAction) action;
        }

        for (Action action : actions) {
            if (action instanceof ActionChain chain) {
                currentTickActions.addAll(chain.getActionsCurrentTick());
                previousTickActions.addAll(chain.getActionsNextTick());
            } else {
                currentTickActions.add(action);
            }
        }
    }

    /** Clears a legacy silent-look lease before a native packet batch. */
    public void clearLookAction() {
        lookAction = null;
    }
}
