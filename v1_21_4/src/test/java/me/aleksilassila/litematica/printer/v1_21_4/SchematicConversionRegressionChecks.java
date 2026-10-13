package me.aleksilassila.litematica.printer.v1_21_4;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.util.FileType;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.gui.interfaces.IMessageConsumer;
import net.minecraft.SharedConstants;
import net.minecraft.block.Blocks;
import net.minecraft.block.StairsBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtHelper;
import net.minecraft.nbt.NbtInt;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtList;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.math.Direction;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

/** Round-trip the target Litematica version's real structure importer and file APIs. */
public final class SchematicConversionRegressionChecks {
    private static int assertions;

    public static int run() throws Exception {
        assertions = 0;
        Path temporary = Files.createTempDirectory("printer-schematic-conversion-");
        DynamicRegistryManager previous = SchematicWorldHandler.INSTANCE.getRegistryManager();
        MinecraftClient previousClient = MinecraftClient.getInstance();
        Field clientInstance = MinecraftClient.class.getDeclaredField("instance");
        clientInstance.setAccessible(true);
        try {
            SchematicWorldHandler.INSTANCE.setDynamicRegistryManager(DynamicRegistryManager.of(Registries.REGISTRIES));
            Path source = temporary.resolve("nested.build.nbt");
            NbtIo.writeCompressed(structure(), source);
            byte[] originalBytes = Files.readAllBytes(source);
            LitematicaSchematic imported = LitematicaSchematic.createFromFile(
                    temporary.toFile(), source.getFileName().toString(), FileType.VANILLA_STRUCTURE);
            check(imported != null, "Vanilla structure fixture did not import");

            Path output = temporary.resolve("converted");
            LitematicaSchematic converted = SchematicConverter.convertAndReturn(
                    imported, output.toFile(), source.getFileName().toString());
            check(converted != null, "Converted schematic did not reload");
            check(Files.isRegularFile(output.resolve("nested.build.litematic")), "Output path or extension is wrong");
            check(converted.getFile().toPath().equals(output.resolve("nested.build.litematic")),
                    "Reload used a full file path as a directory");
            check(converted.getSubRegionCount() == 1, "Conversion changed region count");
            String region = converted.getAreaSizes().keySet().iterator().next();
            check(converted.getSubRegionContainer(region).get(0, 0, 0).equals(Blocks.STONE.getDefaultState()),
                    "Conversion lost the solid block");
            check(converted.getSubRegionContainer(region).get(1, 0, 0).equals(
                    Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.EAST)),
                    "Conversion changed directional block state");
            check(converted.getMetadata().getAuthor().equals("printer-regression"), "Conversion changed author metadata");
            check(Arrays.equals(originalBytes, Files.readAllBytes(source)), "Conversion changed the original structure");

            Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
            unsafeField.setAccessible(true);
            MinecraftClient client = (MinecraftClient) ((Unsafe) unsafeField.get(null)).allocateInstance(MinecraftClient.class);
            MessageScreen messages = new MessageScreen();
            client.currentScreen = messages;
            clientInstance.set(null, client);
            Path blockedParent = temporary.resolve("not-a-directory");
            Files.writeString(blockedParent, "keep");
            Path blockedDirectory = blockedParent.resolve("child");
            // A failed mkdirs must reach Litematica's ordinary false result,
            // without a missing GUI fixture throwing inside its error report.
            check(!imported.writeToFile(blockedDirectory.toFile(), "nested.build.litematic", true),
                    "Blocked destination did not return false from the real writer");
            check(SchematicConverter.convertAndReturn(imported, blockedDirectory.toFile(), "nested.build.nbt") == null,
                    "Failed write was reported as a successful conversion");
            check(messages.errors == 2 && "litematica.error.schematic_write_to_file_failed.directory_creation_failed".equals(messages.lastMessage),
                    "Failed writes did not report the expected directory error");
            check(Files.readString(blockedParent).equals("keep"), "Failed conversion changed the blocking file");
            check(imported.getSubRegionCount() == 1, "Failed conversion discarded the imported regions");
            check(SchematicConverter.convertAndReturn(null, output.toFile(), "missing.nbt") == null,
                    "Missing imported schematic was accepted");
        } finally {
            clientInstance.set(null, previousClient);
            SchematicWorldHandler.INSTANCE.setDynamicRegistryManager(previous.toImmutable());
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
        return assertions;
    }

    private static final class MessageScreen extends Screen implements IMessageConsumer {
        private int errors;
        private String lastMessage;

        private MessageScreen() {
            super(Text.empty());
        }

        @Override
        public void addMessage(Message.MessageType type, String message, Object... args) {
            addMessage(type, 0, message, args);
        }

        @Override
        public void addMessage(Message.MessageType type, int displayTimeMs, String message, Object... args) {
            if (type == Message.MessageType.ERROR) errors++;
            lastMessage = message;
        }
    }

    private static NbtCompound structure() {
        NbtCompound structure = new NbtCompound();
        structure.putInt("DataVersion", SharedConstants.getGameVersion().getSaveVersion().getId());
        structure.putString("author", "printer-regression");
        structure.put("size", coordinates(2, 1, 1));
        NbtList palette = new NbtList();
        palette.add(NbtHelper.fromBlockState(Blocks.STONE.getDefaultState()));
        palette.add(NbtHelper.fromBlockState(Blocks.OAK_STAIRS.getDefaultState().with(StairsBlock.FACING, Direction.EAST)));
        structure.put("palette", palette);
        NbtList blocks = new NbtList();
        for (int x = 0; x < 2; x++) {
            NbtCompound block = new NbtCompound();
            block.put("pos", coordinates(x, 0, 0));
            block.putInt("state", x);
            blocks.add(block);
        }
        structure.put("blocks", blocks);
        structure.put("entities", new NbtList());
        return structure;
    }

    private static NbtList coordinates(int x, int y, int z) {
        NbtList coordinates = new NbtList();
        coordinates.add(NbtInt.of(x));
        coordinates.add(NbtInt.of(y));
        coordinates.add(NbtInt.of(z));
        return coordinates;
    }

    private static void check(boolean condition, String message) {
        assertions++;
        if (!condition) throw new AssertionError(message);
    }
}
