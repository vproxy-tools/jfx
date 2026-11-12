/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.glass.ui.godot;

import com.sun.javafx.tk.godot.GodotBridge;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Coalesces host ticks before dispatch, without a free-running pulse timer. */
public final class GodotPulseScheduler implements AutoCloseable {
    @FunctionalInterface
    public interface TickSource { long await(long after, int timeoutMillis); }
    private final TickSource tickSource;
    private final GodotEventLoop loop;
    private final Runnable hostStopped;
    private final Runnable onFrame;
    private final CopyOnWriteArrayList<GodotTimer> timers = new CopyOnWriteArrayList<>();
    private final AtomicBoolean pulseQueued = new AtomicBoolean();
    private volatile boolean running;
    private boolean started;

    public GodotPulseScheduler(GodotEventLoop loop, Runnable hostStopped) {
        this(loop, hostStopped, () -> {});
    }
    public GodotPulseScheduler(GodotEventLoop loop, Runnable hostStopped, Runnable onFrame) {
        this(loop, hostStopped, onFrame, !"0".equals(System.getenv("GODOT_JFX_FRAME_PULSE")) ? GodotBridge::awaitFrameTick : GodotBridge::awaitTick);
    }
    public GodotPulseScheduler(GodotEventLoop loop, Runnable hostStopped, Runnable onFrame, TickSource tickSource) {
        this.loop = loop;
        this.hostStopped = hostStopped;
        this.onFrame = onFrame;
        this.tickSource = java.util.Objects.requireNonNull(tickSource);
    }

    public synchronized void start() {
        if (started) throw new IllegalStateException("Godot pulse scheduler cannot restart");
        started = true;
        running = true;
        Thread receiver = new Thread(this::receive, "Godot frame receiver");
        receiver.setDaemon(true);
        receiver.start();
    }

    void add(GodotTimer timer) {
        if (!running) throw new IllegalStateException("Godot pulse scheduler is stopped");
        timers.addIfAbsent(timer);
    }
    void remove(GodotTimer timer) { timers.remove(timer); }

    private void receive() {
        long tick = 0;
        int starvedPosts = 0;
        while (running) {
            // A host tick normally arrives every frame. When the host main
            // loop stalls (heavy GPU bursts, first-use pipeline work), the
            // timeout keeps pulses flowing so animations and input handling
            // retain wall-clock progress. The starvation posts are capped:
            // each may paint one frame into the bounded render queue, and
            // the submit retry deadline must never be reached just because
            // the host stopped draining (for example a modal native loop).
            long next = tickSource.await(tick, 100);
            if (!running) break;
            if (next < 0) {
                // Invalidate queued pulses before posting termination behind
                // them. Otherwise they can submit new work to a stopped host.
                running = false;
                try { loop.invokeLater(hostStopped); }
                catch (RejectedExecutionException stopped) { /* Already terminated. */ }
                break;
            }
            if (next > tick) { tick = next; starvedPosts = 0; }
            else if (++starvedPosts > 3) continue;
            if (pulseQueued.compareAndSet(false, true)) {
                try {
                    loop.invokeLater(() -> {
                        pulseQueued.set(false);
                        if (!running) return;
                        onFrame.run();
                        long now = System.nanoTime();
                        for (GodotTimer timer : timers) {
                            try { timer.fire(now); }
                            catch (Throwable failure) { com.sun.glass.ui.Application.reportException(failure); }
                        }
                    });
                } catch (RejectedExecutionException stopped) { break; }
            }
        }
    }

    @Override public void close() {
        running = false;
        for (GodotTimer timer : timers) timer.stop();
        timers.clear();
    }
}
