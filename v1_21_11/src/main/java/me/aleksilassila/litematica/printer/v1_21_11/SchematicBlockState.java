package me.aleksilassila.litematica.printer.v1_21_11;

import fi.dy.masa.litematica.world.WorldSchematic;
import net.minecraft.block.BlockState;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

public class SchematicBlockState {
    public final World world;
    public final WorldSchematic schematic;

    public final BlockPos blockPos;

    public final BlockState targetState;
    public final BlockState currentState;

    public SchematicBlockState(World world, WorldSchematic schematic, BlockPos blockPos) {
        this(world, schematic, blockPos, schematic.getBlockState(blockPos));
    }

    private SchematicBlockState(World world, WorldSchematic schematic, BlockPos blockPos, BlockState target) {
        this.world = world;
        this.schematic = schematic;

        this.blockPos = blockPos;

        this.targetState = target;
        this.currentState = world.getBlockState(blockPos);
    }

    public SchematicBlockState withTarget(BlockState target) {
        return target == targetState ? this : new SchematicBlockState(world, schematic, blockPos, target);
    }

    public SchematicBlockState offset(Direction direction) {
        return new SchematicBlockState(world, schematic, blockPos.offset(direction));
    }

    @Override
    public String toString() {
        return "SchematicBlockState{" +
                "world=" + world +
                ", schematic=" + schematic +
                ", blockPos=" + blockPos +
                ", targetState=" + targetState +
                ", currentState=" + currentState +
                '}';
    }
}
