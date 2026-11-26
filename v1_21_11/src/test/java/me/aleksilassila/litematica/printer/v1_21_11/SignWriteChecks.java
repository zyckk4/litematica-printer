package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.block.entity.SignText;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class SignWriteChecks {
    private static final BlockPos POS = StateInteractionTestFixture.TARGET;
    private static final long MS = TimeUnit.MILLISECONDS.toNanos(1);

    static void run() throws Exception {
        check(PrinterConfig.MIN_SIGN_DELAY_MS.getDefaultIntegerValue() == 500
                        && PrinterConfig.MIN_SIGN_DELAY_MS.getMinIntegerValue() == 0
                        && PrinterConfig.MIN_SIGN_DELAY_MS.getMaxIntegerValue() == 10_000,
                "Sign delay configuration default or bounds changed");
        checkIndependentWaits();
        checkBacklog();
        checkGrantedFaceAndSnapshot();
        checkInvalidatedGrants();
        System.out.println("Sign editor, delayed packet and queue regressions passed.");
    }

    private static void checkIndependentWaits() {
        SignWriteQueue queue = new SignWriteQueue();
        List<String> sent = new ArrayList<>();
        queue.schedule(POS, 0, 500, () -> true, () -> sent.add("first"));
        queue.schedule(POS.east(), 50 * MS, 500, () -> true, () -> sent.add("second"));
        queue.schedule(POS.west(), 50 * MS, 450, () -> true, () -> sent.add("third"));
        queue.tick(500 * MS - 1);
        check(sent.isEmpty(), "A sign was sent before its own delay elapsed");
        queue.tick(500 * MS);
        check(sent.equals(List.of("first", "third")), "Independently mature grants did not send on the same tick");
        queue.tick(550 * MS);
        check(sent.equals(List.of("first", "third", "second")), "Later grant waited an extra global interval");
    }

    private static void checkBacklog() {
        SignWriteQueue queue = new SignWriteQueue();
        List<Integer> sent = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            int id = i;
            queue.schedule(POS.add(i, 0, 0), 0, 10_000, () -> true, () -> sent.add(id));
        }
        queue.tick(10_000 * MS - 1);
        check(sent.isEmpty(), "Maximum configured delay was shortened");
        queue.tick(10_000 * MS);
        check(sent.size() == 20, "Expected 20 submitted signs on the first eligible tick");
        queue.tick(10_050 * MS);
        queue.tick(10_100 * MS);
        check(sent.equals(java.util.stream.IntStream.range(0, 21).boxed().toList()),
                "Ready overflow was duplicated, dropped or reordered");
    }

    private static void checkGrantedFaceAndSnapshot() throws Exception {
        try (var f = new StateInteractionTestFixture()) {
            SignBlockEntity actual = installSigns(f);
            SignBlockEntity target = (SignBlockEntity) f.schematic.blockEntities.get(POS);
            List<String> front = List.of("正面", "", "third", "fourth");
            List<String> back = List.of("背面", " second ", "", "four");
            setSchematicText(target, true, front);
            setSchematicText(target, false, back);

            f.configure(PrinterConfig.MIN_SIGN_DELAY_MS, 0);
            f.player.openEditSignScreen(actual, true);
            List<UpdateSignC2SPacket> sent = writes(f);
            check(sent.size() == 1, "Zero delay did not submit during the editor request");
            checkPacket(sent.getFirst(), true, front);

            f.configure(PrinterConfig.MIN_SIGN_DELAY_MS, 500);
            f.player.openEditSignScreen(actual, false);
            check(f.client.currentScreen == null && writes(f).size() == 1,
                    "Delayed grant opened a screen or sent its packet immediately");
            setSchematicText(target, false, List.of("edited", "after", "editor", "opened"));
            f.configure(LitematicaMixinMod.PRINT_MODE, false);
            flushGranted(f);
            sent = writes(f);
            check(sent.size() == 2, "Stopping print mode discarded a granted write");
            checkPacket(sent.getLast(), false, back);

            f.configure(LitematicaMixinMod.PRINT_MODE, true);
            setSchematicText(target, true, List.of("", "", "", ""));
            f.player.openEditSignScreen(actual, false);
            f.player.openEditSignScreen(actual, true);
            check(writes(f).size() == 2, "An empty face skipped its configured wait");
            flushGranted(f);
            sent = writes(f);
            check(sent.size() == 3, "Reopening the same sign sent a stale grant or lost the empty face");
            checkPacket(sent.getLast(), true, List.of("", "", "", ""));
        }
    }

    private static void checkInvalidatedGrants() throws Exception {
        try (var f = new StateInteractionTestFixture()) {
            f.configure(PrinterConfig.MIN_SIGN_DELAY_MS, 500);
            SignBlockEntity first = installSigns(f);
            f.player.openEditSignScreen(first, true);
            SignBlockEntity replacement = installSigns(f);
            flushGranted(f);
            check(writes(f).isEmpty(), "Replaced sign received the old grant");

            f.player.openEditSignScreen(replacement, true);
            Vec3d original = new Vec3d(f.player.getX(), f.player.getY(), f.player.getZ());
            StateInteractionTestFixture.set(Entity.class, f.player, "pos", original.add(100, 0, 0));
            playerQueue(f).tick(System.nanoTime());
            StateInteractionTestFixture.set(Entity.class, f.player, "pos", original);
            flushGranted(f);
            check(writes(f).isEmpty(), "Returning into range revived an invalidated edit");

            f.player.openEditSignScreen(replacement, true);
            f.client.player = null;
            flushGranted(f);
            f.client.player = f.player;
            flushGranted(f);
            check(writes(f).isEmpty(), "A grant survived loss of its player session");
        }
    }

    private static SignBlockEntity installSigns(StateInteractionTestFixture f) {
        var block = Blocks.OAK_SIGN.getDefaultState();
        f.target(block, block);
        var actual = new SignBlockEntity(POS, block);
        actual.setWorld(f.world);
        f.world.blockEntities.put(POS, actual);
        f.schematic.blockEntities.put(POS, new SignBlockEntity(POS, block));
        return actual;
    }

    private static void setSchematicText(SignBlockEntity target, boolean front, List<String> lines) throws Exception {
        // Load the fixture's schematic data without invoking vanilla world render listeners.
        SignText text = new SignText();
        for (int i = 0; i < lines.size(); i++) text = text.withMessage(i, Text.literal(lines.get(i)));
        StateInteractionTestFixture.set(SignBlockEntity.class, target, front ? "frontText" : "backText", text);
    }

    private static List<UpdateSignC2SPacket> writes(StateInteractionTestFixture f) {
        return f.network.packets.stream().filter(UpdateSignC2SPacket.class::isInstance)
                .map(UpdateSignC2SPacket.class::cast).toList();
    }

    private static void checkPacket(UpdateSignC2SPacket packet, boolean front, List<String> lines) {
        check(packet.getPos().equals(POS) && packet.isFront() == front
                        && Arrays.asList(packet.getText()).equals(lines),
                "Packet coordinates, granted face or four-line snapshot changed");
    }

    private static void flushGranted(StateInteractionTestFixture f) throws Exception {
        // The real openEditSignScreen mixin captured the grant using System.nanoTime().
        playerQueue(f).tick(System.nanoTime() + 501 * MS);
    }

    private static SignWriteQueue playerQueue(StateInteractionTestFixture f) throws Exception {
        var field = Arrays.stream(ClientPlayerEntity.class.getDeclaredFields())
                .filter(candidate -> candidate.getType() == SignWriteQueue.class).findFirst().orElseThrow();
        field.setAccessible(true);
        return (SignWriteQueue) field.get(f.player);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
