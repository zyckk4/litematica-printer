package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.actions.Action;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_11.guides.Guide;
import me.aleksilassila.litematica.printer.v1_21_11.guides.interaction.CycleStateGuide;
import net.minecraft.block.*;
import net.minecraft.block.enums.ComparatorMode;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.List;

/** Real guide/selection logic with a headless inventory and captured click sink. */
public final class InteractionRegressionChecks {
    private static int assertions;

    public static int run() throws Exception {
        assertions = 0;
        checkCorrectableStates();
        MinecraftClient previous = MinecraftClient.getInstance();
        String slots = PrinterConfig.PRINTER_HOTBAR_SLOTS.getStringValue();
        try {
            checkEmptyHandSelection();
        } finally {
            set(MinecraftClient.class, null, "instance", previous);
            PrinterConfig.PRINTER_HOTBAR_SLOTS.setValueFromString(slots);
            InventoryManager.setHotbarSlots(PrinterConfig.PRINTER_HOTBAR_SLOTS);
        }
        return assertions;
    }

    private static void checkCorrectableStates() {
        for (Block block : new Block[]{Blocks.OAK_DOOR, Blocks.OAK_TRAPDOOR, Blocks.OAK_FENCE_GATE}) {
            BlockState closed = block.getDefaultState();
            BlockState open = closed.with(net.minecraft.state.property.Properties.OPEN, true);
            check(CycleStateGuide.needsInteraction(closed, open), "Open state not correctable: " + block);
            check(CycleStateGuide.needsInteraction(open, closed), "Closed state not correctable: " + block);
            check(!CycleStateGuide.needsInteraction(closed, closed), "Correct block would toggle: " + block);
        }
        for (Block block : new Block[]{Blocks.IRON_DOOR, Blocks.IRON_TRAPDOOR}) {
            BlockState closed = block.getDefaultState();
            check(!CycleStateGuide.needsInteraction(closed, closed.with(net.minecraft.state.property.Properties.OPEN, true)),
                    "Iron block would be clicked indefinitely");
        }
        BlockState lever = Blocks.LEVER.getDefaultState();
        check(CycleStateGuide.needsInteraction(lever, lever.with(LeverBlock.POWERED, true)), "Lever power ignored");
        check(!CycleStateGuide.needsInteraction(lever, lever.with(LeverBlock.FACING, Direction.EAST)), "Lever orientation would trigger clicks");
        BlockState trapdoor = Blocks.OAK_TRAPDOOR.getDefaultState();
        check(!CycleStateGuide.needsInteraction(trapdoor, trapdoor.with(TrapdoorBlock.FACING, Direction.EAST)), "Trapdoor facing would trigger clicks");
        check(!CycleStateGuide.needsInteraction(trapdoor, trapdoor.with(TrapdoorBlock.POWERED, true)), "External power would trigger clicks");
        BlockState repeater = Blocks.REPEATER.getDefaultState();
        for (int delay = 1; delay <= 4; delay++) {
            check(CycleStateGuide.needsInteraction(repeater, repeater.with(RepeaterBlock.DELAY, delay)) == (delay != 1), "Repeater delay comparison");
        }
        BlockState comparator = Blocks.COMPARATOR.getDefaultState();
        check(CycleStateGuide.needsInteraction(comparator, comparator.with(ComparatorBlock.MODE, ComparatorMode.SUBTRACT)), "Comparator mode ignored");
        BlockState note = Blocks.NOTE_BLOCK.getDefaultState();
        for (int pitch = 0; pitch <= 24; pitch++) {
            check(CycleStateGuide.needsInteraction(note, note.with(NoteBlock.NOTE, pitch)) == (pitch != 0), "Note comparison");
        }
        check(!CycleStateGuide.needsInteraction(lever, trapdoor), "Different block types would be interacted with");
        for (Direction gateFacing : Direction.Type.HORIZONTAL) {
            BlockState closed = Blocks.OAK_FENCE_GATE.getDefaultState().with(FenceGateBlock.FACING, gateFacing);
            for (Direction camera : Direction.Type.HORIZONTAL) {
                for (Direction server : Direction.Type.HORIZONTAL) {
                    float cameraYaw = camera.getPositiveHorizontalDegrees();
                    float serverYaw = server.getPositiveHorizontalDegrees();
                    boolean safe = camera != gateFacing.getOpposite() && server != gateFacing.getOpposite();
                    check(CycleStateGuide.canInteractWithoutChangingFacing(closed, cameraYaw, serverYaw) == safe,
                            "Opening gate may flip facing or needlessly rejected safe angle");
                    check(CycleStateGuide.canInteractWithoutChangingFacing(closed.with(FenceGateBlock.OPEN, true), cameraYaw, serverYaw),
                            "Closing gate needlessly constrained yaw");
                }
            }
        }
    }

