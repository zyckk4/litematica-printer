package me.aleksilassila.litematica.printer.v1_21_4;

import me.aleksilassila.litematica.printer.v1_21_4.config.PrinterConfig;
import net.minecraft.block.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.List;

/** Stock and scheduling regressions found when auditing the confirmed-SWAP experiment. */
final class InventorySchedulingChecks {
    static void run() throws Exception {
        checkEmptyHandAndDuplicateStacks();
        checkDisjointRefillWithPlacementWindowFull();
        checkWorldAndStockPublications();
        checkMixedReadyBatch();
        System.out.println("Inventory scheduling regressions passed.");
    }

    private static void checkEmptyHandAndDuplicateStacks() throws Exception {
        try (var f = new StateInteractionTestFixture()) {
            f.enableInventoryPacketApplication();
            slots("3,4,5");
            f.fill(new ItemStack(Items.STONE));
            f.player.inventory.main.set(2, ItemStack.EMPTY);
            f.player.inventory.main.set(3, new ItemStack(Items.DIRT, 64));
            f.player.inventory.main.set(4, new ItemStack(Items.DIRT, 64));
            f.player.inventory.main.set(9, new ItemStack(Items.GLASS));
            if (!Printer.inventoryManager.prefetch(new ItemStack(Items.GLASS),
                    List.of(ItemStack.EMPTY, new ItemStack(Items.DIRT)))) throw new AssertionError("Duplicate stack blocked prefetch");
            var clicks = clicks(f);
            if (clicks.size() != 1 || clicks.getFirst().getButton() != 4
                    || !Printer.inventoryManager.selectReadyHotbarMaterial(ItemStack.EMPTY)) {
                throw new AssertionError("Prefetch displaced required empty hand or the retained dirt stack");
            }
        }
    }

    private static void checkDisjointRefillWithPlacementWindowFull() throws Exception {
        try (var f = new StateInteractionTestFixture()) {
            f.enableInventoryPacketApplication();
            f.setVelocity(Vec3d.ZERO);
            f.configure(PrinterConfig.INTERACTION_WINDOW_LIMIT, 1);
            f.configure(PrinterConfig.INTERACTION_WINDOW_MS, 10_000);
            if (!Printer.tryAcquirePlacementPacket()) throw new AssertionError("Missing initial budget");
            f.player.inventory.main.set(9, new ItemStack(Items.WHITE_CONCRETE));
            f.player.inventory.main.set(10, new ItemStack(Items.WHITE_CONCRETE, 64));
            f.player.inventory.main.set(11, new ItemStack(Items.WHITE_CONCRETE, 3));
            f.player.inventory.main.set(12, new ItemStack(Items.ORANGE_CONCRETE, 16));
            BlockPos first = StateInteractionTestFixture.TARGET;
            for (var pos : List.of(first, first.east(), first.west(), first.north())) {
                f.schematic.blocks.put(pos, pos.equals(first) ? Blocks.ORANGE_CONCRETE.getDefaultState()
                        : Blocks.WHITE_CONCRETE.getDefaultState());
                f.world.blocks.put(pos.down(), Blocks.STONE.getDefaultState());
            }
            f.printer.onGameTick();
            f.printer.flushNativePlacements();
            var clicks = clicks(f);
            if (clicks.size() != 2 || clicks.stream().map(ClickSlotC2SPacket::getButton).distinct().count() != 2
                    || clicks.stream().noneMatch(click -> click.getSlot() == 11)
                    || clicks.stream().noneMatch(click -> click.getSlot() == 12) || !f.network.blockPackets.isEmpty()) {
                throw new AssertionError("Expected two disjoint refills covering demand and no over-budget UseOn: " + clicks);
            }
        }
    }

    private static void checkWorldAndStockPublications() throws Exception {
        try (var f = new StateInteractionTestFixture()) {
            var single = new ItemStack(Items.WHITE_CONCRETE);
            f.player.inventory.main.set(2, single.copy());
            f.player.inventory.main.set(3, single.copy());
            f.player.inventory.selectedSlot = 2;
            BlockPos pos = StateInteractionTestFixture.TARGET;
            Printer.trackPlacement(pos, Blocks.WHITE_CONCRETE.getDefaultState(), single, 5);
            Printer.confirmNativePlacement(pos, Blocks.WHITE_CONCRETE.getDefaultState());
            if (Printer.pendingSlotCount(2, single) != 1 || Printer.pendingSlotCount(3, single) != 0
                    || !Printer.inventoryManager.selectReadyNativeMaterial(single) || f.player.inventory.selectedSlot != 3) {
                throw new AssertionError("World confirmation freed an unpaid use or stalled a disjoint stack");
            }
            Printer.observeHotbarContent(2, ItemStack.EMPTY);
            if (Printer.pendingSlotCount(2, single) != 0) throw new AssertionError("Published debit stayed reserved");
            Printer.trackPlacement(pos.east(), Blocks.WHITE_CONCRETE.getDefaultState(), single, 6);
            Printer.observeHotbarContent(3, ItemStack.EMPTY);
            Printer.confirmNativePlacement(pos.east(), Blocks.WHITE_CONCRETE.getDefaultState());
            if (Printer.pendingSlotCount(3, single) != 0) throw new AssertionError("Slot-first publication leaked a debit");
            Printer.trackPlacement(pos.north(), Blocks.WHITE_CONCRETE.getDefaultState(), single, 7);
            if (Printer.pendingSlotCount(3, single) != 1) throw new AssertionError("Expected pending use for session reset");
            Printer.inventoryManager.reset();
            if (Printer.pendingSlotCount(3, single) != 0) throw new AssertionError("A replacement session retained a stock debit");
        }
    }

    private static void checkMixedReadyBatch() throws Exception {
        try (var f = new StateInteractionTestFixture()) {
            f.setVelocity(Vec3d.ZERO);
            f.player.inventory.main.set(2, new ItemStack(Items.DIRT));
            f.player.inventory.main.set(3, new ItemStack(Items.BRICKS));
            var first = StateInteractionTestFixture.TARGET;
            f.schematic.blocks.put(first, Blocks.DIRT.getDefaultState());
            f.schematic.blocks.put(first.east(), Blocks.BRICKS.getDefaultState());
            f.world.blocks.put(first.down(), Blocks.STONE.getDefaultState());
            f.world.blocks.put(first.east().down(), Blocks.STONE.getDefaultState());
            List<Integer> used = new ArrayList<>();
            f.network.packetSink = packet -> {
                if (packet instanceof PlayerInteractBlockC2SPacket) used.add(f.player.inventory.selectedSlot);
            };
            f.printer.onGameTick();
            f.printer.flushNativePlacements();
            if (!used.containsAll(List.of(2, 3)) || used.size() != 2) {
                throw new AssertionError("A rotation-compatible mixed batch did not use its two physical slots: " + used);
            }
        }
    }

    private static List<ClickSlotC2SPacket> clicks(StateInteractionTestFixture f) {
        return f.network.packets.stream().filter(ClickSlotC2SPacket.class::isInstance)
                .map(ClickSlotC2SPacket.class::cast).toList();
    }

    private static void slots(String allowed) {
        PrinterConfig.PRINTER_HOTBAR_SLOTS.setValueFromString(allowed);
        InventoryManager.setHotbarSlots(PrinterConfig.PRINTER_HOTBAR_SLOTS);
    }
}
