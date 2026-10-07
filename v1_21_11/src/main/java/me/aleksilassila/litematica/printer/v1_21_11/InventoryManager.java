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
    private int pendingHotbarSlot = -1;
    private ItemStack pendingStack = ItemStack.EMPTY;

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
        pendingHotbarSlot = -1;
        pendingStack = ItemStack.EMPTY;
    }

    public boolean tick() {
        if (mc.player == null || mc.interactionManager == null) {
            inventoryOwner = null;
            reset();
            return false;
        }
        ensureInventoryOwner(mc.player);
        delay = Math.max(0, delay - 1);

        return false;
    }

    /**
     * Swaps the item from the inventory to the hotbar
     *
     * @param stack The complete stack identity to pull from the inventory
     * @return True if the item could the swapped into the hotbar
     */
    private boolean swapToHotbar(ClientPlayerEntity player, ItemStack stack) {
        if (getHotbarSlotWithItem(player, stack) != -1) {
            return true;
        }
        int slot = getBestInventorySlotWithItem(player, stack);
        if (slot == -1) {
            return false;
        }
        int nextSlot = nextHotbarSlot();
        if (nextSlot == -1) {
            return false;
        }
        if (PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) {
            System.out.println("Swapping item from inventory: " + slot + " into hotbar -> " + nextSlot);
        }
        if (!swapAway(slot, nextSlot, player.playerScreenHandler)) return false;
        pendingHotbarSlot = nextSlot;
        pendingStack = stack.isEmpty() ? ItemStack.EMPTY : stack.copyWithCount(1);
        // A successful vanilla click need not receive a slot-update ACK. Wait a
        // bounded interval, then inspect the stack after any server corrections.
        delay = Math.max(1, PrinterConfig.INVENTORY_DELAY.getIntegerValue());
        return true;
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

    /**
     * Returns true only when MAIN_HAND is ready for an ordered UseOn packet.
     * False after a swap means defer and rescan; it is not a missing-material result.
     */
    public boolean select(ItemStack itemStack) {
        ClientPlayerEntity player = mc.player;
        if (player == null || mc.interactionManager == null || itemStack == null) {
            return false;
        }
        ensureInventoryOwner(player);
        // Never close a screen or dispose of a cursor stack as a side effect of printing.
        if (player.currentScreenHandler != player.playerScreenHandler
                || !player.playerScreenHandler.getCursorStack().isEmpty() || delay > 0) {
            return false;
        }
        if (pendingHotbarSlot != -1) {
            ItemStack actual = player.getInventory().getStack(pendingHotbarSlot);
            boolean settled = pendingStack.isEmpty() ? actual.isEmpty()
                    : ItemStack.areItemsAndComponentsEqual(actual, pendingStack);
            pendingHotbarSlot = -1;
            pendingStack = ItemStack.EMPTY;
            if (!settled) return false;
        }

        int hotbarSlot = getHotbarSlotWithItem(player, itemStack);
        // Empty-hand interactions may use an existing empty slot. Never erase
        // a stack to manufacture an empty hand, including in creative mode.
        if (hotbarSlot == -1 && !itemStack.isEmpty() && player.getAbilities().creativeMode) {
            hotbarSlot = nextHotbarSlot();
            if (hotbarSlot == -1) return false;
            ItemStack picked = itemStack.copy();
            player.getInventory().setStack(hotbarSlot, picked);
            mc.interactionManager.clickCreativeStack(picked, PlayerScreenHandler.HOTBAR_START + hotbarSlot);
        }
        if (hotbarSlot == -1) {
            swapToHotbar(player, itemStack);
            return false;
        }
        selectHotbarSlot(player, hotbarSlot);
        updateLastUsedSlot(hotbarSlot);
        return true;
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

    /**
     * Returns the next available slot for a new item. Returns the slot to the end of the queue after returning.
     * Range from 0-8
     *
     * @return The next available slot
     */
    private int nextHotbarSlot() {
        final String mode = PrinterConfig.PRINTER_INVENTORY_MANAGEMENT_MODE.getStringValue();

        if (PrinterConfig.InventoryManagementModeEnum.LEAST_USED.is(mode)) {
            if (!lastUsedSlots.isEmpty()) {
                List<Integer> usableSlots = lastUsedSlots.stream().filter(USABLE_SLOTS::contains).toList();
                if (usableSlots.isEmpty()) {
                    return -1;
                }
                int last = usableSlots.getLast();
                lastUsedSlots.remove(last);
                lastUsedSlots.addFirst(last);
                if (PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) {
                    System.out.println("Next new least used slot: " + last + "Last used slots: " + lastUsedSlots);
                }
                return last;
            }
        } else if (PrinterConfig.InventoryManagementModeEnum.ROLLING.is(mode)) {
            if (!rollingSlots.isEmpty()) {
                List<Integer> usableSlots = rollingSlots.stream().filter(USABLE_SLOTS::contains).toList();
                if (usableSlots.isEmpty()) {
                    return -1;
                }
                int slot = usableSlots.getFirst();
                rollingSlots.remove(slot);
                rollingSlots.addLast(slot);
                if (PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) {
                    System.out.println("Next new rolling slot: " + slot + "Rolling slots: " + rollingSlots);
                }
                return slot;
            }
        }
        if (PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) {
            System.out.println("No slots available");
        }
        return -1;
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
        PlayerInventory inventory = player.getInventory();

        for (int i = 0; i < 9; ++i) {
            if (itemStack.isEmpty() && inventory.main.get(i).isEmpty()) return i;
            if (!inventory.main.get(i).isEmpty() && ItemStack.areItemsAndComponentsEqual(inventory.main.get(i), itemStack)) {
                return i;
            }
        }

        return -1;
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
