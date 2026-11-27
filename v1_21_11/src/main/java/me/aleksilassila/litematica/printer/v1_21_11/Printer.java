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
import me.aleksilassila.litematica.printer.v1_21_11.guides.interaction.LogStrippingGuide;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.PrinterPlacementContext;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.actions.AirPlaceAction;
import me.aleksilassila.litematica.printer.v1_21_11.actions.ActionChain;
import me.aleksilassila.litematica.printer.v1_21_11.actions.PrepareAction;
import me.aleksilassila.litematica.printer.v1_21_11.actions.PrepareLook;
import me.aleksilassila.litematica.printer.v1_21_11.actions.NativePlacementAction;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.block.AbstractRailBlock;
import net.minecraft.block.BedBlock;
import net.minecraft.block.DoorBlock;
import net.minecraft.block.FallingBlock;
import net.minecraft.block.FluidBlock;
import net.minecraft.block.ObserverBlock;
import net.minecraft.block.PaleMossCarpetBlock;
import net.minecraft.block.PitcherCropBlock;
import net.minecraft.block.TallPlantBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.block.enums.WallShape;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerAbilities;
import net.minecraft.item.ItemStack;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.item.BlockItem;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
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
    private final List<Action> nativeActions = new ArrayList<>();
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
    // One BlockItem click may publish several cells. Each cell's world update arrives independently.
    private static final Map<BlockPos, BlockChange> pendingBlockChanges = new HashMap<>();
    private static int acknowledgedSequence = -1;
    private static final Map<Integer, SlotStock> pendingStock = new HashMap<>();
    private static Object predictionWorld;
    private static final PlacementRateLimiter placementRate = new PlacementRateLimiter();
    int delayCounter = 0;
    @Nullable
    public static Vec2f lastRotation = null;

    public Printer(@NotNull MinecraftClient client, @NotNull ClientPlayerEntity player) {
        this.player = player;
        pendingPlacements.clear();
        pendingBlockChanges.clear();
        acknowledgedSequence = -1;
        resetStock();
        blockPosTimeout.clear();
        placementRate.reset();
        inventoryManager.onPrinterContextChanged();
        MovementHandler.clearRotation();
        this.actionHandler = new ActionHandler(client, player);
    }

    private static boolean printingRequested() {
        var printKey = LitematicaMixinMod.PRINT.getKeybind();
        // MaLiLib also treats an empty modifier binding as held.
        return LitematicaMixinMod.PRINT_MODE.getBooleanValue()
                || (!printKey.getKeys().isEmpty() && printKey.isKeybindHeld());
    }

    public static boolean canRunActions(MinecraftClient client, ClientPlayerEntity player) {
        return client.player == player && client.world != null && player.isAlive()
                && player.getAbilities().allowModifyWorld && !player.isUsingItem()
                && printingRequested()
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

        if (!printingRequested())
            return false;

        PlayerAbilities abilities = player.getAbilities();
        if (!abilities.allowModifyWorld)
            return false;

        if (PrinterConfig.STOP_ON_MOVEMENT.getBooleanValue() && player.getVelocity().length() > 0.1)
            return false; // Stop if the player is moving
        if (PrinterConfig.PRINTER_DISABLE_IN_GUIS.getBooleanValue()) {
            if (mc.currentScreen != null) return false;
        }
        if (!actionHandler.acceptsActions() || player.isUsingItem()) return false;
        boolean placementBudget = canAcquirePlacementPacket();
        if (!placementBudget && !inventoryManager.canPrefetch()) return false;

        List<BlockPos> positions = getReachablePositions();

        positions = positions.stream().filter(pos -> !hasPendingPlacement(pos)
                && blockPosTimeout.stream().noneMatch(entry -> entry.pos.equals(pos))).toList();

        boolean moving = player.input.getMovementInput().lengthSquared() > 0.0001F
                || player.getVelocity().horizontalLengthSquared() > 0.0001D
                || mc.options.forwardKey.isPressed() || mc.options.backKey.isPressed()
                || mc.options.leftKey.isPressed() || mc.options.rightKey.isPressed();
        boolean nativeEnabled = PrinterConfig.PRINTER_NATIVE_MOVING.getBooleanValue()
                || PrinterConfig.PRINTER_NATIVE_OMNI.getBooleanValue();
        if (nativeEnabled && (!moving || PrinterConfig.PRINTER_NATIVE_MOVING.getBooleanValue())) {
            if (planNativePlacements(worldSchematic, positions, moving, placementBudget)) return true;
        }
        if (!placementBudget) return false;
        if (moving && nativeEnabled && !PrinterConfig.PRINTER_NATIVE_MOVING.getBooleanValue()) return false;
        List<Action> batchedActions = new ArrayList<>();
        AirplaceBatchKey batchKey = null;
        int batchedChains = 0;
        List<Action> deferredGuide = List.of();
        ItemStack deferredMaterial = null;
        List<SchematicBlockState> deferredMoving = new ArrayList<>();
        List<ItemStack> readyGuideMaterials = new ArrayList<>();

        long guideDeadline = System.nanoTime() + 3_000_000L;
        int guideStart = positions.isEmpty() ? 0 : Math.floorMod(guideCursor, positions.size());
        findBlock:
        for (int index = 0; index < positions.size(); index++) {
            if (index > 0 && System.nanoTime() >= guideDeadline) break;
            guideCursor = guideStart + index + 1;
            BlockPos position = positions.get((guideStart + index) % positions.size());
            SchematicBlockState state = new SchematicBlockState(player.getEntityWorld(), worldSchematic, position);
            if (state.targetState.equals(state.currentState) || state.targetState.isAir()) continue;
            if (hasPendingPlacement(position)) continue;
            if (moving && nativeEnabled) {
                ItemStack material = MovingPrinterAction.requiredMaterial(state, player);
                if (material == null) continue;
                if (!inventoryManager.hasReadyHotbarMaterial(material)) {
                    deferredMoving.add(state);
                    continue;
                }
                MovingPrinterAction action = MovingPrinterAction.prepare(state, player, getEffectivePrintingRange());
                if (action == null) continue;
                actionHandler.clearLookAction();
                nativeActions.add(action);
                return true;
            }
            // Do not defeat a skipped native placement by running the old
            // arbitrary-rotation solver on it again in the same tick.
            if (nativeEnabled && state.currentState.isReplaceable()
                    && state.targetState.getBlock().asItem() instanceof net.minecraft.item.BlockItem
                    && state.currentState.getBlock() != state.targetState.getBlock()
                    && !requiresLegacyGuide(state.targetState)) continue;

            if (state.currentState.isReplaceable()) {
                state = state.withTarget(LogStrippingGuide.placementTarget(player, state.targetState));
            }
            Guide[] guides = interactionGuides.getInteractionGuides(state);

            for (Guide guide : guides) {
                if (guide.canExecute(player)) {
                    List<Action> actions = guide.execute(player);
                    ItemStack material = guide.requiredMaterial(player);
                    if (!actions.isEmpty() && material != null && !inventoryManager.hasReadyHotbarMaterial(material)) {
                        if (deferredGuide.isEmpty()) {
                            deferredGuide = actions;
                            deferredMaterial = material;
                        }
                        continue findBlock;
                    }
                    AirplaceBatchKey candidateKey = getAirplaceBatchKey(actions);

                    // Non-airplace interactions may require a different
                    // action ordering (shift, look, then use). Keep the
                    // original one-chain behaviour for those interactions.
                    if (candidateKey == null) {
                        if (batchedActions.isEmpty()) {
                            if (!actions.isEmpty() && deferredMaterial != null && !player.getAbilities().creativeMode) {
                                inventoryManager.prefetch(deferredMaterial, material == null ? List.of() : List.of(material));
                            }
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
                    if (material != null) readyGuideMaterials.add(material);
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
            if (deferredMaterial != null && !player.getAbilities().creativeMode) {
                inventoryManager.prefetch(deferredMaterial, readyGuideMaterials);
            }
            actionHandler.addActions(batchedActions.toArray(Action[]::new));
            return true;
        }

        if (!deferredGuide.isEmpty() && inventoryManager.canStartRefill()) {
            actionHandler.addActions(deferredGuide.toArray(Action[]::new));
            return true;
        }
        if (!deferredMoving.isEmpty() && inventoryManager.canStartRefill()) {
            for (SchematicBlockState state : deferredMoving) {
                guideCursor = positions.indexOf(state.blockPos) + 1;
                MovingPrinterAction action = MovingPrinterAction.prepare(state, player, getEffectivePrintingRange());
                if (action != null) {
                    actionHandler.clearLookAction();
                    nativeActions.add(action);
                    return true;
                }
                if (!inventoryManager.canStartRefill() || System.nanoTime() >= guideDeadline) break;
            }
        }

        return false;
    }

    private boolean planNativePlacements(WorldSchematic schematic, List<BlockPos> positions, boolean moving, boolean placementBudget) {
        Set<BlockPos> occupied = Set.copyOf(getBlocksPlayerOccupied());
        List<BlockPos> candidates = positions.stream().filter(pos -> isNativePlacementCandidate(
                new SchematicBlockState(player.getEntityWorld(), schematic, pos), occupied)).toList();
        if (candidates.isEmpty()) return false;

        List<NativeTarget> ready = new ArrayList<>();
        List<NativeTarget> refill = new ArrayList<>();
        int start = Math.floorMod(scanCursor, candidates.size());
        int attempts = Math.min(candidates.size(), 24);
        for (int offset = 0; offset < attempts; offset++) {
            BlockPos pos = candidates.get((start + offset) % candidates.size());
            BlockState target = LogStrippingGuide.placementTarget(player, schematic.getBlockState(pos));
            ItemStack stack = MaterialCache.getInstance().getRequiredBuildItemForState(
                    PlacementStages.initialTarget(target), schematic, pos);
            if (stack.isEmpty()) continue;
            if (inventoryManager.hasReadyNativeMaterial(stack)) {
                ready.add(new NativeTarget(pos, target, stack, offset));
            } else if (inventoryManager.hasMaterial(stack)) {
                refill.add(new NativeTarget(pos, target, stack, offset));
            }
        }

        long deadline = System.nanoTime() + 3_000_000L;
        NativePlacementSolver.Solution batchSolution = null;
        List<ItemStack> keep = new ArrayList<>();
        Set<BlockPos> chestNeighbors = new java.util.HashSet<>();
        int lastExamined = -1;
        for (NativeTarget target : ready) {
            if (!placementBudget) break;
            if (lastExamined >= 0 && System.nanoTime() >= deadline) break;
            lastExamined = target.offset();
            // Adjacent chests must be solved after the first server update.
            if (target.state().getBlock() instanceof ChestBlock && chestNeighbors.contains(target.pos())) continue;
            BlockState desired = NativePlacementSolver.placementTarget(mc.world, schematic, target.pos(), target.state());
            NativePlacementSolver.Solution solution = solveNative(target, desired, moving, batchSolution);
            if (solution == null || dependsOnPendingBlock(target.pos(), desired, target.stack(), solution.hit())) continue;
            if (batchSolution == null) {
                if (!inventoryManager.selectReadyNativeMaterial(target.stack()) || !prepareNativeRotation(solution)) continue;
                batchSolution = solution;
                actionHandler.clearLookAction();
            }
            addNativePlacement(target, desired, solution);
            keep.add(target.stack());
            if (target.state().getBlock() instanceof ChestBlock) {
                for (Direction side : Direction.Type.HORIZONTAL) chestNeighbors.add(target.pos().offset(side));
            }
            if (nativeActions.size() >= PrinterConfig.NATIVE_BURST.getIntegerValue()) break;
        }

        scanCursor = start + (lastExamined >= 0 ? lastExamined + 1 : attempts);
        boolean hasBatch = !nativeActions.isEmpty();
        if (hasBatch && !inventoryManager.canPrefetch()) return true;
        if (!hasBatch && !inventoryManager.canStartRefill()) return false;
        if (!placementBudget) ready.forEach(target -> keep.add(target.stack()));
        List<ItemStack> wanted = new ArrayList<>(3);
        boolean triedRefill = false;
        for (NativeTarget target : refill) {
            if (wanted.stream().anyMatch(stack -> ItemStack.areItemsAndComponentsEqual(stack, target.stack()))) continue;
            if (triedRefill && System.nanoTime() >= deadline) break;
            triedRefill = true;
            BlockState desired = NativePlacementSolver.placementTarget(mc.world, schematic, target.pos(), target.state());
            NativePlacementSolver.Solution solution = solveNative(target, desired, moving, null);
            if (solution == null) continue;
            if (player.getAbilities().creativeMode) {
                if (placementBudget && !hasBatch && inventoryManager.select(target.stack()) && prepareNativeRotation(solution)) {
                    actionHandler.clearLookAction();
                    addNativePlacement(target, desired, solution);
                }
                break;
            }
            int demand = (int) refill.stream().filter(other -> ItemStack.areItemsAndComponentsEqual(target.stack(), other.stack())).count();
            wanted.add(target.stack().copyWithCount(demand));
            if (wanted.size() == 3) break;
        }
        inventoryManager.stageMaterials(wanted, keep, !hasBatch && placementBudget);
        return !nativeActions.isEmpty();
    }

    @Nullable
    private NativePlacementSolver.Solution solveNative(NativeTarget target, BlockState desired, boolean moving,
                                                       @Nullable NativePlacementSolver.Solution batch) {
        double reach = getEffectivePrintingRange();
        boolean allowAir = PrinterConfig.PRINTER_NATIVE_OMNI.getBooleanValue();
        if (!PrinterConfig.PRINTER_GRIM_ROTATION.getBooleanValue()) {
            return allowAir ? NativePlacementSolver.solveAnyRotation(target.pos(), desired, target.stack(), reach) : null;
        }
        if (batch != null) {
            return NativePlacementSolver.solveMovingAtRotation(target.pos(), desired, target.stack(), reach, allowAir,
                    player.isSprinting(), batch.yaw(), batch.pitch());
        }
        return moving ? NativePlacementSolver.solveMoving(target.pos(), desired, target.stack(), reach, allowAir, player.isSprinting())
                : NativePlacementSolver.solveStationary(target.pos(), desired, target.stack(), reach, allowAir);
    }

    private boolean prepareNativeRotation(NativePlacementSolver.Solution solution) {
        return solution.anyRotation() || (PrinterConfig.PRINTER_GRIM_ROTATION.getBooleanValue()
                && MovementHandler.requestRotation(solution.yaw(), solution.pitch()));
    }

    private void addNativePlacement(NativeTarget target, BlockState desired, NativePlacementSolver.Solution solution) {
        BlockState actionTarget = NativeAttachment.hasFaces(desired) ? solution.predicted() : desired;
        nativeActions.add(new NativePlacementAction(target.pos(), actionTarget, target.stack(), solution, true));
    }

    private record NativeTarget(BlockPos pos, BlockState state, ItemStack stack, int offset) {}

    private boolean isNativePlacementCandidate(SchematicBlockState state, java.util.Set<BlockPos> playerOccupied) {
        if (state.targetState == null || state.targetState.isAir()) return false;
        if (state.currentState.equals(state.targetState)) return false;
        boolean addingToBlock = NativePlacementSolver.canCompleteSlab(state.currentState, state.targetState)
                || NativePlacementSolver.canAddToStack(state.currentState, state.targetState)
                || NativePlacementSolver.canAddFaces(state.currentState, state.targetState);
        if (state.currentState.getBlock() == state.targetState.getBlock() && !addingToBlock) return false;
        if (!(PlacementStages.initialTarget(state.targetState).getBlock().asItem() instanceof net.minecraft.item.BlockItem)) return false;
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
        if (!BlockHelper.canReplaceFluid(state.currentState)) return false;
        // Never replace a solid block in the native path.  The guide path is
        // retained for replacement/interaction guides that know the block's
        // update order.
        return state.currentState.isReplaceable() || addingToBlock;
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
                client.player.age, PrinterConfig.NATIVE_BURST.getIntegerValue(),
                PrinterConfig.INTERACTION_WINDOW_MS.getIntegerValue(), PrinterConfig.INTERACTION_WINDOW_LIMIT.getIntegerValue());
    }

    public static boolean canAcquirePlacementPacket() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client.player != null && placementRate.available(System.nanoTime(),
                client.player.age, PrinterConfig.NATIVE_BURST.getIntegerValue(),
                PrinterConfig.INTERACTION_WINDOW_MS.getIntegerValue(), PrinterConfig.INTERACTION_WINDOW_LIMIT.getIntegerValue());
    }

    /** Runs only after this tick's vanilla movement/rotation packet. */
    public void flushNativePlacements() {
        if (nativeActions.isEmpty()) return;
        List<Action> batch = List.copyOf(nativeActions);
        nativeActions.clear();
        if (!canRunActions(mc, player) || mc.getCameraEntity() != player
                || mc.world != predictionWorld || player.hasVehicle()) return;
        for (Action action : batch) {
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
        PendingPlacement pending = new PendingPlacement(desired, stack.copyWithCount(1), sequence, waitTicks);
        pendingPlacements.put(pos.toImmutable(), pending);
        if (sequence >= 0 && stack.getItem() instanceof BlockItem) {
            pendingBlockChanges.put(pos.toImmutable(), new BlockChange(desired, sequence, waitTicks, false));
            if (desired.getBlock() instanceof BedBlock && desired.get(BedBlock.PART) == BedPart.FOOT) {
                pendingBlockChanges.put(pos.offset(desired.get(BedBlock.FACING)),
                        new BlockChange(desired.with(BedBlock.PART, BedPart.HEAD), sequence, waitTicks, true));
            } else if ((desired.getBlock() instanceof DoorBlock || (desired.getBlock() instanceof TallPlantBlock
                    && !(desired.getBlock() instanceof PitcherCropBlock))) && desired.get(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                pendingBlockChanges.put(pos.up(), new BlockChange(desired.with(DoorBlock.HALF, DoubleBlockHalf.UPPER), sequence, waitTicks, true));
            } else if (desired.getBlock() instanceof PaleMossCarpetBlock && desired.get(PaleMossCarpetBlock.BOTTOM)
                    && PaleMossCarpetBlock.WALL_SHAPE_PROPERTIES_BY_DIRECTION.values().stream().anyMatch(side -> desired.get(side) != WallShape.NONE)) {
                // The server randomly chooses which fronds to grow above the base.
                pendingBlockChanges.put(pos.up(), new BlockChange(null, sequence, waitTicks, true));
            }
        }
        if (sequence >= 0 && stack.getItem() instanceof net.minecraft.item.BlockItem && client.player != null
                && !client.player.getAbilities().creativeMode) {
            int slot = client.player.getInventory().getSelectedSlot();
            ItemStack held = client.player.getInventory().getStack(slot);
            if (ItemStack.areItemsAndComponentsEqual(held, stack)) {
                SlotStock stock = pendingStock.computeIfAbsent(slot, ignored -> new SlotStock(held));
                pending.stockSlot = slot;
                pending.stockCheckTick = client.player.age + waitTicks;
                stock.uses.add(pending);
            }
        }
    }

    public static boolean hasPendingPlacement(BlockPos pos) {
        BlockChange change = pendingBlockChanges.get(pos);
        return pendingPlacements.containsKey(pos)
                || (change != null && change.companion && !change.matched && change.ticks > 0);
    }

    private static boolean mayChange(BlockPos pos) {
        return pendingBlockChanges.containsKey(pos) || pendingPlacements.containsKey(pos);
    }

    /** Called with the revalidated wire hit immediately before spending a placement packet. */
    public static boolean dependsOnPendingBlock(BlockPos target, BlockState desired, ItemStack stack, BlockHitResult hit) {
        if (!NativeAttachment.isSensitive(desired) || (pendingBlockChanges.isEmpty() && pendingPlacements.isEmpty())) return false;
        BlockPos clicked = hit.getBlockPos();
        if (mayChange(target) || mayChange(clicked)) return true;
        var client = MinecraftClient.getInstance();
        if (PrinterConfig.RAYCAST.getBooleanValue() && Boolean.TRUE.equals(BlockView.raycast(
                client.player.getEyePos(), hit.getPos(), clicked,
                (anchor, pos) -> !pos.equals(anchor) && mayChange(pos) ? Boolean.TRUE : null, anchor -> Boolean.FALSE))) {
            return true;
        }
        Direction face = NativeAttachment.preferredFace(desired);
        // Moving the clicked support to the front of vanilla's candidate order fixes the mount.
        // revalidate() already checked that the held item actually produces the requested state on it.
        boolean fixed = face != null && face == hit.getSide() && clicked.offset(face).equals(target)
                && client.world.getBlockState(target).isAir() && !client.world.getBlockState(clicked).isReplaceable()
                && !stack.contains(DataComponentTypes.BLOCK_STATE);
        if (fixed) return false;
        for (Direction side : Direction.values()) {
            if (NativeAttachment.readsNeighbor(desired, side) && mayChange(target.offset(side))) return true;
        }
        return false;
    }

    public static void acknowledgeBlockActions(int sequence) {
        acknowledgedSequence = Math.max(acknowledgedSequence, sequence);
        pendingBlockChanges.values().removeIf(change -> change.sequence <= acknowledgedSequence && (change.matched || change.ticks <= 0));
    }

    private static final class BlockChange {
        @Nullable final BlockState predicted;
        final int sequence;
        final boolean companion;
        int ticks;
        boolean matched;

        BlockChange(@Nullable BlockState predicted, int sequence, int ticks, boolean companion) {
            this.predicted = predicted;
            this.sequence = sequence;
            this.ticks = ticks;
            this.companion = companion;
        }
    }

    public static int pendingSlotCount(int slot, ItemStack held) {
        SlotStock stock = pendingStock.get(slot);
        if (stock == null) return 0;
        // Until an authority publishes a replacement, a local edit cannot turn an outstanding use into credit.
        return ItemStack.areItemsAndComponentsEqual(stock.item, held) ? stock.uses.size() : Integer.MAX_VALUE;
    }

    static void resetStock() {
        pendingStock.clear();
    }

    /** Reconcile uses against the inventory stream independently of world block publications. */
    public static void observeHotbarContent(int slot, ItemStack content) {
        SlotStock stock = pendingStock.get(slot);
        if (stock == null) return;
        int count = ItemStack.areItemsAndComponentsEqual(stock.item, content) ? content.getCount() : 0;
        int spent = stock.publishedCount - count;
        for (PendingPlacement use : stock.uses) {
            if (spent <= 0) break;
            if (!use.stockPublished) {
                use.stockPublished = true;
                spent--;
            }
        }
        stock.publishedCount = count;
        settleStock(slot, stock);
    }

    private static void settleStock(int slot, SlotStock stock) {
        stock.uses.removeIf(use -> use.stockPublished && use.blockPublished);
        if (stock.uses.isEmpty()) pendingStock.remove(slot);
    }

    /** The manager kept this slot reserved from the ordered refresh request through its response. */
    public static void reconcileHotbarSlot(int slot, ItemStack actual) {
        var player = MinecraftClient.getInstance().player;
        if (player != null && ItemStack.areEqual(actual, player.getInventory().getStack(slot))) pendingStock.remove(slot);
    }

    /** Invoked on the client thread after vanilla applies a block update. */
    public static void confirmNativePlacement(BlockPos pos, BlockState actual) {
        BlockChange change = pendingBlockChanges.get(pos);
        if (change != null) {
            if (change.sequence <= acknowledgedSequence) pendingBlockChanges.remove(pos);
            else change.matched = actual.equals(change.predicted);
        }
        PendingPlacement pending = pendingPlacements.get(pos);
        if (pending != null && pending.stack.isEmpty() && actual.isOf(pending.desired.getBlock())) {
            var property = MovingPrinterAction.tuningProperty(pending.desired);
            if (property != null && !actual.get(property).equals(pending.desired.get(property))) return;
        }
        pendingPlacements.remove(pos);
        if (pending != null && pending.stockSlot >= 0) {
            pending.blockPublished = true;
            SlotStock stock = pendingStock.get(pending.stockSlot);
            if (stock != null) settleStock(pending.stockSlot, stock);
        }
        if (pending != null && PrinterConfig.PRINTER_DEBUG_LOG.getBooleanValue()) {
            if (pending.stack.isOf(net.minecraft.item.Items.WATER_BUCKET)) {
                boolean filled = actual.isOf(pending.desired.getBlock())
                        && actual.get(net.minecraft.state.property.Properties.WATERLOGGED);
                logger.info("Waterlogging {} at {}", filled ? "confirmed" : "rejected", pos);
                return;
            }
            logger.info("Native placement {} at {} (sequence {})",
                    NativePlacementSolver.statesMatch(pending.desired, actual) ? "confirmed" : "rejected",
                    pos, pending.sequence);
        }
    }

    private static void tickPendingPlacements() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (predictionWorld != client.world) {
            pendingPlacements.clear();
            pendingBlockChanges.clear();
            acknowledgedSequence = -1;
            resetStock();
            blockPosTimeout.clear();
            placementRate.reset();
            inventoryManager.onPrinterContextChanged();
            predictionWorld = client.world;
        }
        pendingPlacements.entrySet().removeIf(entry -> --entry.getValue().ticks <= 0);
        pendingBlockChanges.values().removeIf(change -> {
            if (change.ticks > 0) change.ticks--;
            return change.ticks == 0 && change.sequence <= acknowledgedSequence;
        });
        if (client.player != null) {
            for (var entry : pendingStock.entrySet()) {
                if (entry.getValue().uses.stream().anyMatch(use -> client.player.age - use.stockCheckTick >= 0)) {
                    inventoryManager.requestStockReadback(entry.getKey());
                    break;
                }
            }
        }
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
        private int stockSlot = -1;
        private int stockCheckTick;
        private boolean blockPublished;
        private boolean stockPublished;

        private PendingPlacement(BlockState desired, ItemStack stack, int sequence, int ticks) {
            this.desired = desired;
            this.stack = stack;
            this.sequence = sequence;
            this.ticks = ticks;
        }
    }

    private static final class SlotStock {
        final ItemStack item;
        int publishedCount;
        final List<PendingPlacement> uses = new ArrayList<>();

        SlotStock(ItemStack stack) {
            item = stack.copyWithCount(1);
            publishedCount = stack.getCount();
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
