package me.aleksilassila.litematica.printer.v1_21_11;

import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.c2s.play.ClientStatusC2SPacket;
import net.minecraft.util.math.MathHelper;

/** One counter pair per play connection. The Netty writer and the client thread may differ. */
public final class InventoryFenceOrder {
    private static final int MIN_ROTATION_READ_INTERVAL = 2;
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
    private ClientStatusC2SPacket queuedRotationRead;
    private long outstandingRotationRead;
    private int lastRotationReadAge = -MIN_ROTATION_READ_INTERVAL;
    private int firstPlayTime = -1;
    private boolean playTimeAdvanced;
    private boolean followUpSent;

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
        if (queuedRotationRead == packet) {
            queuedRotationRead = null;
            outstandingRotationRead = requests;
        }
        if (ownedPacket == packet) ownedRequest = requests;
        if (rotationPacket == packet) {
            if (Float.isFinite(lastSentYaw) && MathHelper.wrapDegrees(lastSentYaw - rotationYaw) != 0) rotationPacket = null;
            else rotationRequest = requests;
        }
    }

    synchronized ClientStatusC2SPacket expectRotation(float yaw, int age) {
        ClientStatusC2SPacket packet = reserveRotationRead(age);
        if (packet == null) return null;
        rotationRequest = 0;
        rotationYaw = yaw;
        rotationPacket = packet;
        firstPlayTime = -1;
        playTimeAdvanced = false;
        followUpSent = false;
        return rotationPacket;
    }

    synchronized ClientStatusC2SPacket followUp(ClientStatusC2SPacket marker, int age) {
        if (!receivedRotation(marker) || requests != replies || firstPlayTime < 0 || firstPlayTime == Integer.MAX_VALUE
                || playTimeAdvanced || followUpSent) return null;
        ClientStatusC2SPacket packet = reserveRotationRead(age);
        if (packet != null) followUpSent = true;
        return packet;
    }

    private ClientStatusC2SPacket reserveRotationRead(int age) {
        // Include writes queued on Netty and reads for an abandoned yaw. Any later
        // statistics request (inventory, vanilla UI) can supply the second sample.
        if (queuedRotationRead != null || outstandingRotationRead > replies) return null;
        if (age >= lastRotationReadAge && age - lastRotationReadAge < MIN_ROTATION_READ_INTERVAL) return null;
        lastRotationReadAge = age;
        queuedRotationRead = new ClientStatusC2SPacket(ClientStatusC2SPacket.Mode.REQUEST_STATS);
        return queuedRotationRead;
    }

    /** Invalidate at enqueue time too: a placement can be planned before Netty writes this look. */
    public synchronized void invalidateDifferentYaw(float yaw) {
        if (rotationPacket != null && MathHelper.wrapDegrees(yaw - rotationYaw) != 0.0F) rotationPacket = null;
    }

    public synchronized void sentYaw(float yaw) {
        lastSentYaw = yaw;
        invalidateDifferentYaw(yaw);
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

    synchronized boolean hasPlayerTick(ClientStatusC2SPacket marker) {
        return holdsRotation(marker) && playTimeAdvanced;
    }

    synchronized ClientStatusC2SPacket received(int playTime) {
        if (replies >= requests) return null;
        replies++;
        if (rotationPacket != null && rotationRequest != 0 && replies >= rotationRequest && playTime >= 0) {
            // In ServerPlayerEntity.playerTick, PLAY_TIME increments after tickMovement
            // copies body yaw to head yaw. Both absolute samples must follow the turn.
            if (firstPlayTime < 0) firstPlayTime = playTime;
            else if (playTime > firstPlayTime) playTimeAdvanced = true;
        }
        if (replies != ownedRequest) return null;
        ClientStatusC2SPacket packet = ownedPacket;
        ownedPacket = null;
        ownedRequest = 0;
        return packet;
    }
}
