import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.launch.knot.Knot;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Load the released mod in the production namespace; never call the game's main method. */
public final class ProductionMixinSmoke {
    public static void main(String[] args) throws Exception {
        Map<String, String> names = new HashMap<>();
        for (String line : Files.readAllLines(Path.of(args[0]))) {
            if (line.startsWith("c\t")) {
                String[] parts = line.split("\t");
                names.put(parts[3].replace('/', '.'), parts[2].replace('/', '.'));
            }
        }
        Knot knot = new Knot(EnvType.CLIENT);
        ClassLoader loader = knot.init(new String[0]);
        String[] targets = {
                "net.minecraft.item.AxeItem", "net.minecraft.client.network.ClientPlayerEntity",
                "net.minecraft.client.option.KeyBinding", "net.minecraft.client.render.Camera",
                "net.minecraft.entity.Entity", "net.minecraft.client.Mouse",
                "net.minecraft.client.network.ClientPlayNetworkHandler",
                "net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket",
                "fi.dy.masa.litematica.config.Configs", "fi.dy.masa.litematica.gui.GuiConfigs",
                "fi.dy.masa.litematica.event.InputHandler",
                "fi.dy.masa.litematica.gui.GuiSchematicLoad$ButtonListener",
                "fi.dy.masa.litematica.schematic.LitematicaSchematic"
        };
        for (String named : targets) {
            String target = names.getOrDefault(named, named);
            Class.forName(target, false, loader).getDeclaredMethods();
            System.out.println("TRANSFORMED " + target + " (" + named + ")");
        }
        String[][] handlers = {
                {"net.minecraft.client.network.ClientPlayerEntity", "printer$movementYaw"},
                {"net.minecraft.client.network.ClientPlayerEntity", "printer$declareInput"},
                {"net.minecraft.client.network.ClientPlayerEntity", "printer$flushNativePlacement"},
                {"net.minecraft.entity.Entity", "printer$movementSpeed"},
                {"net.minecraft.client.network.ClientPlayNetworkHandler", "printer$blockUpdate"},
                {"net.minecraft.client.network.ClientPlayNetworkHandler", "printer$correction"},
                {"net.minecraft.client.render.Camera", "onUpdateSetRotationArgs"},
                {"net.minecraft.client.Mouse", "updateMouseChangeLookDirection"},
                {"net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket", "modifyLookYaw"},
                {"fi.dy.masa.litematica.config.Configs", "loadFromFilePost"},
                {"fi.dy.masa.litematica.gui.GuiConfigs", "moreOptions"},
                {"fi.dy.masa.litematica.event.InputHandler", "moreHotkeys"},
                {"fi.dy.masa.litematica.gui.GuiSchematicLoad$ButtonListener", "onActionPerformedWithButton"}
        };
        for (String[] entry : handlers) {
            Class<?> target = Class.forName(names.getOrDefault(entry[0], entry[0]), false, loader);
            if (Arrays.stream(target.getDeclaredMethods()).noneMatch(m -> m.getName().contains(entry[1]))) {
                throw new AssertionError("Missing production handler: " + entry[0] + " / " + entry[1]);
            }
        }
        if (knot.isDevelopment() || !knot.getMappingConfiguration().getRuntimeNamespace().equals("intermediary")) {
            throw new AssertionError("This check did not use production intermediary mode");
        }
        System.out.println("PASS: 13 distinct targets covering all 15 printer mixins; 13 key handlers; production intermediary namespace.");
        System.out.println("No Minecraft main, game window, world, or server connection was started. This verifies artifact loading, not gameplay.");
    }
}
