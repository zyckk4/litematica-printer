package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.locks.LockSupport;

/** Real printer scheduler against a synthetic, tick-paced inventory and block authority. */
final class InventoryLatencyBenchmark {
    private static final long TICK_NANOS = 50_000_000L;
    private static final int MAX_TICKS = 360;
    private static final List<Block> PALETTE = List.of(
            Blocks.WHITE_CONCRETE, Blocks.ORANGE_CONCRETE, Blocks.MAGENTA_CONCRETE,
            Blocks.LIGHT_BLUE_CONCRETE, Blocks.YELLOW_CONCRETE, Blocks.LIME_CONCRETE,
            Blocks.PINK_CONCRETE, Blocks.GRAY_CONCRETE, Blocks.LIGHT_GRAY_CONCRETE,
            Blocks.CYAN_CONCRETE, Blocks.PURPLE_CONCRETE, Blocks.BLUE_CONCRETE,
            Blocks.BROWN_CONCRETE, Blocks.GREEN_CONCRETE, Blocks.RED_CONCRETE, Blocks.BLACK_CONCRETE);

    static void run(boolean baselineTickOrder) throws Exception {
        System.out.println("Inventory latency benchmark: 20 TPS, 200 ms base RTT, fixed or deterministic" +
                " ±50 ms one-way jitter, FIFO delivery, 9 interactions per 310 ms, simple blocks. " +
                "Inventory countdown " + (baselineTickOrder ? "before" : "after") + " placement flush.");
        runScenario("cache-only-jitter", 1, 10, true, false, baselineTickOrder);
        runScenario("mixed-fixed", PALETTE.size(), 10, false, false, baselineTickOrder);
        runScenario("mixed-jitter", PALETTE.size(), 10, true, false, baselineTickOrder);
        runScenario("mixed-short-delay-jitter", PALETTE.size(), 5, true, false, baselineTickOrder);
        runScenario("mixed-rejected-swap-jitter", PALETTE.size(), 10, true, true, baselineTickOrder);
    }

    private static void runScenario(String name, int materials, int delay, boolean jitter,
                                    boolean rejectFirstSwap, boolean baselineTickOrder) throws Exception {
        try (StateInteractionTestFixture fixture = new StateInteractionTestFixture()) {
            fixture.configure(PrinterConfig.INVENTORY_DELAY, delay);
            fixture.configure(PrinterConfig.INTERACTION_WINDOW_LIMIT, 9);
            fixture.configure(PrinterConfig.INTERACTION_WINDOW_MS, 310);
            fixture.configure(PrinterConfig.PRINTER_NATIVE_GRIM_AIRPLACE, false);
            fixture.setVelocity(Vec3d.ZERO);

            Map<BlockPos, BlockState> expected = new LinkedHashMap<>();
            int cell = 0;
            for (int z = -3; z <= 3; z++) {
                for (int x = -3; x <= 3; x++) {
                    if (x == 0 && z == 0) continue;
                    BlockPos pos = new BlockPos(x, 65, z);
                    expected.put(pos, PALETTE.get((cell++ * 5) % materials).getDefaultState());
                    fixture.world.blocks.put(pos.down(), Blocks.STONE.getDefaultState());
                }
            }
            fixture.schematic.blocks.putAll(expected);
            for (int i = 0; i < materials; i++) {
                // Fill the seven writable hotbar slots before using main inventory.
                fixture.player.inventory.main.set(i + 2, new ItemStack(PALETTE.get(i), 64));
            }

            var server = new SimulatedServer(fixture, expected, delay, jitter, rejectFirstSwap);
            fixture.interaction.swapSink = server::submitSwap;
            fixture.network.packetSink = server::submitPacket;
            long start = System.nanoTime();
            int clientComplete = -1;
            for (int tick = 0; tick < MAX_TICKS; tick++) {
                waitUntil(start + tick * TICK_NANOS);
                server.advance(tick);
                fixture.player.age++;
                fixture.printer.actionHandler.processPreviousTickActions();
                fixture.printer.onGameTick();
                fixture.printer.actionHandler.processCurrentTickActions();
                if (baselineTickOrder) Printer.inventoryManager.tick();
                MovementHandler.onMovementSent(fixture.player);
                fixture.printer.flushNativePlacements();
                if (!baselineTickOrder) Printer.inventoryManager.tick();
                if (server.completeTick >= 0 && server.quiet()
                        && expected.entrySet().stream().allMatch(entry ->
                                entry.getValue().equals(fixture.world.getBlockState(entry.getKey())))) {
                    clientComplete = tick;
                    break;
                }
            }

            System.out.printf("INVENTORY_BENCHMARK %s delay=%d targets=%d materials=%d correct=%d wrong=%d " +
                            "swaps=%d rejectedSwaps=%d fullInventorySyncs=%d placementAttempts=%d failedAttempts=%d " +
                            "firstUses=%d firstUseRejected=%d firstUseEmpty=%d firstUseWrong=%d " +
                            "usesDuringSettle=%d usesPendingDestination=%d overlappingSwapsDuringSettle=%d " +
                            "maxInFlightSwaps=%d " +
                            "serverCompleteTick=%d clientCompleteTick=%d inventoryMatches=%b%n",
                    name, delay, expected.size(), materials, server.countCorrect(), server.wrongPlacements,
                    fixture.interaction.swaps, server.rejectedSwaps, server.corrections, server.placementAttempts,
                    server.failedPlacements, server.firstUses, server.firstUseRejected, server.firstUseEmpty,
                    server.firstUseWrong, server.usesDuringSettle, server.usesPendingDestination,
                    server.overlappingSwapsDuringSettle, server.maxInFlightSwaps,
                    server.completeTick, clientComplete, server.inventoryMatches());
            if (clientComplete < 0 || server.wrongPlacements != 0 || !server.inventoryMatches()) {
                List<BlockPos> incorrect = expected.entrySet().stream()
                        .filter(entry -> !entry.getValue().equals(server.stateAt(entry.getKey())))
                        .map(Map.Entry::getKey).toList();
                throw new AssertionError("Failed to complete " + name + " with exact blocks and inventory. " +
                        "Incorrect or missing targets: " + incorrect);
            }
            if (materials == 1 && fixture.interaction.swaps != 0) {
                throw new AssertionError("A cache-only job attempted to refill");
            }
            if (rejectFirstSwap && server.rejectedSwaps != 1) {
                throw new AssertionError("The planned server rejection did not happen");
            }
            if (server.usesPendingDestination != 0 || server.overlappingSwapsDuringSettle != 0) {
                throw new AssertionError("Used pending destination or attempted another SWAP before settlement");
            }
        }
    }

