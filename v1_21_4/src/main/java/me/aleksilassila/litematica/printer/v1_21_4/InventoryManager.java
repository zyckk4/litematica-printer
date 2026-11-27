package me.aleksilassila.litematica.printer.v1_21_4;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.options.ConfigString;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import me.aleksilassila.litematica.printer.v1_21_4.config.PrinterConfig;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

public class InventoryManager {
    private static final List<Integer> USABLE_SLOTS = new ArrayList<>();
    private static final int REVISION_MASK = 0x7fff;
    private static final int HALF_REVISION_RANGE = 0x4000;
    private static final int MIN_RESPONSE_TICKS = 10;
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Deque<Integer> rollingSlots = new ArrayDeque<>();
    private final Deque<Integer> lastUsedSlots = new ArrayDeque<>();
    private static InventoryManager instance;
    private ClientPlayerEntity inventoryOwner;
    private ClientPlayNetworkHandler inventoryConnection;
    private PendingSwap pendingSwap;
    private int refillBackoff;

    private InventoryManager() {
        // The config callback is not guaranteed to fire on the first launch.
        setHotbarSlots(PrinterConfig.PRINTER_HOTBAR_SLOTS);
        for (int i = 0; i < 9; i++) {
            rollingSlots.add(i);
            lastUsedSlots.add(i);
        }
    }

    public static void setHotbarSlots(ConfigString config) {
        USABLE_SLOTS.clear();
        String configStr = config.getStringValue();
        String[] parts = configStr.split(",");

        for (String str : parts) {
            try {
                int slotNum = Integer.parseInt(str.trim()) - 1;

                if (PlayerInventory.isValidHotbarIndex(slotNum) &&
                        !USABLE_SLOTS.contains(slotNum)) {
                    USABLE_SLOTS.add(slotNum);
                }
            } catch (NumberFormatException ignore) {
            }
        }
    }

    public static InventoryManager getInstance() {
        if (instance == null) {
            instance = new InventoryManager();
        }
        return instance;
    }

    public void reset() {
        pendingSwap = null;
        refillBackoff = 0;
        Printer.resetStock();
    }

    void onPrinterContextChanged() {
        if (mc == null || mc.player == null) return;
        ensureInventoryOwner(mc.player);
        if (pendingSwap != null) pendingSwap.interrupt();
    }

    public boolean tick() {
        if (mc.player == null) {
            inventoryOwner = null;
            inventoryConnection = null;
            reset();
            return false;
        }
        ensureInventoryOwner(mc.player);
        if (refillBackoff > 0) refillBackoff--;
        PendingSwap swap = pendingSwap;
        if (swap == null) return false;
        if (!canExchange(mc.player)) {
            swap.interrupt();
            return false;
        }
        if (swap.ticksRemaining > 0 && --swap.ticksRemaining > 0) return false;
        if (swap.phase == Phase.RETRY_READBACK) {
            requestReadback(swap, mc.getNetworkHandler());
        } else if (!swap.warned) {
            swap.warned = true;
            Printer.logger.warn("Printer is waiting for its inventory acknowledgement. Other hotbar slots are still available.");
        }
        return false;
    }

    record Refill(int sourceSlot, int hotbarSlot) {}

    private boolean swapToHotbar(ClientPlayerEntity player, ItemStack stack, int nextSlot) {
        if (!PlayerInventory.isValidHotbarIndex(nextSlot)) return false;
        int source = getBestInventorySlotWithItem(player, stack);
        return source != -1 && startSwapGroup(player, List.of(new Refill(source, nextSlot)));
    }

