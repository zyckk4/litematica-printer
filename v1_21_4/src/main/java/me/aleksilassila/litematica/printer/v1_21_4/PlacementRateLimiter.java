package me.aleksilassila.litematica.printer.v1_21_4;

import java.util.ArrayDeque;

/** Monotonic rolling budget shared by printer block and item interactions. */
public final class PlacementRateLimiter {
    public static final int MAX_WINDOW_MILLIS = 10_000;
    private static final long HISTORY_NANOS = MAX_WINDOW_MILLIS * 1_000_000L;
    private final ArrayDeque<Long> sent = new ArrayDeque<>();
    private int lastTick = Integer.MIN_VALUE;
    private int sentThisTick;

    public boolean available(long now, int tick, int burst, int windowMs, int windowLimit) {
        // Keep enough history for a later window increase, including after a
        // temporary decrease. Changing settings must not grant a fresh budget.
        while (!sent.isEmpty() && now - sent.peekFirst() >= HISTORY_NANOS) sent.removeFirst();
        if (tick != lastTick) {
            lastTick = tick;
            sentThisTick = 0;
        }
        if (burst <= 0 || windowLimit <= 0 || windowMs <= 0 || windowMs > MAX_WINDOW_MILLIS
                || sentThisTick >= burst) return false;
        long windowNanos = windowMs * 1_000_000L;
        int active = 0;
        var newestFirst = sent.descendingIterator();
        while (newestFirst.hasNext()) {
            if (now - newestFirst.next() >= windowNanos) break;
            if (++active >= windowLimit) return false;
        }
        return true;
    }

    public boolean tryAcquire(long now, int tick, int burst, int windowMs, int windowLimit) {
        if (!available(now, tick, burst, windowMs, windowLimit)) return false;
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
