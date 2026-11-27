package me.aleksilassila.litematica.printer.v1_21_4;

import org.jetbrains.annotations.Nullable;

/** Rotation evidence from ordered outgoing packets on one play connection. */
public interface NativePacketLook {
    record Angles(float yaw, float pitch, boolean uncertain) {}

    @Nullable Angles printer$lastLook();
}
