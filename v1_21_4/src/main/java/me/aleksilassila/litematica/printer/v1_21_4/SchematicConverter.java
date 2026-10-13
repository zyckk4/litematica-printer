package me.aleksilassila.litematica.printer.v1_21_4;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.util.FileType;

import java.io.File;

/** Saves an already imported schematic in Litematica format on load. */
public final class SchematicConverter {
    private SchematicConverter() {
    }

    public static LitematicaSchematic convertAndReturn(LitematicaSchematic schematic, File directory, String sourceName) {
        if (schematic == null || directory == null || sourceName == null || sourceName.isEmpty()) {
            return null;
        }
        int extension = sourceName.lastIndexOf('.');
        String fileName = (extension > 0 ? sourceName.substring(0, extension) : sourceName) + ".litematic";
        try {
            // The loader has already imported the original format. Both of the
            // file APIs below take a directory and a name, not a complete path.
            if (!schematic.writeToFile(directory, fileName, true)) {
                return null;
            }
            return LitematicaSchematic.createFromFile(directory, fileName, FileType.LITEMATICA_SCHEMATIC);
        } catch (RuntimeException exception) {
            Printer.logger.warn("Could not save imported schematic {} as Litematica; retaining the loaded schematic",
                    sourceName, exception);
            return null;
        }
    }
}