    /** All pairs are disjoint and reserved before the first packet; one fence covers the whole group. */
    boolean startSwapGroup(ClientPlayerEntity player, List<Refill> refills) {
        ClientPlayNetworkHandler network = mc.getNetworkHandler();
        if (network == null || !canExchange(player) || refills.isEmpty() || refills.size() > 3) return false;
        ensureInventoryOwner(player);
        if (pendingSwap != null || refillBackoff > 0) return false;
        PlayerInventory inventory = player.getInventory();
        PendingSwap swap = new PendingSwap(player.playerScreenHandler, refills.getFirst().hotbarSlot);
        for (Refill refill : refills) {
            int source = refill.sourceSlot;
            int hotbar = refill.hotbarSlot;
            if (source < 9 || source >= 36 || !PlayerInventory.isValidHotbarIndex(hotbar)
                    || !USABLE_SLOTS.contains(hotbar) || swap.endpoints[source] != null || swap.endpoints[hotbar] != null) return false;
            ItemStack incoming = inventory.getStack(source);
            ItemStack displaced = inventory.getStack(hotbar);
            if (ItemStack.areEqual(incoming, displaced)) return false;
            swap.endpoints[source] = new Endpoint(source, displaced.copy());
            swap.endpoints[hotbar] = new Endpoint(hotbar, incoming.copy());
        }
        pendingSwap = swap;
        for (Refill refill : refills) {
            sendSwapClick(network, swap.handler.syncId, swap.revision, refill.sourceSlot, refill.hotbarSlot);
            recordRefillSlot(refill.hotbarSlot);
        }
        sendFence(swap, network);
        if (PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) Printer.logger.info("Printer refill group {}", refills);
        return true;
    }

    boolean isReservedHotbar(ClientPlayerEntity player, int slot) {
        PendingSwap swap = unsettledSwap(player);
        return swap != null && PlayerInventory.isValidHotbarIndex(slot) && swap.endpoints[slot] != null;
    }

    /** Recount one slot after its placement and inventory responses failed to settle its debits. */
    boolean requestStockReadback(int slot) {
        ClientPlayerEntity player = mc.player;
        ClientPlayNetworkHandler network = mc.getNetworkHandler();
        if (network == null || !PlayerInventory.isValidHotbarIndex(slot) || !canExchange(player)) return false;
        ensureInventoryOwner(player);
        if (pendingSwap != null) return false;
        PendingSwap swap = new PendingSwap(player.playerScreenHandler, slot);
        swap.endpoints[slot] = new Endpoint(slot, player.getInventory().getStack(slot).copy());
        swap.stockReadback = true;
        pendingSwap = swap;
        // Drain any old inventory snapshots and all uses before asking for a fresh full count.
        sendFence(swap, network);
        return true;
    }

    public void onAuthoritativeSlot(int syncId, int revision, int screenSlot) {
        PendingSwap swap = observeRevision(syncId, revision);
        if (swap == null) return;
        int index = screenSlot >= PlayerScreenHandler.HOTBAR_START ? screenSlot - PlayerScreenHandler.HOTBAR_START : screenSlot;
        if (screenSlot < 9 || screenSlot >= PlayerScreenHandler.HOTBAR_START + 9 || swap.endpoints[index] == null) return;
        swap.endpoints[index].observe(inventoryOwner.getInventory(), false);
    }

    /** A full inventory packet must cover every slot before it can attest to the group. */
    public void onAuthoritativeInventory(int syncId, int revision, int slotCount) {
        if (pendingSwap == null || pendingSwap.handler.slots.size() != slotCount) return;
        PendingSwap swap = observeRevision(syncId, revision);
        if (swap == null) return;
        for (Endpoint endpoint : swap.endpoints) {
            if (endpoint != null) endpoint.observe(inventoryOwner.getInventory(), swap.phase == Phase.READBACK && !swap.interrupted);
        }
    }

    private PendingSwap observeRevision(int syncId, int revision) {
        ClientPlayerEntity player = mc.player;
        PendingSwap swap = unsettledSwap(player);
        if (swap == null || swap.handler.syncId != syncId) return null;
        if (!canExchange(player)) {
            swap.interrupt();
            return null;
        }
        int distance = (revision - swap.revision) & REVISION_MASK;
        if (distance == 0 || distance >= HALF_REVISION_RANGE) return null;
        swap.revision = revision;
        return swap;
    }

