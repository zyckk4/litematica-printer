package me.aleksilassila.litematica.printer.v1_21_11;

import fi.dy.masa.litematica.config.Configs;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.InventoryUtils;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.options.ConfigString;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
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
    int delay = 0;
    private final MinecraftClient mc = MinecraftClient.getInstance();
    private final Deque<Integer> rollingSlots = new ArrayDeque<>();
    private final Deque<Integer> lastUsedSlots = new ArrayDeque<>();
    private static InventoryManager instance;
    private ClientPlayerEntity inventoryOwner;
    private PendingSwap pendingSwap;

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
        delay = 0;
        pendingSwap = null;
    }

    public boolean tick() {
        if (mc.player == null || mc.interactionManager == null) {
            inventoryOwner = null;
            reset();
            return false;
        }
        ensureInventoryOwner(mc.player);
        // Give the first eligible tick a chance to inspect both slots before
        // releasing a swap whose result no longer matches our prediction.
        if (delay == 0) pendingSwap = null;
        else delay--;

        return false;
    }

    private boolean swapToHotbar(ClientPlayerEntity player, ItemStack stack, int nextSlot) {
        if (nextSlot == -1) return false;
        int slot = getBestInventorySlotWithItem(player, stack);
        if (slot == -1) return false;
        PlayerInventory inventory = player.getInventory();
        PendingSwap swap = new PendingSwap(slot, nextSlot,
                inventory.getStack(nextSlot).copy(), inventory.getStack(slot).copy());
        if (PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) {
            System.out.println("Swapping item from inventory: " + slot + " into hotbar -> " + nextSlot);
        }
        if (!swapAway(slot, nextSlot, player.playerScreenHandler)) return false;
        pendingSwap = swap;
        recordRefillSlot(nextSlot);
        // A successful vanilla click can go unacknowledged. Allow time for
        // corrections before inspecting the slots affected by the click.
        delay = Math.max(1, PrinterConfig.INVENTORY_DELAY.getIntegerValue());
        return true;
    }

    private record PendingSwap(int sourceSlot, int hotbarSlot, ItemStack expectedSource, ItemStack expectedHotbar) {
        boolean matches(PlayerInventory inventory) {
            return ItemStack.areEqual(inventory.getStack(sourceSlot), expectedSource)
                    && ItemStack.areEqual(inventory.getStack(hotbarSlot), expectedHotbar);
        }
    }

    private PendingSwap unsettledSwap(ClientPlayerEntity player) {
        if (inventoryOwner != player || pendingSwap == null) return null;
        return delay > 0 || !pendingSwap.matches(player.getInventory()) ? pendingSwap : null;
    }

    private void releaseMatchingSwap(ClientPlayerEntity player) {
        if (pendingSwap != null && unsettledSwap(player) == null) pendingSwap = null;
    }

    public void pickSlot(WorldSchematic world, ClientPlayerEntity player, BlockPos pos) {
        if (mc.interactionManager == null) {
            return;
        }
        PlayerInventory inv = player.getInventory();
        BlockState state = world.getBlockState(pos);
        ItemStack stack = MaterialCache.getInstance().getRequiredBuildItemForState(state, world, pos);
        int slot = inv.getSlotWithStack(stack);
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
        releaseMatchingSwap(player);
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
        if (!canAccessInventory(player) || unsettledSwap(player) != null) return false;
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
        return canAccessInventory(player) && !player.getAbilities().creativeMode && unsettledSwap(player) == null;
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
        releaseMatchingSwap(player);
        int hotbarSlot = findReadyHotbarSlot(player, itemStack, false);
        if (hotbarSlot != -1) {
            selectReadySlot(player, hotbarSlot);
            return true;
        }
        if (unsettledSwap(player) != null) return false;

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
        if (inventoryOwner != player) {
            inventoryOwner = player;
            reset();
        }
    }

    private void selectHotbarSlot(ClientPlayerEntity player, int slot) {
        player.getInventory().selectedSlot = slot;
        // Use vanilla's tracker so raw sequenced placements cannot precede the
        // selected-slot update, and normal movement does not send duplicate swaps.
        mc.interactionManager.syncSelectedSlot();
    }

    public boolean swapAway(int inventorySlot, int hotbarSlot, PlayerScreenHandler screenHandler) {
        ClientPlayNetworkHandler networkHandler = mc.getNetworkHandler();
        if (mc.player == null || mc.interactionManager == null || networkHandler == null
                || screenHandler != mc.player.currentScreenHandler
                || !screenHandler.getCursorStack().isEmpty()
                || inventorySlot < 0 || inventorySlot >= 36
                || !PlayerInventory.isValidHotbarIndex(hotbarSlot)) return false;

        int screenSlot = inventorySlot < 9 ? PlayerScreenHandler.HOTBAR_START + inventorySlot : inventorySlot;
        mc.interactionManager.clickSlot(screenHandler.syncId, screenSlot, hotbarSlot, SlotActionType.SWAP, mc.player);
        return true;
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
        int pendingSlot = pending == null ? -1 : pending.hotbarSlot();
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
