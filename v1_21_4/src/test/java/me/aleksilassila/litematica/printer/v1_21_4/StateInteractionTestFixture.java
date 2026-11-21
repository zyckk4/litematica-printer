package me.aleksilassila.litematica.printer.v1_21_4;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import me.aleksilassila.litematica.printer.v1_21_4.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_4.mixin.MixinAccessorClientPlayerEntity;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
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
import net.minecraft.entity.EntityDimensions;
import net.minecraft.entity.player.ItemCooldownManager;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.world.World;
import net.minecraft.world.dimension.DimensionType;
import net.minecraft.world.dimension.DimensionTypes;
import net.minecraft.world.border.WorldBorder;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Small live-method fixture: real printer actions, captured network and inventory sinks. */
final class StateInteractionTestFixture implements AutoCloseable {
    static final BlockPos TARGET = new BlockPos(0, 65, 3);
    final MinecraftClient client;
    final FixturePlayer player;
    final FixtureWorld world;
    final FixtureSchematic schematic;
    final FixtureNetwork network;
    final FixtureInteraction interaction;
    Printer printer;
    private final List<Runnable> restore = new ArrayList<>();
    private final MinecraftClient previousClient = MinecraftClient.getInstance();
    private final WorldSchematic previousSchematic;
    private final MinecraftClient previousInventoryClient;
    private final String previousSlots = PrinterConfig.PRINTER_HOTBAR_SLOTS.getStringValue();

    StateInteractionTestFixture() throws Exception {
        // The public getter initializes rendering when no schematic exists.
        previousSchematic = (WorldSchematic) field(SchematicWorldHandler.class, "world")
                .get(SchematicWorldHandler.INSTANCE);
        Unsafe unsafe = (Unsafe) field(Unsafe.class, "theUnsafe").get(null);
        client = (MinecraftClient) unsafe.allocateInstance(MinecraftClient.class);
        set(MinecraftClient.class, null, "instance", client);
        set(MinecraftClient.class, client, "runDirectory", Path.of(".").toFile());
        player = (FixturePlayer) unsafe.allocateInstance(FixturePlayer.class);
        player.inventory = new PlayerInventory(player);
        player.abilities = new PlayerAbilities();
        player.abilities.allowModifyWorld = true;
        set(PlayerEntity.class, player, "abilities", player.abilities);
        PlayerScreenHandler screen = (PlayerScreenHandler) unsafe.allocateInstance(PlayerScreenHandler.class);
        screen.setCursorStack(ItemStack.EMPTY);
        set(PlayerEntity.class, player, "playerScreenHandler", screen);
        player.currentScreenHandler = screen;
        player.input = new Input();
        player.cooldowns = new ItemCooldownManager();
        player.reach = 4.5;
        network = (FixtureNetwork) unsafe.allocateInstance(FixtureNetwork.class);
        network.inputs = new ArrayList<>(); network.blockPackets = new ArrayList<>();
        set(ClientPlayerEntity.class, player, "networkHandler", network);
        interaction = (FixtureInteraction) unsafe.allocateInstance(FixtureInteraction.class);
        set(ClientPlayerInteractionManager.class, interaction, "client", client);
        set(ClientPlayerInteractionManager.class, interaction, "networkHandler", network);
        world = (FixtureWorld) unsafe.allocateInstance(FixtureWorld.class);
        world.blocks = new HashMap<>(); world.border = new WorldBorder();
        var builtins = BuiltinRegistries.createWrapperLookup();
        var dimension = builtins.getOrThrow(RegistryKeys.DIMENSION_TYPE).getOrThrow(DimensionTypes.OVERWORLD);
        world.dimension = dimension.value();
        InteractionRegressionChecks.initializeFixtureRegistries(world, builtins, dimension);
        set(World.class, world, "isClient", true);
        set(ClientWorld.class, world, "pendingUpdateManager", new PendingUpdateManager());
        set(Entity.class, player, "world", world);
        set(Entity.class, player, "pos", new Vec3d(0.5, 64, 0.5));
        set(Entity.class, player, "standingEyeHeight", 1.62F);
        set(Entity.class, player, "boundingBox", new Box(0.2, 64, 0.2, 0.8, 65.8, 0.8));
        set(Entity.class, player, "dimensions", EntityDimensions.fixed(0.6F, 1.8F));
        set(Entity.class, player, "blockPos", new BlockPos(0, 64, 0));
        GameOptions options = (GameOptions) unsafe.allocateInstance(GameOptions.class);
        for (String name : List.of("sneakKey", "forwardKey", "backKey", "leftKey", "rightKey"))
            set(GameOptions.class, options, name, unsafe.allocateInstance(KeyBinding.class));
        set(MinecraftClient.class, client, "options", options);
        set(MinecraftClient.class, client, "cameraEntity", player);
        client.player = player; client.world = world; client.interactionManager = interaction;
        schematic = (FixtureSchematic) unsafe.allocateInstance(FixtureSchematic.class);
        schematic.blocks = new HashMap<>();
        set(SchematicWorldHandler.class, SchematicWorldHandler.INSTANCE, "world", schematic);
        previousInventoryClient = (MinecraftClient) field(InventoryManager.class, "mc").get(Printer.inventoryManager);
        set(InventoryManager.class, Printer.inventoryManager, "mc", client);
        PrinterConfig.PRINTER_HOTBAR_SLOTS.setValueFromString("3,4,5,6,7,8,9");
        InventoryManager.setHotbarSlots(PrinterConfig.PRINTER_HOTBAR_SLOTS);
        configure(LitematicaMixinMod.PRINT_MODE, true);
        configure(PrinterConfig.PRINTER_NATIVE_MOVING, true);
        configure(PrinterConfig.PRINTER_NATIVE_OMNI, true);
        configure(PrinterConfig.STOP_ON_MOVEMENT, false);
        configure(PrinterConfig.PRINTER_GRIM_ROTATION, false);
        configure(PrinterConfig.RAYCAST, false);
        configure(PrinterConfig.STRICT_BLOCK_FACE_CHECK, false);
        configure(PrinterConfig.NATIVE_BURST, 9);
        configure(PrinterConfig.INTERACTION_WINDOW_LIMIT, 64);
        configure(PrinterConfig.INTERACTION_WINDOW_MS, 10_000);
        configure(PrinterConfig.BLOCK_TIMEOUT, 0);
        configure(PrinterConfig.TICK_DELAY, 0);
        // Focused runs do not execute the placement suite's earlier config bootstrap.
        Class.forName("fi.dy.masa.litematica.config.Configs$Generic");
        reset();
    }

