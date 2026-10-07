package me.aleksilassila.litematica.printer.v1_21_11;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.util.FileType;

import java.nio.file.Path;

/** Converts a vanilla structure file to a Litematica schematic on load. */
public final class SchematicConverter {
    private SchematicConverter() {
    }

    public static LitematicaSchematic convertAndReturn(Path file, Path out) {
        String fileName = file.getFileName().toString().replaceFirst("\\.nbt$", "");
        LitematicaSchematic schematic = LitematicaSchematic.createFromFile(file, fileName, FileType.VANILLA_STRUCTURE);
        if (schematic == null) {
            return null;
        }
        Path output = out.resolve(fileName + ".litematic");
        schematic.writeToFile(out, fileName, true);
        return LitematicaSchematic.createFromFile(output, fileName, FileType.LITEMATICA_SCHEMATIC);
    }
}