    private void requestReadback(PendingSwap swap, ClientPlayNetworkHandler network) {
        if (network == null) return;
        swap.phase = Phase.READBACK;
        swap.interrupted = false;
        swap.warned = false;
        swap.ticksRemaining = responseTicks();
        // During a long open menu vanilla can advance past half the revision range. This
        // epoch follows the previous fence and must start from the currently applied revision.
        swap.revision = swap.handler.getRevision();
        for (Endpoint endpoint : swap.endpoints) {
            if (endpoint != null) endpoint.readback = null;
        }
        // A stale revision on a hotbar self-SWAP publishes the full menu without moving items.
        sendSwapClick(network, swap.handler.syncId, swap.revision ^ HALF_REVISION_RANGE,
                PlayerScreenHandler.HOTBAR_START + swap.firstHotbar, swap.firstHotbar);
        sendFence(swap, network);
    }

    private void sendFence(PendingSwap swap, ClientPlayNetworkHandler network) {
        swap.fence = InventoryFenceOrder.get(network).expect();
        network.sendPacket(swap.fence);
    }

    public void onInventoryReadbackBarrier(ClientPlayNetworkHandler network) {
        // Always consume the reply, even when no printer session is running. Vanilla replies to
        // REQUEST_STATS in order on the same server thread that applies and publishes clicks.
        ClientStatusC2SPacket fence = InventoryFenceOrder.get(network).received();
        if (fence == null || pendingSwap == null || pendingSwap.fence != fence || mc.getNetworkHandler() != network) return;
        if (mc.player != null) ensureInventoryOwner(mc.player);
        PendingSwap swap = pendingSwap;
        if (swap == null) return;
        swap.fence = null;
        if (!canExchange(mc.player)) {
            swap.interrupt();
            swap.deferReadback();
            return;
        }
        PlayerInventory inventory = mc.player.getInventory();
        if (swap.phase == Phase.GROUP) {
            if (!swap.stockReadback && swap.confirmed(inventory)) pendingSwap = null;
            else requestReadback(swap, network);
        } else if (swap.matchesReadback(inventory)) {
            if (swap.stockReadback) Printer.reconcileHotbarSlot(swap.firstHotbar, inventory.getStack(swap.firstHotbar));
            else if (!swap.confirmed(inventory)) refillBackoff = responseTicks();
            pendingSwap = null;
        } else {
            // The fence was consumed. A missing/invalidated snapshot cannot be repaired by waiting
            // for another unsolicited reply. Issue a new read-only probe after bounded backoff.
            swap.deferReadback();
        }
    }

    public boolean tracksConnection(ClientPlayNetworkHandler network) {
        return pendingSwap != null && mc.getNetworkHandler() == network;
    }

    private static int responseTicks() {
        return Math.max(MIN_RESPONSE_TICKS, PrinterConfig.INVENTORY_DELAY.getIntegerValue());
    }

    private static void sendSwapClick(ClientPlayNetworkHandler network, int syncId, int revision,
                                      int screenSlot, int hotbarSlot) {
        network.sendPacket(new ClickSlotC2SPacket(syncId, revision, screenSlot, hotbarSlot,
                SlotActionType.SWAP, ItemStack.EMPTY, Int2ObjectMaps.emptyMap()));
    }

    private PendingSwap unsettledSwap(ClientPlayerEntity player) {
        return player != null && player == inventoryOwner && pendingSwap != null
                && player.playerScreenHandler == pendingSwap.handler ? pendingSwap : null;
    }

    private enum Phase { GROUP, READBACK, RETRY_READBACK }

    private static final class Endpoint {
        final int slot;
        final ItemStack expected;
        boolean published;
        ItemStack readback;

        Endpoint(int slot, ItemStack expected) {
            this.slot = slot;
            this.expected = expected;
        }

        void observe(PlayerInventory inventory, boolean fullReadback) {
            ItemStack actual = inventory.getStack(slot);
            published = ItemStack.areEqual(actual, expected);
            if (fullReadback || readback != null) readback = actual.copy();
        }
    }

    private static final class PendingSwap {
        PlayerScreenHandler handler;
        final int firstHotbar;
        final Endpoint[] endpoints = new Endpoint[36];
        int revision;
        int ticksRemaining = responseTicks();
        int readbackRetries;
        boolean interrupted;
        boolean warned;
        boolean stockReadback;
        Phase phase = Phase.GROUP;
        ClientStatusC2SPacket fence;