    Printer reset() throws Exception {
        world.blocks.clear(); schematic.blocks.clear();
        network.inputs.clear(); network.blockPackets.clear();
        interaction.blockUses = 0;
        fill(ItemStack.EMPTY);
        player.inventory.selectedSlot = 0; player.offhand = new ItemStack(Items.TORCH, 12);
        player.sneaking = false; player.reach = 4.5; player.age++;
        player.input.playerInput = PlayerInput.DEFAULT;
        client.player = player; client.world = world; client.currentScreen = null;
        client.options.sneakKey.setPressed(false);
        setLastSneaking(false);
        set(ClientPlayerEntity.class, player, "lastPlayerInput", PlayerInput.DEFAULT);
        setVelocity(new Vec3d(0.16, 0, 0));
        setYaw(0);
        printer = new Printer(client, player);
        // Initialize the world lease before direct action tests track confirmations.
        printer.onGameTick();
        return printer;
    }

    void fill(ItemStack stack) {
        for (int i = 0; i < 36; i++) player.inventory.main.set(i, stack.copy());
    }
    void target(BlockState current, BlockState desired) {
        world.blocks.put(TARGET, current); schematic.blocks.put(TARGET, desired);
    }
    SchematicBlockState state(BlockPos pos) { return new SchematicBlockState(world, schematic, pos); }
    void setVelocity(Vec3d velocity) throws Exception { set(Entity.class, player, "velocity", velocity); }
    void setLastSneaking(boolean sneak) throws Exception { set(ClientPlayerEntity.class, player, "lastSneaking", sneak); }
    void setYaw(float yaw) { player.setYaw(yaw); player.setPitch(0); setServerYaw(yaw); }
    void setServerYaw(float yaw) { ((MixinAccessorClientPlayerEntity) (Object) player).setLastYaw(yaw); }
    void configure(ConfigBoolean config, boolean value) {
        boolean previous = config.getBooleanValue(); restore.add(() -> config.setBooleanValue(previous));
        config.setBooleanValue(value);
    }
    void configure(ConfigInteger config, int value) {
        int previous = config.getIntegerValue(); restore.add(() -> config.setIntegerValue(previous));
        config.setIntegerValue(value);
    }
    static Field field(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name); field.setAccessible(true); return field;
    }
    static void set(Class<?> owner, Object instance, String name, Object value) throws Exception {
        field(owner, name).set(instance, value);
    }
    @Override public void close() throws Exception {
        printer.actionHandler.clear();
        for (int i = restore.size() - 1; i >= 0; i--) restore.get(i).run();
        PrinterConfig.PRINTER_HOTBAR_SLOTS.setValueFromString(previousSlots);
        InventoryManager.setHotbarSlots(PrinterConfig.PRINTER_HOTBAR_SLOTS);
        Printer.inventoryManager.reset();
        set(InventoryManager.class, Printer.inventoryManager, "mc", previousInventoryClient);
        set(SchematicWorldHandler.class, SchematicWorldHandler.INSTANCE, "world", previousSchematic);
        set(MinecraftClient.class, null, "instance", previousClient);
    }

    static final class FixtureSchematic extends WorldSchematic {
        Map<BlockPos, BlockState> blocks;
        private FixtureSchematic() { super(null, null, null, null); }
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
    }
    static final class FixtureWorld extends ClientWorld {
        Map<BlockPos, BlockState> blocks;
        WorldBorder border;
        DimensionType dimension;
        private FixtureWorld() { super(null, null, null, null, 0, 0, null, false, 0L, 0); }
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.getDefaultState()); }
        @Override public net.minecraft.fluid.FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public boolean isClient() { return true; }
        @Override public WorldBorder getWorldBorder() { return border; }
        @Override public boolean isInBuildLimit(BlockPos pos) { return pos.getY() >= -64 && pos.getY() < 320; }
        @Override public boolean setBlockState(BlockPos pos, BlockState state, int flags) { blocks.put(pos.toImmutable(), state); return true; }
        @Override public net.minecraft.resource.featuretoggle.FeatureSet getEnabledFeatures() {
            return net.minecraft.resource.featuretoggle.FeatureFlags.VANILLA_FEATURES;
        }
        @Override public boolean doesNotIntersectEntities(Entity except, net.minecraft.util.shape.VoxelShape shape) { return true; }
        @Override public DimensionType getDimension() { return dimension; }
    }
    static final class FixturePlayer extends ClientPlayerEntity {
        PlayerInventory inventory;
        PlayerAbilities abilities;
        ItemStack offhand;
        boolean sneaking;
        double reach;
        ItemCooldownManager cooldowns;
        private FixturePlayer() { super(null, null, null, null, null, false, false); }
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
        @Override public ItemCooldownManager getItemCooldownManager() { return cooldowns; }
        @Override public void swingHand(Hand hand) {}
    }
    static final class FixtureNetwork extends ClientPlayNetworkHandler {
        List<PlayerInput> inputs;
        List<PlayerInteractBlockC2SPacket> blockPackets;
        private FixtureNetwork() { super(null, null, null); }
        @Override public net.minecraft.client.network.PlayerListEntry getPlayerListEntry(java.util.UUID uuid) { return null; }
        @Override public void sendPacket(Packet<?> packet) {
            if (packet instanceof PlayerInputC2SPacket input) inputs.add(input.input());
            if (packet instanceof PlayerInteractBlockC2SPacket block) blockPackets.add(block);
        }
    }
    static final class FixtureInteraction extends ClientPlayerInteractionManager {
        int blockUses;
        private FixtureInteraction() { super(null, null); }
        @Override public void clickSlot(int syncId, int slotId, int button, SlotActionType type, PlayerEntity player) {
            if (type != SlotActionType.SWAP || slotId < 9 || slotId >= 36) throw new AssertionError("Unexpected inventory operation");
            ItemStack old = player.getInventory().main.get(button);
            player.getInventory().main.set(button, player.getInventory().main.get(slotId));
            player.getInventory().main.set(slotId, old);
        }
        @Override public ActionResult interactBlock(ClientPlayerEntity player, Hand hand, BlockHitResult hit) {
            blockUses++;
            return ActionResult.SUCCESS;
        }
    }
}
