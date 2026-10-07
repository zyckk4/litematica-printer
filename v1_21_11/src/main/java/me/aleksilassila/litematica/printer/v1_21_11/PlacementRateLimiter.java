package me.aleksilassila.litematica.printer.v1_21_11;

import java.util.ArrayDeque;

/** Monotonic rolling budget shared by all printer block interactions. */
public final class PlacementRateLimiter {
    private static final long WINDOW_NANOS = 310_000_000L;
    private static final int WINDOW_LIMIT = 9;
    private final ArrayDeque<Long> sent = new ArrayDeque<>();
    private int lastTick = Integer.MIN_VALUE;
    private int sentThisTick;

    public boolean available(long now, int tick, int burst) {
        while (!sent.isEmpty() && now - sent.peekFirst() >= WINDOW_NANOS) sent.removeFirst();
        if (tick != lastTick) {
            lastTick = tick;
            sentThisTick = 0;
        }
        return sent.size() < WINDOW_LIMIT && sentThisTick < burst;
    }

    public boolean tryAcquire(long now, int tick, int burst) {
        if (!available(now, tick, burst)) return false;
        sent.addLast(now);
        sentThisTick++;
        return true;
    }

    public void reset() {
        sent.clear();
        lastTick = Integer.MIN_VALUE;
        sentThisTick = 0;
    }
}