        PendingSwap(PlayerScreenHandler handler, int firstHotbar) {
            this.handler = handler;
            this.firstHotbar = firstHotbar;
            revision = handler.getRevision();
        }

        boolean confirmed(PlayerInventory inventory) {
            if (interrupted) return false;
            for (Endpoint endpoint : endpoints) {
                if (endpoint != null && (!endpoint.published || !ItemStack.areEqual(inventory.getStack(endpoint.slot), endpoint.expected))) return false;
            }
            return true;
        }

        boolean matchesReadback(PlayerInventory inventory) {
            if (interrupted) return false;
            for (Endpoint endpoint : endpoints) {
                if (endpoint != null && (endpoint.readback == null || !ItemStack.areEqual(inventory.getStack(endpoint.slot), endpoint.readback))) return false;
            }
            return true;
        }

        void deferReadback() {
            phase = Phase.RETRY_READBACK;
            ticksRemaining = Math.min(200, responseTicks() << Math.min(readbackRetries, 4));
            if (readbackRetries < 4) readbackRetries++;
        }

        void interrupt() {
            interrupted = true;
        }

        void rebind(PlayerScreenHandler newHandler) {
            handler = newHandler;
            revision = newHandler.getRevision();
            interrupt();
        }
    }

    public void pickSlot(WorldSchematic world, ClientPlayerEntity player, BlockPos pos) {
        if (mc.interactionManager == null) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        BlockState state = world.getBlockState(pos);
        ItemStack stack = MaterialCache.getInstance().getRequiredBuildItemForState(state, world, pos);
        int slot = inv.getSlotWithStack(stack);
        if (unsettledSwap(player) != null && (slot < 0 || slot > 8 || isReservedHotbar(player, slot))) return;
        boolean shouldPick = slot > 8;
        if (slot != -1 && !shouldPick) {
            selectHotbarSlot(player, slot);
        } else if (slot != -1) {
            InventoryUtils.setPickedItemToHand(slot, stack, mc); // https://github.com/sakura-ryoko/litematica-printer/blob/f8e38a2b31708e61f8a5fad0f2989d6834495da4/src/main/java/me/aleksilassila/litematica/printer/actions/PrepareAction.java#L71
        } else if (Configs.Generic.PICK_BLOCK_SHULKERS.getBooleanValue()) {
            slot = findSlotWithBoxWithItem(player.getInventory(), stack, true);
            if (slot > -1) {
                if (slot > 8) {
                    InventoryUtils.setPickedItemToHand(slot, stack, mc);
                } else {
                    selectHotbarSlot(player, slot);
                }
            }
        }
    }

    public static int findSlotWithBoxWithItem(PlayerInventory inventory, ItemStack stackReference, boolean lestFirst) {
        int bestCount = lestFirst ? Integer.MAX_VALUE : 0;
        int bestSlot = -1;

        for (int slotNum = 0; slotNum < inventory.main.size(); slotNum += 1) {
            ItemStack itemStack = inventory.getStack(slotNum);
            int count = shulkerBoxItemCount(itemStack, stackReference);
            if (lestFirst && count < bestCount && count > 0) {
                bestCount = count;
                bestSlot = slotNum;
            } else if (!lestFirst && count > bestCount) {
                bestCount = count;
                bestSlot = slotNum;
            }
        }

        return bestSlot;
    }

    public static int shulkerBoxItemCount(ItemStack stack, ItemStack referenceItem) {
        DefaultedList<ItemStack> items = fi.dy.masa.malilib.util.InventoryUtils.getStoredItems(stack);
        int count = 0;
        if (!items.isEmpty()) {
            for (ItemStack item : items) {
                if (fi.dy.masa.malilib.util.InventoryUtils.areStacksEqual(item, referenceItem)) {
                    count += item.getCount();
                }
            }
        }

        return count;
    }

