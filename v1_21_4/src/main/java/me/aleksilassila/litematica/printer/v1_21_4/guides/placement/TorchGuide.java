package me.aleksilassila.litematica.printer.v1_21_4.guides.placement;

import me.aleksilassila.litematica.printer.v1_21_4.SchematicBlockState;
import me.aleksilassila.litematica.printer.v1_21_4.TorchPlacement;
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
