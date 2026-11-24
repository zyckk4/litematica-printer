package me.aleksilassila.litematica.printer.v1_21_11;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.v1_21_11.actions.Action;
import me.aleksilassila.litematica.printer.v1_21_11.actions.ActionChain;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_11.guides.Guide;
import me.aleksilassila.litematica.printer.v1_21_11.guides.Guides;
import me.aleksilassila.litematica.printer.v1_21_11.guides.interaction.CampfireExtinguishGuide;
import me.aleksilassila.litematica.printer.v1_21_11.guides.interaction.CycleStateGuide;
import me.aleksilassila.litematica.printer.v1_21_11.guides.interaction.TillingGuide;
import me.aleksilassila.litematica.printer.v1_21_11.guides.interaction.WaterLogGuide;
import me.aleksilassila.litematica.printer.v1_21_11.guides.placement.SlabGuide;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.PrinterPlacementContext;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.actions.InteractActionImpl;
import me.aleksilassila.litematica.printer.v1_21_11.mixin.MixinAccessorClientPlayerEntity;
import net.minecraft.block.*;
import net.minecraft.block.enums.ComparatorMode;
import net.minecraft.block.enums.RailShape;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.client.network.PendingUpdateManager;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.fluid.FluidState;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.ClickSlotC2SPacket;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.screen.sync.ItemStackHash;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.state.property.Properties;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.attribute.WorldEnvironmentAttributeAccess;
import net.minecraft.world.border.WorldBorder;
import sun.misc.Unsafe;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Real guide, inventory and interaction actions with headless captured sinks. */
public final class InteractionRegressionChecks {
    private static int assertions;

