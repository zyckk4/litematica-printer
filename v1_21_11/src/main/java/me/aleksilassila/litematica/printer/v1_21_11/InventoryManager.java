package me.aleksilassila.litematica.printer.v1_21_11;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.options.ConfigString;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
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
import net.minecraft.screen.sync.ItemStackHash;
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
        if (swap.recovery == Recovery.NONE) {
            startRecovery(swap);
        } else if (!swap.warned) {
            swap.warned = true;
            Printer.logger.warn("Printer is waiting for an authoritative inventory refresh. Other hotbar slots are still available.");
        }

        return false;
    }

    private boolean swapToHotbar(ClientPlayerEntity player, ItemStack stack, int nextSlot) {
        ClientPlayNetworkHandler network = mc.getNetworkHandler();
        if (nextSlot == -1 || network == null || !canExchange(player) || pendingSwap != null || refillBackoff > 0) return false;
        int slot = getBestInventorySlotWithItem(player, stack);
        if (slot == -1) return false;
        PlayerInventory inventory = player.getInventory();
        ItemStack source = inventory.getStack(slot);
        ItemStack destination = inventory.getStack(nextSlot);
        if (ItemStack.areEqual(source, destination)) return false;
        PlayerScreenHandler handler = player.playerScreenHandler;
        pendingSwap = new PendingSwap(handler, slot, nextSlot, destination.copy(), source.copy());
        // Leave the client inventory untouched so the server publishes both changed slots.
        sendSwapClick(network, handler.syncId, handler.getRevision(), slot, nextSlot);
        recordRefillSlot(nextSlot);
        if (PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) {
            System.out.println("Swapping item from inventory: " + slot + " into hotbar -> " + nextSlot);
        }
        return true;
    }

    public void onAuthoritativeSlot(int syncId, int revision, int screenSlot) {
        PendingSwap swap = observeRevision(syncId, revision);
        if (swap == null) return;
        PlayerInventory inventory = inventoryOwner.getInventory();
        if (screenSlot == swap.sourceSlot) {
            ItemStack actual = inventory.getStack(swap.sourceSlot);
            swap.sourcePublished = ItemStack.areEqual(actual, swap.expectedSource);
            if (swap.readbackSource != null) swap.readbackSource = actual.copy();
        } else if (screenSlot == swap.hotbarScreenSlot()) {
            ItemStack actual = inventory.getStack(swap.hotbarSlot);
            swap.hotbarPublished = ItemStack.areEqual(actual, swap.expectedHotbar);
            if (swap.readbackHotbar != null) swap.readbackHotbar = actual.copy();
        }
        finishIfConfirmed(swap);
    }

    public void onAuthoritativeInventory(int syncId, int revision) {
        PendingSwap swap = observeRevision(syncId, revision);
        if (swap == null) return;
        PlayerInventory inventory = inventoryOwner.getInventory();
        ItemStack source = inventory.getStack(swap.sourceSlot);
        ItemStack hotbar = inventory.getStack(swap.hotbarSlot);
        swap.sourcePublished = ItemStack.areEqual(source, swap.expectedSource);
        swap.hotbarPublished = ItemStack.areEqual(hotbar, swap.expectedHotbar);
        if (swap.recovery == Recovery.REFRESHING && !swap.refreshInvalidated) {
            swap.readbackSource = source.copy();
            swap.readbackHotbar = hotbar.copy();
        }
        finishIfConfirmed(swap);
    }

    /** A full inventory packet must cover every slot before it can attest to both endpoints. */
    public void onAuthoritativeInventory(int syncId, int revision, int slotCount) {
        if (pendingSwap != null && pendingSwap.handler.slots.size() == slotCount) {
            onAuthoritativeInventory(syncId, revision);
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

    private void finishIfConfirmed(PendingSwap swap) {
        if (swap.recovery == Recovery.NONE && swap.confirmed(inventoryOwner.getInventory())) {
            pendingSwap = null;
        }
    }

    private void startRecovery(PendingSwap swap) {
        ClientPlayNetworkHandler network = mc.getNetworkHandler();
        if (network == null) return;
        swap.recovery = Recovery.DRAINING;
        swap.ticksRemaining = responseTicks();
        sendInventoryFence(network);
    }

    private void requestReadback(PendingSwap swap, ClientPlayNetworkHandler network) {
        swap.recovery = Recovery.REFRESHING;
        swap.refreshInvalidated = false;
        swap.ticksRemaining = responseTicks();
        // Clicking a hotbar slot with its own number does not move items. A stale revision asks
        // vanilla for a complete snapshot. The following stats response is a main-thread fence.
        sendSwapClick(network, swap.handler.syncId, swap.handler.getRevision() ^ HALF_REVISION_RANGE,
                swap.hotbarScreenSlot(), swap.hotbarSlot);
        sendInventoryFence(network);
    }

    private static void sendInventoryFence(ClientPlayNetworkHandler network) {
        network.sendPacket(new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS));
    }

    public void onInventoryReadbackBarrier() {
        ClientPlayerEntity player = mc.player;
        PendingSwap swap = unsettledSwap(player);
        if (swap == null || swap.recovery == Recovery.NONE) return;
        if (!canExchange(player)) {
            swap.interrupt();
            swap.recovery = Recovery.NONE;
            return;
        }
        PlayerInventory inventory = inventoryOwner.getInventory();
        if (swap.recovery == Recovery.DRAINING) {
            // The server has already handled the original click. Any snapshot from before it
            // preceded this response, so it cannot be mistaken for the recovery snapshot.
            if (swap.confirmed(inventory)) pendingSwap = null;
            else if (mc.getNetworkHandler() != null) requestReadback(swap, mc.getNetworkHandler());
            return;
        }
        if (!swap.matchesReadback(inventory)) {
            if (swap.refreshInvalidated) {
                swap.recovery = Recovery.NONE;
                swap.ticksRemaining = responseTicks();
            }
            return;
        }
        if (!swap.matches(inventory)) refillBackoff = responseTicks();
        // Replan from the server snapshot. This never retransmits the original exchange.
        pendingSwap = null;
    }

    public boolean tracksConnection(ClientPlayNetworkHandler network) {
        return pendingSwap != null && mc.getNetworkHandler() == network;
    }

    private static int responseTicks() {
        return Math.max(MIN_RESPONSE_TICKS, PrinterConfig.INVENTORY_DELAY.getIntegerValue());
    }

    private static void sendSwapClick(ClientPlayNetworkHandler network, int syncId, int revision,
                                      int screenSlot, int hotbarSlot) {
        network.sendPacket(new ClickSlotC2SPacket(syncId, revision, (short) screenSlot, (byte) hotbarSlot,
                SlotActionType.SWAP, Int2ObjectMaps.emptyMap(), ItemStackHash.EMPTY));
    }

    private PendingSwap unsettledSwap(ClientPlayerEntity player) {
        return player != null && player == inventoryOwner && pendingSwap != null
                && player.playerScreenHandler == pendingSwap.handler ? pendingSwap : null;
    }

    private enum Recovery { NONE, DRAINING, REFRESHING }

    private static final class PendingSwap {
        PlayerScreenHandler handler;
        final int sourceSlot;
        final int hotbarSlot;
        final ItemStack expectedSource;
        final ItemStack expectedHotbar;
        int revision;
        int ticksRemaining = responseTicks();
        boolean sourcePublished;
        boolean hotbarPublished;
        boolean interrupted;
        boolean refreshInvalidated;
        Recovery recovery = Recovery.NONE;
        ItemStack readbackSource;
        ItemStack readbackHotbar;
        boolean warned;

        PendingSwap(PlayerScreenHandler handler, int sourceSlot, int hotbarSlot, ItemStack expectedSource, ItemStack expectedHotbar) {
            this.handler = handler;
            this.sourceSlot = sourceSlot;
            this.hotbarSlot = hotbarSlot;
            this.expectedSource = expectedSource;
            this.expectedHotbar = expectedHotbar;
            revision = handler.getRevision();
        }

        int hotbarScreenSlot() {
            return PlayerScreenHandler.HOTBAR_START + hotbarSlot;
        }

        boolean matches(PlayerInventory inventory) {
            return ItemStack.areEqual(inventory.getStack(sourceSlot), expectedSource)
                    && ItemStack.areEqual(inventory.getStack(hotbarSlot), expectedHotbar);
        }

        boolean confirmed(PlayerInventory inventory) {
            return !interrupted && sourcePublished && hotbarPublished && matches(inventory);
        }

        boolean matchesReadback(PlayerInventory inventory) {
            return readbackSource != null && ItemStack.areEqual(inventory.getStack(sourceSlot), readbackSource)
                    && ItemStack.areEqual(inventory.getStack(hotbarSlot), readbackHotbar);
        }

        void interrupt() {
            if (!interrupted) ticksRemaining = responseTicks();
            interrupted = true;
            sourcePublished = false;
            hotbarPublished = false;
            if (recovery == Recovery.REFRESHING) refreshInvalidated = true;
            readbackSource = null;
            readbackHotbar = null;
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
        PendingSwap swap = unsettledSwap(player);
        if (swap != null && (slot < 0 || slot > 8 || slot == swap.hotbarSlot)) return;
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
        ClientPlayerEntity player = mc.player;
        if (stack == null || !canAccessInventory(player)) return false;
        ensureInventoryOwner(player);
        int slot = findReadyHotbarSlot(player, stack, false);
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
        PlayerInventory inventory = player.getInventory();
        int selectedSlot = inventory.getSelectedSlot();
        int idleSlot = -1;
        int selectedFallback = -1;
        for (int slot : refillSlotOrder()) {
            if (!USABLE_SLOTS.contains(slot) || (slot == selectedSlot && !allowSelected)) continue;
            ItemStack held = inventory.getStack(slot);
            if (!held.isEmpty() && (Printer.pendingItemCount(held) > 0 || keepInHotbar.stream()
                    .anyMatch(stack -> ItemStack.areItemsAndComponentsEqual(held, stack)))) continue;
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

    /**
     * Returns the first slot with the given item. Returns -1 if no slot is found.
     *
     * @param player    The player to check
     * @param itemStack The item to check for
     * @return The first slot with the given item
     */
    private static int getBestInventorySlotWithItem(ClientPlayerEntity player, ItemStack itemStack) {
        PlayerInventory inventory = player.getInventory();

        int lowestCount = 0;
        int lowestSlot = -1;
        for (int i = 9; i < inventory.main.size(); ++i) {
            if (itemStack.isEmpty() && inventory.main.get(i).isEmpty()) return i;
            if (!(inventory.main.get(i)).isEmpty() && ItemStack.areItemsAndComponentsEqual(itemStack, inventory.main.get(i))) {
                if (inventory.main.get(i).getCount() < lowestCount || lowestSlot == -1) {
                    lowestCount = inventory.main.get(i).getCount();
                    lowestSlot = i;
                }
            }
        }

        return lowestSlot;
    }

    /**
     * Return a number between 0-8 representing the hotbar slot with the given item.
     */
    public int getHotbarSlotWithItem(ClientPlayerEntity player, ItemStack itemStack) {
        return findHotbarSlot(player.getInventory(), itemStack, -1, 0, false);
    }

    private int findReadyHotbarSlot(ClientPlayerEntity player, ItemStack itemStack, boolean requireAvailableCount) {
        PendingSwap pending = unsettledSwap(player);
        int pendingSlot = pending == null ? -1 : pending.hotbarSlot;
        int pendingUseCount = itemStack.isEmpty() || player.getAbilities().creativeMode
                ? 0 : Printer.pendingItemCount(itemStack);
        return findHotbarSlot(player.getInventory(), itemStack, pendingSlot, pendingUseCount, requireAvailableCount);
    }

    private static int findHotbarSlot(PlayerInventory inventory, ItemStack itemStack, int excludedSlot,
                                      int pendingUseCount, boolean requireAvailableCount) {
        int matchingSlot = -1;
        for (int slot = 0; slot < 9; slot++) {
            if (slot == excludedSlot) continue;
            ItemStack held = inventory.getStack(slot);
            if (itemStack.isEmpty()) {
                if (held.isEmpty()) return slot;
            } else if (!held.isEmpty() && ItemStack.areItemsAndComponentsEqual(held, itemStack)) {
                if (held.getCount() > pendingUseCount) return slot;
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
