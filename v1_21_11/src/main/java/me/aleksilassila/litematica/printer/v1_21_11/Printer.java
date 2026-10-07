package me.aleksilassila.litematica.printer.v1_21_11;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.v1_21_11.actions.Action;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_11.guides.Guide;
import me.aleksilassila.litematica.printer.v1_21_11.guides.Guides;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.PrinterPlacementContext;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.actions.AirPlaceAction;
import me.aleksilassila.litematica.printer.v1_21_11.actions.ActionChain;
import me.aleksilassila.litematica.printer.v1_21_11.actions.PrepareAction;
import me.aleksilassila.litematica.printer.v1_21_11.actions.PrepareLook;
import me.aleksilassila.litematica.printer.v1_21_11.actions.NativePlacementAction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.ObserverBlock;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.item.ItemStack;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class Printer {
    private final List<NativePlacementAction> nativeActions = new ArrayList<>();
    private int scanCursor;
    private int guideCursor;
    private int correctionCooldown;

    public static final Logger logger = LogManager.getLogger("litematica-printer");
    @NotNull
    public final ClientPlayerEntity player;
    MinecraftClient mc = MinecraftClient.getInstance();

    public final ActionHandler actionHandler;

    private final Guides interactionGuides = new Guides();
    public static final InventoryManager inventoryManager = InventoryManager.getInstance();
    public static int inactivityCounter = 0;
    static final LinkedList<BlockTimeout> blockPosTimeout = new LinkedList<>();
    private static final Map<BlockPos, PendingPlacement> pendingPlacements = new HashMap<>();
    private static Object predictionWorld;
    private static final PlacementRateLimiter placementRate = new PlacementRateLimiter();
    int delayCounter = 0;
    @Nullable
    public static Vec2f lastRotation = null;

    public Printer(@NotNull MinecraftClient client, @NotNull ClientPlayerEntity player) {
        this.player = player;
        pendingPlacements.clear();
        blockPosTimeout.clear();
        placementRate.reset();
        inventoryManager.reset();
        MovementHandler.clearRotation();
        this.actionHandler = new ActionHandler(client, player);
    }

    public static boolean canRunActions(MinecraftClient client, ClientPlayerEntity player) {
        return client.player == player && client.world != null && player.isAlive()
                && player.getAbilities().allowModifyWorld && !player.isUsingItem()
                && (LitematicaMixinMod.PRINT_MODE.getBooleanValue() || LitematicaMixinMod.PRINT.getKeybind().isPressed())
                && (!PrinterConfig.PRINTER_DISABLE_IN_GUIS.getBooleanValue() || client.currentScreen == null)
                && (!PrinterConfig.STOP_ON_MOVEMENT.getBooleanValue() || player.getVelocity().length() <= 0.1);
    }

    public static boolean canRunLegacyActions(MinecraftClient client, ClientPlayerEntity player) {
        if (!canRunActions(client, player)) return false;
        if (!PrinterConfig.PRINTER_NATIVE_MOVING.getBooleanValue()
                && !PrinterConfig.PRINTER_NATIVE_OMNI.getBooleanValue()) return true;
        return player.getVelocity().horizontalLengthSquared() < 0.0001D
                && !client.options.forwardKey.isPressed() && !client.options.backKey.isPressed()
                && !client.options.leftKey.isPressed() && !client.options.rightKey.isPressed();
    }

    public void onMiddleClick() {
        if (mc.world == null || mc.player == null) return;
        BlockPos pos = RayTraceUtils.getSchematicWorldTraceIfClosest(mc.world, mc.player, 6.0);

        if (pos != null) {
            WorldSchematic world = SchematicWorldHandler.getSchematicWorld();
            if (world == null) return;
            inventoryManager.pickSlot(world, player, pos);
        }
    }

    public boolean onGameTick() {
        MovementHandler.beginPrinterTick();
        nativeActions.clear();
        WorldSchematic worldSchematic = SchematicWorldHandler.getSchematicWorld();
        tickPendingPlacements();
        blockPosTimeout.forEach((entry) -> entry.timer--);
        blockPosTimeout.removeIf((entry) -> entry.timer <= 0);

        // If the inactivityCounter is greater than the inactive snap back value, then set the lastRotation to the current rotation
        // This is used to snap back to the last rotation when the player is inactive
        inactivityCounter++;

        if (correctionCooldown > 0) {
            correctionCooldown--;
            return false;
        }

        if (worldSchematic == null) return false;

        if (PrinterConfig.TICK_DELAY.getIntegerValue() != 0 && delayCounter < PrinterConfig.TICK_DELAY.getIntegerValue()) {
            delayCounter++;
            return false;
        } else {
            delayCounter = 0;
        }

        if (!LitematicaMixinMod.PRINT_MODE.getBooleanValue() && !LitematicaMixinMod.PRINT.getKeybind().isPressed())
            return false;

        PlayerAbilities abilities = player.getAbilities();
        if (!abilities.allowModifyWorld)
            return false;

        if (PrinterConfig.STOP_ON_MOVEMENT.getBooleanValue() && player.getVelocity().length() > 0.1)
            return false; // Stop if the player is moving
        if (PrinterConfig.PRINTER_DISABLE_IN_GUIS.getBooleanValue()) {
            if (mc.currentScreen != null) return false;
        }
        if (!actionHandler.acceptsActions() || !canAcquirePlacementPacket() || player.isUsingItem()) return false;

        List<BlockPos> positions = getReachablePositions();

        positions = positions.stream().filter(pos -> !pendingPlacements.containsKey(pos)
                && blockPosTimeout.stream().noneMatch(entry -> entry.pos.equals(pos))).toList();

        boolean moving = player.input.getMovementInput().lengthSquared() > 0.0001F
                || player.getVelocity().horizontalLengthSquared() > 0.0001D
                || mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
                || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed();
        boolean nativeEnabled = PrinterConfig.PRINTER_NATIVE_MOVING.getBooleanValue()
                || PrinterConfig.PRINTER_NATIVE_OMNI.getBooleanValue();
        if (nativeEnabled && (!moving || PrinterConfig.PRINTER_NATIVE_MOVING.getBooleanValue())) {
            NativePlacementSolver.Solution batchSolution = null;
            ItemStack batchStack = null;
            Set<BlockPos> chestNeighborsInBatch = new java.util.HashSet<>();
            Set<BlockPos> playerOccupied = Set.copyOf(getBlocksPlayerOccupied());
            List<BlockPos> candidates = positions.stream().filter(pos -> isNativePlacementCandidate(
                    new SchematicBlockState(player.getEntityWorld(), worldSchematic, pos), playerOccupied)).toList();
            int attempts = Math.min(candidates.size(), 24);
            int start = candidates.isEmpty() ? 0 : Math.floorMod(scanCursor, candidates.size());
            long solveDeadline = System.nanoTime() + 3_000_000L;
            int examined = 0;
            for (int i = 0; i < attempts; i++) {
                if (i > 0 && System.nanoTime() >= solveDeadline) break;
                examined++;
                BlockPos position = candidates.get((start + i) % candidates.size());
                BlockState finalState = worldSchematic.getBlockState(position);
                // Adjacent chests change one another's type. The second half
                // must be solved after the first server update, not against
                // the same empty client world in this batch.
                if (finalState.getBlock() instanceof ChestBlock && chestNeighborsInBatch.contains(position)) continue;
                BlockState desired = NativePlacementSolver.placementTarget(mc.world, worldSchematic, position, finalState);
                ItemStack required = MaterialCache.getInstance().getRequiredBuildItemForState(finalState, worldSchematic, position);
                if (required.isEmpty() || !inventoryManager.hasMaterial(required)) continue;
                if (batchStack != null && !ItemStack.areItemsAndComponentsEqual(batchStack, required)) continue;
                boolean allowAir = PrinterConfig.PRINTER_NATIVE_OMNI.getBooleanValue();
                NativePlacementSolver.Solution solution;
                if (batchSolution != null) {
                    // Ordinary and directional targets may share a batch if
                    // they work at the same already-selected server angles.
                    solution = PrinterConfig.PRINTER_GRIM_ROTATION.getBooleanValue()
                            ? NativePlacementSolver.solveMovingAtRotation(position, desired, required,
                                    getEffectivePrintingRange(), allowAir, player.isSprinting(),
                                    batchSolution.yaw(), batchSolution.pitch())
                            : NativePlacementSolver.solveAnyRotation(position, desired, required, getEffectivePrintingRange());
                } else {
                    // The solver checks rotation-independent placement itself;
                    // do not repeat that full hit/angle scan for every target.
                    if (PrinterConfig.PRINTER_GRIM_ROTATION.getBooleanValue()) {
                        solution = moving
                                ? NativePlacementSolver.solveMoving(position, desired, required,
                                        getEffectivePrintingRange(), allowAir, player.isSprinting())
                                : NativePlacementSolver.solveStationary(position, desired, required,
                                        getEffectivePrintingRange(), allowAir);
                    } else {
                        solution = allowAir ? NativePlacementSolver.solveAnyRotation(
                                position, desired, required, getEffectivePrintingRange()) : null;
                    }
                }
                if (solution == null) continue;
                if (!solution.anyRotation() && !PrinterConfig.PRINTER_GRIM_ROTATION.getBooleanValue()) continue;
                if (batchSolution == null) {
                    // A refill stages the stack for a later tick. No old hit or
                    // rotation is retained while waiting for inventory changes.
                    if (!inventoryManager.select(required)) return false;
                    if (!solution.anyRotation() && !MovementHandler.requestRotation(solution.yaw(), solution.pitch())) continue;
                    batchSolution = solution;
                    batchStack = required;
                    actionHandler.clearLookAction();
                }
                nativeActions.add(new NativePlacementAction(position, desired, required, solution));
                if (finalState.getBlock() instanceof ChestBlock) {
                    for (Direction side : Direction.Type.HORIZONTAL) chestNeighborsInBatch.add(position.offset(side));
                }
                if (nativeActions.size() >= PrinterConfig.NATIVE_BURST.getIntegerValue()) break;
            }
            scanCursor = start + examined;
            if (!nativeActions.isEmpty()) return true;
        }
        // The older guide chain uses discrete view rotations. Do not fall back
        // to it mid-stride after the native movement solver deliberately skipped
        // an unsafe direction. Special interactions can resume once stationary.
        if (nativeEnabled && moving) return false;
        List<Action> batchedActions = new ArrayList<>();
        AirplaceBatchKey batchKey = null;
        int batchedChains = 0;

        long guideDeadline = System.nanoTime() + 3_000_000L;
        int guideStart = positions.isEmpty() ? 0 : Math.floorMod(guideCursor, positions.size());
        findBlock:
        for (int index = 0; index < positions.size(); index++) {
            if (index > 0 && System.nanoTime() >= guideDeadline) break;
            guideCursor = guideStart + index + 1;
            BlockPos position = positions.get((guideStart + index) % positions.size());
            SchematicBlockState state = new SchematicBlockState(player.getEntityWorld(), worldSchematic, position);
            if (state.targetState.equals(state.currentState) || state.targetState.isAir()) continue;
            if (pendingPlacements.containsKey(position)) continue;
            // Do not defeat a skipped native placement by running the old
            // arbitrary-rotation solver on it again in the same tick.
            if (nativeEnabled && state.currentState.isReplaceable()
                    && state.targetState.getBlock().asItem() instanceof net.minecraft.item.BlockItem
                    && state.currentState.getBlock() != state.targetState.getBlock()
                    && !requiresLegacyGuide(state.targetState)) continue;

            Guide[] guides = interactionGuides.getInteractionGuides(state);

            for (Guide guide : guides) {
                if (guide.canExecute(player)) {
                    List<Action> actions = guide.execute(player);
                    AirplaceBatchKey candidateKey = getAirplaceBatchKey(actions);

                    // Non-airplace interactions may require a different
                    // action ordering (shift, look, then use). Keep the
                    // original one-chain behaviour for those interactions.
                    if (candidateKey == null) {
                        if (batchedActions.isEmpty()) {
                            actionHandler.addActions(actions.toArray(Action[]::new));
                            return true;
                        }
                        break findBlock;
                    }

                    if (batchKey == null) {
                        batchKey = candidateKey;
                    } else if (!batchKey.compatibleWith(candidateKey)) {
                        break findBlock;
                    }

                    batchedActions.addAll(actions);
                    batchedChains++;
                    if (batchedChains >= PrinterConfig.NATIVE_BURST.getIntegerValue()) {
                        break findBlock;
                    }

                    // A position can be handled by multiple specialised
                    // guides; use only the first executable guide as before.
                    break;
                }
                if (guide.skipOtherGuides()) continue findBlock;
            }
        }

        if (!batchedActions.isEmpty()) {
            actionHandler.addActions(batchedActions.toArray(Action[]::new));
            return true;
        }

        return false;
    }

    private boolean isNativePlacementCandidate(SchematicBlockState state, java.util.Set<BlockPos> playerOccupied) {
        if (state.targetState == null || state.targetState.isAir()) return false;
        if (state.currentState.equals(state.targetState)) return false;
        boolean slabCompletion = NativePlacementSolver.canCompleteSlab(state.currentState, state.targetState);
        if (state.currentState.getBlock() == state.targetState.getBlock() && !slabCompletion) return false;
        if (!(state.targetState.getBlock().asItem() instanceof net.minecraft.item.BlockItem)) return false;
        if (playerOccupied.contains(state.blockPos)) return false;
        if (requiresLegacyGuide(state.targetState)) return false;
        if (state.targetState.getBlock() instanceof ChestBlock) {
            for (Direction side : Direction.Type.HORIZONTAL) {
                if (hasPendingPlacement(state.blockPos.offset(side))) return false;
            }
        }
        if (state.targetState.getBlock() instanceof ObserverBlock
                && PrinterConfig.PRINTER_PLACE_OBSERVERS_LAST.getBooleanValue()) {
            BlockPos observed = state.blockPos.offset(state.targetState.get(net.minecraft.state.property.Properties.FACING));
            WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
            if (schematic == null || (!schematic.getBlockState(observed).isAir()
                    && mc.world.getBlockState(observed).isAir())) return false;
        }
        if (state.currentState.getBlock() instanceof FluidBlock
                && !LitematicaMixinMod.REPLACE_FLUIDS_SOURCE_BLOCKS.getBooleanValue()) return false;
        // Never replace a solid block in the native path.  The guide path is
        // retained for replacement/interaction guides that know the block's
        // update order.
        return state.currentState.isReplaceable() || slabCompletion;
    }

    /**
     * Keep blocks whose placement is neighbor/order/state sensitive on the
     * specialised guide path.  The native solver is deliberately limited to
     * ordinary BlockItem placement so rail and gravity rules
     * cannot be bypassed by a permissive predicted state.
     */
    private static boolean requiresLegacyGuide(net.minecraft.block.BlockState target) {
        if (target.getBlock() instanceof AbstractRailBlock
                || target.getBlock() instanceof FallingBlock
                || target.getBlock() instanceof FluidBlock) return true;
        return false;
    }

    /**
     * Returns a key only for the simple airplace action chain emitted by
     * PlacementGuide.  Keeping the key narrow prevents batches from mixing
     * strict interactions or server rotations with incompatible contexts.
     */
    @Nullable
    private AirplaceBatchKey getAirplaceBatchKey(List<Action> actions) {
        if (actions.size() != 1 || !(actions.get(0) instanceof ActionChain chain)) {
            return null;
        }

        PrinterPlacementContext context = null;
        boolean airplace = false;
        List<Action> chainActions = new ArrayList<>(chain.getActionsCurrentTick());
        chainActions.addAll(chain.getActionsNextTick());
        for (Action action : chainActions) {
            if (action instanceof AirPlaceAction place) {
                context = place.context;
                airplace = true;
            } else if (action instanceof PrepareAction prepare) {
                if (context == null) context = prepare.context;
            } else if (action instanceof PrepareLook look) {
                if (context == null) context = look.context;
            } else {
                return null;
            }
        }

        if (!airplace || context == null || !context.isAirPlace) {
            return null;
        }

        return new AirplaceBatchKey(context.getStack().getItem(), context.lookDirection);
    }

    private record AirplaceBatchKey(net.minecraft.item.Item item, net.minecraft.util.math.Direction lookDirection) {
        private boolean compatibleWith(AirplaceBatchKey other) {
            return this.item == other.item && this.lookDirection == other.lookDirection;
        }
    }

    private List<BlockPos> getBlocksPlayerOccupied() {
        ArrayList<BlockPos> positions = new ArrayList<>();
        BlockPos playerPos = player.getBlockPos();
        Vec3d playerEntityPos = player.getEntityPos();
        int blocksHeightOccupied = (int) Math.ceil(playerEntityPos.y + player.getHeight() - playerPos.getY());

        positions.add(player.getBlockPos());
        positions.add(player.getBlockPos().up());
        if (blocksHeightOccupied > 2) {
            positions.add(playerPos.up(2));
        }
        if (Math.floor(playerEntityPos.x + player.getWidth() / 2) > playerPos.getX()) {
            for (int i = 0; i < blocksHeightOccupied; i++) {
                positions.add(playerPos.up(i).east());
            }
        }
        if ((playerEntityPos.x - player.getWidth() / 2) < playerPos.getX()) {
            for (int i = 0; i < blocksHeightOccupied; i++) {
                positions.add(playerPos.up(i).west());
            }
        }
        if (Math.floor(playerEntityPos.z + player.getWidth() / 2) > playerPos.getZ()) {
            for (int i = 0; i < blocksHeightOccupied; i++) {
                positions.add(playerPos.up(i).south());
            }
        }
        if ((playerEntityPos.z - player.getWidth() / 2) < playerPos.getZ()) {
            for (int i = 0; i < blocksHeightOccupied; i++) {
                positions.add(playerPos.up(i).north());
            }
        }
        return positions.stream().distinct().toList();
    }

    private List<BlockPos> getReachablePositions() {
        double effectiveRange = getEffectivePrintingRange();
        double maxReachSquared = MathHelper.square(effectiveRange);
        Vec3d eye = player.getEyePos();
        ArrayList<BlockPos> positions = new ArrayList<>();

        // Reach is measured from the eyes. A cube centred at the feet omitted
        // the highest reachable row even though those hits passed vanilla reach.
        for (int y = MathHelper.floor(eye.y - effectiveRange) - 1; y <= MathHelper.floor(eye.y + effectiveRange); y++) {
            for (int x = MathHelper.floor(eye.x - effectiveRange) - 1; x <= MathHelper.floor(eye.x + effectiveRange); x++) {
                for (int z = MathHelper.floor(eye.z - effectiveRange) - 1; z <= MathHelper.floor(eye.z + effectiveRange); z++) {
                    BlockPos blockPos = new BlockPos(x, y, z);

                    if (!DataManager.getRenderLayerRange().isPositionWithinRange(blockPos)) continue;
                    if (distanceToBlock(eye, blockPos) > maxReachSquared) {
                        continue;
                    }

                    positions.add(blockPos);
                }
            }
        }

        return positions.stream()
                .sorted((a, b) -> {
                    double aDistance = this.player.getEntityPos().squaredDistanceTo(Vec3d.ofCenter(a));
                    double bDistance = this.player.getEntityPos().squaredDistanceTo(Vec3d.ofCenter(b));
                    return Double.compare(aDistance, bDistance);
                }).toList();
    }

    /** Keep candidate generation inside the native interaction range. */
    private double getEffectivePrintingRange() {
        return Math.min(LitematicaMixinMod.PRINTING_RANGE.getDoubleValue(),
                player.getBlockInteractionRange());
    }

    /** Uses the nearest point on the block AABB, matching vanilla interaction range. */
    private static double distanceToBlock(Vec3d eye, BlockPos pos) {
        double x = Math.max(pos.getX(), Math.min(eye.x, pos.getX() + 1.0D));
        double y = Math.max(pos.getY(), Math.min(eye.y, pos.getY() + 1.0D));
        double z = Math.max(pos.getZ(), Math.min(eye.z, pos.getZ() + 1.0D));
        return eye.squaredDistanceTo(x, y, z);
    }

    public static void addTimeout(BlockPos pos) {
        blockPosTimeout.add(new BlockTimeout(pos, PrinterConfig.BLOCK_TIMEOUT.getIntegerValue()));
    }

    /** Both native and guide interactions share the same paced budget. */
    public static boolean tryAcquirePlacementPacket() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && placementRate.tryAcquire(System.nanoTime(),
                client.player.age, PrinterConfig.NATIVE_BURST.getIntegerValue());
    }

    public static boolean canAcquirePlacementPacket() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && placementRate.available(System.nanoTime(),
                client.player.age, PrinterConfig.NATIVE_BURST.getIntegerValue());
    }

    /** Runs only after this tick's vanilla movement/rotation packet. */
    public void flushNativePlacements() {
        if (nativeActions.isEmpty()) return;
        List<NativePlacementAction> batch = List.copyOf(nativeActions);
        nativeActions.clear();
        if (!canRunActions(mc, player) || mc.getCameraEntity() != player
                || mc.world != predictionWorld || player.hasVehicle()) return;
        for (NativePlacementAction action : batch) {
            if (!canAcquirePlacementPacket()) break;
            // One target becoming obstructed during movement must not discard
            // other independent targets that still fit this batch's rotation.
            if (action.send(mc, player)) inactivityCounter = 0;
        }
    }

    /** Track an attempt without inserting an unconfirmed block into the world. */
    public static void trackPlacement(BlockPos pos, BlockState desired, ItemStack stack, int sequence) {
        MinecraftClient client = MinecraftClient.getInstance();
        int latency = 0;
        if (client.getNetworkHandler() != null && client.player != null) {
            var entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
            if (entry != null) latency = entry.getLatency();
        }
        // ACK only acknowledges processing, not success. Wait for block state
        // packets, with a bounded retry if no authoritative state arrives.
        int waitTicks = MathHelper.clamp((latency * 2 + 750) / 50, 15, 60);
        pendingPlacements.put(pos.toImmutable(), new PendingPlacement(desired, stack.copyWithCount(1),
                sequence, waitTicks));
    }

    public static boolean hasPendingPlacement(BlockPos pos) {
        return pendingPlacements.containsKey(pos);
    }

    public static int pendingItemCount(ItemStack stack) {
        return (int) pendingPlacements.values().stream()
                .filter(pending -> ItemStack.areItemsAndComponentsEqual(stack, pending.stack)).count();
    }

    /** Invoked on the client thread after vanilla applies a block update. */
    public static void confirmNativePlacement(BlockPos pos, BlockState actual) {
        PendingPlacement pending = pendingPlacements.remove(pos);
        if (pending != null && PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) {
            logger.info("Native placement {} at {} (sequence {})",
                    NativePlacementSolver.statesMatch(pending.desired, actual) ? "confirmed" : "rejected",
                    pos, pending.sequence);
        }
    }

    private static void tickPendingPlacements() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (predictionWorld != client.world) {
            pendingPlacements.clear();
            blockPosTimeout.clear();
            placementRate.reset();
            inventoryManager.reset();
            predictionWorld = client.world;
        }
        pendingPlacements.entrySet().removeIf(entry -> --entry.getValue().ticks <= 0);
    }

    public void onServerCorrection() {
        nativeActions.clear();
        actionHandler.clear();
        MovementHandler.clearRotation();
        correctionCooldown = 5;
    }

    private static final class PendingPlacement {
        private final BlockState desired;
        private final ItemStack stack;
        private final int sequence;
        private int ticks;

        private PendingPlacement(BlockState desired, ItemStack stack, int sequence, int ticks) {
            this.desired = desired;
            this.stack = stack;
            this.sequence = sequence;
            this.ticks = ticks;
        }
    }

    public void rotate(float yaw, float pitch) {
        if (FreeLook.nativeMode()) {
            MovementHandler.grimRotate(player, yaw, pitch);
            return;
        }
        LitematicaMixinMod.freeLook.ticksSinceLastRotation = 0;
        this.player.setYaw(yaw);
        this.player.setPitch(pitch);
    }

    public static class BlockTimeout {
        int timer = 0;
        BlockPos pos;

        public BlockTimeout(BlockPos pos, int timer) {
            this.pos = pos;
            this.timer = timer;
        }
    }
}
