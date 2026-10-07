package me.aleksilassila.litematica.printer.v1_21_11;

import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_11.implementation.NativePlacementContext;
import net.minecraft.block.*;
import net.minecraft.block.enums.ChestType;
import net.minecraft.block.enums.SlabType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BlockStateComponent;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemPlacementContext;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.Property;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.BlockView;

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

    /** The state achievable by the next single use; later uses follow server updates. */
    public static BlockState placementTarget(BlockView current, BlockView schematic, BlockPos target, BlockState desired) {
        BlockState existing = current.getBlockState(target);
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

    /** Ordinary blocks use no rotation; directional blocks use a movement-safe lattice. */
    public static Solution solveMoving(BlockPos target, BlockState desired, ItemStack stack,
                                       double reach, boolean allowAir, boolean sprinting) {
        if (!canSolve(target, stack)) return null;
        List<HitCandidate> hits = candidates(target, reach, allowAir, true);
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
        return solveRotations(target, desired, stack, candidates(target, reach, allowAir, true),
                List.of(new Rotation(yaw, pitch, 0)));
    }

    /** Standing still has no movement lattice constraint and covers every direction bin. */
    public static Solution solveStationary(BlockPos target, BlockState desired, ItemStack stack,
                                           double reach, boolean allowAir) {
        if (!canSolve(target, stack)) return null;
        List<HitCandidate> hits = candidates(target, reach, allowAir, true);
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

    /** Returns only rotation-independent airplace solutions; never a directional fallback. */
    public static Solution solveAnyRotation(BlockPos target, BlockState desired, ItemStack stack, double reach) {
        if (!canSolve(target, stack)) return null;
        return solveAnyRotation(target, desired, stack, candidates(target, reach, true, false));
    }

    private static Solution solveAnyRotation(BlockPos target, BlockState desired, ItemStack stack,
                                              List<HitCandidate> hits) {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        for (HitCandidate candidate : hits) {
            BlockState predicted = simulate(target, stack, candidate.hit(), player.getYaw(), player.getPitch());
            if (!statesMatch(desired, predicted)) continue;
            boolean stable = true;
            for (float[] rotation : OMNI_ROTATIONS) {
                if (!statesMatch(desired, simulate(target, stack, candidate.hit(), rotation[0], rotation[1]))) {
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
                if (!statesMatch(desired, result)) continue;
                // Avoid nearest-direction boundaries while the player is moving.
                if (!statesMatch(desired, simulate(target, stack, candidate.hit(), rotation.yaw() - 1.0F, rotation.pitch()))
                        || !statesMatch(desired, simulate(target, stack, candidate.hit(), rotation.yaw() + 1.0F, rotation.pitch()))) continue;
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
                && validHit(mc.player.getEyePos(), hit, effectiveReach(reach))) {
            BlockState predicted = simulate(target, stack, hit, yaw, pitch);
            if (statesMatch(desired, predicted)) {
                return new Solution(hit, predicted, yaw, pitch, solution.yawStep(), solution.airPlace(), solution.anyRotation());
            }
        }
        // The player may cross a face plane between preparation and movement.
        // Rebuild the hit at the new eyes, preserving the already-sent angles.
        // Reusing the old point alone needlessly dropped a whole moving batch.
        Solution refreshed = solveRotations(target, desired, stack,
                candidates(target, reach, solution.airPlace(), true), List.of(new Rotation(yaw, pitch, solution.yawStep())));
        return refreshed == null ? null : new Solution(refreshed.hit(), refreshed.predicted(), yaw, pitch,
                solution.yawStep(), refreshed.airPlace(), solution.anyRotation());
    }

    public static Solution revalidate(BlockPos target, BlockState desired, ItemStack stack, Solution solution, double reach) {
        return solution == null ? null : revalidate(target, desired, stack, solution, reach, solution.yaw(), solution.pitch());
    }

    private static boolean canSolve(BlockPos target, ItemStack stack) {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc.player != null && mc.world != null && !stack.isEmpty() && stack.getItem() instanceof BlockItem
                && mc.world.isInBuildLimit(target) && mc.world.getWorldBorder().contains(target);
    }

    private static double effectiveReach(double reach) {
        return Math.max(0.0, Math.min(reach, MinecraftClient.getInstance().player.getBlockInteractionRange()));
    }

    private static List<HitCandidate> candidates(BlockPos target, double configuredReach, boolean allowAir, boolean allowSupports) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d eye = mc.player.getEyePos();
        double reach = effectiveReach(configuredReach);
        List<HitCandidate> result = new ArrayList<>();
        // Target-position interaction is the native airplace path. A local
        // inside=true flag is unnecessary and would differ from the wire hit.
        BlockState targetState = mc.world.getBlockState(target);
        if (allowAir && targetState.isReplaceable()) {
            for (Direction face : Direction.values()) addFace(result, eye, target, face, true, reach);
        } else if (allowSupports && targetState.getBlock() instanceof SlabBlock
                && targetState.get(SlabBlock.TYPE) != SlabType.DOUBLE) {
            // Slab.canReplace depends on the held slab, side and hit height.
            // Let the real context validate each same-position merge.
            for (Direction face : Direction.values()) addFace(result, eye, target, face, false, reach);
        }
        if (allowSupports) {
            for (Direction side : Direction.values()) {
                BlockPos support = target.offset(side);
                BlockState state = mc.world.getBlockState(support);
                if (!state.isReplaceable() && canClick(state) && !state.getOutlineShape(mc.world, support).isEmpty()) {
                    addFace(result, eye, support, side.getOpposite(), false, reach);
                }
            }
        }
        return result;
    }

    private static void addFace(List<HitCandidate> hits, Vec3d eye, BlockPos block, Direction face, boolean air, double reach) {
        Vec3d center = Vec3d.ofCenter(block).add(Vec3d.of(face.getVector()).multiply(0.5));
        // Clamp eye coordinates onto the face first. This reaches
        // near edges without requiring the face centre to be inside reach.
        double eyeU = face.getAxis() == Direction.Axis.X ? eye.z - block.getZ() : eye.x - block.getX();
        double eyeV = face.getAxis() == Direction.Axis.Y ? eye.z - block.getZ() : eye.y - block.getY();
        addHit(hits, eye, block, face, offset(center, face,
                MathHelper.clamp(eyeU, 0.05, 0.95) - 0.5, MathHelper.clamp(eyeV, 0.05, 0.95) - 0.5), air, reach);
        for (double u : SAMPLES) {
            for (double v : SAMPLES) addHit(hits, eye, block, face, offset(center, face, u - 0.5, v - 0.5), air, reach);
        }
    }

    private static void addHit(List<HitCandidate> hits, Vec3d eye, BlockPos block, Direction face,
                                Vec3d sample, boolean air, double reach) {
        BlockHitResult hit = new BlockHitResult(sample, face, block, false);
        for (HitCandidate existing : hits) {
            if (existing.hit().getBlockPos().equals(block) && existing.hit().getSide() == face
                    && existing.hit().getPos().squaredDistanceTo(sample) < 1.0E-12) return;
        }
        if (validHit(eye, hit, reach)) hits.add(new HitCandidate(hit, air));
    }

    private static boolean validHit(Vec3d eye, BlockHitResult hit, double reach) {
        double distanceSq = eye.squaredDistanceTo(hit.getPos());
        if (distanceSq < 1.0E-6 || distanceSq > reach * reach) return false;
        // An air interaction's side is a placement input (e.g. hopper outlet),
        // not a visible surface of a solid block. All six sides must survive
        // both preparation and send-time validation. Solid supports retain the
        // configured face restriction; raycast is an independent opt-in gate.
        if (!MinecraftClient.getInstance().world.getBlockState(hit.getBlockPos()).isReplaceable()
                && PrinterConfig.STRICT_BLOCK_FACE_CHECK.getBooleanValue()
                && !faceVisible(eye, hit.getBlockPos(), hit.getSide())) return false;
        if (!PrinterConfig.RAYCAST.getBooleanValue()) return true;

        MinecraftClient mc = MinecraftClient.getInstance();
        Vec3d direction = hit.getPos().subtract(eye);
        Vec3d end = hit.getPos().add(direction.normalize().multiply(0.001));
        BlockHitResult obstruction = mc.world.raycast(new RaycastContext(eye, end,
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, mc.player));
        // An air target has no outline to hit. A clear ray is still valid.
        if (obstruction.getType() == HitResult.Type.MISS) return mc.world.getBlockState(hit.getBlockPos()).isReplaceable();
        return obstruction.getBlockPos().equals(hit.getBlockPos())
                && (!PrinterConfig.RAYCAST_STRICT_BLOCK_HIT.getBooleanValue() || obstruction.getSide() == hit.getSide());
    }

    private static boolean faceVisible(Vec3d eye, BlockPos block, Direction face) {
        // Solid-support compatibility gate, independent of crosshair alignment.
        return switch (face) {
            case DOWN -> eye.y < block.getY();
            case UP -> eye.y > block.getY() + 1.0;
            case NORTH -> eye.z < block.getZ();
            case SOUTH -> eye.z > block.getZ() + 1.0;
            case WEST -> eye.x < block.getX();
            case EAST -> eye.x > block.getX() + 1.0;
        };
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
                || block instanceof RespawnAnchorBlock);
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

    public static boolean statesMatch(BlockState desired, BlockState actual) {
        if (desired == null || actual == null || desired.getBlock() != actual.getBlock()) return false;
        for (Property<?> property : desired.getProperties()) {
            String name = property.getName();
            if (ENVIRONMENT_PROPERTIES.contains(name)) continue;
            // Placement establishes geometry first; the interaction guide
            // subsequently applies these mutable states after server updates.
            if (name.equals("open") && (desired.getBlock() instanceof DoorBlock
                    || desired.getBlock() instanceof TrapdoorBlock || desired.getBlock() instanceof FenceGateBlock)) continue;
            if (name.equals("mode") && desired.getBlock() instanceof ComparatorBlock) continue;
            if (name.equals("shape") && desired.getBlock() instanceof StairsBlock) continue;
            if (PrinterConfig.PRINTER_IGNORE_ROTATION.getBooleanValue() && ROTATION_PROPERTIES.contains(name)) continue;
            if (CONNECTION_PROPERTIES.contains(name) && !(desired.getBlock() instanceof MultifaceBlock)) continue;
            if (!actual.contains(property) || !desired.get(property).equals(actual.get(property))) return false;
        }
        return true;
    }

    private record HitCandidate(BlockHitResult hit, boolean airPlace) {}
    private record Rotation(float yaw, float pitch, int step) {}

    public record Solution(BlockHitResult hit, BlockState predicted, float yaw, float pitch,
                           int yawStep, boolean airPlace, boolean anyRotation) {}
}