    /** True when the material exists, even if moving it to the hotbar takes another tick. */
    public boolean hasMaterial(ItemStack stack) {
        ClientPlayerEntity player = mc.player;
        if (player == null || stack == null || stack.isEmpty()) return false;
        return getHotbarSlotWithItem(player, stack) != -1
                || (!USABLE_SLOTS.isEmpty() && (player.getAbilities().creativeMode
                || getBestInventorySlotWithItem(player, stack) != -1));
    }

    /** True when the matching hotbar slot is usable without changing the inventory. */
    public boolean hasReadyHotbarMaterial(ItemStack stack) {
        ClientPlayerEntity player = mc.player;
        return stack != null && canAccessInventory(player) && findReadyHotbarSlot(player, stack, false) != -1;
    }

    /** Native placements also need a unit beyond the material's in-flight uses. */
    public boolean hasReadyNativeMaterial(ItemStack stack) {
        ClientPlayerEntity player = mc.player;
        return stack != null && !stack.isEmpty() && canAccessInventory(player)
                && findReadyHotbarSlot(player, stack, true) != -1;
    }

    public boolean selectReadyHotbarMaterial(ItemStack stack) {
        return selectReadyMaterial(stack, false);
    }

    public boolean selectReadyNativeMaterial(ItemStack stack) {
        return selectReadyMaterial(stack, true);
    }

    public boolean canUseSelectedSlot(ClientPlayerEntity player) {
        if (!canAccessInventory(player)) return false;
        int slot = player.getInventory().selectedSlot;
        if (isReservedHotbar(player, slot)) return false;
        ItemStack held = player.getInventory().getStack(slot);
        int reserved = Printer.pendingSlotCount(slot, held);
        return player.getAbilities().creativeMode || reserved == 0 || held.getCount() > reserved;
    }

    private boolean selectReadyMaterial(ItemStack stack, boolean requireStock) {
        ClientPlayerEntity player = mc.player;
        if (stack == null || !canAccessInventory(player)) return false;
        ensureInventoryOwner(player);
        int slot = findReadyHotbarSlot(player, stack, requireStock);
        if (slot == -1) return false;
        selectReadySlot(player, slot);
        return true;
    }

    public boolean canPrefetch() {
        ClientPlayerEntity player = mc.player;
        return canStageRefill(player) && findRefillSlot(player, false, List.of()) != -1;
    }

    public boolean canStartRefill() {
        ClientPlayerEntity player = mc.player;
        if (!canExchange(player) || unsettledSwap(player) != null || refillBackoff > 0) return false;
        if (player.getAbilities().creativeMode) return firstCreativeRefillSlot() != -1;
        return findRefillSlot(player, true, List.of()) != -1;
    }

    /** Stage a future material without changing the current selection. */
    public boolean prefetch(ItemStack stack, List<ItemStack> keepInHotbar) {
        ClientPlayerEntity player = mc.player;
        if (stack == null || !canStageRefill(player) || findReadyHotbarSlot(player, stack, false) != -1) return false;
        ensureInventoryOwner(player);
        return swapToHotbar(player, stack, findRefillSlot(player, false, keepInHotbar));
    }

    /** Ranked, placeable material types. Each count is the nearby demand for that type. */
    boolean stageMaterials(List<ItemStack> materials, List<ItemStack> keep, boolean demand) {
        ClientPlayerEntity player = mc.player;
        if (materials.isEmpty() || !canStageRefill(player)) return false;
        ensureInventoryOwner(player);
        int reserved = protectedSlots(player, keep);
        List<Refill> refills = new ArrayList<>(3);
        for (ItemStack stack : materials) {
            if (findReadyHotbarSlot(player, stack, true) != -1) continue;
            int source = getBestInventorySlotWithItem(player, stack);
            if (source == -1) continue;
            int destination = findRefillSlot(player, demand, reserved);
            if (destination == -1) break;
            refills.add(new Refill(source, destination));
            reserved |= 1 << destination;
            if (refills.size() == 3) break;
        }
        return !refills.isEmpty() && startSwapGroup(player, refills);
    }

    private boolean canStageRefill(ClientPlayerEntity player) {
        return canExchange(player) && !player.getAbilities().creativeMode
                && unsettledSwap(player) == null && refillBackoff == 0;
    }

