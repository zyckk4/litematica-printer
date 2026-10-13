package me.aleksilassila.litematica.printer.v1_21_4.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import me.aleksilassila.litematica.printer.v1_21_4.config.PrinterConfig;
import me.aleksilassila.litematica.printer.v1_21_4.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.v1_21_4.MovementHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.util.PlayerInput;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class MixinClientPlayerEntity {
    // 1.21.4 samples/adjusts keys and decides sprinting in tickMovement,
    // then LivingEntity invokes tickNewAi before its jump/travel physics.
    @Inject(method = "tickNewAi", at = @At("HEAD"))
    private void printer$validateMovementInput(CallbackInfo ci) {
        MovementHandler.validateMovementInput((ClientPlayerEntity) (Object) this);
    }

    @ModifyExpressionValue(method = "tick", at = @At(value = "FIELD",
            target = "Lnet/minecraft/client/input/Input;playerInput:Lnet/minecraft/util/PlayerInput;"))
    private PlayerInput printer$declareInput(PlayerInput original) {
        return MovementHandler.declaredInput(original);
    }

    @ModifyExpressionValue(method = "sendMovementPackets", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;getYaw()F"))
    private float printer$movementYaw(float original) {
        return MovementHandler.serverYaw(original);
    }

    @ModifyExpressionValue(method = "sendMovementPackets", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/network/ClientPlayerEntity;getPitch()F"))
    private float printer$movementPitch(float original) {
        return MovementHandler.serverPitch(original);
    }

    @Inject(method = "sendMovementPackets", at = @At("RETURN"))
    private void printer$flushNativePlacement(CallbackInfo ci) {
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        MovementHandler.onMovementSent(player);
        if (LitematicaMixinMod.printer != null) {
            LitematicaMixinMod.printer.flushNativePlacements();
        }
    }

    @Inject(method = "tickMovement", at = @At("HEAD"))
    public void tickMovement(CallbackInfo ci) {
        MixinClientPlayerEntity clientPlayer = this;
        if (PrinterConfig.PREVENT_DOUBLE_TAP_SPRINTING.getBooleanValue()) {
            ((MixinAccessorClientPlayerEntity) clientPlayer).setTicksLeftToDoubleTapSprint(0);
        }
    }
}
