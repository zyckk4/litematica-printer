package me.aleksilassila.litematica.printer.v1_21_4;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;

/** One counter pair per play connection. The Netty writer and the client thread may differ. */
public final class InventoryFenceOrder {
    public interface Access {
        InventoryFenceOrder printer$getInventoryFenceOrder();
    }

    private long requests;
    private long replies;
    private long ownedRequest;
    private ClientStatusC2SPacket ownedPacket;

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
    }

    synchronized ClientStatusC2SPacket received() {
        if (replies >= requests || ++replies != ownedRequest) return null;
        ClientStatusC2SPacket packet = ownedPacket;
        ownedPacket = null;
        ownedRequest = 0;
        return packet;
    }
}