    private static void checkEmptyHandSelection() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        MinecraftClient client = (MinecraftClient) unsafe.allocateInstance(MinecraftClient.class);
        FixturePlayer player = (FixturePlayer) unsafe.allocateInstance(FixturePlayer.class);
        player.inventory = (PlayerInventory) unsafe.allocateInstance(PlayerInventory.class);
        set(PlayerInventory.class, player.inventory, "main", DefaultedList.ofSize(36, ItemStack.EMPTY));
        player.abilities = new PlayerAbilities();
        player.offhand = new ItemStack(Items.TORCH, 12);
        PlayerScreenHandler screen = (PlayerScreenHandler) unsafe.allocateInstance(PlayerScreenHandler.class);
        screen.setCursorStack(ItemStack.EMPTY);
        set(PlayerEntity.class, player, "playerScreenHandler", screen);
        player.currentScreenHandler = screen;
        FixtureNetwork network = (FixtureNetwork) unsafe.allocateInstance(FixtureNetwork.class);
        set(ClientPlayerEntity.class, player, "networkHandler", network);
        FixtureInteraction interaction = (FixtureInteraction) unsafe.allocateInstance(FixtureInteraction.class);
        set(ClientPlayerInteractionManager.class, interaction, "client", client);
        set(ClientPlayerInteractionManager.class, interaction, "networkHandler", network);
        client.player = player;
        client.interactionManager = interaction;
        set(MinecraftClient.class, null, "instance", client);
        Constructor<InventoryManager> constructor = InventoryManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        InventoryManager manager = constructor.newInstance();
        SchematicBlockState state = (SchematicBlockState) unsafe.allocateInstance(SchematicBlockState.class);
        set(SchematicBlockState.class, state, "currentState", Blocks.LEVER.getDefaultState());
        set(SchematicBlockState.class, state, "targetState", Blocks.LEVER.getDefaultState().with(LeverBlock.POWERED, true));
        set(SchematicBlockState.class, state, "blockPos", new BlockPos(0, 64, 1));
        ProbeGuide empty = new ProbeGuide(state, List.of(ItemStack.EMPTY));
        ProbeGuide unavailable = new ProbeGuide(state, List.of());

        fill(player, new ItemStack(Items.STONE));
        player.inventory.main.set(5, ItemStack.EMPTY);
        check(empty.slot(player) == 5, "Explicit EMPTY rejected in survival");
        check(unavailable.slot(player) == -1, "Unavailable requirement mistaken for empty hand");
        check(manager.select(ItemStack.EMPTY), "Existing empty hotbar slot not selected");
        check(player.inventory.selectedSlot == 5, "Wrong empty hotbar slot selected");
        check(interaction.swaps == 0 && interaction.creativeWrites == 0, "Empty hotbar selection changed inventory");
        check(new CycleStateGuide(state).canExecute(player), "Survival lever guide still blocked");
        player.sneaking = true;
        check(!new CycleStateGuide(state).canExecute(player), "User sneak input was overridden");
        player.sneaking = false;

        fill(player, new ItemStack(Items.STONE));
        player.abilities.creativeMode = true;
        check(empty.slot(player) == -1, "Creative mode invented an empty slot");
        check(!manager.select(ItemStack.EMPTY), "Full inventory accepted empty hand");
        check(interaction.swaps == 0 && interaction.creativeWrites == 0, "Full inventory was cleared for empty hand");
        check(player.inventory.main.stream().allMatch(stack -> stack.isOf(Items.STONE)), "Existing stack was lost");
        player.abilities.creativeMode = false;

