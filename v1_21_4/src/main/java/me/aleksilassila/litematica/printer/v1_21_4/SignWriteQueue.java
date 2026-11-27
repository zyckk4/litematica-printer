package me.aleksilassila.litematica.printer.v1_21_4;

import net.minecraft.util.math.BlockPos;

import java.util.LinkedHashMap;
import java.util.function.BooleanSupplier;

/** Client-thread edits with independent deadlines. Eligible edits retain their arrival order. */
public final class SignWriteQueue {
    private static final int MAX_WRITES_PER_TICK = 20;
    private final LinkedHashMap<BlockPos, Pending> pending = new LinkedHashMap<>();

    public void schedule(BlockPos pos, long nowNanos, int delayMs, BooleanSupplier isValid, Runnable send) {
        cancel(pos);
        if (delayMs == 0) {
            send.run();
            return;
        }

        pending.put(pos.toImmutable(), new Pending(nowNanos + delayMs * 1_000_000L, isValid, send));
    }

    public void cancel(BlockPos pos) {
        pending.remove(pos);
    }

    public void tick(long nowNanos) {
        int sent = 0;
        var it = pending.values().iterator();
        while (it.hasNext()) {
            Pending edit = it.next();
            boolean valid = edit.isValid().getAsBoolean();
            if (valid && (nowNanos - edit.dueNanos() < 0 || sent >= MAX_WRITES_PER_TICK)) continue;
            it.remove();
            if (valid) {
                edit.send().run();
                sent++;
            }
        }
    }

    public void clear() {
        pending.clear();
    }

    private record Pending(long dueNanos, BooleanSupplier isValid, Runnable send) {}
}
