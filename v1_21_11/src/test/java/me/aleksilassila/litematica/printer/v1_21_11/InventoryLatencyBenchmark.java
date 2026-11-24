package me.aleksilassila.litematica.printer.v1_21_11;

import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.SetPlayerInventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.StatisticsS2CPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
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

    static void run(boolean baselineTickOrder, boolean authoritative) throws Exception {
        System.out.println("Inventory latency benchmark: 20 TPS, 200 ms base RTT, fixed or deterministic" +
                " ±50 ms one-way jitter, FIFO delivery, 9 interactions per 310 ms, simple blocks. " +
                "Inventory countdown " + (baselineTickOrder ? "before" : "after") + " placement flush. " +
                "Refill mode: " + (authoritative ? "authoritative update" : "timed prediction") + ".");
        runScenario("cache-only-jitter", 1, 10, true, Fault.NONE, baselineTickOrder, authoritative);
        runScenario("mixed-fixed", PALETTE.size(), 10, false, Fault.NONE, baselineTickOrder, authoritative);
        runScenario("mixed-jitter", PALETTE.size(), 10, true, Fault.NONE, baselineTickOrder, authoritative);
        runScenario("mixed-short-delay-jitter", PALETTE.size(), 5, true, Fault.NONE, baselineTickOrder, authoritative);
        runScenario("mixed-rejected-swap-jitter", PALETTE.size(), 10, true, Fault.REJECT_FIRST,
                baselineTickOrder, authoritative);
        if (authoritative) {
            runScenario("split-and-unscoped-update", 1, 10, false, Fault.SPLIT_FIRST, baselineTickOrder, true);
            runScenario("authoritative-count-mismatch", 1, 10, false, Fault.COUNT_MISMATCH_FIRST, baselineTickOrder, true);
            runScenario("original-updates-withheld", 1, 10, false, Fault.WITHHOLD_FIRST, baselineTickOrder, true);
            runScenario("readback-fence-withheld", 1, 10, false, Fault.WITHHOLD_FENCE, baselineTickOrder, true);
            runScenario("matching-snapshot-before-swap", 1, 10, true, Fault.EARLIER_MATCH, baselineTickOrder, true);
            runScenario("group-reply-withheld", 1, 10, false, Fault.WITHHOLD_GROUP_REPLY, baselineTickOrder, true);
            runScenario("partially-accepted-group", PALETTE.size(), 10, true, Fault.REJECT_MIDDLE, baselineTickOrder, true);
            runScenario("fragmented-backpack", 1, 10, false, Fault.NONE, baselineTickOrder, true, Layout.FRAGMENTED_BACKPACK);
            runScenario("fragmented-hotbar", 1, 10, false, Fault.NONE, baselineTickOrder, true, Layout.FRAGMENTED_HOTBAR);
        }
    }

    private static void runScenario(String name, int materials, int delay, boolean jitter,
                                    Fault fault, boolean baselineTickOrder,
                                    boolean authoritative) throws Exception {
        runScenario(name, materials, delay, jitter, fault, baselineTickOrder, authoritative, Layout.DEFAULT);
    }

    private enum Layout { DEFAULT, FRAGMENTED_BACKPACK, FRAGMENTED_HOTBAR }

    private static void runScenario(String name, int materials, int delay, boolean jitter, Fault fault,
                                    boolean baselineTickOrder, boolean authoritative, Layout layout) throws Exception {
        try (StateInteractionTestFixture fixture = new StateInteractionTestFixture()) {
            fixture.enableInventoryPacketApplication();
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
            if (layout == Layout.FRAGMENTED_BACKPACK) {
                for (int slot = 9; slot < 25; slot++) fixture.player.inventory.main.set(slot, new ItemStack(PALETTE.getFirst()));
                fixture.player.inventory.main.set(25, new ItemStack(PALETTE.getFirst(), 48));
            } else if (layout == Layout.FRAGMENTED_HOTBAR) {
                while (expected.size() > 7) expected.remove(expected.keySet().iterator().next());
                for (int slot = 2; slot < 9; slot++) fixture.player.inventory.main.set(slot, new ItemStack(PALETTE.getFirst()));
            } else if (fault.singleRefill) {
                expected.clear();
                expected.put(StateInteractionTestFixture.TARGET, PALETTE.getLast().getDefaultState());
                fixture.player.inventory.main.set(9, new ItemStack(PALETTE.getLast(), 64));
                if (fault == Fault.EARLIER_MATCH) {
                    fixture.player.inventory.main.set(2, new ItemStack(Blocks.DIRT, 8));
                    PrinterConfig.PRINTER_HOTBAR_SLOTS.setValueFromString("3");
                    InventoryManager.setHotbarSlots(PrinterConfig.PRINTER_HOTBAR_SLOTS);
                }
            } else {
                for (int i = 0; i < materials; i++) {
                    // Fill the seven writable hotbar slots before using main inventory.
                    fixture.player.inventory.main.set(i + 2, new ItemStack(PALETTE.get(i), 64));
                }
            }
            fixture.schematic.blocks.putAll(expected);

            var server = new SimulatedServer(fixture, expected, delay, jitter, fault, authoritative);
            fixture.interaction.swapSink = server::submitPredictedSwap;
            fixture.network.packetSink = server::submitPacket;
            long start = System.nanoTime();
            int clientComplete = -1;
            int tickLimit = fault.singleRefill ? 40 : MAX_TICKS;
            for (int tick = 0; tick < tickLimit; tick++) {
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
                server.verifyNoPostSendPrediction();
                if (server.completeTick >= 0 && server.quiet()
                        && expected.entrySet().stream().allMatch(entry ->
                                entry.getValue().equals(fixture.world.getBlockState(entry.getKey())))) {
                    clientComplete = tick;
                    break;
                }
            }

            System.out.printf("INVENTORY_BENCHMARK %s delay=%d targets=%d materials=%d correct=%d wrong=%d " +
                            "swaps=%d rawSwaps=%d readbacks=%d statsRequests=%d statsResponses=%d " +
                            "rejectedSwaps=%d fullInventorySyncs=%d " +
                            "placementAttempts=%d failedAttempts=%d " +
                            "firstUses=%d firstUseRejected=%d firstUseEmpty=%d firstUseWrong=%d " +
                            "usesDuringSettle=%d usesPendingDestination=%d overlappingSwapsDuringSettle=%d " +
                            "maxInFlightSwaps=%d " +
                            "serverCompleteTick=%d clientCompleteTick=%d inventoryMatches=%b%n",
                    name, delay, expected.size(), materials, server.countCorrect(), server.wrongPlacements,
                    server.materialSwaps, server.rawSwaps, server.readbacks, server.statsRequests,
                    server.statsResponses, server.rejectedSwaps,
                    server.corrections, server.placementAttempts,
                    server.failedPlacements, server.firstUses, server.firstUseRejected, server.firstUseEmpty,
                    server.firstUseWrong, server.usesDuringSettle, server.usesPendingDestination,
                    server.overlappingSwapsDuringSettle, server.maxInFlightSwaps,
                    server.completeTick, clientComplete, server.inventoryMatches());
            boolean mustStayLocked = fault == Fault.WITHHOLD_FENCE || fault == Fault.WITHHOLD_GROUP_REPLY;
            if (mustStayLocked) {
                if (clientComplete != -1 || server.placementAttempts != 0 || server.materialSwaps != 1) {
                    throw new AssertionError("Tried to use or retry a refill after the recovery fence was withheld");
                }
            } else if (clientComplete < 0 || server.wrongPlacements != 0 || !server.inventoryMatches()) {
                List<BlockPos> incorrect = expected.entrySet().stream()
                        .filter(entry -> !entry.getValue().equals(server.stateAt(entry.getKey())))
                        .map(Map.Entry::getKey).toList();
                throw new AssertionError("Failed to complete " + name + " with exact blocks and inventory. " +
                        "Incorrect or missing targets: " + incorrect);
            }
            if (server.failedPlacements != 0 || server.firstUseRejected != 0 || server.firstUseEmpty != 0
                    || server.firstUseWrong != 0 || (!mustStayLocked && server.placementAttempts != expected.size())) {
                throw new AssertionError("Unexpected placement or rejected use in " + name);
            }
            if (layout == Layout.FRAGMENTED_BACKPACK && server.materialSwaps != 1) {
                throw new AssertionError("Fragmented backpack should supply 48 blocks in one refill");
            }
            if (materials == 1 && fault == Fault.NONE && layout != Layout.FRAGMENTED_BACKPACK && server.materialSwaps != 0) {
                throw new AssertionError("A cache-only job attempted to refill");
            }
            int expectedRawSwaps = authoritative ? server.materialSwaps : 0;
            if (server.rawSwaps != expectedRawSwaps) throw new AssertionError("Unexpected refill wire path");
            if ((fault == Fault.REJECT_FIRST || fault == Fault.REJECT_MIDDLE) && server.rejectedSwaps != 1) {
                throw new AssertionError("The planned server rejection did not happen");
            }
            if (authoritative) verifyFaultResponse(fault, server);
            if (server.usesPendingDestination != 0 || server.overlappingSwapsDuringSettle != 0) {
                throw new AssertionError("Used pending destination or attempted another SWAP before settlement");
            }
        }
    }

    private static void verifyFaultResponse(Fault fault, SimulatedServer server) {
        if (fault == Fault.SPLIT_FIRST && server.splitUpdates != 1) {
            throw new AssertionError("The split/scoped response was not exercised");
        }
        if (fault == Fault.COUNT_MISMATCH_FIRST || fault == Fault.WITHHOLD_FIRST || fault == Fault.WITHHOLD_FENCE) {
            int expectedStats = fault == Fault.WITHHOLD_FENCE ? 1 : 2;
            if (server.readbacks != 1 || server.statsRequests != 2 || server.statsResponses != expectedStats
                    || server.corrections != 1) {
                throw new AssertionError("Expected one readback and a scoped full inventory with the planned fence response");
            }
        }
        if (fault == Fault.EARLIER_MATCH && (server.materialSwaps != 2 || server.readbacks < 1)) {
            throw new AssertionError("The pre-click inventory must lead to reconciliation and fresh planning");
        }
        if ((fault == Fault.REJECT_FIRST || fault == Fault.REJECT_MIDDLE) && (server.readbacks < 1 || server.statsResponses < 2)) {
            throw new AssertionError("Expected a fenced authoritative readback after the rejected swap");
        }
    }

    private enum Fault {
        NONE(false), REJECT_FIRST(false), SPLIT_FIRST(true), COUNT_MISMATCH_FIRST(true),
        WITHHOLD_FIRST(true), WITHHOLD_FENCE(true), WITHHOLD_GROUP_REPLY(true), EARLIER_MATCH(true), REJECT_MIDDLE(false);

        private final boolean singleRefill;

        Fault(boolean singleRefill) {
            this.singleRefill = singleRefill;
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
        private final Fault fault;
        private final boolean authoritative;
        private List<ItemStack> clientBeforeTick;
        private int selectedSlot;
        private int serverRevision;
        private int tick;
        private int materialSwaps;
        private int rawSwaps;
        private int readbacks;
        private int statsRequests;
        private int statsResponses;
        private int splitUpdates;
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
        private SwapGroup group;
        private int usesDuringSettle;
        private int usesPendingDestination;
        private int overlappingSwapsDuringSettle;
        private int inFlightSwaps;
        private int maxInFlightSwaps;
        private int completeTick = -1;

        SimulatedServer(StateInteractionTestFixture fixture, Map<BlockPos, BlockState> expected,
                        int delay, boolean jitter, Fault fault, boolean authoritative) {
            this.fixture = fixture;
            this.expected = expected;
            this.delay = delay;
            this.fault = fault;
            this.authoritative = authoritative;
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
            if (authoritative) {
                clientBeforeTick = fixture.player.inventory.main.stream().map(ItemStack::copy).toList();
            }
        }

        boolean quiet() {
            return uplink.pending.isEmpty() && downlink.pending.isEmpty() && dirtySlots.isEmpty();
        }

        private boolean swapOutstanding() {
            return group != null && (authoritative ? !group.complete() : tick < settleUntil);
        }

        void submitPredictedSwap(int source, int destination) {
            if (authoritative) throw new AssertionError("Authoritative refill invoked local prediction");
            int revision = fixture.player.playerScreenHandler.getRevision();
            ItemStack sourcePrediction = fixture.player.inventory.main.get(source).copy();
            ItemStack destinationPrediction = fixture.player.inventory.main.get(destination).copy();
            submitSwap(source, destination, revision, sourcePrediction, destinationPrediction);
        }

        private void submitSwap(int source, int destination, int revision,
                                ItemStack sourcePrediction, ItemStack destinationPrediction) {
            if (!swapOutstanding()) group = new SwapGroup(tick);
            boolean reusedEndpoint = group.swaps.stream().anyMatch(swap -> swap.source == source || swap.destination == destination);
            if (reusedEndpoint || group.initialStatsRequested || group.swaps.size() >= 3 || group.submittedTick != tick) {
                overlappingSwapsDuringSettle++;
                throw new AssertionError("Concurrent refills must belong to one disjoint, bounded group");
            }
            materialSwaps++;
            settleUntil = tick + Math.max(1, delay);
            SwapObservation swap = new SwapObservation(source, destination, materialSwaps == 1, group);
            group.swaps.add(swap);
            if (fault == Fault.EARLIER_MATCH && swap.first) {
                ItemStack old = inventory.get(destination);
                inventory.set(destination, inventory.get(source));
                inventory.set(source, old);
                sendInventory(null, false);
                group.recoveryRequired = true;
            }
            inFlightSwaps++;
            maxInFlightSwaps = Math.max(maxInFlightSwaps, inFlightSwaps);
            uplink.enqueue(tick, () -> applySwap(swap, revision, sourcePrediction, destinationPrediction));
        }

        private void applySwap(SwapObservation swap, int revision,
                               ItemStack sourcePrediction, ItemStack destinationPrediction) {
            inFlightSwaps--;
            int source = swap.source;
            int destination = swap.destination;
            waitingForFirstUse.add(destination);
            boolean rejectMiddle = fault == Fault.REJECT_MIDDLE && rejectedSwaps == 0
                    && swap.group.swaps.size() >= 2 && swap == swap.group.swaps.get(1);
            if ((fault == Fault.REJECT_FIRST && swap.first) || rejectMiddle) {
                rejectedSwaps++;
                swap.group.recoveryRequired = true;
                sendInventory(swap.group, false);
                return;
            }
            ItemStack displaced = inventory.get(destination);
            ItemStack incoming = inventory.get(source);
            if (swap.first && fault == Fault.COUNT_MISMATCH_FIRST) {
                incoming.decrement(1);
                swap.group.recoveryRequired = true;
            }
            inventory.set(destination, incoming);
            inventory.set(source, displaced);
            if (swap.first && (fault == Fault.WITHHOLD_FIRST || fault == Fault.WITHHOLD_FENCE)) {
                swap.group.recoveryRequired = true;
                return;
            }
            if (revision != serverRevision) {
                sendInventory(swap.group, false);
                return;
            }
            dirtySlots.remove(source);
            dirtySlots.remove(destination);
            if (authoritative) {
                // No optimistic slot hashes replace the server's pre-click slot snapshots.
                if (!ItemStack.areEqual(displaced, incoming)) {
                    sendSlot(source, swap);
                    if (swap.first && fault == Fault.SPLIT_FIRST) {
                        splitUpdates++;
                        ItemStack copy = incoming.copy();
                        downlink.enqueue(tick, () -> fixture.network.onSetPlayerInventory(
                                new SetPlayerInventoryS2CPacket(destination, copy)));
                        sendSlot(destination, swap, 1);
                    } else {
                        sendSlot(destination, swap);
                    }
                }
            } else {
                if (!ItemStack.areEqual(sourcePrediction, inventory.get(source))) sendSlot(source);
                if (!ItemStack.areEqual(destinationPrediction, inventory.get(destination))) sendSlot(destination);
            }
        }

        private void sendSlot(int slot) {
            sendSlot(slot, null);
        }

        private void sendSlot(int slot, SwapObservation swap) {
            sendSlot(slot, swap, 0);
        }

        private void sendSlot(int slot, SwapObservation swap, int extraDelay) {
            ItemStack stack = inventory.get(slot).copy();
            int revision = advanceRevision();
            downlink.enqueue(tick, extraDelay, () -> {
                fixture.network.onScreenHandlerSlotUpdate(new ScreenHandlerSlotUpdateS2CPacket(
                        0, revision, toScreenSlot(slot), stack));
                if (swap != null) swap.noteSlot(slot);
            });
        }

        private void sendInventory(SwapGroup observer, boolean readback) {
            corrections++;
            dirtySlots.clear();
            List<ItemStack> snapshot = new ArrayList<>(java.util.Collections.nCopies(
                    PlayerScreenHandler.OFFHAND_ID + 1, ItemStack.EMPTY));
            for (int slot = 0; slot < inventory.size(); slot++) {
                snapshot.set(toScreenSlot(slot), inventory.get(slot).copy());
            }
            int revision = advanceRevision();
            downlink.enqueue(tick, () -> {
                fixture.network.onInventory(new InventoryS2CPacket(0, revision, snapshot, ItemStack.EMPTY));
                if (observer != null) observer.noteInventory(readback);
            });
        }

        private int advanceRevision() {
            serverRevision = (serverRevision + 1) & 0x7fff;
            return serverRevision;
        }

        private static int toScreenSlot(int inventorySlot) {
            return inventorySlot < PlayerScreenHandler.INVENTORY_START
                    ? PlayerScreenHandler.HOTBAR_START + inventorySlot : inventorySlot;
        }

        private void submitRawSwap(ClickSlotC2SPacket click) {
            if (!authoritative || click.syncId() != 0 || click.actionType() != SlotActionType.SWAP
                    || click.button() < 0 || click.button() >= PlayerScreenHandler.INVENTORY_START
                    || !click.modifiedStacks().isEmpty() || !click.cursor().equals(ItemStackHash.EMPTY)) {
                throw new AssertionError("Unexpected authoritative click packet: " + click);
            }
            int destination = click.button();
            if (click.slot() == toScreenSlot(destination)) {
                SwapGroup active = group;
                if (active == null || !active.initialStatsSeen || active.probeSent || !active.usesDestination(destination)
                        || click.revision() != (fixture.player.playerScreenHandler.getRevision() ^ 0x4000)) {
                    throw new AssertionError("Unexpected recovery readback packet: " + click);
                }
                readbacks++;
                active.probeSent = true;
                uplink.enqueue(tick, () -> {
                    if (click.revision() != serverRevision) sendInventory(active, true);
                });
                return;
            }
            int source = click.slot();
            if (source < PlayerScreenHandler.INVENTORY_START || source >= PlayerScreenHandler.HOTBAR_START
                    || click.revision() != fixture.player.playerScreenHandler.getRevision()) {
                throw new AssertionError("Unexpected source or revision for material SWAP: " + click);
            }
            verifyEndpointsUnchanged(source, destination);
            rawSwaps++;
            submitSwap(source, destination, click.revision(), null, null);
        }

        void verifyNoPostSendPrediction() {
            if (authoritative && group != null && group.submittedTick == tick) {
                for (SwapObservation swap : group.swaps) verifyEndpointsUnchanged(swap.source, swap.destination);
            }
        }

        private void verifyEndpointsUnchanged(int source, int destination) {
            if (!ItemStack.areEqual(clientBeforeTick.get(source), fixture.player.inventory.main.get(source))
                    || !ItemStack.areEqual(clientBeforeTick.get(destination), fixture.player.inventory.main.get(destination))) {
                throw new AssertionError("Raw material SWAP also updated an endpoint locally");
            }
        }

        void submitPacket(Packet<?> packet) {
            if (packet instanceof ClickSlotC2SPacket click) {
                submitRawSwap(click);
            } else if (packet instanceof ClientStatusC2SPacket status
                    && status.getMode() == ClientStatusC2SPacket.Mode.REQUEST_STATS) {
                submitStatsFence();
            } else if (packet instanceof UpdateSelectedSlotC2SPacket selected) {
                uplink.enqueue(tick, () -> selectedSlot = selected.getSelectedSlot());
            } else if (packet instanceof PlayerInteractBlockC2SPacket placement) {
                if (placement.getHand() != Hand.MAIN_HAND) throw new AssertionError("Unexpected offhand path");
                if (swapOutstanding()) {
                    usesDuringSettle++;
                    if (group.usesDestination(fixture.player.inventory.selectedSlot)) usesPendingDestination++;
                }
                uplink.enqueue(tick, () -> place(placement));
            }
        }

        private void submitStatsFence() {
            SwapGroup swap = group;
            if (swap == null) throw new AssertionError("Unexpected stats fence without a pending refill");
            boolean initial = !swap.initialStatsRequested;
            if (initial) {
                swap.initialStatsRequested = true;
            } else {
                if (!swap.probeSent || swap.statsRequested) {
                    throw new AssertionError("A second stats fence is only expected after its own readback");
                }
                swap.statsRequested = true;
            }
            statsRequests++;
            uplink.enqueue(tick, () -> {
                if ((!initial && fault == Fault.WITHHOLD_FENCE) || (initial && fault == Fault.WITHHOLD_GROUP_REPLY)) return;
                // Delay the second fence so a full inventory alone cannot release a recovery.
                downlink.enqueue(tick, initial ? 0 : 1, () -> {
                    if (initial) swap.initialStatsSeen = true;
                    else swap.statsSeen = true;
                    fixture.network.onStatistics(new StatisticsS2CPacket(Object2IntMaps.emptyMap()));
                    statsResponses++;
                });
            });
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

    private static final class SwapObservation {
        private final int source;
        private final int destination;
        private final boolean first;
        private final SwapGroup group;
        private boolean sourceSeen;
        private boolean destinationSeen;

        SwapObservation(int source, int destination, boolean first, SwapGroup group) {
            this.source = source;
            this.destination = destination;
            this.first = first;
            this.group = group;
        }

        void noteSlot(int slot) {
            if (slot == source) sourceSeen = true;
            if (slot == destination) destinationSeen = true;
        }
    }

    private static final class SwapGroup {
        private final List<SwapObservation> swaps = new ArrayList<>(3);
        private final int submittedTick;
        private boolean recoveryRequired;
        private boolean initialStatsRequested;
        private boolean initialStatsSeen;
        private boolean probeSent;
        private boolean statsRequested;
        private boolean recoveryInventorySeen;
        private boolean statsSeen;

        SwapGroup(int submittedTick) {
            this.submittedTick = submittedTick;
        }

        boolean usesDestination(int slot) {
            return swaps.stream().anyMatch(swap -> swap.destination == slot);
        }

        void noteInventory(boolean readback) {
            for (SwapObservation swap : swaps) {
                swap.sourceSeen = true;
                swap.destinationSeen = true;
            }
            if (readback) recoveryInventorySeen = true;
        }

        boolean complete() {
            if (recoveryRequired) return recoveryInventorySeen && statsSeen;
            return initialStatsSeen && swaps.stream().allMatch(swap -> swap.sourceSeen && swap.destinationSeen);
        }
    }

    private static final class OrderedLink {
        private final ArrayDeque<Delivery> pending = new ArrayDeque<>();
        private final int[] jitter;

        OrderedLink(int... jitter) {
            this.jitter = jitter;
        }

        void enqueue(int tick, Runnable action) {
            enqueue(tick, 0, action);
        }

        void enqueue(int tick, int extraDelay, Runnable action) {
            int deliveryTick = tick + 2 + jitter[tick % jitter.length] + extraDelay;
            if (!pending.isEmpty()) deliveryTick = Math.max(deliveryTick, pending.getLast().tick());
            pending.addLast(new Delivery(deliveryTick, action));
        }

        void deliver(int tick) {
            while (!pending.isEmpty() && pending.getFirst().tick() <= tick) pending.removeFirst().action().run();
        }
    }

    private record Delivery(int tick, Runnable action) {}
}
