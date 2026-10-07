package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.actions.Action;
import me.aleksilassila.litematica.printer.v1_21_11.actions.ActionChain;
import me.aleksilassila.litematica.printer.v1_21_11.actions.PrepareAction;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

import java.util.LinkedList;
import java.util.Queue;

public class ActionHandler {
    private final MinecraftClient client;
    private final ClientPlayerEntity player;

    private final Queue<Action> currentTickActions = new LinkedList<>();
    private final Queue<Action> previousTickActions = new LinkedList<>();
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
            boolean success = nextAction.send(client, player);
            if (PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) {
                if (success) {
                    System.out.println("Action (Pre) success " + nextAction);
                } else {
                    System.out.println("Action (Pre) failed " + nextAction);
                }
            }
            if (!success) {
                currentTickActions.clear();
                previousTickActions.clear();
                lookAction = null;
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
            boolean success = nextAction.send(client, player);
            if (PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) {
                if (success) {
                    System.out.println("Action (Post Tick) success " + nextAction);
                } else {
                    System.out.println("Action (Post Tick) failed " + nextAction);
                }
            }
            if (!success) {
                previousTickActions.clear();
                currentTickActions.clear();
                lookAction = null;
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
