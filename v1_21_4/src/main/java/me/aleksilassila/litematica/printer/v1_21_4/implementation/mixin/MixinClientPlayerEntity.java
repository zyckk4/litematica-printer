package me.aleksilassila.litematica.printer.v1_21_4.implementation.mixin;

import com.mojang.authlib.GameProfile;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.v1_21_4.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.v1_21_4.Printer;
import me.aleksilassila.litematica.printer.v1_21_4.SignWriteQueue;
import me.aleksilassila.litematica.printer.v1_21_4.config.PrinterConfig;
import net.minecraft.block.entity.SignBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.network.packet.c2s.play.UpdateSignC2SPacket;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public class MixinClientPlayerEntity extends AbstractClientPlayerEntity {

    @Shadow
    protected MinecraftClient client;
    @Shadow
    public ClientPlayNetworkHandler networkHandler;
    @Unique private SignWriteQueue printer$signWrites;

    public MixinClientPlayerEntity(ClientWorld world, GameProfile profile) {
        super(world, profile);
    }

    @Inject(at = @At("TAIL"), method = "tick")
    public void tick(CallbackInfo ci) {
        if (LitematicaMixinMod.freeLook.getPrevPerspective() == null) {
            LitematicaMixinMod.freeLook.setPrevPerspective(client.options.getPerspective());
        }
        if (LitematicaMixinMod.printer != null) {
//            LitematicaMixinMod.printer.actionHandler.onPostTick();
        }
    }

    @Inject(at = @At("RETURN"), method = "tick")
    private void finishInventoryTick(CallbackInfo ci) {
        // Movement-tail actions must observe the same refill delay as queued actions.
        Printer.inventoryManager.tick();
    }

    @Inject(at = @At("HEAD"), method = "tick")
    public void tickHead(CallbackInfo ci) {
        ClientPlayerEntity clientPlayer = (ClientPlayerEntity) (Object) this;
        if (printer$signWrites != null) {
            if (client.player != clientPlayer || client.world == null || !clientPlayer.isAlive()) {
                printer$signWrites.clear();
            } else {
                printer$signWrites.tick(System.nanoTime());
            }
        }
        if (LitematicaMixinMod.printer == null || LitematicaMixinMod.printer.player != clientPlayer) {
            if (LitematicaMixinMod.printer != null) LitematicaMixinMod.printer.actionHandler.clear();
            System.out.println("Initializing printer, player: " + clientPlayer + ", client: " + client);
            LitematicaMixinMod.printer = new Printer(client, clientPlayer);
        }
        LitematicaMixinMod.printer.actionHandler.processPreviousTickActions();
        LitematicaMixinMod.printer.onGameTick();
        LitematicaMixinMod.printer.actionHandler.processCurrentTickActions();
        LitematicaMixinMod.freeLook.onGameTick();
        LitematicaMixinMod.movementHandler.onGameTick();
    }

    @Inject(method = "openEditSignScreen", at = @At("HEAD"), cancellable = true)
    private void printer$queueSignText(SignBlockEntity sign, boolean front, CallbackInfo ci) {
        BlockPos pos = sign.getPos();
        ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        ClientWorld world = client.world;
        if (!Printer.canRunActions(client, player) || sign.getWorld() != world || schematic == null
                || !(schematic.getBlockEntity(pos) instanceof SignBlockEntity target)) {
            // Opening either face for manual editing supersedes any delayed automatic submission.
            if (printer$signWrites != null) printer$signWrites.cancel(pos);
            return;
        }

        var text = target.getText(front);
        UpdateSignC2SPacket packet = new UpdateSignC2SPacket(pos, front,
                text.getMessage(0, false).getString(), text.getMessage(1, false).getString(),
                text.getMessage(2, false).getString(), text.getMessage(3, false).getString());
        ClientPlayNetworkHandler network = networkHandler;
        if (printer$signWrites == null) printer$signWrites = new SignWriteQueue();
        printer$signWrites.schedule(pos, System.nanoTime(), PrinterConfig.MIN_SIGN_DELAY_MS.getIntegerValue(),
                () -> client.player == player && player.isAlive() && client.world == world && client.getNetworkHandler() == network
                        && !sign.isRemoved() && !sign.isWaxed() && world.getBlockEntity(pos) == sign
                        && player.canInteractWithBlockAt(pos, 4.0D), // Vanilla SignBlockEntity editor distance.
                () -> network.sendPacket(packet));
        ci.cancel();
    }
}
