package me.aleksilassila.litematica.printer.v1_21_11.mixin;

import me.aleksilassila.litematica.printer.v1_21_11.LitematicaMixinMod;
import me.aleksilassila.litematica.printer.v1_21_11.Printer;
import me.aleksilassila.litematica.printer.v1_21_11.InventoryFenceOrder;
import me.aleksilassila.litematica.printer.v1_21_11.NativeHeadRotation;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.InventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerActionResponseS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRotationS2CPacket;
import net.minecraft.network.packet.s2c.play.ScreenHandlerSlotUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.StatisticsS2CPacket;
import net.minecraft.network.packet.s2c.play.SetPlayerInventoryS2CPacket;
import net.minecraft.network.packet.s2c.play.WorldTimeUpdateS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Vanilla has already switched to the client thread at these method tails. */
@Mixin(ClientPlayNetworkHandler.class)
public class NativePlacementNetworkMixin implements InventoryFenceOrder.Access {
    @Unique private InventoryFenceOrder printer$inventoryFenceOrder;

    @Override
    public synchronized InventoryFenceOrder printer$getInventoryFenceOrder() {
        if (printer$inventoryFenceOrder == null) printer$inventoryFenceOrder = new InventoryFenceOrder();
        return printer$inventoryFenceOrder;
    }

    @Unique
    private ClientPlayerEntity printer$inventoryPlayer() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.getNetworkHandler() == (Object) this ? client.player : null;
    }

    @Inject(method = "onScreenHandlerSlotUpdate", at = @At("TAIL"))
    private void printer$inventorySlot(ScreenHandlerSlotUpdateS2CPacket packet, CallbackInfo ci) {
        ClientPlayerEntity player = printer$inventoryPlayer();
        int slot = packet.getSlot() - PlayerScreenHandler.HOTBAR_START;
        if (player != null && packet.getSyncId() == 0 && PlayerInventory.isValidHotbarIndex(slot)) {
            Printer.observeHotbarContent(slot, player.getInventory().getStack(slot));
        }
        if (Printer.inventoryManager.tracksConnection((ClientPlayNetworkHandler) (Object) this)) {
            Printer.inventoryManager.onAuthoritativeSlot(packet.getSyncId(), packet.getRevision(), packet.getSlot());
        }
    }

    @Inject(method = "onInventory", at = @At("TAIL"))
    private void printer$fullInventory(InventoryS2CPacket packet, CallbackInfo ci) {
        ClientPlayerEntity player = printer$inventoryPlayer();
        if (player != null && packet.syncId() == 0 && packet.contents().size() == player.playerScreenHandler.slots.size()) {
            for (int slot = 0; slot < 9; slot++) Printer.observeHotbarContent(slot, player.getInventory().getStack(slot));
        }
        if (Printer.inventoryManager.tracksConnection((ClientPlayNetworkHandler) (Object) this)) {
            Printer.inventoryManager.onAuthoritativeInventory(packet.syncId(), packet.revision(), packet.contents().size());
        }
    }

    @Inject(method = "onStatistics", at = @At("TAIL"))
    private void printer$inventoryReadbackFence(StatisticsS2CPacket packet, CallbackInfo ci) {
        Printer.inventoryManager.onInventoryReadbackBarrier((ClientPlayNetworkHandler) (Object) this);
    }

    @Inject(method = "onWorldTimeUpdate", at = @At("TAIL"))
    private void printer$headRotationTime(WorldTimeUpdateS2CPacket packet, CallbackInfo ci) {
        NativeHeadRotation.onWorldTime((ClientPlayNetworkHandler) (Object) this, packet.time());
    }

    @Inject(method = "onPlayerRotation", at = @At("TAIL"))
    private void printer$headRotationCorrection(PlayerRotationS2CPacket packet, CallbackInfo ci) {
        NativeHeadRotation.clear();
    }

    @Inject(method = "onSetPlayerInventory", at = @At("TAIL"))
    private void printer$directInventorySlot(SetPlayerInventoryS2CPacket packet, CallbackInfo ci) {
        ClientPlayerEntity player = printer$inventoryPlayer();
        if (player != null && PlayerInventory.isValidHotbarIndex(packet.slot())) {
            Printer.observeHotbarContent(packet.slot(), player.getInventory().getStack(packet.slot()));
        }
    }

    @Inject(method = "onBlockUpdate", at = @At("TAIL"))
    private void printer$blockUpdate(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        Printer.confirmNativePlacement(packet.getPos(), packet.getState());
    }

    @Inject(method = "onPlayerActionResponse", at = @At("TAIL"))
    private void printer$acknowledgeBlockActions(PlayerActionResponseS2CPacket packet, CallbackInfo ci) {
        if (MinecraftClient.getInstance().getNetworkHandler() == (Object) this) {
            NativeHeadRotation.onActionResponse((ClientPlayNetworkHandler) (Object) this);
            Printer.acknowledgeBlockActions(packet.sequence());
        }
    }

    @Inject(method = "onChunkDeltaUpdate", at = @At("TAIL"))
    private void printer$chunkDelta(ChunkDeltaUpdateS2CPacket packet, CallbackInfo ci) {
        packet.visitUpdates(Printer::confirmNativePlacement);
    }

    @Inject(method = "onPlayerPositionLook", at = @At("TAIL"))
    private void printer$correction(PlayerPositionLookS2CPacket packet, CallbackInfo ci) {
        NativeHeadRotation.clear();
        if (LitematicaMixinMod.printer != null) LitematicaMixinMod.printer.onServerCorrection();
    }
}