        // Move one existing empty inventory slot to an explicitly usable
        // hotbar slot; the former hotbar stack moves into that empty slot.
        PrinterConfig.PRINTER_HOTBAR_SLOTS.setValueFromString("3");
        InventoryManager.setHotbarSlots(PrinterConfig.PRINTER_HOTBAR_SLOTS);
        player.inventory.main.set(12, ItemStack.EMPTY);
        check(empty.slot(player) == 12, "Empty main inventory slot not available to guide");
        ItemStack moved = player.inventory.main.get(2);
        check(!manager.select(ItemStack.EMPTY), "Swap was used before settlement interval");
        check(interaction.swaps == 1 && interaction.lastType == SlotActionType.SWAP, "Expected one nondestructive SWAP");
        check(player.inventory.main.get(2).isEmpty() && player.inventory.main.get(12) == moved, "Swap lost displaced stack");
        check(!manager.select(ItemStack.EMPTY), "Pending empty slot was used too early");
        while (manager.delay > 0) manager.tick();
        check(manager.select(ItemStack.EMPTY), "Settled empty hand not selected");
        check(player.inventory.selectedSlot == 2, "Selected outside configured empty-hand staging slot");
        check(player.offhand.isOf(Items.TORCH) && player.offhand.getCount() == 12, "Offhand changed");
        check(interaction.creativeWrites == 0, "Empty hand used a creative item write");

        screen.setCursorStack(new ItemStack(Items.DIRT));
        check(!manager.select(ItemStack.EMPTY), "Cursor stack guard was bypassed");
        screen.setCursorStack(ItemStack.EMPTY);
        ScreenHandler other = (ScreenHandler) unsafe.allocateInstance(PlayerScreenHandler.class);
        player.currentScreenHandler = other;
        check(!manager.select(ItemStack.EMPTY), "Open container guard was bypassed");
    }

    private static void fill(FixturePlayer player, ItemStack stack) {
        for (int i = 0; i < 36; i++) player.inventory.main.set(i, stack.copy());
    }

    private static final class ProbeGuide extends Guide {
        private final List<ItemStack> required;
        private ProbeGuide(SchematicBlockState state, List<ItemStack> required) { super(state); this.required = required; }
        private int slot(ClientPlayerEntity player) { return getRequiredItemStackSlot(player); }
        @Override protected List<ItemStack> getRequiredItems() { return required; }
        @Override public List<Action> execute(ClientPlayerEntity player) { return List.of(); }
    }

    private static final class FixturePlayer extends ClientPlayerEntity {
        private PlayerInventory inventory;
        private PlayerAbilities abilities;
        private ItemStack offhand;
        private boolean sneaking;
        private FixturePlayer() { super(null, null, null, null, null, PlayerInput.DEFAULT, false); }
        @Override public PlayerInventory getInventory() { return inventory; }
        @Override public PlayerAbilities getAbilities() { return abilities; }
        @Override public ItemStack getOffHandStack() { return offhand; }
        @Override public boolean isSneaking() { return sneaking; }
    }

    private static final class FixtureNetwork extends ClientPlayNetworkHandler {
        private FixtureNetwork() { super(null, null, null); }
        @Override public void sendPacket(Packet<?> packet) {}
    }

    private static final class FixtureInteraction extends ClientPlayerInteractionManager {
        private int swaps;
        private int creativeWrites;
        private SlotActionType lastType;
        private FixtureInteraction() { super(null, null); }
        @Override public void clickSlot(int syncId, int slotId, int button, SlotActionType type, PlayerEntity player) {
            check(type == SlotActionType.SWAP && slotId >= 9 && slotId < 36, "Unexpected click action");
            swaps++;
            lastType = type;
            ItemStack displaced = player.getInventory().main.get(button);
            player.getInventory().main.set(button, player.getInventory().main.get(slotId));
            player.getInventory().main.set(slotId, displaced);
        }
        @Override public void clickCreativeStack(ItemStack stack, int slotId) { creativeWrites++; }
    }

    private static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