    private boolean canExchange(ClientPlayerEntity player) {
        return canAccessInventory(player) && !(mc.currentScreen instanceof HandledScreen<?>);
    }

    private boolean canAccessInventory(ClientPlayerEntity player) {
        // Never close a screen or dispose of a cursor stack as a side effect of printing.
        return player != null && mc.interactionManager != null
                && player.currentScreenHandler == player.playerScreenHandler
                && player.playerScreenHandler.getCursorStack().isEmpty();
    }

    /**
     * Returns true only when MAIN_HAND is ready for an ordered UseOn packet.
     * False after a swap means defer and rescan; it is not a missing-material result.
     */
    public boolean select(ItemStack itemStack) {
        ClientPlayerEntity player = mc.player;
        if (itemStack == null || !canAccessInventory(player)) return false;
        ensureInventoryOwner(player);
        int hotbarSlot = findReadyHotbarSlot(player, itemStack, false);
        if (hotbarSlot != -1) {
            selectReadySlot(player, hotbarSlot);
            return true;
        }
        if (unsettledSwap(player) != null || refillBackoff > 0) return false;

        // Empty-hand interactions may use an existing empty slot. Never erase
        // a stack to manufacture an empty hand, including in creative mode.
        if (itemStack.isEmpty() || !player.getAbilities().creativeMode) {
            swapToHotbar(player, itemStack, findRefillSlot(player, true, List.of()));
            return false;
        }
        hotbarSlot = firstCreativeRefillSlot();
        if (hotbarSlot == -1) return false;
        ItemStack picked = itemStack.copy();
        player.getInventory().setStack(hotbarSlot, picked);
        mc.interactionManager.clickCreativeStack(picked, PlayerScreenHandler.HOTBAR_START + hotbarSlot);
        recordRefillSlot(hotbarSlot);
        selectReadySlot(player, hotbarSlot);
        return true;
    }

    private void selectReadySlot(ClientPlayerEntity player, int slot) {
        selectHotbarSlot(player, slot);
        updateLastUsedSlot(slot);
    }

    private void ensureInventoryOwner(ClientPlayerEntity player) {
        ClientPlayNetworkHandler network = mc.getNetworkHandler();
        if (inventoryConnection != network) {
            inventoryConnection = network;
            reset();
        } else if (inventoryOwner != player && pendingSwap != null) {
            // Respawn and dimension changes can replace the player on the same ordered connection.
            // Keep the slots reserved until a refresh from the new player handler is fenced.
            pendingSwap.rebind(player.playerScreenHandler);
        }
        inventoryOwner = player;
    }

    private void selectHotbarSlot(ClientPlayerEntity player, int slot) {
        player.getInventory().selectedSlot = slot;
        // Use vanilla's tracker so raw sequenced placements cannot precede the
        // selected-slot update, and normal movement does not send duplicate swaps.
        mc.interactionManager.syncSelectedSlot();
    }

    private int findRefillSlot(ClientPlayerEntity player, boolean allowSelected, List<ItemStack> keepInHotbar) {
        return findRefillSlot(player, allowSelected, protectedSlots(player, keepInHotbar));
    }

    private int protectedSlots(ClientPlayerEntity player, List<ItemStack> keep) {
        int[] demand = new int[9];
        int protectedSlots = 0;
        for (ItemStack wanted : keep) {
            for (int slot = 0; slot < 9; slot++) {
                ItemStack held = player.getInventory().getStack(slot);
                if (isReservedHotbar(player, slot)) continue;
                if (wanted.isEmpty() ? !held.isEmpty() : held.isEmpty() || !ItemStack.areItemsAndComponentsEqual(wanted, held)) continue;
                if (!held.isEmpty() && held.getCount() - Printer.pendingSlotCount(slot, held) <= demand[slot]) continue;
                protectedSlots |= 1 << slot;
                demand[slot]++;
                break;
            }
        }
        return protectedSlots;
    }