    public static int run() throws Exception {
        assertions = 0;
        checkCorrectableStates();
        MinecraftClient previous = MinecraftClient.getInstance();
        String slots = PrinterConfig.PRINTER_HOTBAR_SLOTS.getStringValue();
        try {
            checkEmptyHandSelection(createFixture());
            checkRefillReadiness(createFixture());
            checkAuthoritativeRefillRecovery(createFixture());
            checkGroupFences(createFixture());
            checkRefillSessionChange(createFixture());
            checkRefillPlayerReplacement(createFixture());
            checkWaterlogging(createFixture());
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
        BlockState detector = Blocks.DAYLIGHT_DETECTOR.getDefaultState();
        for (boolean currentMode : new boolean[]{false, true}) {
            for (boolean targetMode : new boolean[]{false, true}) {
                for (int currentPower = 0; currentPower <= 15; currentPower++) {
                    for (int targetPower = 0; targetPower <= 15; targetPower++) {
                        BlockState current = detector.with(DaylightDetectorBlock.INVERTED, currentMode)
                                .with(DaylightDetectorBlock.POWER, currentPower);
                        BlockState target = detector.with(DaylightDetectorBlock.INVERTED, targetMode)
                                .with(DaylightDetectorBlock.POWER, targetPower);
                        check(CycleStateGuide.needsInteraction(current, target) == (currentMode != targetMode),
                                "Daylight detector tuning depends on ambient power or misses its mode");
                    }
                }
            }
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

    private record Fixture(Unsafe unsafe, MinecraftClient client, FixturePlayer player,
                           FixtureNetwork network, FixtureInteraction interaction) {}

    private static Fixture createFixture() throws Exception {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        MinecraftClient client = (MinecraftClient) unsafe.allocateInstance(MinecraftClient.class);
        set(MinecraftClient.class, client, "runDirectory", new java.io.File("."));
        FixturePlayer player = (FixturePlayer) unsafe.allocateInstance(FixturePlayer.class);
        player.inventory = (PlayerInventory) unsafe.allocateInstance(PlayerInventory.class);
        set(PlayerInventory.class, player.inventory, "main", DefaultedList.ofSize(36, ItemStack.EMPTY));
        player.abilities = new PlayerAbilities();
        set(PlayerEntity.class, player, "abilities", player.abilities);
        player.offhand = new ItemStack(Items.TORCH, 12);
        PlayerScreenHandler screen = (PlayerScreenHandler) unsafe.allocateInstance(PlayerScreenHandler.class);
        set(ScreenHandler.class, screen, "slots", DefaultedList.ofSize(PlayerScreenHandler.OFFHAND_ID + 1,
                (Slot) unsafe.allocateInstance(Slot.class)));
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
        return new Fixture(unsafe, client, player, network, interaction);
    }

    private static void checkEmptyHandSelection(Fixture fixture) throws Exception {
        Unsafe unsafe = fixture.unsafe;
        FixturePlayer player = fixture.player;
        FixtureInteraction interaction = fixture.interaction;
        PlayerScreenHandler screen = player.playerScreenHandler;
        InventoryManager manager = newInventoryManager();
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
        check(fixture.network.inventorySwaps == 0 && interaction.creativeWrites == 0,
                "Empty hotbar selection changed inventory");
        check(new CycleStateGuide(state).canExecute(player), "Survival lever guide still blocked");
        player.sneaking = true;
        check(!new CycleStateGuide(state).canExecute(player), "User sneak input was overridden");
        player.sneaking = false;

        fill(player, new ItemStack(Items.STONE));
        player.abilities.creativeMode = true;
        check(empty.slot(player) == -1, "Creative mode invented an empty slot");
        check(!manager.select(ItemStack.EMPTY), "Full inventory accepted empty hand");
        check(fixture.network.inventorySwaps == 0 && interaction.creativeWrites == 0,
                "Full inventory was cleared for empty hand");
        check(player.inventory.main.stream().allMatch(stack -> stack.isOf(Items.STONE)), "Existing stack was lost");
        player.abilities.creativeMode = false;

        // Move one existing empty inventory slot to an explicitly usable
        // hotbar slot; the former hotbar stack moves into that empty slot.
        PrinterConfig.PRINTER_HOTBAR_SLOTS.setValueFromString("3");
        InventoryManager.setHotbarSlots(PrinterConfig.PRINTER_HOTBAR_SLOTS);
        player.inventory.main.set(12, ItemStack.EMPTY);
        check(empty.slot(player) == 12, "Empty main inventory slot not available to guide");
        ItemStack moved = player.inventory.main.get(2);
        set(ScreenHandler.class, screen, "revision", 32766);
        check(!manager.select(ItemStack.EMPTY), "Selected an empty hand before authoritative slot updates");
        check(fixture.network.inventorySwaps == 1, "Expected one nondestructive SWAP");
        ClickSlotC2SPacket request = fixture.network.latestSwap;
        check(request.syncId() == screen.syncId && request.revision() == 32766 && request.slot() == 12 && request.button() == 2,
                "Refill did not address the expected handler, revision, or slots");
        check(player.inventory.main.get(2) == moved && player.inventory.main.get(12).isEmpty(),
                "Unconfirmed refill mutated the local inventory");
        player.inventory.main.set(2, ItemStack.EMPTY);
        manager.onAuthoritativeSlot(screen.syncId, request.revision(), PlayerScreenHandler.HOTBAR_START + 2);
        publishSlot(fixture, manager, 12, moved.copy());
        check(!manager.select(ItemStack.EMPTY), "A stale hotbar revision completed a backpack-only update");
        publishSlot(fixture, manager, 2, ItemStack.EMPTY);
        check(!manager.select(ItemStack.EMPTY), "Released an empty hotbar from a pre-click snapshot");
        manager.onInventoryReadbackBarrier(fixture.network);
        check(manager.select(ItemStack.EMPTY), "Authoritative empty hand was not selected");
        check(player.inventory.selectedSlot == 2, "Selected outside configured empty-hand staging slot");
        check(player.offhand.isOf(Items.TORCH) && player.offhand.getCount() == 12, "Offhand changed");
        check(interaction.creativeWrites == 0, "Empty hand used a creative item write");

        screen.setCursorStack(new ItemStack(Items.DIRT));
        check(!manager.select(ItemStack.EMPTY), "Cursor stack guard was bypassed");
        screen.setCursorStack(ItemStack.EMPTY);
        ScreenHandler other = (ScreenHandler) unsafe.allocateInstance(PlayerScreenHandler.class);
        player.currentScreenHandler = other;
        check(!manager.select(ItemStack.EMPTY), "Open container guard was bypassed");
        player.currentScreenHandler = screen;
    }

    private static void checkRefillReadiness(Fixture fixture) throws Exception {
        FixturePlayer player = fixture.player;
        InventoryManager manager = newInventoryManager();
        int previousDelay = PrinterConfig.INVENTORY_DELAY.getIntegerValue();
        try {
            PrinterConfig.INVENTORY_DELAY.setIntegerValue(10);
            PrinterConfig.PRINTER_HOTBAR_SLOTS.setValueFromString("1,3,5");
            InventoryManager.setHotbarSlots(PrinterConfig.PRINTER_HOTBAR_SLOTS);
            ItemStack stone = new ItemStack(Items.STONE);
            ItemStack dirt = new ItemStack(Items.DIRT);
            ItemStack sand = new ItemStack(Items.SAND, 16);
            ItemStack gravel = new ItemStack(Items.GRAVEL, 16);
            fill(player, new ItemStack(Items.COBBLESTONE));
            player.inventory.main.set(2, stone);
            player.inventory.selectedSlot = 0;
            player.inventory.main.set(4, dirt);
            player.inventory.main.set(12, sand);
            player.inventory.main.set(13, gravel);
            int before = fixture.network.inventorySwaps;

            check(!manager.prefetch(sand, List.of(stone, dirt)) && fixture.network.inventorySwaps == before,
                    "Prefetch evicted a protected or selected stack");
            check(manager.prefetch(sand, List.of(dirt)) && fixture.network.inventorySwaps == before + 1,
                    "Prefetch did not stage the material in the available slot");
            check(fixture.network.latestSwap.button() == 2 && player.inventory.selectedSlot == 0
                            && player.inventory.main.get(2).isOf(Items.STONE) && player.inventory.main.get(4).isOf(Items.DIRT),
                    "Unconfirmed prefetch changed the selected slot or the inventory");
            check(manager.selectReadyHotbarMaterial(dirt), "Pending refill blocked a disjoint ready hotbar material");
            check(!manager.selectReadyHotbarMaterial(sand), "Unconfirmed refill was available before a server response");
            check(!manager.select(gravel) && fixture.network.inventorySwaps == before + 1,
                    "A second refill started while the first was in flight");

            // Reverse the notification order from the empty-hand test and send
            // duplicate target updates before the backpack update is delivered.
            publishSlot(fixture, manager, 2, sand.copy());
            publishSlot(fixture, manager, 2, sand.copy());
            check(!manager.hasReadyNativeMaterial(sand) && !manager.selectReadyHotbarMaterial(sand),
                    "Repeated hotbar updates were mistaken for both endpoints");
            check(manager.selectReadyHotbarMaterial(dirt), "Partial correction blocked a disjoint hotbar slot");
            publishSlot(fixture, manager, 12, stone.copy());
            manager.onInventoryReadbackBarrier(fixture.network);
            check(manager.selectReadyHotbarMaterial(sand), "Exact authoritative pair did not settle immediately");

            PrinterConfig.INVENTORY_DELAY.setIntegerValue(0);
            PrinterConfig.PRINTER_HOTBAR_SLOTS.setValueFromString("3");
            InventoryManager.setHotbarSlots(PrinterConfig.PRINTER_HOTBAR_SLOTS);
            check(!manager.canPrefetch() && manager.canStartRefill(), "Selected refill slot was treated as unavailable");
            check(!manager.prefetch(gravel, List.of()) && fixture.network.inventorySwaps == before + 1,
                    "Prefetch evicted the only refill slot while it was selected");
            check(!manager.select(gravel) && fixture.network.inventorySwaps == before + 2,
                    "Demand refill did not use the selected fallback");
            check(!manager.selectReadyHotbarMaterial(gravel), "Zero-delay refill skipped the publication requirement");
            player.inventory.main.set(13, sand.copy());
            player.inventory.main.set(2, gravel.copy());
            publishInventory(fixture, manager);
            manager.onInventoryReadbackBarrier(fixture.network);
            check(manager.selectReadyHotbarMaterial(gravel), "Full authoritative snapshot did not settle selected fallback");
        } finally {
            PrinterConfig.INVENTORY_DELAY.setIntegerValue(previousDelay);
        }
    }

    private static InventoryManager newInventoryManager() throws Exception {
        Constructor<InventoryManager> constructor = InventoryManager.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }

    private static void checkAuthoritativeRefillRecovery(Fixture fixture) throws Exception {
        FixturePlayer player = fixture.player;
        PlayerScreenHandler handler = player.playerScreenHandler;
        InventoryManager manager = newInventoryManager();
        int previousDelay = PrinterConfig.INVENTORY_DELAY.getIntegerValue();
        try {
            PrinterConfig.INVENTORY_DELAY.setIntegerValue(10);
            PrinterConfig.PRINTER_HOTBAR_SLOTS.setValueFromString("3");
            InventoryManager.setHotbarSlots(PrinterConfig.PRINTER_HOTBAR_SLOTS);
            ItemStack stone = new ItemStack(Items.STONE, 8);
            ItemStack dirt = new ItemStack(Items.DIRT);
            ItemStack sand = new ItemStack(Items.SAND, 16);
            ItemStack gravel = new ItemStack(Items.GRAVEL, 16);
            fill(player, new ItemStack(Items.COBBLESTONE));
            player.inventory.selectedSlot = 2;
            player.inventory.main.set(2, stone.copy());
            player.inventory.main.set(4, dirt.copy());
            player.inventory.main.set(12, sand.copy());
            player.inventory.main.set(13, gravel.copy());
            int before = fixture.network.inventorySwaps;
            int probes = fixture.network.inventoryReadbacks;
            int fences = fixture.network.statisticsRequests;

            check(!manager.select(sand) && fixture.network.inventorySwaps == before + 1,
                    "Did not send the refill used for the timeout check");
            advanceInventoryTicks(fixture, manager, 300);
            check(!manager.select(gravel) && fixture.network.inventorySwaps == before + 1,
                    "An unresolved refill allowed a second mutation");
            check(fixture.network.inventoryReadbacks == probes && fixture.network.statisticsRequests == fences + 1,
                    "Timeout sent a readback before an ordered server fence");
            check(!manager.selectReadyHotbarMaterial(stone), "Unknown refill outcome released its old destination stack");
            check(manager.selectReadyHotbarMaterial(dirt), "Unknown refill outcome blocked an unrelated slot");

            // An authoritative publication can still settle the first mutation
            // after its deadline. It must never cause a replay of that SWAP.
            player.inventory.main.set(12, stone.copy());
            player.inventory.main.set(2, sand.copy());
            publishInventory(fixture, manager);
            manager.onInventoryReadbackBarrier(fixture.network);
            check(manager.selectReadyHotbarMaterial(sand) && fixture.network.inventorySwaps == before + 1
                            && fixture.network.inventoryReadbacks == probes,
                    "A late matching snapshot did not release the quarantined destination");

            check(!manager.select(gravel) && fixture.network.inventorySwaps == before + 2,
                    "Could not refill after the late authoritative snapshot");
            // The intent matches at its destination, but the displaced stack
            // was externally changed. Both endpoints must match for fast release.
            player.inventory.main.set(13, sand.copyWithCount(15));
            player.inventory.main.set(2, gravel.copy());
            publishInventory(fixture, manager);
            check(!manager.selectReadyHotbarMaterial(gravel), "A mismatched displaced stack was reported as a confirmed refill");
            check(manager.selectReadyHotbarMaterial(dirt), "A mismatched snapshot blocked an unrelated slot");

            ScreenHandler other = (ScreenHandler) fixture.unsafe.allocateInstance(PlayerScreenHandler.class);
            set(ScreenHandler.class, other, "syncId", 9);
            player.currentScreenHandler = other;
            manager.onAuthoritativeInventory(other.syncId, handler.getRevision() + 1, handler.slots.size());
            advanceInventoryTicks(fixture, manager, 30);
            check(!manager.select(dirt) && !manager.canStartRefill() && fixture.network.statisticsRequests == fences + 2,
                    "An open container allowed selection, refill, or a player-inventory readback");
            player.currentScreenHandler = handler;
            check(!manager.canStartRefill(), "A foreign container update completed the active refill");
            set(ScreenHandler.class, handler, "revision", (handler.getRevision() + 0x4000) & 0x7fff);
            advanceInventoryTicks(fixture, manager, 30);
            check(fixture.network.inventorySwaps == before + 2 && fixture.network.inventoryReadbacks == probes
                            && fixture.network.statisticsRequests == fences + 2,
                    "Closing the container replayed the mutation or failed to order its readback");
            int revisionBeforeProbe = handler.getRevision();
            manager.onInventoryReadbackBarrier(fixture.network);
            check(fixture.network.inventoryReadbacks == probes + 1 && fixture.network.statisticsRequests == fences + 3,
                    "The first fence did not issue one readback followed by its own fence");
            ClickSlotC2SPacket probe = fixture.network.latestReadback;
            check(probe.syncId() == handler.syncId && probe.slot() == PlayerScreenHandler.HOTBAR_START + probe.button()
                            && probe.revision() == (revisionBeforeProbe ^ 0x4000)
                            && fixture.network.statisticsRequestsAtLatestReadback == fences + 2,
                    "Readback would touch distinct slots, use the synchronized revision, or precede its first fence");
            check(!manager.selectReadyHotbarMaterial(gravel), "The first fence released the slot without a new readback");
            manager.onAuthoritativeInventory(handler.syncId, advanceRevision(handler), handler.slots.size() - 1);
            check(!manager.selectReadyHotbarMaterial(gravel), "A short inventory update released the slot");
            publishInventory(fixture, manager);
            check(!manager.selectReadyHotbarMaterial(gravel), "A mismatched readback released the slot before its fence");
            manager.onInventoryReadbackBarrier(fixture.network);
            check(manager.selectReadyHotbarMaterial(gravel), "A fenced readback did not expose authoritative inventory");
            check(!manager.canStartRefill(), "Readback recovery did not back off before a new mutation");
            advanceInventoryTicks(fixture, manager, 30);

            // The server silently refuses this click. A full readback shows
            // the unchanged endpoints before the fence unlocks fresh planning.
            check(!manager.select(sand) && fixture.network.inventorySwaps == before + 3,
                    "Could not start a fresh refill after backoff");
            advanceInventoryTicks(fixture, manager, 30);
            manager.onInventoryReadbackBarrier(fixture.network);
            publishInventory(fixture, manager);
            check(!manager.selectReadyHotbarMaterial(gravel), "An unchanged readback released a silent refusal too early");
            manager.onInventoryReadbackBarrier(fixture.network);
            check(manager.selectReadyHotbarMaterial(gravel) && fixture.network.inventorySwaps == before + 3,
                    "Recovering a silent refusal replayed the original click");
            advanceInventoryTicks(fixture, manager, 30);

            check(!manager.select(sand) && fixture.network.inventorySwaps == before + 4,
                    "Did not reach a second silent request for the stale-snapshot check");
            advanceInventoryTicks(fixture, manager, 30);
            // This is a full snapshot, but it precedes the readback request.
            publishInventory(fixture, manager);
            manager.onInventoryReadbackBarrier(fixture.network);
            manager.onInventoryReadbackBarrier(fixture.network);
            check(!manager.selectReadyHotbarMaterial(gravel) && !manager.canStartRefill()
                            && fixture.network.inventorySwaps == before + 4,
                    "A snapshot preceding the readback released a still-ambiguous mutation");
        } finally {
            player.currentScreenHandler = handler;
            PrinterConfig.INVENTORY_DELAY.setIntegerValue(previousDelay);
        }
    }

    private static void checkGroupFences(Fixture fixture) throws Exception {
        InventoryManager manager = newInventoryManager();
        FixturePlayer player = fixture.player;
        PrinterConfig.PRINTER_HOTBAR_SLOTS.setValueFromString("2,3,4");
        InventoryManager.setHotbarSlots(PrinterConfig.PRINTER_HOTBAR_SLOTS);
        ItemStack dirt = new ItemStack(Items.DIRT, 8);
        ItemStack bricks = new ItemStack(Items.BRICKS, 8);
        ItemStack sand = new ItemStack(Items.SAND, 8);
        ItemStack gravel = new ItemStack(Items.GRAVEL, 4);
        fill(player, dirt);
        player.inventory.main.set(9, bricks.copy());
        player.inventory.main.set(10, sand.copy());
        player.inventory.main.set(11, gravel.copy());
        var first = new InventoryManager.Refill(9, 1);
        var second = new InventoryManager.Refill(10, 2);
        var third = new InventoryManager.Refill(11, 3);
        check(!manager.startSwapGroup(player, List.of(first, new InventoryManager.Refill(9, 2)))
                        && !manager.startSwapGroup(player, List.of(first, new InventoryManager.Refill(10, 1)))
                        && fixture.network.inventorySwaps == 0, "An overlapping group partially moved inventory");
        // A plain statistics screen opened before the printer request. Its reply must not
        // unlock a group even when an earlier authoritative snapshot has the desired layout.
        fixture.network.sendPacket(new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS));
        check(manager.startSwapGroup(player, List.of(first, second, third))
                        && fixture.network.inventorySwaps == 3 && fixture.network.statisticsRequests == 2,
                "A disjoint batch did not send all three clicks followed by one owned fence");
        for (int source : new int[]{9, 10, 11}) player.inventory.main.set(source, dirt.copy());
        player.inventory.main.set(1, bricks.copy());
        player.inventory.main.set(2, sand.copy());
        player.inventory.main.set(3, gravel.copy());
        publishInventory(fixture, manager);
        manager.onInventoryReadbackBarrier(fixture.network);
        check(!manager.selectReadyHotbarMaterial(bricks) && !manager.selectReadyHotbarMaterial(sand)
                        && !manager.selectReadyHotbarMaterial(gravel) && manager.selectReadyHotbarMaterial(dirt),
                "A matching snapshot and someone else's stats reply released part of the group");
        // Vanilla executes the real click after the earlier snapshot, restoring the old
        // values of this pair. The other two pairs must stay reserved until the full refresh.
        publishSlot(fixture, manager, 1, dirt.copy());
        publishSlot(fixture, manager, 9, bricks.copy());
        manager.onInventoryReadbackBarrier(fixture.network);
        check(fixture.network.inventoryReadbacks == 1 && fixture.network.statisticsRequests == 3
                        && !manager.selectReadyHotbarMaterial(sand), "An incomplete group skipped the fenced readback");
        publishInventory(fixture, manager);
        player.inventory.selectedSlot = 3;
        check(player.inventory.dropSelectedItem(false).getCount() == 1, "Local drop did not change the reserved stack");
        manager.onInventoryReadbackBarrier(fixture.network);
        advanceInventoryTicks(fixture, manager, 220);
        check(fixture.network.inventoryReadbacks == 2 && fixture.network.statisticsRequests == 4
                        && fixture.network.inventorySwaps == 3 && !manager.canStartRefill(),
                "A changed readback never retried safely or retransmitted the material group");
        publishInventory(fixture, manager);
        advanceInventoryTicks(fixture, manager, 1200);
        check(!manager.selectReadyHotbarMaterial(gravel) && !manager.canStartRefill()
                        && fixture.network.inventoryReadbacks == 2 && fixture.network.inventorySwaps == 3,
                "An unacknowledged retry released or retransmitted a pending group");
        manager.onInventoryReadbackBarrier(fixture.network);
        advanceInventoryTicks(fixture, manager, 220);
        check(manager.selectReadyHotbarMaterial(gravel) && manager.canStartRefill()
                        && !manager.hasReadyNativeMaterial(bricks), "A fresh valid snapshot did not recover the actual inventory");

        // No inventory mutation is needed to recount an exhausted stack. Drain all earlier
        // uses first, then accept only a fresh full readback followed by its own ordered reply.
        int priorReads = fixture.network.inventoryReadbacks;
        check(manager.requestStockReadback(3) && manager.isReservedHotbar(player, 3)
                        && !manager.requestStockReadback(2), "Stock recount did not reserve exactly one transaction");
        publishInventory(fixture, manager);
        manager.onInventoryReadbackBarrier(fixture.network);
        check(fixture.network.inventoryReadbacks == priorReads + 1 && manager.isReservedHotbar(player, 3),
                "A publication from before the stock probe released the slot");
        manager.onInventoryReadbackBarrier(fixture.network);
        advanceInventoryTicks(fixture, manager, 220);
        check(fixture.network.inventoryReadbacks == priorReads + 2 && manager.isReservedHotbar(player, 3),
                "The stock readback accepted a stale snapshot or failed to retry");
        publishInventory(fixture, manager);
        check(!manager.selectReadyHotbarMaterial(gravel) && manager.selectReadyHotbarMaterial(sand),
                "A stock readback exposed its slot early or blocked disjoint work");
        manager.onInventoryReadbackBarrier(fixture.network);
        check(manager.selectReadyHotbarMaterial(gravel) && fixture.network.inventorySwaps == 3,
                "The stock readback did not settle without mutating inventory");
        PrinterConfig.PRINTER_HOTBAR_SLOTS.setValueFromString("3");
        InventoryManager.setHotbarSlots(PrinterConfig.PRINTER_HOTBAR_SLOTS);
    }

    private static void advanceInventoryTicks(Fixture fixture, InventoryManager manager, int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            fixture.player.age++;
            manager.tick();
        }
    }

    private static void checkRefillSessionChange(Fixture fixture) throws Exception {
        FixturePlayer oldPlayer = fixture.player;
        InventoryManager manager = newInventoryManager();
        fill(oldPlayer, new ItemStack(Items.STONE));
        oldPlayer.inventory.selectedSlot = 2;
        oldPlayer.inventory.main.set(12, new ItemStack(Items.SAND));
        int before = fixture.network.inventorySwaps;
        check(!manager.select(new ItemStack(Items.SAND)) && fixture.network.inventorySwaps == before + 1,
                "Did not reach an active refill in the old session");

        Fixture replacement = createFixture();
        set(MinecraftClient.class, null, "instance", fixture.client);
        fixture.client.player = replacement.player;
        fixture.client.interactionManager = replacement.interaction;
        try {
            replacement.player.inventory.main.set(2, new ItemStack(Items.DIRT));
            replacement.player.inventory.main.set(12, new ItemStack(Items.GRAVEL));
            manager.tick();
            check(manager.selectReadyHotbarMaterial(new ItemStack(Items.DIRT)),
                    "Prior session's refill quarantined the replacement player's hotbar");
            check(!manager.select(new ItemStack(Items.GRAVEL)) && replacement.network.inventorySwaps == 1
                            && fixture.network.inventorySwaps == before + 1,
                    "A fresh refill used or was blocked by the old session's active request");
            check(manager.tracksConnection(replacement.network) && !manager.tracksConnection(fixture.network),
                    "The old session's packets remained eligible to confirm the new refill");
        } finally {
            fixture.client.player = oldPlayer;
            fixture.client.interactionManager = fixture.interaction;
        }
    }

    private static void checkRefillPlayerReplacement(Fixture fixture) throws Exception {
        FixturePlayer oldPlayer = fixture.player;
        FixtureNetwork connection = fixture.network;
        InventoryManager manager = newInventoryManager();
        ItemStack stone = new ItemStack(Items.STONE, 8);
        ItemStack sand = new ItemStack(Items.SAND, 16);
        ItemStack dirt = new ItemStack(Items.DIRT);
        ItemStack gravel = new ItemStack(Items.GRAVEL);
        fill(oldPlayer, new ItemStack(Items.COBBLESTONE));
        oldPlayer.inventory.selectedSlot = 2;
        oldPlayer.inventory.main.set(2, stone.copy());
        oldPlayer.inventory.main.set(12, sand.copy());
        int swaps = connection.inventorySwaps;
        int probes = connection.inventoryReadbacks;
        int fences = connection.statisticsRequests;
        check(!manager.select(sand) && connection.inventorySwaps == swaps + 1,
                "Did not send the refill before replacing the player on its connection");
        publishSlot(fixture, manager, 12, stone.copy());

        Fixture replacement = createFixture();
        set(MinecraftClient.class, null, "instance", fixture.client);
        set(ClientPlayerEntity.class, replacement.player, "networkHandler", connection);
        set(ClientPlayerInteractionManager.class, replacement.interaction, "networkHandler", connection);
        fixture.client.player = replacement.player;
        fixture.client.interactionManager = replacement.interaction;
        int previousDelay = PrinterConfig.INVENTORY_DELAY.getIntegerValue();
        try {
            PrinterConfig.INVENTORY_DELAY.setIntegerValue(10);
            replacement.player.inventory.selectedSlot = 2;
            replacement.player.inventory.main.set(2, stone.copy());
            replacement.player.inventory.main.set(4, dirt.copy());
            replacement.player.inventory.main.set(12, stone.copy());
            replacement.player.inventory.main.set(13, gravel.copy());
            manager.onPrinterContextChanged();
            check(manager.tracksConnection(connection) && !manager.selectReadyHotbarMaterial(stone),
                    "Player replacement released the destination despite retaining the potentially active connection");
            check(!manager.select(gravel) && connection.inventorySwaps == swaps + 1,
                    "Player replacement allowed a second inventory mutation");

            publishSlot(replacement, manager, 2, sand.copy());
            check(!manager.selectReadyHotbarMaterial(sand), "Old source publication confirmed the replacement player's hotbar");
            publishSlot(replacement, manager, 12, stone.copy());
            check(!manager.selectReadyHotbarMaterial(sand) && manager.selectReadyHotbarMaterial(dirt),
                    "Player replacement fast-confirmed affected material or blocked an unrelated slot");

            advanceInventoryTicks(replacement, manager, 30);
            check(connection.statisticsRequests == fences + 1 && connection.inventoryReadbacks == probes,
                    "Recovery did not order a new-player inventory refresh");
            PlayerScreenHandler handler = replacement.player.playerScreenHandler;
            int revision = handler.getRevision();
            manager.onInventoryReadbackBarrier(connection);
            check(connection.inventoryReadbacks == probes + 1 && connection.statisticsRequests == fences + 2
                            && connection.latestReadback.revision() == (revision ^ 0x4000),
                    "Readback did not use the replacement player's handler revision");
            publishInventory(replacement, manager);
            check(!manager.selectReadyHotbarMaterial(sand), "Rebound refill was released before the final fence");
            manager.onInventoryReadbackBarrier(connection);
            check(manager.selectReadyHotbarMaterial(sand) && connection.inventorySwaps == swaps + 1,
                    "Fenced replacement-player inventory did not recover without another SWAP");

            checkRefillInterruptedReadback(connection, replacement, manager, gravel, sand);
        } finally {
            PrinterConfig.INVENTORY_DELAY.setIntegerValue(previousDelay);
            fixture.client.player = oldPlayer;
            fixture.client.interactionManager = fixture.interaction;
        }
    }

    private static void checkRefillInterruptedReadback(FixtureNetwork connection, Fixture replacement,
                                                        InventoryManager manager, ItemStack gravel, ItemStack sand)
            throws Exception {
        FixturePlayer player = replacement.player;
        PlayerScreenHandler handler = player.playerScreenHandler;
        int swaps = connection.inventorySwaps;
        int probes = connection.inventoryReadbacks;
        int fences = connection.statisticsRequests;
        check(!manager.select(gravel) && connection.inventorySwaps == swaps + 1,
                "Did not send the refill for the readback-interruption check");
        advanceInventoryTicks(replacement, manager, 30);
        manager.onInventoryReadbackBarrier(connection);
        player.inventory.main.set(13, sand.copy());
        player.inventory.main.set(2, gravel.copy());
        publishInventory(replacement, manager);

        ScreenHandler other = (ScreenHandler) replacement.unsafe.allocateInstance(PlayerScreenHandler.class);
        player.currentScreenHandler = other;
        manager.tick();
        player.currentScreenHandler = handler;
        manager.onInventoryReadbackBarrier(connection);
        check(!manager.selectReadyHotbarMaterial(gravel) && connection.inventorySwaps == swaps + 1,
                "Closing a container allowed an interrupted readback to release its destination");

        advanceInventoryTicks(replacement, manager, 30);
        check(connection.inventoryReadbacks == probes + 2 && connection.statisticsRequests == fences + 3,
                "Interrupted refresh did not restart with an ordered readback");
        publishInventory(replacement, manager);
        manager.onInventoryReadbackBarrier(connection);
        check(manager.selectReadyHotbarMaterial(gravel) && connection.inventorySwaps == swaps + 1,
                "Restarted readback did not recover without replaying the refill");
    }

    private static void publishSlot(Fixture fixture, InventoryManager manager, int inventorySlot, ItemStack stack)
            throws Exception {
        PlayerScreenHandler handler = fixture.player.playerScreenHandler;
        int revision = advanceRevision(handler);
        fixture.player.inventory.main.set(inventorySlot, stack);
        int screenSlot = inventorySlot < 9 ? PlayerScreenHandler.HOTBAR_START + inventorySlot : inventorySlot;
        manager.onAuthoritativeSlot(handler.syncId, revision, screenSlot);
    }

    private static void publishInventory(Fixture fixture, InventoryManager manager) throws Exception {
        PlayerScreenHandler handler = fixture.player.playerScreenHandler;
        manager.onAuthoritativeInventory(handler.syncId, advanceRevision(handler), handler.slots.size());
    }

    private static int advanceRevision(PlayerScreenHandler handler) throws Exception {
        int revision = (handler.getRevision() + 1) & 32767;
        set(ScreenHandler.class, handler, "revision", revision);
        return revision;
    }

    private static void fill(FixturePlayer player, ItemStack stack) {
        for (int i = 0; i < 36; i++) player.inventory.main.set(i, stack.copy());
    }

    private static void checkWaterlogging(Fixture fixture) throws Exception {
        MinecraftClient client = fixture.client;
        FixturePlayer player = fixture.player;
        BlockPos target = new BlockPos(0, 65, 3);
        BlockState dry = Blocks.OAK_SLAB.getDefaultState();
        BlockState wet = dry.with(Properties.WATERLOGGED, true);
        FixtureWorld world = (FixtureWorld) fixture.unsafe.allocateInstance(FixtureWorld.class);
        world.blocks = new HashMap<>();
        world.border = new WorldBorder();
        world.attributes = WorldEnvironmentAttributeAccess.builder().build();
        set(ClientWorld.class, world, "pendingUpdateManager", new PendingUpdateManager());
        set(Entity.class, player, "world", world);
        set(Entity.class, player, "pos", new Vec3d(0.5, 64.0, 0.5));
        set(Entity.class, player, "standingEyeHeight", 1.62F);
        set(Entity.class, player, "boundingBox", new Box(0.2, 64, 0.2, 0.8, 65.8, 0.8));
        set(ClientPlayerEntity.class, player, "lastPlayerInput", PlayerInput.DEFAULT);
        player.input = new Input();
        GameOptions options = (GameOptions) fixture.unsafe.allocateInstance(GameOptions.class);
        set(GameOptions.class, options, "sneakKey", fixture.unsafe.allocateInstance(KeyBinding.class));
        set(MinecraftClient.class, client, "options", options);
        player.cooldowns = new ItemCooldownManager();
        player.reach = 4.5;
        player.abilities.allowModifyWorld = true;
        client.world = world;
        world.blocks.put(target, dry);
        BlockPos crosshairBlock = new BlockPos(-3, 65, 0);
        world.blocks.put(crosshairBlock, Blocks.STONE.getDefaultState());
        player.setYaw(90);
        player.setPitch(0);
        client.crosshairTarget = new BlockHitResult(crosshairBlock.toCenterPos(), Direction.EAST, crosshairBlock, false);
        fixture.network.uses = new ArrayList<>();
        FixtureSchematic schematic = (FixtureSchematic) fixture.unsafe.allocateInstance(FixtureSchematic.class);
        schematic.target = target;
        schematic.desired = wet;
        Field schematicWorldField = SchematicWorldHandler.class.getDeclaredField("world");
        schematicWorldField.setAccessible(true);
        WorldSchematic previousSchematic = (WorldSchematic) schematicWorldField.get(SchematicWorldHandler.INSTANCE);
        set(SchematicWorldHandler.class, SchematicWorldHandler.INSTANCE, "world", schematic);
        Field inventoryClient = InventoryManager.class.getDeclaredField("mc");
        inventoryClient.setAccessible(true);
        MinecraftClient previousInventoryClient = (MinecraftClient) inventoryClient.get(Printer.inventoryManager);
        inventoryClient.set(Printer.inventoryManager, client);
        Printer.inventoryManager.reset();
        fill(player, ItemStack.EMPTY);
        player.inventory.main.set(2, new ItemStack(Items.WATER_BUCKET));
        player.inventory.selectedSlot = 0;
        boolean previousWaterlogging = PrinterConfig.WATERLOGGING.getBooleanValue();
        Field rateField = Printer.class.getDeclaredField("placementRate");
        rateField.setAccessible(true);
        PlacementRateLimiter limiter = (PlacementRateLimiter) rateField.get(null);
        int previousBurst = PrinterConfig.NATIVE_BURST.getIntegerValue();
        int previousWindow = PrinterConfig.INTERACTION_WINDOW_MS.getIntegerValue();
        int previousLimit = PrinterConfig.INTERACTION_WINDOW_LIMIT.getIntegerValue();
        int previousTimeout = PrinterConfig.BLOCK_TIMEOUT.getIntegerValue();
        boolean previousGrim = PrinterConfig.PRINTER_GRIM_ROTATION.getBooleanValue();
        List<Printer.BlockTimeout> previousTimeouts = List.copyOf(Printer.blockPosTimeout);
        try {
            limiter.reset();
            assertions += LogFluidRegressionChecks.run(client, player, schematic, world.blocks, target);
            PrinterConfig.NATIVE_BURST.setIntegerValue(9);
            // Use the maximum window so normal headless execution does not rely
            // on finishing these real production action calls within 310 ms.
            PrinterConfig.INTERACTION_WINDOW_MS.setIntegerValue(10_000);
            PrinterConfig.INTERACTION_WINDOW_LIMIT.setIntegerValue(2);
            PrinterConfig.BLOCK_TIMEOUT.setIntegerValue(0);
            PrinterConfig.PRINTER_GRIM_ROTATION.setBooleanValue(true);
            PrinterPlacementContext context = (PrinterPlacementContext) fixture.unsafe.allocateInstance(PrinterPlacementContext.class);
            Action block = new ProbeBlockAction(context);
            Action item = checkWaterloggingGuards(fixture, schematic);
            checkBucketHit(fixture, schematic, item);
            checkSharedInteractionBudget(fixture, item, block, limiter, target, dry);
            fixture.interaction.throwOnUse = false;
            limiter.reset();
            PrinterConfig.PRINTER_GRIM_ROTATION.setBooleanValue(false);
            checkScaffoldingBucketSwitch(fixture, world, schematic);
            checkMovingInteractions(fixture, world, schematic, limiter);
            checkCopperTools(fixture, world, schematic, limiter);
        } finally {
            set(SchematicWorldHandler.class, SchematicWorldHandler.INSTANCE, "world", previousSchematic);
            inventoryClient.set(Printer.inventoryManager, previousInventoryClient);
            Printer.inventoryManager.reset();
            Printer.confirmNativePlacement(target, dry);
            PrinterConfig.WATERLOGGING.setBooleanValue(previousWaterlogging);
            PrinterConfig.BLOCK_TIMEOUT.setIntegerValue(previousTimeout);
            PrinterConfig.PRINTER_GRIM_ROTATION.setBooleanValue(previousGrim);
            PrinterConfig.NATIVE_BURST.setIntegerValue(previousBurst);
            PrinterConfig.INTERACTION_WINDOW_MS.setIntegerValue(previousWindow);
            PrinterConfig.INTERACTION_WINDOW_LIMIT.setIntegerValue(previousLimit);
            limiter.reset();
            Printer.blockPosTimeout.clear();
            Printer.blockPosTimeout.addAll(previousTimeouts);
        }
    }

    private static Action checkWaterloggingGuards(Fixture fixture, FixtureSchematic schematic) {
        MinecraftClient client = fixture.client;
        FixturePlayer player = fixture.player;
        FixtureWorld world = (FixtureWorld) client.world;
        BlockPos target = schematic.target;
        BlockState dry = world.getBlockState(target);
        BlockState wet = schematic.desired;
        WaterLogGuide guide = new WaterLogGuide(new SchematicBlockState(world, schematic, target));
        PrinterConfig.WATERLOGGING.setBooleanValue(false);
        check(!guide.canExecute(player) && guide.execute(player).isEmpty(), "Disabled printer scheduled a water bucket");
        PrinterConfig.WATERLOGGING.setBooleanValue(true);
        world.blocks.remove(target);
        world.blocks.put(target.down(), Blocks.STONE.getDefaultState());
        player.inventory.main.set(4, new ItemStack(Items.OAK_SLAB));
        check(new SlabGuide(new SchematicBlockState(world, schematic, target)).canExecute(player),
                "Legacy guide could not place the dry slab before waterlogging");
        check(!guide.canExecute(player), "Waterlogging tried to place a water source in empty space");
        world.blocks.put(target, dry);
        check(guide.canExecute(player), "Printer did not find a reachable dry slab behind the camera view");
        List<Action> actions = guide.execute(player);
        check(actions.size() == 1, "Expected one targeted waterlogging action");
        Action item = actions.getFirst();
        PrinterConfig.WATERLOGGING.setBooleanValue(false);
        check(!item.send(client, player), "Disabling waterlogging left a queued bucket use active");
        PrinterConfig.WATERLOGGING.setBooleanValue(true);
        schematic.desired = dry;
        check(!item.send(client, player), "Queued bucket ignored a schematic edit to dry");
        world.blocks.put(target, wet);
        check(!new WaterLogGuide(new SchematicBlockState(world, schematic, target)).canExecute(player),
                "Wet world / dry schematic scheduled a full bucket");
        schematic.desired = wet;
        check(!item.send(client, player), "Already waterlogged target received a second bucket");
        world.blocks.put(target, Blocks.STONE_SLAB.getDefaultState());
        check(!item.send(client, player), "Different waterloggable block received a bucket");
        world.blocks.put(target, dry);
        world.blocks.put(target.north(), Blocks.STONE.getDefaultState());
        check(!item.send(client, player), "New obstruction redirected the queued water bucket");
        world.blocks.remove(target.north());
        player.reach = 2;
        check(!item.send(client, player), "Bucket reached outside current player interaction range");
        player.reach = 4.5;
        player.sneaking = true;
        check(!item.send(client, player), "Sneak-use could empty the bucket in a neighbor");
        player.sneaking = false;
        client.options.sneakKey.setPressed(true);
        check(!item.send(client, player), "Pending sneak input could spill water");
        client.options.sneakKey.setPressed(false);
        world.blocks.put(target, dry.with(SlabBlock.TYPE, net.minecraft.block.enums.SlabType.DOUBLE));
        check(!item.send(client, player), "A double slab cannot take water and could redirect it");
        world.blocks.put(target, dry);
        check(fixture.interaction.itemUses == 0 && fixture.network.uses.isEmpty(), "Rejected targets emitted bucket use");
        player.inventory.main.set(2, ItemStack.EMPTY);
        check(!guide.canExecute(player) && !item.send(client, player), "Missing water bucket was accepted");
        player.inventory.main.set(2, new ItemStack(Items.WATER_BUCKET));
        return item;
    }

    private static void checkBucketHit(Fixture fixture, FixtureSchematic schematic, Action item) {
        MinecraftClient client = fixture.client;
        FixturePlayer player = fixture.player;
        FixtureWorld world = (FixtureWorld) client.world;
        BlockPos target = schematic.target;
        HitResult originalCrosshair = client.crosshairTarget;
        check(item.send(client, player), "Production waterlogging action was not accepted");
        check(fixture.network.uses.size() == 1, "Expected one vanilla item-use packet");
        PlayerInteractItemC2SPacket use = fixture.network.uses.getFirst();
        check(use.getHand() == Hand.MAIN_HAND && use.getSequence() > 0, "Wrong hand or unsequenced bucket use");
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(player.getRotationVector(use.getPitch(), use.getYaw()).multiply(player.getBlockInteractionRange()));
        BlockHitResult serverHit = world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player));
        check(serverHit.getType() == HitResult.Type.BLOCK && target.equals(serverHit.getBlockPos()),
                "Vanilla ray cast with packet angles missed the target");
        MixinAccessorClientPlayerEntity sent = (MixinAccessorClientPlayerEntity) (ClientPlayerEntity) player;
        check(sent.getLastYaw() == use.getYaw() && sent.getLastPitch() == use.getPitch(),
                "Vanilla movement did not track the bucket packet rotation");
        check(target.equals(world.emptiedAt), "Vanilla bucket used a position outside the schematic target");
        check(player.getMainHandStack().isOf(Items.BUCKET), "Survival water bucket exchange was lost");
        check(player.getYaw() == 90 && player.getPitch() == 0, "Bucket use turned the local camera");
        check(client.crosshairTarget == originalCrosshair
                && player.input.playerInput.equals(PlayerInput.DEFAULT), "Bucket use changed crosshair or input");
        check(Printer.blockPosTimeout.stream().anyMatch(entry -> entry.pos.equals(target)), "Wrong target cooldown");
        player.inventory.main.set(2, new ItemStack(Items.WATER_BUCKET));
        check(!item.send(client, player),
                "Waterlogged twice while waiting for the authoritative block update");
        check(fixture.network.uses.size() == 1, "Sent another bucket before server confirmation");
        // Simulate an authoritative rejection so this dry position is eligible for the budget checks below.
        Printer.confirmNativePlacement(target, world.getBlockState(target));
    }

    private static void checkSharedInteractionBudget(Fixture fixture, Action item, Action block,
                                                    PlacementRateLimiter limiter, BlockPos target, BlockState dry) {
        MinecraftClient client = fixture.client;
        FixturePlayer player = fixture.player;
        FixtureInteraction interaction = fixture.interaction;
        check(block.send(client, player), "Block interaction did not share remaining item-use budget");
        check(interaction.itemUses == 1 && interaction.blockUses == 1, "Interactions did not reach the captured manager");
        check(!item.send(client, player), "Item use bypassed exhausted shared budget");
        check(!block.send(client, player), "Block interaction bypassed exhausted shared budget");
        check(interaction.itemUses == 1 && interaction.blockUses == 1 && player.swings == 2,
                "Rejected budget still invoked interaction or swing");
        player.age++;
        check(!item.send(client, player), "Item use obtained a fresh rolling budget on tick change");
        PrinterConfig.INTERACTION_WINDOW_LIMIT.setIntegerValue(3);
        check(item.send(client, player), "Raising the configured budget did not allow the remaining item use");
        check(!block.send(client, player), "Live limit change reset production usage");
        check(interaction.itemUses == 2 && interaction.blockUses == 1, "Wrong number of production interactions");
        Printer.confirmNativePlacement(target, dry);
        player.inventory.main.set(2, new ItemStack(Items.WATER_BUCKET));

        limiter.reset();
        PrinterConfig.NATIVE_BURST.setIntegerValue(1);
        PrinterConfig.INTERACTION_WINDOW_LIMIT.setIntegerValue(9);
        check(item.send(client, player), "First item use under one-per-tick burst denied");
        check(!block.send(client, player), "Block interaction ignored item-use burst consumption");
        player.age++;
        check(block.send(client, player), "Next tick did not refresh the shared interaction burst");
        Printer.confirmNativePlacement(target, dry);
        player.inventory.main.set(2, new ItemStack(Items.WATER_BUCKET));

        limiter.reset();
        PrinterConfig.NATIVE_BURST.setIntegerValue(9);
        PrinterConfig.INTERACTION_WINDOW_LIMIT.setIntegerValue(1);
        interaction.rejectItemUse = true;
        int priorUses = interaction.itemUses;
        check(!item.send(client, player), "Rejected item-use result was reported as successful");
        check(interaction.itemUses == priorUses + 1, "Rejected attempt never reached interaction manager");
        check(!block.send(client, player), "An attempted item use refunded its network budget after rejection");
        check(player.getYaw() == 90 && player.getPitch() == 0, "Rejected bucket use turned the camera");
        interaction.rejectItemUse = false;
        interaction.throwOnUse = true;
        limiter.reset();
        try {
            item.send(client, player);
            throw new AssertionError("Missing test interaction failure");
        } catch (IllegalStateException expected) {
            check("test bucket failure".equals(expected.getMessage()), "Unexpected bucket failure");
        }
        check(player.getYaw() == 90 && player.getPitch() == 0, "Failed interaction leaked temporary aim");
    }

    private static void checkScaffoldingBucketSwitch(Fixture fixture, FixtureWorld world, FixtureSchematic schematic) {
        BlockPos target = schematic.target;
        FixturePlayer player = fixture.player;
        BlockState scaffolding = Blocks.SCAFFOLDING.getDefaultState();
        world.blocks.put(target, scaffolding);
        world.blocks.put(target.south(), Blocks.STONE.getDefaultState());
        schematic.desired = scaffolding.with(Properties.WATERLOGGED, true);
        player.inventory.main.set(0, new ItemStack(Items.SCAFFOLDING));
        player.inventory.main.set(2, new ItemStack(Items.WATER_BUCKET));
        player.inventory.selectedSlot = 0;
        Vec3d eye = player.getEyePos();
        Vec3d end = eye.add(target.toCenterPos().subtract(eye).normalize().multiply(player.getBlockInteractionRange()));
        check(world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player)).getBlockPos().equals(target), "Held scaffolding is a full outline");
        player.inventory.selectedSlot = 2;
        check(world.raycast(new RaycastContext(eye, end, RaycastContext.ShapeType.OUTLINE,
                RaycastContext.FluidHandling.NONE, player)).getBlockPos().equals(target.south()), "Bucket ray must pass through the center");
        player.inventory.selectedSlot = 0;
        WaterLogGuide guide = new WaterLogGuide(new SchematicBlockState(world, schematic, target));
        check(guide.canExecute(player), "Dry scaffolding with an accessible bucket was skipped");
        int packets = fixture.network.uses.size();
        world.emptiedAt = null;
        check(guide.execute(player).getFirst().send(fixture.client, player), "Failed to waterlog scaffolding after selecting a bucket");
        check(fixture.network.uses.size() == packets + 1 && target.equals(world.emptiedAt),
                "Used the old scaffolding outline and aimed through its hollow center");
    }

    private static void checkMovingInteractions(Fixture fixture, FixtureWorld world, FixtureSchematic schematic,
                                                PlacementRateLimiter limiter) throws Exception {
        var player = fixture.player;
        var client = fixture.client;
        var target = schematic.target;
        boolean printing = LitematicaMixinMod.PRINT_MODE.getBooleanValue();
        boolean nativeMoving = PrinterConfig.PRINTER_NATIVE_MOVING.getBooleanValue();
        boolean nativeOmni = PrinterConfig.PRINTER_NATIVE_OMNI.getBooleanValue();
        boolean stop = PrinterConfig.STOP_ON_MOVEMENT.getBooleanValue();
        try {
            LitematicaMixinMod.PRINT_MODE.setBooleanValue(true);
            PrinterConfig.PRINTER_NATIVE_MOVING.setBooleanValue(true);
            PrinterConfig.PRINTER_NATIVE_OMNI.setBooleanValue(true);
            PrinterConfig.STOP_ON_MOVEMENT.setBooleanValue(false);
            PrinterConfig.WATERLOGGING.setBooleanValue(true);
            PrinterConfig.NATIVE_BURST.setIntegerValue(9);
            PrinterConfig.INTERACTION_WINDOW_LIMIT.setIntegerValue(64);
            limiter.reset();
            set(MinecraftClient.class, client, "cameraEntity", player);
            set(Entity.class, player, "velocity", new Vec3d(0.16, 0, 0));
            set(Entity.class, player, "dimensions", net.minecraft.entity.EntityDimensions.fixed(0.6F, 1.8F));
            set(Entity.class, player, "blockPos", new BlockPos(0, 64, 0));
            for (String key : List.of("forwardKey", "backKey", "leftKey", "rightKey"))
                set(GameOptions.class, client.options, key, fixture.unsafe.allocateInstance(KeyBinding.class));
            player.setYaw(0);
            player.setPitch(0);
            ((MixinAccessorClientPlayerEntity) (Object) player).setLastYaw(0);
            ((MixinAccessorClientPlayerEntity) (Object) player).setLastPitch(0);
            Printer printer = new Printer(client, player);
            fill(player, ItemStack.EMPTY);
            world.blocks.clear();
            BlockState repeater = Blocks.REPEATER.getDefaultState();
            world.blocks.put(target, repeater);
            schematic.desired = repeater.with(RepeaterBlock.DELAY, 4);
            fixture.network.blockPackets = new ArrayList<>();
            int clicks = fixture.network.blockPackets.size();
            check(schedule(printer), "Moving repeater was not scheduled");
            check(fixture.network.blockPackets.size() == clicks, "Moving tune ran before movement");
            player.reach = 0.5;
            printer.flushNativePlacements();
            check(fixture.network.blockPackets.size() == clicks, "A queued tune ignored post-movement reach");
            player.reach = 4.5;
            check(schedule(printer), "Moving tune did not resume when back in reach");
            printer.flushNativePlacements();
            check(fixture.network.blockPackets.size() == clicks + 1
                    && fixture.network.blockPackets.get(clicks).getBlockHitResult().getBlockPos().equals(target),
                    "Missing directed repeater packet");
            Printer.confirmNativePlacement(target, repeater.with(RepeaterBlock.POWERED, true));
            check(Printer.hasPendingPlacement(target) && !printer.onGameTick(), "An unrelated update repeated a dial click");
            Printer.confirmNativePlacement(target, repeater.with(RepeaterBlock.DELAY, 2));
            check(!Printer.hasPendingPlacement(target), "Next dial step was not confirmed");

            checkDaylightInteractions(fixture, world, schematic, limiter, printer);

            BlockState dry = Blocks.OAK_SLAB.getDefaultState();
            world.blocks.put(target, dry);
            schematic.desired = dry.with(Properties.WATERLOGGED, true);
            player.inventory.main.set(2, new ItemStack(Items.WATER_BUCKET));
            check(schedule(printer), "Moving waterlogging was not scheduled");
            int uses = fixture.network.uses.size();
            world.blocks.put(target.north(), Blocks.STONE.getDefaultState());
            printer.flushNativePlacements();
            check(fixture.network.uses.size() == uses, "Moving bucket did not revalidate its ray after movement");
            world.blocks.remove(target.north());
            check(schedule(printer), "Reachable moving bucket failed rescan");
            printer.flushNativePlacements();
            check(fixture.network.uses.size() == uses + 1 && target.equals(world.emptiedAt), "Moving bucket missed target");
            Printer.confirmNativePlacement(target, schematic.desired);

            world.blocks.clear();
            BlockState rail = Blocks.RAIL.getDefaultState();
            schematic.desired = rail;
            player.inventory.main.set(1, new ItemStack(Items.RAIL));
            world.blocks.put(target.down(), Blocks.STONE.getDefaultState());
            check(schedule(printer), "Moving flat rail was not scheduled");
            clicks = fixture.network.blockPackets.size();
            world.blocks.remove(target.down());
            MovementHandler.onMovementSent(player);
            printer.flushNativePlacements();
            check(fixture.network.blockPackets.size() == clicks, "Rail was placed after losing its support");
            world.blocks.put(target.down(), Blocks.STONE.getDefaultState());
            check(schedule(printer), "Supported rail did not rescan");
            ((MixinAccessorClientPlayerEntity) (Object) player).setLastYaw(player.getYaw());
            ((MixinAccessorClientPlayerEntity) (Object) player).setLastPitch(player.getPitch());
            MovementHandler.onMovementSent(player);
            printer.flushNativePlacements();
            check(fixture.network.blockPackets.size() == clicks + 1 && Printer.hasPendingPlacement(target),
                    "Supported moving rail was not sent");
            Printer.confirmNativePlacement(target, rail);

            schematic.desired = rail.with(Properties.RAIL_SHAPE, net.minecraft.block.enums.RailShape.ASCENDING_EAST);
            check(MovingPrinterAction.railPlacementSeed(new SchematicBlockState(world, schematic, target), (AbstractRailBlock) Blocks.RAIL) == null,
                    "Slope rail accepted a missing high support");
            world.blocks.put(target.east(), Blocks.STONE.getDefaultState());
            Printer.trackPlacement(target.east().up(), rail, new ItemStack(Items.RAIL), -1);
            check(MovingPrinterAction.railPlacementSeed(new SchematicBlockState(world, schematic, target), (AbstractRailBlock) Blocks.RAIL) == null,
                    "Slope rail ignored pending upper track");
            Printer.confirmNativePlacement(target.east().up(), rail);
            PrinterConfig.STOP_ON_MOVEMENT.setBooleanValue(true);
            check(!printer.onGameTick(), "Moving interaction bypassed StopOnMovement");
            checkRailConnections(world, schematic, target);
            checkNativeBorderPackets(fixture, world, schematic, limiter, printer);
        } finally {
            LitematicaMixinMod.PRINT_MODE.setBooleanValue(printing);
            PrinterConfig.PRINTER_NATIVE_MOVING.setBooleanValue(nativeMoving);
            PrinterConfig.PRINTER_NATIVE_OMNI.setBooleanValue(nativeOmni);
            PrinterConfig.STOP_ON_MOVEMENT.setBooleanValue(stop);
        }
    }

    private static void checkNativeBorderPackets(Fixture fixture, FixtureWorld world, FixtureSchematic schematic,
                                                  PlacementRateLimiter limiter, Printer printer) throws Exception {
        var player = fixture.player;
        BlockPos target = schematic.target;
        BlockPos support = target.north();
        BlockPos secondRow = target.south();
        BlockState stone = Blocks.STONE.getDefaultState();
        WorldBorder previousBorder = world.border;
        Map<BlockPos, BlockState> previousBlocks = new HashMap<>(world.blocks);
        BlockState previousDesired = schematic.desired;
        Map<BlockPos, BlockState> previousNeighbors = schematic.neighbors;
        List<ItemStack> previousInventory = player.inventory.main.stream().map(ItemStack::copy).toList();
        int previousSlot = player.inventory.selectedSlot;
        boolean stop = PrinterConfig.STOP_ON_MOVEMENT.getBooleanValue();
        boolean strictFace = PrinterConfig.STRICT_BLOCK_FACE_CHECK.getBooleanValue();
        boolean raycast = PrinterConfig.RAYCAST.getBooleanValue();
        boolean strictHit = PrinterConfig.RAYCAST_STRICT_BLOCK_HIT.getBooleanValue();
        boolean grimAirplace = PrinterConfig.PRINTER_NATIVE_GRIM_AIRPLACE.getBooleanValue();
        boolean grimRotation = PrinterConfig.PRINTER_GRIM_ROTATION.getBooleanValue();
        try {
            PrinterConfig.STOP_ON_MOVEMENT.setBooleanValue(false);
            PrinterConfig.STRICT_BLOCK_FACE_CHECK.setBooleanValue(true);
            PrinterConfig.RAYCAST.setBooleanValue(true);
            PrinterConfig.RAYCAST_STRICT_BLOCK_HIT.setBooleanValue(true);
            PrinterConfig.PRINTER_NATIVE_GRIM_AIRPLACE.setBooleanValue(true);
            PrinterConfig.PRINTER_GRIM_ROTATION.setBooleanValue(false);
            world.border = new WorldBorder();
            world.border.setCenter(0, target.getZ() - 3);
            world.border.setSize(6);
            world.blocks.clear();
            world.blocks.put(support, stone);
            schematic.desired = stone;
            schematic.neighbors = Map.of();
            fill(player, ItemStack.EMPTY);
            player.inventory.main.set(2, new ItemStack(Items.STONE, 64));
            Printer.inventoryManager.reset();
            limiter.reset();
            check(world.border.contains(support) && !world.border.contains(target), "Border packet fixture is not across the border");

            int packets = fixture.network.blockPackets.size();
            int swaps = fixture.network.handSwaps;
            check(schedule(printer), "First outside row did not reach native scheduling");
            check(fixture.network.blockPackets.size() == packets && !Printer.hasPendingPlacement(target),
                    "Border placement sent or became pending before movement flush");
            MovementHandler.onMovementSent(player);
            printer.flushNativePlacements();
            check(fixture.network.blockPackets.size() == packets + 1, "Border placement did not send exactly one block packet");
            PlayerInteractBlockC2SPacket packet = fixture.network.blockPackets.get(packets);
            check(packet.getBlockHitResult().getBlockPos().equals(support)
                            && packet.getBlockHitResult().getSide() == Direction.SOUTH,
                    "Border packet did not click the inside support's outward face");
            check(packet.getHand() == Hand.MAIN_HAND && packet.getSequence() > 0 && fixture.network.handSwaps == swaps,
                    "Supported border placement incorrectly used the airplace hand-swap path");
            check(Printer.hasPendingPlacement(target) && !Printer.hasPendingPlacement(support),
                    "Border pending state tracked the clicked support instead of the outside target");
            check(world.getBlockState(target).isAir(), "Border send inserted an unconfirmed client block");
            Printer.confirmNativePlacement(support, stone);
            check(Printer.hasPendingPlacement(target), "An inside support update confirmed the outside target");
            world.blocks.put(target, stone);
            Printer.confirmNativePlacement(target, stone);
            check(!Printer.hasPendingPlacement(target), "Outside target update did not clear its pending placement");

            world.blocks.remove(target);
            packets = fixture.network.blockPackets.size();
            check(schedule(printer), "Border target did not prepare for support removal check");
            world.blocks.remove(support);
            MovementHandler.onMovementSent(player);
            printer.flushNativePlacements();
            check(fixture.network.blockPackets.size() == packets && !Printer.hasPendingPlacement(target),
                    "Removed inside support still produced a packet or pending target");

            world.blocks.put(support, stone);
            check(schedule(printer), "Border target did not prepare for border shrink check");
            world.border.setSize(4);
            MovementHandler.onMovementSent(player);
            printer.flushNativePlacements();
            check(fixture.network.blockPackets.size() == packets && !Printer.hasPendingPlacement(target),
                    "Shrunken border still allowed the previously prepared outside placement");

            world.border.setSize(6);
            world.blocks.put(target, stone);
            schematic.target = secondRow;
            check(!schedule(printer), "Native printer scheduled the second outside row using an outside support");
            printer.flushNativePlacements();
            check(fixture.network.blockPackets.size() == packets && !Printer.hasPendingPlacement(secondRow),
                    "Second outside row produced a packet or pending target");
        } finally {
            // Discard any prepared action if an assertion interrupted the scenario.
            world.blocks.clear();
            world.border.setSize(1);
            printer.flushNativePlacements();
            Printer.confirmNativePlacement(target, stone);
            Printer.confirmNativePlacement(secondRow, stone);
            world.border = previousBorder;
            world.blocks.putAll(previousBlocks);
            schematic.target = target;
            schematic.desired = previousDesired;
            schematic.neighbors = previousNeighbors;
            for (int i = 0; i < previousInventory.size(); i++) player.inventory.main.set(i, previousInventory.get(i));
            player.inventory.selectedSlot = previousSlot;
            Printer.inventoryManager.reset();
            PrinterConfig.STOP_ON_MOVEMENT.setBooleanValue(stop);
            PrinterConfig.STRICT_BLOCK_FACE_CHECK.setBooleanValue(strictFace);
            PrinterConfig.RAYCAST.setBooleanValue(raycast);
            PrinterConfig.RAYCAST_STRICT_BLOCK_HIT.setBooleanValue(strictHit);
            PrinterConfig.PRINTER_NATIVE_GRIM_AIRPLACE.setBooleanValue(grimAirplace);
            PrinterConfig.PRINTER_GRIM_ROTATION.setBooleanValue(grimRotation);
            limiter.reset();
        }
    }

    private static void checkDaylightInteractions(Fixture fixture, FixtureWorld world, FixtureSchematic schematic,
                                                  PlacementRateLimiter limiter, Printer printer) throws Exception {
        var player = fixture.player;
        BlockPos target = schematic.target;
        for (boolean moving : new boolean[]{false, true}) {
            PrinterConfig.PRINTER_NATIVE_MOVING.setBooleanValue(moving);
            PrinterConfig.PRINTER_NATIVE_OMNI.setBooleanValue(moving);
            set(Entity.class, player, "velocity", moving ? new Vec3d(0.16, 0, 0) : Vec3d.ZERO);
            for (boolean initiallyInverted : new boolean[]{false, true}) {
                limiter.reset();
                BlockState before = Blocks.DAYLIGHT_DETECTOR.getDefaultState()
                        .with(DaylightDetectorBlock.INVERTED, initiallyInverted).with(DaylightDetectorBlock.POWER, 15);
                world.blocks.put(target, before);
                schematic.desired = before.with(DaylightDetectorBlock.INVERTED, !initiallyInverted)
                        .with(DaylightDetectorBlock.POWER, 0);
                int packets = fixture.network.blockPackets.size();
                check(schedule(printer), "Daylight detector mode was not scheduled, moving=" + moving);
                check(fixture.network.blockPackets.size() == packets, "Detector toggled before its action was processed");
                if (moving) printer.flushNativePlacements();
                else printer.actionHandler.processCurrentTickActions();
                check(fixture.network.blockPackets.size() == packets + 1 && Printer.hasPendingPlacement(target),
                        "Detector toggle did not send exactly one pending interaction");
                check(fixture.network.blockPackets.get(packets).getBlockHitResult().getBlockPos().equals(target),
                        "Detector interaction targeted another block");
                BlockState powerUpdate = before.with(DaylightDetectorBlock.POWER, 7);
                world.blocks.put(target, powerUpdate);
                Printer.confirmNativePlacement(target, powerUpdate);
                check(Printer.hasPendingPlacement(target) && !printer.onGameTick(),
                        "A daylight power update released the pending mode toggle");
                BlockState confirmed = powerUpdate.with(DaylightDetectorBlock.INVERTED, !initiallyInverted);
                world.blocks.put(target, confirmed);
                Printer.confirmNativePlacement(target, confirmed);
                check(!Printer.hasPendingPlacement(target), "Detector mode acknowledgement was not accepted");
                SchematicBlockState completed = new SchematicBlockState(world, schematic, target);
                check(!new CycleStateGuide(completed).canExecute(player)
                                && MovingPrinterAction.prepare(completed, player, player.reach) == null,
                        "Matched detector mode was clicked again because its power differed");
            }
        }
        limiter.reset();
    }

    private static void checkCopperTools(Fixture fixture, FixtureWorld world, FixtureSchematic schematic,
                                         PlacementRateLimiter limiter) {
        var player = fixture.player;
        BlockPos target = schematic.target;
        BlockState[] existing = {Blocks.DIRT.getDefaultState(), Blocks.CAMPFIRE.getDefaultState()};
        BlockState[] desired = {Blocks.FARMLAND.getDefaultState(), Blocks.CAMPFIRE.getDefaultState().with(CampfireBlock.LIT, false)};
        var tools = new net.minecraft.item.Item[]{Items.COPPER_HOE, Items.COPPER_SHOVEL};
        for (int i = 0; i < tools.length; i++) {
            limiter.reset();
            fill(player, ItemStack.EMPTY);
            player.inventory.main.set(2, new ItemStack(tools[i]));
            world.blocks.put(target, existing[i]);
            schematic.desired = desired[i];
            SchematicBlockState state = new SchematicBlockState(world, schematic, target);
            Guide selected = java.util.Arrays.stream(new Guides().getInteractionGuides(state))
                    .filter(guide -> guide instanceof TillingGuide || guide instanceof CampfireExtinguishGuide)
                    .filter(guide -> guide.canExecute(player)).findFirst().orElse(null);
            check(selected != null, "Registered guide rejected its only available copper tool: " + tools[i]);
            int uses = fixture.interaction.blockUses;
            for (Action action : selected.execute(player)) {
                if (action instanceof ActionChain chain) {
                    for (Action step : chain.getActionsCurrentTick()) check(step.send(fixture.client, player), "Copper tool action failed");
                    for (Action step : chain.getActionsNextTick()) check(step.send(fixture.client, player), "Copper tool next-tick action failed");
                } else check(action.send(fixture.client, player), "Copper tool action failed");
            }
            check(player.getMainHandStack().isOf(tools[i]) && fixture.interaction.blockUses == uses + 1,
                    "Copper tool was not selected and used by its real guide");
        }
    }

    private static void checkRailConnections(FixtureWorld world, FixtureSchematic schematic, BlockPos target) throws Exception {
        BlockState rail = Blocks.RAIL.getDefaultState();
        var entry = Blocks.RAIL.getRegistryEntry();
        Field tagField = net.minecraft.registry.entry.RegistryEntry.Reference.class.getDeclaredField("tags");
        tagField.setAccessible(true);
        Object oldTags = tagField.get(entry);
        // Headless bootstrap has no server-supplied rail tag. Exercise the real vanilla neighbor algorithm.
        tagField.set(entry, java.util.Set.of(net.minecraft.registry.tag.BlockTags.RAILS));
        try {
            world.blocks.clear();
            world.blocks.put(target.down(), Blocks.STONE.getDefaultState());
            schematic.desired = rail.with(Properties.RAIL_SHAPE, RailShape.NORTH_EAST);
            SchematicBlockState curve = new SchematicBlockState(world, schematic, target);
            check(MovingPrinterAction.railPlacementSeed(curve, (AbstractRailBlock) Blocks.RAIL) == null,
                    "Impossible isolated curve accepted as a permanently straight rail");
            schematic.neighbors = Map.of(target.north(), rail, target.east(), rail.with(Properties.RAIL_SHAPE, RailShape.EAST_WEST));
            BlockState first = MovingPrinterAction.railPlacementSeed(curve, (AbstractRailBlock) Blocks.RAIL);
            check(first != null, "Could not start a schematic curve before its neighbors exist");
            for (BlockPos pos : List.of(target, target.east(), target.north())) {
                BlockState placed = pos.equals(target) ? first : schematic.getBlockState(pos);
                world.blocks.put(pos, placed);
                new RailPlacementHelper(world, pos, placed).updateBlockState(false, true, placed.get(Properties.RAIL_SHAPE));
            }
            check(world.getBlockState(target).get(Properties.RAIL_SHAPE) == RailShape.NORTH_EAST,
                    "A staged curve did not converge after vanilla connected its neighbors");
            schematic.neighbors = Map.of();
            for (var shape : List.of(RailShape.NORTH_EAST, RailShape.ASCENDING_EAST)) {
                world.blocks.clear();
                world.blocks.put(target.down(), Blocks.STONE.getDefaultState());
                schematic.desired = rail.with(Properties.RAIL_SHAPE, shape);
                if (shape.isAscending()) {
                    world.blocks.put(target.east(), Blocks.STONE.getDefaultState());
                    world.blocks.put(target.east().up(), rail.with(Properties.RAIL_SHAPE, RailShape.EAST_WEST));
                } else {
                    world.blocks.put(target.north(), rail);
                    world.blocks.put(target.east(), rail.with(Properties.RAIL_SHAPE, RailShape.EAST_WEST));
                }
                SchematicBlockState state = new SchematicBlockState(world, schematic, target);
                BlockState seed = MovingPrinterAction.railPlacementSeed(state, (AbstractRailBlock) Blocks.RAIL);
                check(seed != null, "Ready curve/slope was deferred: " + shape);
                world.blocks.put(target, seed);
                BlockState result = new RailPlacementHelper(world, target, seed)
                        .updateBlockState(false, true, seed.get(Properties.RAIL_SHAPE)).getBlockState();
                check(result.get(Properties.RAIL_SHAPE) == shape, "Vanilla neighbor updates produced wrong moving rail " + shape);
                world.blocks.remove(target);
                world.blocks.put(target.south(), rail);
                check(MovingPrinterAction.railPlacementSeed(state, (AbstractRailBlock) Blocks.RAIL) == null,
                        "Unstable junction was allowed to turn another rail");
            }
        } finally {
            schematic.neighbors = Map.of();
            tagField.set(entry, oldTags);
        }
    }

    private static boolean schedule(Printer printer) {
        // Even if the 3 ms budget permits only one entry per scan, this cube covers a full rotation.
        int side = 3 + 2 * (int) Math.ceil(LitematicaMixinMod.PRINTING_RANGE.getDoubleValue());
        for (int i = 0; i < side * side * side; i++) if (printer.onGameTick()) return true;
        return false;
    }

    private static final class ProbeBlockAction extends InteractActionImpl {
        private ProbeBlockAction(PrinterPlacementContext context) { super(context, true); }
        @Override protected BlockPos getTimeoutPos() { return BlockPos.ORIGIN; }
    }

    private static final class ProbeGuide extends Guide {
        private final List<ItemStack> required;
        private ProbeGuide(SchematicBlockState state, List<ItemStack> required) { super(state); this.required = required; }
        private int slot(ClientPlayerEntity player) { return getRequiredItemStackSlot(player); }
        @Override protected List<ItemStack> getRequiredItems() { return required; }
        @Override public List<Action> execute(ClientPlayerEntity player) { return List.of(); }
    }

    private static final class FixtureSchematic extends WorldSchematic {
        private BlockPos target;
        private BlockState desired;
        private Map<BlockPos, BlockState> neighbors;
        private FixtureSchematic() { super(null, null, null, null); }
        @Override public BlockState getBlockState(BlockPos pos) {
            return pos.equals(target) ? desired : neighbors == null ? Blocks.AIR.getDefaultState()
                    : neighbors.getOrDefault(pos, Blocks.AIR.getDefaultState());
        }
    }

    private static final class FixtureWorld extends ClientWorld {
        private Map<BlockPos, BlockState> blocks;
        private BlockPos emptiedAt;
        private WorldBorder border;
        private WorldEnvironmentAttributeAccess attributes;
        private FixtureWorld() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public boolean isClient() { return true; }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public boolean isInBuildLimit(BlockPos pos) { return pos.getY() >= -64 && pos.getY() < 320; }
        @Override public boolean setBlockState(BlockPos pos, BlockState block, int flags) {
            blocks.put(pos.toImmutable(), block);
            return true;
        }
        @Override public net.minecraft.resource.featuretoggle.FeatureSet getEnabledFeatures() {
            return net.minecraft.resource.featuretoggle.FeatureFlags.VANILLA_FEATURES;
        }
        @Override public boolean doesNotIntersectEntities(Entity except, net.minecraft.util.shape.VoxelShape shape) { return true; }
        @Override public WorldEnvironmentAttributeAccess getEnvironmentAttributes() { return attributes; }
        @Override public void playSound(Entity source, BlockPos pos, SoundEvent sound, SoundCategory cat, float volume, float pitch) {
            emptiedAt = pos.toImmutable();
        }
    }

    private static final class FixturePlayer extends ClientPlayerEntity {
        private PlayerInventory inventory;
        private PlayerAbilities abilities;
        private ItemStack offhand;
        private boolean sneaking;
        private int swings;
        private ItemCooldownManager cooldowns;
        private double reach;
        private FixturePlayer() { super(null, null, null, null, null, PlayerInput.DEFAULT, false); }
        @Override public PlayerInventory getInventory() { return inventory; }
        @Override public PlayerAbilities getAbilities() { return abilities; }
        @Override public ItemStack getOffHandStack() { return offhand; }
        @Override public boolean isSneaking() { return sneaking; }
        @Override public boolean isAlive() { return true; }
        @Override public boolean isUsingItem() { return false; }
        @Override public boolean isSprinting() { return false; }
        @Override public boolean isDescending() { return sneaking; }
        @Override public double getBlockInteractionRange() { return reach; }
        @Override public ItemStack getMainHandStack() { return inventory.main.get(inventory.selectedSlot); }
        @Override public ItemStack getStackInHand(Hand hand) { return hand == Hand.MAIN_HAND ? getMainHandStack() : offhand; }
        @Override public void setStackInHand(Hand hand, ItemStack stack) {
            check(hand == Hand.MAIN_HAND, "Waterlogging changed the offhand");
            inventory.main.set(inventory.selectedSlot, stack);
        }
        @Override public ItemCooldownManager getItemCooldownManager() { return cooldowns; }
        @Override public void swingHand(Hand hand) { swings++; }
    }

    private static final class FixtureNetwork extends ClientPlayNetworkHandler {
        private FixtureNetwork() { super(null, null, null); }
        @Override public net.minecraft.client.network.PlayerListEntry getPlayerListEntry(java.util.UUID uuid) { return null; }
        private List<PlayerInteractItemC2SPacket> uses;
        private List<PlayerInteractBlockC2SPacket> blockPackets;
        private int handSwaps;
        private int inventorySwaps;
        private int inventoryReadbacks;
        private int statisticsRequests;
        private int statisticsRequestsAtLatestReadback;
        private ClickSlotC2SPacket latestSwap;
        private ClickSlotC2SPacket latestReadback;
        @Override public void sendPacket(Packet<?> packet) {
            if (packet instanceof PlayerInteractItemC2SPacket use) uses.add(use);
            if (packet instanceof PlayerInteractBlockC2SPacket use && blockPackets != null) blockPackets.add(use);
            if (packet instanceof PlayerActionC2SPacket action
                    && action.getAction() == PlayerActionC2SPacket.Action.SWAP_ITEM_WITH_OFFHAND) handSwaps++;
            if (packet instanceof ClickSlotC2SPacket swap) {
                boolean refill = swap.slot() >= 9 && swap.slot() < 36;
                boolean sameSlot = swap.slot() == PlayerScreenHandler.HOTBAR_START + swap.button();
                check(swap.actionType() == SlotActionType.SWAP && (refill || sameSlot),
                        "Printer sent an unexpected inventory action");
                check(swap.modifiedStacks().isEmpty() && swap.cursor() == ItemStackHash.EMPTY,
                        "Printer sent predicted inventory state with its authoritative refill request");
                if (refill) {
                    inventorySwaps++;
                    latestSwap = swap;
                } else {
                    inventoryReadbacks++;
                    latestReadback = swap;
                    statisticsRequestsAtLatestReadback = statisticsRequests;
                }
            }
            if (packet instanceof ClientStatusC2SPacket status && status.getMode() == ClientStatusC2SPacket.Mode.REQUEST_STATS) {
                InventoryFenceOrder.get(this).sent(status);
                statisticsRequests++;
            }
        }
    }

    private static final class FixtureInteraction extends ClientPlayerInteractionManager {
        private int creativeWrites;
        private int itemUses;
        private int blockUses;
        private boolean rejectItemUse;
        private boolean throwOnUse;
        private FixtureInteraction() { super(null, null); }
        @Override public void clickSlot(int syncId, int slotId, int button, SlotActionType type, PlayerEntity player) {
            throw new AssertionError("Printer predicted an automated swap in the local inventory");
        }
        @Override public void clickCreativeStack(ItemStack stack, int slotId) { creativeWrites++; }
        @Override public ActionResult interactItem(PlayerEntity player, Hand hand) {
            itemUses++;
            if (throwOnUse) throw new IllegalStateException("test bucket failure");
            return rejectItemUse ? ActionResult.FAIL : super.interactItem(player, hand);
        }
        @Override public ActionResult interactBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hit) {
            blockUses++;
            return ActionResult.SUCCESS;
        }
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
