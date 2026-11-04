package me.aleksilassila.litematica.printer.v1_21_11;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.v1_21_11.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_11.guides.placement.BlockReplacementGuide;
import net.minecraft.block.*;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.state.property.Property;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import sun.misc.Unsafe;

import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/** Initial placement with real BlockItems; map-backed world, no server simulation. */
final class InitialPlacementChecks {
    private static final BlockPos TARGET = new BlockPos(0, 65, 3);
    private static int assertions;

    static int run(MinecraftClient client, Map<BlockPos, BlockState> blocks, Unsafe unsafe) throws Exception {
        assertions = 0;
        Field inventoryField = PlayerEntity.class.getDeclaredField("inventory");
        Field abilitiesField = PlayerEntity.class.getDeclaredField("abilities");
        inventoryField.setAccessible(true);
        abilitiesField.setAccessible(true);
        Object oldInventory = inventoryField.get(client.player), oldAbilities = abilitiesField.get(client.player);
        boolean raycast = PrinterConfig.RAYCAST.getBooleanValue();
        boolean strictFace = PrinterConfig.STRICT_BLOCK_FACE_CHECK.getBooleanValue();
        Map<Block, List<TagKey<Block>>> oldTags = new HashMap<>();
        Method bindTags = RegistryEntry.Reference.class.getDeclaredMethod("setTags", Collection.class);
        bindTags.setAccessible(true);
        try {
            loadPlantTags(oldTags, bindTags);
            inventoryField.set(client.player, new PlayerInventory(client.player, new net.minecraft.entity.EntityEquipment()));
            abilitiesField.set(client.player, client.player.getAbilities());
            PrinterConfig.RAYCAST.setBooleanValue(false);
            PrinterConfig.STRICT_BLOCK_FACE_CHECK.setBooleanValue(false);
            Schematic schematic = (Schematic) unsafe.allocateInstance(Schematic.class);
            Printer printer = new Printer(client, client.player);
            Method candidate = Printer.class.getDeclaredMethod("isNativePlacementCandidate", SchematicBlockState.class, Set.class);
            candidate.setAccessible(true);

            for (boolean inverted : new boolean[]{false, true}) for (int power : new int[]{0, 7, 15}) {
                initial(client, blocks, schematic, printer, candidate,
                        Blocks.DAYLIGHT_DETECTOR.getDefaultState().with(DaylightDetectorBlock.INVERTED, inverted)
                                .with(DaylightDetectorBlock.POWER, power), Blocks.STONE);
            }
            for (String[] spec : new String[][]{
                    {"oak_shelf", "side_chain", "center"}, {"beehive", "honey_level", "5"},
                    {"bee_nest", "honey_level", "5"}, {"dried_ghast", "hydration", "3"},
                    {"sculk_sensor", "sculk_sensor_phase", "active"},
                    {"calibrated_sculk_sensor", "sculk_sensor_phase", "cooldown"},
                    {"sculk_catalyst", "bloom", "true"}, {"sculk_shrieker", "shrieking", "true"},
                    {"crafter", "crafting", "true"}, {"composter", "level", "8"},
                    {"lectern", "has_book", "true"}, {"jukebox", "has_record", "true"},
                    {"brewing_stand", "has_bottle_0", "true"},
                    {"chiseled_bookshelf", "slot_0_occupied", "true"},
                    {"respawn_anchor", "charges", "4"}, {"cake", "bites", "4"},
                    {"end_portal_frame", "eye", "true"}
            }) {
                Block block = Registries.BLOCK.get(Identifier.ofVanilla(spec[0]));
                initial(client, blocks, schematic, printer, candidate, change(block.getDefaultState(), spec[1], spec[2]), Blocks.STONE);
            }
            for (Block block : new Block[]{Blocks.TURTLE_EGG, Blocks.PINK_PETALS, Blocks.WILDFLOWERS, Blocks.LEAF_LITTER}) {
                String count = block == Blocks.TURTLE_EGG ? "eggs" : block == Blocks.LEAF_LITTER ? "segment_amount" : "flower_amount";
                blocks.clear();
                blocks.put(TARGET.down(), Blocks.GRASS_BLOCK.getDefaultState());
                schematic.desired = change(block.getDefaultState(), count, "4");
                if (block == Blocks.TURTLE_EGG) schematic.desired = change(schematic.desired, "hatch", "2");
                ItemStack material = new ItemStack(block);
                client.player.getInventory().main.set(0, material);
                for (int placed = 0; placed < 4; placed++) {
                    SchematicBlockState cell = new SchematicBlockState(client.world, schematic, TARGET);
                    check((boolean) candidate.invoke(printer, cell, Set.of()), "Missing stack candidate " + cell);
                    BlockState step = NativePlacementSolver.placementTarget(client.world, schematic, TARGET, schematic.desired);
                    var solution = NativePlacementSolver.solveStationary(TARGET, step, material, 4.5, true);
                    check(solution != null, "Stack step failed " + cell);
                    check(solution.predicted().get(block.getStateManager().getProperty(count)).toString().equals("" + (placed + 1)),
                            "Did not add exactly one item " + cell);
                    if (placed > 0) check(new BlockReplacementGuide(cell).canExecute(client.player), "Legacy top-up failed " + cell);
                    blocks.put(TARGET, solution.predicted());
                    check(NativePlacementSolver.revalidate(TARGET, step, material, solution, 4.5) == null,
                            "Stale stack action could add an extra item " + cell);
                }
                check(!(boolean) candidate.invoke(printer, new SchematicBlockState(client.world, schematic, TARGET), Set.of()),
                        "Finished stack remains a placement candidate");
            }
            for (Block block : new Block[]{Blocks.GLOW_LICHEN, Blocks.SCULK_VEIN, Blocks.RESIN_CLUMP}) {
                blocks.clear();
                for (Direction direction : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.EAST}) {
                    blocks.put(TARGET.offset(direction), Blocks.STONE.getDefaultState());
                }
                schematic.desired = block.getDefaultState().with(MultifaceBlock.getProperty(Direction.DOWN), true)
                        .with(MultifaceBlock.getProperty(Direction.NORTH), true);
                ItemStack material = new ItemStack(block);
                client.player.getInventory().main.set(0, material);
                for (int placed = 0; placed < 2; placed++) {
                    SchematicBlockState cell = new SchematicBlockState(client.world, schematic, TARGET);
                    check((boolean) candidate.invoke(printer, cell, Set.of()), "Missing face candidate " + cell);
                    BlockState step = NativePlacementSolver.placementTarget(client.world, schematic, TARGET, schematic.desired);
                    var solution = NativePlacementSolver.solveStationary(TARGET, step, material, 4.5, true);
                    check(solution != null && MultifaceBlock.collectDirections(solution.predicted()).size() == placed + 1,
                            "Did not add exactly one face " + cell);
                    check(!solution.predicted().get(MultifaceBlock.getProperty(Direction.EAST)), "Invented an unwanted face");
                    if (placed > 0) check(new BlockReplacementGuide(cell).canExecute(client.player), "Legacy face top-up failed " + cell);
                    if (placed > 0) {
                        BlockState before = blocks.remove(TARGET);
                        check(NativePlacementSolver.revalidate(TARGET, solution.predicted(), material, solution, 4.5) == null,
                                "A removed existing face changed the frozen action target");
                        blocks.put(TARGET, before);
                    }
                    blocks.put(TARGET, solution.predicted());
                    check(NativePlacementSolver.revalidate(TARGET, solution.predicted(), material, solution, 4.5) == null, "Stale face action still valid");
                }
                check(!NativePlacementSolver.canAddFaces(blocks.get(TARGET), schematic.desired), "Completed faces not recognized");
                check(!NativePlacementSolver.canAddFaces(blocks.get(TARGET).with(MultifaceBlock.getProperty(Direction.EAST), true),
                        schematic.desired.with(MultifaceBlock.getProperty(Direction.UP), true)), "Unexpected existing face accepted");
            }
            blocks.clear();
            blocks.put(TARGET.north(), Blocks.STONE.getDefaultState());
            blocks.put(TARGET.east(), Blocks.STONE.getDefaultState());
            schematic.desired = Blocks.GLOW_LICHEN.getDefaultState().with(MultifaceBlock.getProperty(Direction.NORTH), true)
                    .with(MultifaceBlock.getProperty(Direction.EAST), true);
            PrinterConfig.STRICT_BLOCK_FACE_CHECK.setBooleanValue(true);
            var visibleFace = NativePlacementSolver.solveStationary(TARGET, schematic.desired, new ItemStack(Items.GLOW_LICHEN), 4.5, false);
            check(visibleFace != null && visibleFace.predicted().get(MultifaceBlock.getProperty(Direction.EAST))
                    && !visibleFace.predicted().get(MultifaceBlock.getProperty(Direction.NORTH)), "Hidden first support blocked a visible planned face");
            check(NativePlacementSolver.solveMoving(TARGET, schematic.desired, new ItemStack(Items.GLOW_LICHEN), 4.5, false, false) != null,
                    "Moving solver could not select the visible planned face");
            PrinterConfig.STRICT_BLOCK_FACE_CHECK.setBooleanValue(false);
            Field randomField = net.minecraft.world.World.class.getDeclaredField("random");
            randomField.setAccessible(true);
            Object oldRandom = randomField.get(client.world);
            try {
                randomField.set(client.world, net.minecraft.util.math.random.Random.create(123));
                for (Block vine : new Block[]{Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT}) {
                    blocks.clear();
                    blocks.put(TARGET.up(), Blocks.STONE.getDefaultState());
                    schematic.desired = vine.getDefaultState().with(CaveVines.BERRIES, true);
                    check((boolean) candidate.invoke(printer, new SchematicBlockState(client.world, schematic, TARGET), Set.of()),
                            "Berry vine was not a placement candidate");
                    BlockState goal = NativePlacementSolver.placementTarget(client.world, schematic, TARGET, schematic.desired);
                    check(NativePlacementSolver.solveStationary(TARGET, goal, new ItemStack(Items.GLOW_BERRIES), 4.5, true) != null,
                            "Berry vine initial head could not be placed");
                    for (Block below : new Block[]{Blocks.CAVE_VINES, Blocks.CAVE_VINES_PLANT}) {
                        blocks.put(TARGET.down(), below.getDefaultState());
                        var joined = NativePlacementSolver.solveStationary(TARGET, goal, new ItemStack(Items.GLOW_BERRIES), 4.5, true);
                        check(joined != null && joined.predicted().isOf(Blocks.CAVE_VINES_PLANT), "Berry vine body above existing vine rejected");
                    }
                }
            } finally {
                randomField.set(client.world, oldRandom);
            }
            initial(client, blocks, schematic, printer, candidate, Blocks.BAMBOO.getDefaultState(), Blocks.DIRT);
            check(NativePlacementSolver.statesMatch(Blocks.BAMBOO.getDefaultState(), Blocks.BAMBOO_SAPLING.getDefaultState()), "Bamboo shoot stage rejected");
            check(!NativePlacementSolver.statesMatch(Blocks.OAK_LOG.getDefaultState(), Blocks.OAK_SAPLING.getDefaultState()), "Unrelated sapling accepted");
            initial(client, blocks, schematic, printer, candidate,
                    change(Blocks.POINTED_DRIPSTONE.getDefaultState(), "thickness", "frustum"), Blocks.STONE);
            BlockState tip = change(Blocks.POINTED_DRIPSTONE.getDefaultState(), "thickness", "tip");
            check(NativePlacementSolver.solveStationary(TARGET.up(), tip, new ItemStack(Items.POINTED_DRIPSTONE), 4.5, true) != null,
                    "Second dripstone segment still cannot start");
            check(!NativePlacementSolver.statesMatch(Blocks.SCULK_SHRIEKER.getDefaultState(),
                    change(Blocks.SCULK_SHRIEKER.getDefaultState(), "can_summon", "true")), "can_summon was relaxed");
            check(!NativePlacementSolver.statesMatch(Blocks.CREAKING_HEART.getDefaultState(),
                    change(Blocks.CREAKING_HEART.getDefaultState(), "natural", "true")), "natural was relaxed");
            check(!NativePlacementSolver.statesMatch(Blocks.TRIAL_SPAWNER.getDefaultState(),
                    change(Blocks.TRIAL_SPAWNER.getDefaultState(), "ominous", "true")), "ominous was relaxed");
            check(!NativePlacementSolver.statesMatch(Blocks.LIGHT.getDefaultState(),
                    change(Blocks.LIGHT.getDefaultState(), "level", "1")), "Unrelated level property was relaxed");
            check(!NativePlacementSolver.statesMatch(Blocks.CHEST.getDefaultState(),
                    change(Blocks.CHEST.getDefaultState(), "type", "left")), "Chest geometry was relaxed");
            return assertions;
        } finally {
            for (var entry : oldTags.entrySet()) bindTags.invoke(entry.getKey().getRegistryEntry(), entry.getValue());
            inventoryField.set(client.player, oldInventory);
            abilitiesField.set(client.player, oldAbilities);
            PrinterConfig.RAYCAST.setBooleanValue(raycast);
            PrinterConfig.STRICT_BLOCK_FACE_CHECK.setBooleanValue(strictFace);
            blocks.clear();
        }
    }

    private static void initial(MinecraftClient client, Map<BlockPos, BlockState> blocks, Schematic schematic,
                                Printer printer, Method candidate, BlockState desired, Block support) throws Exception {
        blocks.clear();
        blocks.put(TARGET.down(), support.getDefaultState());
        schematic.desired = desired;
        var cell = new SchematicBlockState(client.world, schematic, TARGET);
        check((boolean) candidate.invoke(printer, cell, Set.of()), "Initial candidate rejected " + desired);
        BlockState step = NativePlacementSolver.placementTarget(client.world, schematic, TARGET, desired);
        ItemStack material = new ItemStack(PlacementStages.initialTarget(desired).getBlock());
        var solution = NativePlacementSolver.solveStationary(TARGET, step, material, 4.5, true);
        check(solution != null, "Initial native placement failed " + desired);
        check(NativePlacementSolver.revalidate(TARGET, step, material, solution, 4.5) != null, "Initial revalidation failed " + desired);
        blocks.put(TARGET, solution.predicted());
        check(!(boolean) candidate.invoke(printer, new SchematicBlockState(client.world, schematic, TARGET), Set.of()),
                "Existing shell would be placed again " + desired);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockState change(BlockState state, String name, String value) {
        Property property = state.getBlock().getStateManager().getProperty(name);
        return state.with(property, (Comparable) property.parse(value).orElseThrow());
    }

    private static Set<String> tagValues(String id) throws Exception {
        String[] path = id.split(":", 2);
        try (var in = Block.class.getClassLoader().getResourceAsStream("data/" + path[0] + "/tags/block/" + path[1] + ".json")) {
            if (in == null) throw new AssertionError("Missing vanilla tag " + id);
            Set<String> result = new HashSet<>();
            for (JsonElement value : JsonParser.parseReader(new InputStreamReader(in)).getAsJsonObject().getAsJsonArray("values")) {
                String entry = value.isJsonObject() ? value.getAsJsonObject().get("id").getAsString() : value.getAsString();
                if (entry.startsWith("#")) result.addAll(tagValues(entry.substring(1))); else result.add(entry);
            }
            return result;
        }
    }

    private static void loadPlantTags(Map<Block, List<TagKey<Block>>> previous, Method bind) throws Exception {
        Map<Block, Set<TagKey<Block>>> additions = new HashMap<>();
        for (String id : List.of("minecraft:dirt", "minecraft:bamboo_plantable_on", "minecraft:sand")) {
            TagKey<Block> key = TagKey.of(RegistryKeys.BLOCK, Identifier.of(id));
            for (String entry : tagValues(id)) {
                Block block = Registries.BLOCK.get(Identifier.of(entry));
                previous.computeIfAbsent(block, b -> b.getRegistryEntry().streamTags().toList());
                additions.computeIfAbsent(block, b -> new HashSet<>(previous.get(b))).add(key);
            }
        }
        for (var entry : additions.entrySet()) bind.invoke(entry.getKey().getRegistryEntry(), entry.getValue());
    }

    private static final class Schematic extends WorldSchematic {
        private BlockState desired;
        private Schematic() { super(null, null, null, null); }
        @Override public BlockState getBlockState(BlockPos pos) { return TARGET.equals(pos) ? desired : Blocks.AIR.getDefaultState(); }
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
