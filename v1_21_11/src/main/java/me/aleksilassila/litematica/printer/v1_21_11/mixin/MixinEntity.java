package me.aleksilassila.litematica.printer.v1_21_11.mixin;

import me.aleksilassila.litematica.printer.v1_21_11.MovementHandler;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Match modern vanilla's cardinal/diagonal speed to declared lattice input. */
@Mixin(Entity.class)
public class MixinEntity {
    @ModifyVariable(method = "updateVelocity", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private float printer$movementSpeed(float speed) {
        MinecraftClient client = MinecraftClient.getInstance();
        return (Object) this == client.player ? speed * MovementHandler.movementSpeedScale() : speed;
    }
}