    private static void waitUntil(long target) {
        long remaining;
        while ((remaining = target - System.nanoTime()) > 0L) LockSupport.parkNanos(remaining);
    }

    private static final class SimulatedServer {
        private final StateInteractionTestFixture fixture;
        private final Map<BlockPos, BlockState> expected;
        private final Map<BlockPos, BlockState> authoritativeBlocks;
        private final List<ItemStack> inventory = new ArrayList<>();
        private final Set<Integer> dirtySlots = new TreeSet<>();
        private final Set<Integer> waitingForFirstUse = new HashSet<>();
        private final OrderedLink uplink;
        private final OrderedLink downlink;
        private final int delay;
        private final boolean rejectFirstSwap;
        private int selectedSlot;
        private int clientRevision;
        private int serverRevision;
        private int tick;
        private int rejectedSwaps;
        private int corrections;
        private int failedPlacements;
        private int placementAttempts;
        private int wrongPlacements;
        private int firstUses;
        private int firstUseRejected;
        private int firstUseEmpty;
        private int firstUseWrong;
        private int settleUntil;
        private int settlingDestination = -1;
        private int usesDuringSettle;
        private int usesPendingDestination;
        private int overlappingSwapsDuringSettle;
        private int inFlightSwaps;
        private int maxInFlightSwaps;
        private int completeTick = -1;

        SimulatedServer(StateInteractionTestFixture fixture, Map<BlockPos, BlockState> expected,
                        int delay, boolean jitter, boolean rejectFirstSwap) {
            this.fixture = fixture;
            this.expected = expected;
            this.delay = delay;
            this.rejectFirstSwap = rejectFirstSwap;
            uplink = jitter ? new OrderedLink(0, 0, 1, 0, -1, 0, 0, 1) : new OrderedLink(0);
            downlink = jitter ? new OrderedLink(0, 1, 0, 0, -1, 0, 1, 0) : new OrderedLink(0);
            authoritativeBlocks = new HashMap<>(fixture.world.blocks);
            fixture.player.inventory.main.forEach(stack -> inventory.add(stack.copy()));
            selectedSlot = fixture.player.inventory.selectedSlot;
        }

        void advance(int now) {
            tick = now;
            uplink.deliver(tick);
            dirtySlots.forEach(this::sendSlot);
            dirtySlots.clear();
            downlink.deliver(tick);
        }

        boolean quiet() {
            return uplink.pending.isEmpty() && downlink.pending.isEmpty() && dirtySlots.isEmpty();
        }

        void submitSwap(int source, int destination) {
            if (tick < settleUntil) overlappingSwapsDuringSettle++;
            settleUntil = tick + Math.max(1, delay);
            settlingDestination = destination;
            inFlightSwaps++;
            maxInFlightSwaps = Math.max(maxInFlightSwaps, inFlightSwaps);
            int revision = clientRevision;
            ItemStack sourcePrediction = fixture.player.inventory.main.get(source).copy();
            ItemStack destinationPrediction = fixture.player.inventory.main.get(destination).copy();
            uplink.enqueue(tick, () -> applySwap(source, destination, revision,
                    sourcePrediction, destinationPrediction));
        }

