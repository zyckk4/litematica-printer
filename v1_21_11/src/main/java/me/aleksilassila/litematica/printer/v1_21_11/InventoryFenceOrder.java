package me.aleksilassila.litematica.printer.v1_21_11;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.util.math.MathHelper;

/** One counter pair per play connection. The Netty writer and the client thread may differ. */
public final class InventoryFenceOrder {
    public interface Access {
        InventoryFenceOrder printer$getInventoryFenceOrder();
    }

    private long requests;
    private long replies;
    private long ownedRequest;
    private ClientStatusC2SPacket ownedPacket;
    private ClientStatusC2SPacket rotationPacket;
    private long rotationRequest;
    private float rotationYaw;
    private float lastSentYaw = Float.NaN;

    public static InventoryFenceOrder get(ClientPlayNetworkHandler handler) {
        return ((Access) handler).printer$getInventoryFenceOrder();
    }

    synchronized ClientStatusC2SPacket expect() {
        ownedRequest = 0;
        ownedPacket = new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS);
        return ownedPacket;
    }

    /** Called in wire order for every REQUEST_STATS, including the statistics screen's requests. */
    public synchronized void sent(ClientStatusC2SPacket packet) {
        requests++;
        if (ownedPacket == packet) ownedRequest = requests;
        if (rotationPacket == packet) {
            if (Float.isFinite(lastSentYaw) && MathHelper.wrapDegrees(lastSentYaw - rotationYaw) != 0) rotationPacket = null;
            else rotationRequest = requests;
        }
    }

    synchronized ClientStatusC2SPacket expectRotation(float yaw) {
        rotationRequest = 0;
        rotationYaw = yaw;
        rotationPacket = new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS);
        return rotationPacket;
    }

    /** A different yaw written by vanilla or another mod invalidates even a completed fence. */
    public synchronized void sentYaw(float yaw) {
        lastSentYaw = yaw;
        if (rotationPacket != null && MathHelper.wrapDegrees(yaw - rotationYaw) != 0.0F) rotationPacket = null;
    }

    synchronized float lastSentYaw() {
        return lastSentYaw;
    }

    synchronized boolean holdsRotation(ClientStatusC2SPacket packet) {
        return rotationPacket != null && rotationPacket == packet;
    }

    synchronized boolean receivedRotation(ClientStatusC2SPacket packet) {
        return holdsRotation(packet) && rotationRequest != 0 && replies >= rotationRequest;
    }

    synchronized ClientStatusC2SPacket received() {
        if (replies >= requests || ++replies != ownedRequest) return null;
        ClientStatusC2SPacket packet = ownedPacket;
        ownedPacket = null;
        ownedRequest = 0;
        return packet;
    }
}