    private int findRefillSlot(ClientPlayerEntity player, boolean allowSelected, int protectedSlots) {
        PlayerInventory inventory = player.getInventory();
        int selectedSlot = inventory.selectedSlot;
        int idleSlot = -1;
        int selectedFallback = -1;
        for (int slot : refillSlotOrder()) {
            if (!USABLE_SLOTS.contains(slot) || (slot == selectedSlot && !allowSelected)) continue;
            ItemStack held = inventory.getStack(slot);
            if ((protectedSlots & (1 << slot)) != 0 || Printer.pendingSlotCount(slot, held) > 0) continue;
            if (slot == selectedSlot) selectedFallback = slot;
            else if (held.isEmpty()) return slot;
            else if (idleSlot == -1) idleSlot = slot;
        }
        return idleSlot == -1 ? selectedFallback : idleSlot;
    }

    private Deque<Integer> refillSlotOrder() {
        String mode = PrinterConfig.PRINTER_INVENTORY_MANAGEMENT_MODE.getStringValue();
        if (PrinterConfig.InventoryManagementModeEnum.ROLLING.is(mode)) return rollingSlots;
        return lastUsedSlots.reversed();
    }

    private int firstCreativeRefillSlot() {
        return refillSlotOrder().stream().filter(USABLE_SLOTS::contains).findFirst().orElse(-1);
    }

    private void recordRefillSlot(int slot) {
        String mode = PrinterConfig.PRINTER_INVENTORY_MANAGEMENT_MODE.getStringValue();
        if (PrinterConfig.InventoryManagementModeEnum.ROLLING.is(mode)) {
            rollingSlots.remove(slot);
            rollingSlots.addLast(slot);
        } else {
            lastUsedSlots.remove(slot);
            lastUsedSlots.addFirst(slot);
        }
    }

    /** Prefer a stack sufficient for nearby demand, otherwise the largest available stack. */
    private static int getBestInventorySlotWithItem(ClientPlayerEntity player, ItemStack itemStack) {
        PlayerInventory inventory = player.getInventory();

        int bestSlot = -1;
        int bestCount = 0;
        int demand = itemStack.getCount();
        for (int i = 9; i < inventory.main.size(); ++i) {
            if (itemStack.isEmpty() && inventory.main.get(i).isEmpty()) return i;
            ItemStack stack = inventory.main.get(i);
            if (stack.isEmpty() || !ItemStack.areItemsAndComponentsEqual(itemStack, stack)) continue;
            int count = stack.getCount();
            if (count == demand) return i;
            if (bestSlot == -1 || (bestCount < demand ? count > bestCount : count >= demand && count < bestCount)) {
                bestCount = count;
                bestSlot = i;
            }
        }

        return bestSlot;
    }

    /**
     * Return a number between 0-8 representing the hotbar slot with the given item.
     */
    public int getHotbarSlotWithItem(ClientPlayerEntity player, ItemStack itemStack) {
        return findHotbarSlot(player, itemStack, false, false);
    }

    private int findReadyHotbarSlot(ClientPlayerEntity player, ItemStack itemStack, boolean requireAvailableCount) {
        return findHotbarSlot(player, itemStack, true, requireAvailableCount);
    }

    private int findHotbarSlot(ClientPlayerEntity player, ItemStack itemStack, boolean checkReservation,
                               boolean requireAvailableCount) {
        PlayerInventory inventory = player.getInventory();
        int matchingSlot = -1;
        for (int slot = 0; slot < 9; slot++) {
            if (checkReservation && isReservedHotbar(player, slot)) continue;
            ItemStack held = inventory.getStack(slot);
            if (itemStack.isEmpty()) {
                if (held.isEmpty()) return slot;
            } else if (!held.isEmpty() && ItemStack.areItemsAndComponentsEqual(held, itemStack)) {
                if (player.getAbilities().creativeMode || held.getCount() > Printer.pendingSlotCount(slot, held)) return slot;
                if (matchingSlot == -1) matchingSlot = slot;
            }
        }
        return requireAvailableCount ? -1 : matchingSlot;
    }

    private void updateLastUsedSlot(int slot) {
        lastUsedSlots.remove(slot);
        lastUsedSlots.addFirst(slot);
        if (PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) {
            String list = lastUsedSlots.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("");
            System.out.println("Updating last used slot: " + slot + ". Now: " + list);
        }
    }

}