        private void applySwap(int source, int destination, int revision,
                               ItemStack sourcePrediction, ItemStack destinationPrediction) {
            inFlightSwaps--;
            waitingForFirstUse.add(destination);
            if (rejectFirstSwap && rejectedSwaps == 0) {
                rejectedSwaps++;
                sendInventory();
                return;
            }
            ItemStack displaced = inventory.get(destination);
            inventory.set(destination, inventory.get(source));
            inventory.set(source, displaced);
            if (revision != serverRevision) {
                sendInventory();
                return;
            }
            dirtySlots.remove(source);
            dirtySlots.remove(destination);
            if (!ItemStack.areEqual(sourcePrediction, inventory.get(source))) sendSlot(source);
            if (!ItemStack.areEqual(destinationPrediction, inventory.get(destination))) sendSlot(destination);
        }

        private void sendSlot(int slot) {
            ItemStack stack = inventory.get(slot).copy();
            int revision = ++serverRevision;
            downlink.enqueue(tick, () -> {
                fixture.player.inventory.main.set(slot, stack);
                clientRevision = revision;
            });
        }

        private void sendInventory() {
            corrections++;
            dirtySlots.clear();
            List<ItemStack> snapshot = inventory.stream().map(ItemStack::copy).toList();
            int revision = ++serverRevision;
            downlink.enqueue(tick, () -> {
                for (int i = 0; i < snapshot.size(); i++) fixture.player.inventory.main.set(i, snapshot.get(i));
                clientRevision = revision;
            });
        }

        void submitPacket(Packet<?> packet) {
            if (packet instanceof UpdateSelectedSlotC2SPacket selected) {
                uplink.enqueue(tick, () -> selectedSlot = selected.getSelectedSlot());
            } else if (packet instanceof PlayerInteractBlockC2SPacket placement) {
                if (placement.getHand() != Hand.MAIN_HAND) throw new AssertionError("Unexpected offhand path");
                if (tick < settleUntil) {
                    usesDuringSettle++;
                    if (fixture.player.inventory.selectedSlot == settlingDestination) usesPendingDestination++;
                }
                uplink.enqueue(tick, () -> place(placement));
            }
        }

        private void place(PlayerInteractBlockC2SPacket packet) {
            placementAttempts++;
            var hit = packet.getBlockHitResult();
            BlockPos clicked = hit.getBlockPos();
            BlockPos place = stateAt(clicked).isReplaceable() ? clicked : clicked.offset(hit.getSide());
            ItemStack held = inventory.get(selectedSlot);
            boolean firstUse = waitingForFirstUse.remove(selectedSlot);
            if (firstUse) firstUses++;
            if (!held.isEmpty() && held.getItem() instanceof BlockItem block && stateAt(place).isReplaceable()) {
                BlockState placed = block.getBlock().getDefaultState();
                authoritativeBlocks.put(place, placed);
                held.decrement(1);
                dirtySlots.add(selectedSlot);
                if (!placed.equals(expected.get(place))) {
                    wrongPlacements++;
                    if (firstUse) firstUseWrong++;
                }
                if (completeTick < 0 && countCorrect() == expected.size()) completeTick = tick;
            } else {
                failedPlacements++;
                if (firstUse && held.isEmpty()) firstUseEmpty++;
                else if (firstUse) firstUseRejected++;
            }
            sendBlock(clicked);
            sendBlock(clicked.offset(hit.getSide()));
        }

        private void sendBlock(BlockPos pos) {
            BlockState state = stateAt(pos);
            downlink.enqueue(tick, () -> {
                fixture.world.blocks.put(pos, state);
                Printer.confirmNativePlacement(pos, state);
            });
        }

        private BlockState stateAt(BlockPos pos) {
            return authoritativeBlocks.getOrDefault(pos, Blocks.AIR.getDefaultState());
        }

        int countCorrect() {
            return (int) expected.entrySet().stream()
                    .filter(entry -> entry.getValue().equals(stateAt(entry.getKey()))).count();
        }

        boolean inventoryMatches() {
            for (int i = 0; i < inventory.size(); i++) {
                if (!ItemStack.areEqual(inventory.get(i), fixture.player.inventory.main.get(i))) return false;
            }
            return true;
        }
    }

    private static final class OrderedLink {
        private final ArrayDeque<Delivery> pending = new ArrayDeque<>();
        private final int[] jitter;

        OrderedLink(int... jitter) {
            this.jitter = jitter;
        }

        void enqueue(int tick, Runnable action) {
            int deliveryTick = tick + 2 + jitter[tick % jitter.length];
            if (!pending.isEmpty()) deliveryTick = Math.max(deliveryTick, pending.getLast().tick());
            pending.addLast(new Delivery(deliveryTick, action));
        }

        void deliver(int tick) {
            while (!pending.isEmpty() && pending.getFirst().tick() <= tick) pending.removeFirst().action().run();
        }
    }

    private record Delivery(int tick, Runnable action) {}
}
