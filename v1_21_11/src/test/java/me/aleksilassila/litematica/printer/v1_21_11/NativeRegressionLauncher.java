package me.aleksilassila.litematica.printer.v1_21_11;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Arrays;

/** Headless Fabric launch: initialize the loader, never launch Minecraft's main/window. */
public final class NativeRegressionLauncher {
    public static void main(String[] args) throws Exception {
        Knot knot = new Knot(EnvType.CLIENT);
        ClassLoader loader = knot.init(new String[0]);
        knot.addToClassPath(Path.of(NativeRegressionLauncher.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()));
        try {
            Class.forName("me.aleksilassila.litematica.printer.v1_21_11.NativeRegressionChecks", true, loader)
                    .getMethod("main", String[].class).invoke(null, (Object) args);
            // Loading these targets forces production movement, inventory,
            // sequenced-interaction and network mixins to transform their bytecode.
            for (String name : new String[]{
                    "net.minecraft.client.network.ClientPlayerEntity",
                    "net.minecraft.entity.Entity",
                    "net.minecraft.client.network.ClientPlayerInteractionManager",
                    "net.minecraft.client.network.ClientPlayNetworkHandler",
                    "net.minecraft.network.ClientConnection",
                    "net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket",
                    "fi.dy.masa.litematica.config.Configs",
                    "fi.dy.masa.litematica.gui.GuiConfigs",
                    "fi.dy.masa.litematica.event.InputHandler",
                    "fi.dy.masa.litematica.gui.GuiSchematicLoad$ButtonListener",
                    "fi.dy.masa.litematica.schematic.LitematicaSchematic"}) {
                Class.forName(name, false, loader);
            }
            requireInjected(loader, "net.minecraft.client.network.ClientPlayerEntity", "printer$movementYaw");
            requireInjected(loader, "net.minecraft.client.network.ClientPlayerEntity", "printer$declareInput");
            requireInjected(loader, "net.minecraft.client.network.ClientPlayerEntity", "printer$flushNativePlacement");
            requireInjected(loader, "net.minecraft.entity.Entity", "printer$movementSpeed");
            requireInjected(loader, "net.minecraft.client.network.ClientPlayNetworkHandler", "printer$blockUpdate");
            requireInjected(loader, "net.minecraft.client.network.ClientPlayNetworkHandler", "printer$correction");
            requireInjected(loader, "fi.dy.masa.litematica.config.Configs", "loadFromFilePost");
            requireInjected(loader, "fi.dy.masa.litematica.gui.GuiConfigs", "moreOptions");
            requireInjected(loader, "fi.dy.masa.litematica.event.InputHandler", "moreHotkeys");
            requireInjected(loader, "fi.dy.masa.litematica.gui.GuiSchematicLoad$ButtonListener", "onActionPerformedWithButton");
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Error error) throw error;
            if (e.getCause() instanceof Exception exception) throw exception;
            throw e;
        }
        System.out.println("Native printer regression and production Minecraft/Litematica mixin transformation passed with real Litematica/MaLiLib mods.");
        System.out.println("Scope: headless Fabric runtime and native algorithms; no game UI or live server behavior is tested.");
    }

    private static void requireInjected(ClassLoader loader, String target, String handler) throws Exception {
        Class<?> transformed = Class.forName(target, false, loader);
        if (Arrays.stream(transformed.getDeclaredMethods()).noneMatch(method -> method.getName().contains(handler))) {
            throw new AssertionError("Production mixin handler is absent: " + target + " / " + handler);
        }
    }
}
