package me.aleksilassila.litematica.printer.v1_21_11.guides.placement;

import me.aleksilassila.litematica.printer.v1_21_11.SchematicBlockState;
import me.aleksilassila.litematica.printer.v1_21_11.TorchPlacement;
import net.minecraft.util.math.Direction;

import java.util.List;

public class TorchGuide extends PropertySpecificGuesserGuide {
    public TorchGuide(SchematicBlockState state) {
        super(state);
    }

    @Override
    protected List<Direction> getPossibleSides() {
        return List.of(TorchPlacement.supportDirection(targetState));
    }
}
