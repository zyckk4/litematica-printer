package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.NativePlacementContext;
import net.minecraft.block.*;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.enums.SlabType;
import net.minecraft.block.enums.WallShape;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BlockStateComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Property;
import net.minecraft.state.property.IntProperty;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.BlockView;
import net.minecraft.world.border.WorldBorder;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Evaluates native BlockItem placement independently of the camera/crosshair. */
public final class NativePlacementSolver {
    private static final double[] SAMPLES = {0.5, 0.25, 0.75};
    private static final float[] PITCHES = {30.0F, -30.0F, 75.0F, -75.0F};
    private static final float[] LOOK_UP = {-90.0F};
    private static final float[] LOOK_DOWN = {90.0F};
    private static final float[] LOOK_HORIZONTAL = {0.0F};
    private static final int[] MOVING_STEPS = {0, 1, -1, 2, -2, 3, -3, 4};
    private static final float[][] OMNI_ROTATIONS = createOmniRotations();
    // Hints only: every hit still runs BlockItem placement against the current
    // world. Never cache a successful block state across positions/worlds.
    private static final Map<BlockState, Rotation> ROTATION_HINTS = new LinkedHashMap<>(128, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<BlockState, Rotation> eldest) {
            return size() > 256;
        }
    };
    // These properties are produced by the environment or changed after placement.
    // They must not make a perfectly placeable stair, leaf or redstone block fail
    // every orientation test. Orientation and attachment properties remain strict.
    private static final Set<String> ENVIRONMENT_PROPERTIES = Set.of(
            "waterlogged", "powered", "power", "lit", "distance", "persistent",
            "note", "instrument", "age", "stage", "snowy", "occupied", "signal_fire",
            "extended", "triggered", "locked", "enabled", "delay", "in_wall", "bottom"
    );
    private static final Set<String> CONNECTION_PROPERTIES = Set.of("north", "south", "east", "west", "up", "down");
    private static final Set<String> ROTATION_PROPERTIES = Set.of(
            "facing", "horizontal_facing", "axis", "half", "type", "hinge", "orientation", "shape", "rotation"
    );

    private NativePlacementSolver() {
    }

    /** The next placement goal; multiface goals are resolved to one face by the solver. */
    public static BlockState placementTarget(BlockView current, BlockView schematic, BlockPos target, BlockState desired) {
        BlockState existing = current.getBlockState(target);
        if (existing.isReplaceable()) desired = PlacementStages.initialTarget(desired);
        IntProperty count = stackCount(desired);
        if (count != null) {
            int placed = existing.isOf(desired.getBlock()) ? existing.get(count) : 0;
            return desired.with(count, Math.min(placed + 1, desired.get(count)));
        }
        if (desired.getBlock() instanceof SlabBlock && desired.get(SlabBlock.TYPE) == SlabType.DOUBLE
                && existing.isReplaceable()) {
            return desired.with(SlabBlock.TYPE, SlabType.BOTTOM);
        }
        if (desired.getBlock() instanceof ChestBlock && desired.get(ChestBlock.CHEST_TYPE) != ChestType.SINGLE) {
            Direction facing = desired.get(ChestBlock.FACING);
            ChestType type = desired.get(ChestBlock.CHEST_TYPE);
            Direction connection = type == ChestType.LEFT ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
            BlockPos partner = target.offset(connection);
            BlockState plannedPartner = schematic.getBlockState(partner);
            // A double chest must start as a single chest. Require the exact
            // planned partner, so a malformed/partial schematic is not treated
            // as permission to ignore chest type everywhere.
            if (plannedPartner.isOf(desired.getBlock())
                    && plannedPartner.get(ChestBlock.FACING) == facing
                    && plannedPartner.get(ChestBlock.CHEST_TYPE) == type.getOpposite()
                    && current.getBlockState(partner).isReplaceable()) {
                return desired.with(ChestBlock.CHEST_TYPE, ChestType.SINGLE);
            }
        }
        return desired;
    }

    public static boolean canCompleteSlab(BlockState current, BlockState desired) {
        return desired.getBlock() instanceof SlabBlock && current.isOf(desired.getBlock())
                && desired.get(SlabBlock.TYPE) == SlabType.DOUBLE && current.get(SlabBlock.TYPE) != SlabType.DOUBLE;
    }

    private static IntProperty stackCount(BlockState state) {
        if (state.getBlock() instanceof SnowBlock) return SnowBlock.LAYERS;
        if (state.getBlock() instanceof CandleBlock) return CandleBlock.CANDLES;
        if (state.getBlock() instanceof SeaPickleBlock) return SeaPickleBlock.PICKLES;
        if (state.getBlock() instanceof TurtleEggBlock) return TurtleEggBlock.EGGS;
        if (state.getBlock() instanceof FlowerbedBlock) return FlowerbedBlock.FLOWER_AMOUNT;
        if (state.getBlock() instanceof LeafLitterBlock) return Properties.SEGMENT_AMOUNT;
        return null;
    }

    public static boolean canAddToStack(BlockState current, BlockState desired) {
        if (!current.isOf(desired.getBlock())) return false;
        IntProperty count = stackCount(desired);
        return count != null && current.get(count) < desired.get(count);
    }

    public static boolean canAddFaces(BlockState current, BlockState desired) {
        if (!NativeAttachment.hasFaces(desired) || !current.isOf(desired.getBlock())) return false;
        boolean missing = false;
        for (Direction direction : Direction.values()) {
            BooleanProperty face = MultifaceBlock.getProperty(direction);
            if (!desired.contains(face)) continue;
            // Repairing/removing an incorrect existing attachment is outside
            // placement. Only extend an exact subset of the schematic faces.
            if (current.get(face) && !desired.get(face)) return false;
            missing |= desired.get(face) && !current.get(face);
        }
        return missing;
    }

    /** Allow one new planned face, chosen from the hits that are reachable now. */
    public static boolean matchesNextPlacement(BlockView current, BlockPos target, BlockState desired, BlockState predicted) {
        if (!NativeAttachment.hasFaces(desired)) return statesMatch(desired, predicted);
        if (predicted == null || !predicted.isOf(desired.getBlock())) return false;
        BlockState before = current.getBlockState(target);
        boolean sameBlock = before.isOf(desired.getBlock());
        int added = 0;
        for (Direction direction : Direction.values()) {
            BooleanProperty face = MultifaceBlock.getProperty(direction);
            if (!desired.contains(face)) continue;
            boolean existing = sameBlock && before.get(face);
            if (existing && !predicted.get(face)) return false;
            if (predicted.get(face) && !desired.get(face)) return false;
            if (predicted.get(face) && !existing) added++;
        }
        return added == 1;
    }

    /** Ordinary blocks use no rotation; directional blocks use a movement-safe lattice. */
    public static Solution solveMoving(BlockPos target, BlockState desired, ItemStack stack,
                                       double reach, boolean allowAir, boolean sprinting) {
        if (!canSolve(target, stack)) return null;
        List<HitCandidate> hits = candidates(target, desired, reach, allowAir, true);
        Solution independent = hasKnownLookDirection(desired) ? null : solveAnyRotation(target, desired, stack, hits);
        if (independent != null) return independent;

        float baseYaw = MathHelper.wrapDegrees(MinecraftClient.getInstance().player.getYaw());
        List<Rotation> rotations = new ArrayList<>();
        Rotation hint = ROTATION_HINTS.get(desired);
        float[] pitches = pitchesFor(desired);
        // All eight input-equivalent turns remain available when sprinting.
        // MovementHandler can stop sprint for a side/backward-input tick.
        for (int step : MOVING_STEPS) {
            // Keep yaw unchanged for vertical blocks whenever possible. Small
            // turns also take precedence over a cached turn that stops sprint.
            if (step == 2 && hint != null) {
                rotations.add(new Rotation(MathHelper.wrapDegrees(baseYaw + hint.step() * 45.0F), hint.pitch(), hint.step()));
            }
            for (float pitch : pitches) {
                rotations.add(new Rotation(MathHelper.wrapDegrees(baseYaw + step * 45.0F), pitch, step));
            }
        }
        rotations.removeIf(rotation -> !MovementHandler.canRequestRotation(rotation.yaw(), rotation.pitch()));
        return remember(desired, solveRotations(target, desired, stack, hits, rotations));
    }

    /** Reuses one already selected server rotation for the rest of a batch. */
    public static Solution solveMovingAtRotation(BlockPos target, BlockState desired, ItemStack stack,
                                                 double reach, boolean allowAir, boolean sprinting,
                                                 float yaw, float pitch) {
        if (!canSolve(target, stack)) return null;
        return solveRotations(target, desired, stack, candidates(target, desired, reach, allowAir, true),
                List.of(new Rotation(yaw, pitch, 0)));
    }

    /** Standing still has no movement lattice constraint and covers every direction bin. */
    public static Solution solveStationary(BlockPos target, BlockState desired, ItemStack stack,
                                           double reach, boolean allowAir) {
        if (!canSolve(target, stack)) return null;
        List<HitCandidate> hits = candidates(target, desired, reach, allowAir, true);
        Solution independent = hasKnownLookDirection(desired) ? null : solveAnyRotation(target, desired, stack, hits);
        if (independent != null) return independent;

        List<Rotation> rotations = new ArrayList<>();
        Rotation hint = ROTATION_HINTS.get(desired);
        if (hint != null) rotations.add(hint);
        if (hasKnownLookDirection(desired)) {
            Direction look = requiredLook(desired);
            float yaw = switch (look) {
                case SOUTH -> 0.0F;
                case WEST -> 90.0F;
                case NORTH -> 180.0F;
                case EAST -> -90.0F;
                default -> MinecraftClient.getInstance().player.getYaw();
            };
            rotations.add(new Rotation(yaw, pitchesFor(desired)[0], 0));
        }
        for (float[] rotation : OMNI_ROTATIONS) {
            rotations.add(new Rotation(rotation[0], rotation[1], 0));
        }
        // Standing signs, banners and skulls use a 16-step yaw property.
        if (desired.getProperties().stream().anyMatch(property -> property.getName().equals("rotation"))) {
            for (int step = 0; step < 16; step++) {
                if (step % 4 == 0) continue;
                for (float pitch : PITCHES) rotations.add(new Rotation(step * 22.5F, pitch, 0));
            }
        }
        rotations.removeIf(rotation -> !MovementHandler.canRequestRotation(rotation.yaw(), rotation.pitch()));
        return remember(desired, solveRotations(target, desired, stack, hits, rotations));
    }

    private static boolean hasKnownLookDirection(BlockState desired) {
        if (PrinterConfig.PRINTER_IGNORE_ROTATION.getBooleanValue()) return false;
        Block block = desired.getBlock();
        return block instanceof PistonBlock || block instanceof DispenserBlock || block instanceof ObserverBlock;
    }

    private static float[] pitchesFor(BlockState desired) {
        if (!hasKnownLookDirection(desired)) return PITCHES;
        Direction look = requiredLook(desired);
        return look == Direction.UP ? LOOK_UP : look == Direction.DOWN ? LOOK_DOWN : LOOK_HORIZONTAL;
    }

    private static Direction requiredLook(BlockState desired) {
        Direction facing = desired.get(net.minecraft.state.property.Properties.FACING);
        // Observer's detecting face follows the look; pistons/dispensers face
        // back toward the player. Both still use the real BlockItem validator.
        return desired.getBlock() instanceof ObserverBlock ? facing : facing.getOpposite();
    }

    private static Solution remember(BlockState desired, Solution solution) {
        if (solution != null && !solution.anyRotation()) {
            ROTATION_HINTS.put(desired, new Rotation(solution.yaw(), solution.pitch(), solution.yawStep()));
        }
        return solution;
    }

    /** Rotation-independent target click or an explicit attachment or border support. */
    public static Solution solveAnyRotation(BlockPos target, BlockState desired, ItemStack stack, double reach) {
        if (!canSolve(target, stack)) return null;
        return solveAnyRotation(target, desired, stack, candidates(target, desired, reach, true, false));
    }

    private static Solution solveAnyRotation(BlockPos target, BlockState desired, ItemStack stack,
                                              List<HitCandidate> hits) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        for (HitCandidate candidate : hits) {
            BlockState predicted = simulate(target, stack, candidate.hit(), player.getYaw(), player.getPitch());
            if (!matchesNextPlacement(MinecraftClient.getInstance().world, target, desired, predicted)) continue;
            BlockState stableTarget = NativeAttachment.hasFaces(desired) ? predicted : desired;
            boolean stable = true;
            for (float[] rotation : OMNI_ROTATIONS) {
                if (!statesMatch(stableTarget, simulate(target, stack, candidate.hit(), rotation[0], rotation[1]))) {
                    stable = false;
                    break;
                }
            }
            if (stable) {
                return new Solution(candidate.hit(), predicted, player.getYaw(), player.getPitch(),
                        0, candidate.airPlace(), true);
            }
        }
        return null;
    }

    private static Solution solveRotations(BlockPos target, BlockState desired, ItemStack stack,
                                           List<HitCandidate> hits, List<Rotation> rotations) {
        // Each hit gets every rotation bin before moving to another sample.
        // The old shared counter could exhaust itself on the first yaws and
        // never test the direction needed for this block. This finite search
        // has no mutable, cross-target budget and stops on the first match.
        for (HitCandidate candidate : hits) {
            for (Rotation rotation : rotations) {
                BlockState result = simulate(target, stack, candidate.hit(), rotation.yaw(), rotation.pitch());
                if (!matchesNextPlacement(MinecraftClient.getInstance().world, target, desired, result)) continue;
                BlockState stableTarget = NativeAttachment.hasFaces(desired) ? result : desired;
                // Avoid nearest-direction boundaries while the player is moving.
                if (!statesMatch(stableTarget, simulate(target, stack, candidate.hit(), rotation.yaw() - 1.0F, rotation.pitch()))
                        || !statesMatch(stableTarget, simulate(target, stack, candidate.hit(), rotation.yaw() + 1.0F, rotation.pitch()))) continue;
                return new Solution(candidate.hit(), result, rotation.yaw(), rotation.pitch(),
                        rotation.step(), candidate.airPlace(), false);
            }
        }
        return null;
    }

    /** Rechecks a queued hit at the rotation actually sent to the server. */
    public static Solution revalidate(BlockPos target, BlockState desired, ItemStack stack, Solution solution,
                                       double reach, float yaw, float pitch) {
        if (solution == null || !canSolve(target, stack)) return null;
        MinecraftClient mc = MinecraftClient.getInstance();
        BlockHitResult hit = solution.hit();
        if (canClick(mc.world.getBlockState(hit.getBlockPos()))
                && validHit(target, mc.player.getEyePos(), hit, effectiveReach(reach))) {
            BlockState predicted = simulate(target, stack, hit, yaw, pitch);
            if (matchesNextPlacement(mc.world, target, desired, predicted)
                    && (!NativeAttachment.hasFaces(desired) || statesMatch(desired, predicted))) {
                return new Solution(hit, predicted, yaw, pitch, solution.yawStep(), solution.airPlace(), solution.anyRotation());
            }
        }
        // The player may cross a face plane between preparation and movement.
        // Rebuild the hit at the new eyes, preserving the already-sent angles.
        // Reusing the old point alone needlessly dropped a whole moving batch.
        Solution refreshed = solveRotations(target, desired, stack,
                candidates(target, desired, reach, solution.airPlace(), true), List.of(new Rotation(yaw, pitch, solution.yawStep())));
        if (refreshed != null && NativeAttachment.hasFaces(desired) && !statesMatch(desired, refreshed.predicted())) return null;
        return refreshed == null ? null : new Solution(refreshed.hit(), refreshed.predicted(), yaw, pitch,
                solution.yawStep(), refreshed.airPlace(), solution.anyRotation());
    }

    public static Solution revalidate(BlockPos target, BlockState desired, ItemStack stack, Solution solution, double reach) {
        return solution == null ? null : revalidate(target, desired, stack, solution, reach, solution.yaw(), solution.pitch());
    }

    private static boolean canSolve(BlockPos target, ItemStack stack) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && mc.world != null && !stack.isEmpty() && stack.getItem() instanceof BlockItem
                && mc.world.isInBuildLimit(target) && withinPlacementBorder(mc.world.getWorldBorder(), target)
                && BlockHelper.canReplaceFluid(mc.world.getBlockState(target));
    }

    /** The outer row must have a face-adjacent inside cell; diagonal corners do not qualify. */
    private static boolean withinPlacementBorder(WorldBorder border, BlockPos target) {
        if (border.contains(target)) return true;
        for (Direction side : Direction.Type.HORIZONTAL) {
            if (border.contains(target.offset(side))) return true;
        }
        return false;
    }

    private static double effectiveReach(double reach) {
        return Math.max(0.0, Math.min(reach, MinecraftClient.getInstance().player.getBlockInteractionRange()));
    }

    private static List<HitCandidate> candidates(BlockPos target, BlockState desired, double configuredReach,
                                                  boolean allowAir, boolean allowSupports) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d eye = mc.player.getEyePos();
        double reach = effectiveReach(configuredReach);
        List<HitCandidate> result = new ArrayList<>();
        WorldBorder border = mc.world.getWorldBorder();
        if (!border.contains(target)) {
            // Vanilla checks the clicked cell against the border, then offsets
            // the placement into its neighbor. Air at the inside cell would be
            // replaced in place, so this route always needs an existing support.
            // It also serves rotation-independent native placement (allowSupports=false).
            for (Direction side : Direction.Type.HORIZONTAL) {
                BlockPos support = target.offset(side);
                if (!border.contains(support)) continue;
                BlockState state = mc.world.getBlockState(support);
                if (!state.isReplaceable() && canClick(state) && !state.getOutlineShape(mc.world, support).isEmpty()) {
                    addFace(result, target, eye, support, side.getOpposite(), false, reach);
                }
            }
            return result;
        }
        // Target-position interaction is the native airplace path. A local
        // inside=true flag is unnecessary and would differ from the wire hit.
        BlockState targetState = mc.world.getBlockState(target);
        if (canAddFaces(targetState, desired)) {
            for (Direction face : Direction.values()) addFace(result, target, eye, target, face, false, reach);
            // Clicking a missing face's support makes vanilla prioritize that
            // attachment independently of look. This also serves the legacy
            // rotation-independent top-up guide.
            for (Direction direction : Direction.values()) {
                BooleanProperty face = MultifaceBlock.getProperty(direction);
                if (!desired.contains(face) || !desired.get(face) || targetState.get(face)) continue;
                BlockPos support = target.offset(direction);
                BlockState supportState = mc.world.getBlockState(support);
                if (!supportState.isReplaceable() && canClick(supportState)) {
                    addFace(result, target, eye, support, direction.getOpposite(), false, reach);
                }
            }
            return result;
        }
        if (canAddToStack(targetState, desired)) {
            // Click the actual outline of the stack. Snow in particular requires its upper face.
            for (var box : targetState.getOutlineShape(mc.world, target).getBoundingBoxes()) {
                Vec3d center = box.offset(target).getCenter();
                for (Direction face : Direction.values()) {
                    Vec3d sample = center.add(face.getOffsetX() * box.getLengthX() / 2,
                            face.getOffsetY() * box.getLengthY() / 2, face.getOffsetZ() * box.getLengthZ() / 2);
                    if (!hasOutlinePoint(result, face, sample)) addHit(result, target, eye, target, face, sample, false, reach);
                }
            }
            return result;
        }
        // A real click on the schematic mount puts that support first in the item's
        // placement context. Clicking the empty target leaves the choice to the
        // server, where a preceding packet may already have added another support.
        Direction mountFace = targetState.isReplaceable() ? NativeAttachment.preferredFace(desired) : null;
        if (mountFace != null) addSupport(result, target, eye, mountFace.getOpposite(), reach);
        if (allowAir && targetState.isReplaceable()) {
            for (Direction face : Direction.values()) addFace(result, target, eye, target, face, true, reach);
        } else if ((allowSupports || canCompleteSlab(targetState, desired)) && targetState.getBlock() instanceof SlabBlock
                && targetState.get(SlabBlock.TYPE) != SlabType.DOUBLE) {
            // Slab.canReplace depends on the held slab, side and hit height.
            // Let the real context validate each same-position merge.
            for (Direction face : Direction.values()) addFace(result, target, eye, target, face, false, reach);
        }
        if (allowSupports) {
            for (Direction side : Direction.values()) {
                if (side.getOpposite() != mountFace) addSupport(result, target, eye, side, reach);
            }
        }
        return result;
    }

    private static void addSupport(List<HitCandidate> hits, BlockPos target, Vec3d eye, Direction side, double reach) {
        var world = MinecraftClient.getInstance().world;
        BlockPos support = target.offset(side);
        BlockState state = world.getBlockState(support);
        if (!state.isReplaceable() && canClick(state) && !state.getOutlineShape(world, support).isEmpty()) {
            addFace(hits, target, eye, support, side.getOpposite(), false, reach);
        }
    }

    private static void addFace(List<HitCandidate> hits, BlockPos target, Vec3d eye, BlockPos block,
                                Direction face, boolean air, double reach) {
        Vec3d center = Vec3d.ofCenter(block).add(Vec3d.of(face.getVector()).multiply(0.5));
        // Clamp eye coordinates onto the face first. This reaches
        // near edges without requiring the face centre to be inside reach.
        double eyeU = face.getAxis() == Direction.Axis.X ? eye.z - block.getZ() : eye.x - block.getX();
        double eyeV = face.getAxis() == Direction.Axis.Y ? eye.z - block.getZ() : eye.y - block.getY();
        Vec3d nearest = offset(center, face, MathHelper.clamp(eyeU, 0.05, 0.95) - 0.5,
                MathHelper.clamp(eyeV, 0.05, 0.95) - 0.5);
        boolean addedNearest = addHit(hits, target, eye, block, face, nearest, air, reach);
        // This block face is enumerated once. Only the eye projection can
        // coincide with one of its distinct grid points.
        for (double u : SAMPLES) {
            for (double v : SAMPLES) {
                Vec3d sample = offset(center, face, u - 0.5, v - 0.5);
                if (!addedNearest || nearest.squaredDistanceTo(sample) >= 1.0E-12) {
                    addHit(hits, target, eye, block, face, sample, air, reach);
                }
            }
        }
    }

    // All outline boxes here belong to one target. Their faces can overlap.
    private static boolean hasOutlinePoint(List<HitCandidate> hits, Direction face, Vec3d sample) {
        for (HitCandidate candidate : hits) {
            if (candidate.hit().getSide() == face && candidate.hit().getPos().squaredDistanceTo(sample) < 1.0E-12) return true;
        }
        return false;
    }

    private static boolean addHit(List<HitCandidate> hits, BlockPos target, Vec3d eye, BlockPos block, Direction face,
                                  Vec3d sample, boolean air, double reach) {
        BlockHitResult hit = new BlockHitResult(sample, face, block, false);
        if (!validHit(target, eye, hit, reach)) return false;
        hits.add(new HitCandidate(hit, air));
        return true;
    }

    private static boolean validHit(BlockPos target, Vec3d eye, BlockHitResult hit, double reach) {
        MinecraftClient mc = MinecraftClient.getInstance();
        WorldBorder border = mc.world.getWorldBorder();
        BlockPos clicked = hit.getBlockPos();
        if (!border.contains(clicked)) return false;
        BlockState support = mc.world.getBlockState(clicked);
        boolean acrossBorder = !border.contains(target);
        // Recheck the anchor as well as the goal after movement/world updates.
        // Never preserve a cached outside air click or extend from the outer row.
        if (acrossBorder && (hit.getSide().getAxis() == Direction.Axis.Y
                || !clicked.offset(hit.getSide()).equals(target) || support.isReplaceable()
                || !canClick(support) || support.getOutlineShape(mc.world, clicked).isEmpty())) return false;
        double distanceSq = eye.squaredDistanceTo(hit.getPos());
        if (distanceSq < 1.0E-6 || distanceSq > reach * reach) return false;
        // An air interaction's side is a placement input (e.g. hopper outlet),
        // not a visible surface of a solid block. All six sides must survive
        // both preparation and send-time validation. Solid supports retain the
        // configured face restriction. The border route deliberately uses the
        // outward face of an inside support, even from its hidden side.
        if (!acrossBorder && !support.isReplaceable()
                && PrinterConfig.STRICT_BLOCK_FACE_CHECK.getBooleanValue()
                && eye.subtract(hit.getPos()).dotProduct(Vec3d.of(hit.getSide().getVector())) <= 0) return false;
        if (!PrinterConfig.RAYCAST.getBooleanValue()) return true;

        Vec3d direction = hit.getPos().subtract(eye);
        Vec3d end = hit.getPos().add(direction.normalize().multiply(0.001));
        BlockHitResult obstruction = mc.world.raycast(new RaycastContext(eye, end,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        // An air target has no outline to hit. A clear ray is still valid.
        if (obstruction.getType() == HitResult.Type.MISS) return mc.world.getBlockState(hit.getBlockPos()).isReplaceable();
        return obstruction.getBlockPos().equals(clicked)
                && (acrossBorder || !PrinterConfig.RAYCAST_STRICT_BLOCK_HIT.getBooleanValue()
                        || obstruction.getSide() == hit.getSide());
    }

    private static boolean canClick(BlockState state) {
        if (state.isReplaceable()) return true;
        Block block = state.getBlock();
        // No sneak packet is used by this path, so an interactive support
        // could consume UseOn instead of placing the item.
        return !state.hasBlockEntity() && !(block instanceof DoorBlock || block instanceof TrapdoorBlock
                || block instanceof FenceGateBlock || block instanceof ButtonBlock || block instanceof LeverBlock
                || block instanceof CraftingTableBlock || block instanceof AnvilBlock || block instanceof SmithingTableBlock
                || block instanceof StonecutterBlock || block instanceof GrindstoneBlock || block instanceof LoomBlock
                || block instanceof CartographyTableBlock || block instanceof NoteBlock || block instanceof ComposterBlock
                || block instanceof CakeBlock || block instanceof FlowerPotBlock || block instanceof DragonEggBlock
                || block instanceof RespawnAnchorBlock || block instanceof AbstractRedstoneGateBlock);
    }

    private static BlockState simulate(BlockPos target, ItemStack stack, BlockHitResult hit, float yaw, float pitch) {
        BlockItem item = (BlockItem) stack.getItem();
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        NativePlacementContext context = new NativePlacementContext(player, Hand.MAIN_HAND, stack, hit, yaw, pitch);
        if (!context.canPlace() || !item.getBlock().isEnabled(context.getWorld().getEnabledFeatures())) return null;
        ItemPlacementContext placement = item.getPlacementContext(context);
        if (placement == null || !placement.getBlockPos().equals(target)) return null;
        // Access-widened BlockItem method retains canPlace/collision gates and
        // subclass dispatch (wall torches/signs, scaffolding, tall blocks...).
        BlockState result = item.getPlacementState(placement);
        if (result == null) return null;
        return stack.getOrDefault(DataComponentTypes.BLOCK_STATE, BlockStateComponent.DEFAULT).applyToState(result);
    }

    private static float[][] createOmniRotations() {
        float[] yaws = {0.0F, 90.0F, 180.0F, -90.0F};
        float[][] result = new float[yaws.length * PITCHES.length][2];
        int i = 0;
        for (float pitch : PITCHES) for (float yaw : yaws) result[i++] = new float[]{yaw, pitch};
        return result;
    }

    private static Vec3d offset(Vec3d center, Direction face, double du, double dv) {
        return switch (face.getAxis()) {
            case X -> center.add(0.0, dv, du);
            case Y -> center.add(du, 0.0, dv);
            case Z -> center.add(du, dv, 0.0);
        };
    }

    /** Properties that cannot be required of the first ordinary item placement.
     * These are block-specific: geometry and unrecoverable natural flags stay strict.
     * Ignoring content here permits the empty shell, not automatic content restoration. */
    public static boolean isDeferredProperty(BlockState state, Property<?> property) {
        Block block = state.getBlock();
        String name = property.getName();
        if (block instanceof DaylightDetectorBlock) return property == DaylightDetectorBlock.INVERTED;
        if (block instanceof EndPortalFrameBlock) return property == EndPortalFrameBlock.EYE;
        if (block instanceof PointedDripstoneBlock) return property == PointedDripstoneBlock.THICKNESS;
        if (block instanceof BambooBlock) return property == BambooBlock.LEAVES;
        if (block instanceof ShelfBlock) return property == ShelfBlock.SIDE_CHAIN;
        if (block instanceof LecternBlock) return property == LecternBlock.HAS_BOOK;
        if (block instanceof ChiseledBookshelfBlock) return name.startsWith("slot_") && name.endsWith("_occupied");
        if (block instanceof BrewingStandBlock) return name.startsWith("has_bottle_");
        if (block instanceof JukeboxBlock) return property == JukeboxBlock.HAS_RECORD;
        if (block instanceof BeehiveBlock) return property == BeehiveBlock.HONEY_LEVEL;
        if (block instanceof ComposterBlock) return property == ComposterBlock.LEVEL;
        if (block instanceof RespawnAnchorBlock) return property == RespawnAnchorBlock.CHARGES;
        if (block instanceof CakeBlock) return property == CakeBlock.BITES;
        if (block instanceof DriedGhastBlock) return name.equals("hydration");
        if (block instanceof SculkSensorBlock) return name.equals("sculk_sensor_phase");
        if (block instanceof SculkCatalystBlock) return name.equals("bloom");
        if (block instanceof SculkShriekerBlock) return name.equals("shrieking");
        if (block instanceof CrafterBlock) return name.equals("crafting");
        if (block instanceof CaveVines) return property == CaveVines.BERRIES;
        if (block instanceof BigDripleafBlock) return property == Properties.TILT;
        if (block instanceof TurtleEggBlock) return property == TurtleEggBlock.HATCH;
        return false;
    }

    public static boolean statesMatch(BlockState desired, BlockState actual) {
        if (desired == null || actual == null) return false;
        if (TorchPlacement.isTorch(desired)) return TorchPlacement.matches(desired, actual);
        if (desired.getBlock() instanceof PaleMossCarpetBlock) {
            if (!actual.isOf(desired.getBlock())
                    || !desired.get(PaleMossCarpetBlock.BOTTOM).equals(actual.get(PaleMossCarpetBlock.BOTTOM))) return false;
            // A base's LOW side can grow TALL when the game creates an upper
            // frond after placement. The side itself must match the schematic.
            for (var side : PaleMossCarpetBlock.WALL_SHAPE_PROPERTIES_BY_DIRECTION.values()) {
                if ((desired.get(side) == WallShape.NONE) != (actual.get(side) == WallShape.NONE)) return false;
            }
            return true;
        }
        // Planting bamboo starts as a shoot; subsequent growth/upper placement
        // changes it into bamboo. Never generalize this to arbitrary block types.
        if (desired.isOf(Blocks.BAMBOO) && actual.isOf(Blocks.BAMBOO_SAPLING)) return true;
        // Vine tips become body segments as the next segment is added. Both
        // are the same plant/material, and vanilla decides from its neighbors.
        if (desired.getBlock() != actual.getBlock()
                && !(desired.getBlock() instanceof CaveVines && actual.getBlock() instanceof CaveVines)) return false;
        for (Property<?> property : desired.getProperties()) {
            String name = property.getName();
            if (ENVIRONMENT_PROPERTIES.contains(name)) continue;
            if (isDeferredProperty(desired, property)) continue;
            // Placement establishes geometry first; the interaction guide
            // subsequently applies these mutable states after server updates.
            if (name.equals("open") && (desired.getBlock() instanceof DoorBlock
                    || desired.getBlock() instanceof TrapdoorBlock || desired.getBlock() instanceof FenceGateBlock)) continue;
            if (name.equals("mode") && desired.getBlock() instanceof ComparatorBlock) continue;
            if (name.equals("shape") && desired.getBlock() instanceof StairsBlock) continue;
            if (PrinterConfig.PRINTER_IGNORE_ROTATION.getBooleanValue() && ROTATION_PROPERTIES.contains(name)
                    && !(name.equals("facing") && NativeAttachment.facingDeterminesMount(desired))) continue;
            if (CONNECTION_PROPERTIES.contains(name) && !NativeAttachment.hasFaces(desired)) continue;
            if (!actual.contains(property) || !desired.get(property).equals(actual.get(property))) return false;
        }
        return true;
    }

    private record HitCandidate(BlockHitResult hit, boolean airPlace) {}
    private record Rotation(float yaw, float pitch, int step) {}

    public record Solution(BlockHitResult hit, BlockState predicted, float yaw, float pitch,
                           int yawStep, boolean airPlace, boolean anyRotation) {}
}
