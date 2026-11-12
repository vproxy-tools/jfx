/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.glass.ui.godot;

import com.sun.glass.ui.Timer;
import java.util.concurrent.atomic.AtomicLong;

/** Glass timer whose callbacks are driven exclusively by host frame ticks. */
public final class GodotTimer extends Timer {
    private static final AtomicLong ids = new AtomicLong();
    private final GodotPulseScheduler scheduler;
    private Runnable callback;
    private long active;
    private long periodNanos;
    private long next;
    private boolean paused;

    public GodotTimer(Runnable runnable, GodotPulseScheduler scheduler) {
        super(runnable);
        this.scheduler = scheduler;
    }

    @Override protected long _start(Runnable runnable) { return begin(runnable, 0); }
    @Override protected long _start(Runnable runnable, int period) { return begin(runnable, period * 1_000_000L); }
    private long begin(Runnable runnable, long nanos) {
        callback = runnable;
        periodNanos = nanos;
        next = System.nanoTime() + nanos;
        paused = false;
        active = ids.incrementAndGet();
        scheduler.add(this);
        return active;
    }

    @Override protected void _stop(long timer) {
        active = 0;
        callback = null;
        scheduler.remove(this);
    }
    @Override protected void _pause(long timer) { paused = true; }
    @Override protected void _resume(long timer) {
        paused = false;
        next = System.nanoTime() + periodNanos;
    }

    void fire(long now) {
        Runnable runnable;
        synchronized (this) {
            if (active == 0 || paused || now < next) return;
            // Keep the original cadence when a host tick arrives late. Resetting
            // to now + period makes harmless host jitter skip the following tick
            // and can turn a 60 Hz host into a roughly 30 Hz JavaFX timer.
            // Skip missed deadlines but dispatch only once, never a catch-up storm.
            next = periodNanos == 0 ? now : next + ((now - next) / periodNanos + 1) * periodNanos;
            runnable = callback;
        }
        runnable.run();
    }
}
