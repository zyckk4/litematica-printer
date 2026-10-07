package me.aleksilassila.litematica.printer.v1_21_11.mixin;

import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.util.FileType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.nio.file.Path;

@Mixin(LitematicaSchematic.class)
public interface LitematicaSchematicAccessor {
    @Invoker("<init>")
    static LitematicaSchematic invokeConstructor(Path path, FileType fileType) {
        throw new AssertionError();
    }
}
