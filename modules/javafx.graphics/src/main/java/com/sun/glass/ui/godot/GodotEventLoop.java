/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.glass.ui.godot;

import com.sun.javafx.tk.godot.GodotRenderCommands;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Consumer;

/** Dedicated Glass event dispatcher; Godot main thread never runs user code. */
public final class GodotEventLoop {
    private record Task(Runnable action, CompletableFuture<Void> completion) {}
    private static final class Nested {
        boolean done;
        Object result;
    }
    private final Object lifecycle = new Object();
    private final LinkedBlockingQueue<Task> queue = new LinkedBlockingQueue<>();
    private final ArrayDeque<Nested> nested = new ArrayDeque<>();
    private final Consumer<Throwable> errorHandler;
    private final Thread thread;
    private volatile boolean accepting;
    private boolean started;

    public GodotEventLoop(Consumer<Throwable> errorHandler) {
        this.errorHandler = errorHandler;
        thread = new Thread(this::run, "JavaFX Application Thread");
    }

    public Thread thread() { return thread; }

    public void start(Runnable launchable) {
        synchronized (lifecycle) {
            if (started) throw new IllegalStateException("Glass dispatcher cannot restart");
            started = true;
            accepting = true;
            queue.add(new Task(launchable, null));
            thread.start();
        }
    }

    public void invokeLater(Runnable runnable) { enqueue(new Task(runnable, null)); }

    public void invokeAndWait(Runnable runnable) {
        if (Thread.currentThread() == thread) {
            if (!accepting) throw stopped();
            runnable.run();
            return;
        }
        CompletableFuture<Void> completion = new CompletableFuture<>();
        enqueue(new Task(runnable, completion));
        try { completion.join(); }
        catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtime) throw runtime;
            if (failure.getCause() instanceof Error error) throw error;
            throw failure;
        }
    }

    private void enqueue(Task task) {
        if (task.action == null) throw new NullPointerException("runnable");
        synchronized (lifecycle) {
            if (!accepting) throw stopped();
            queue.add(task);
        }
    }

    public Object enterNested() {
        checkThread();
        if (!accepting) throw stopped();
        Nested loop = new Nested();
        nested.push(loop);
        try {
            while (accepting && !loop.done) processOne();
            return loop.result;
        } finally { nested.pop(); }
    }

    // A drag owns its completion condition. It must not leave an unrelated
    // showAndWait loop that an application callback may have opened above it.
    void dispatchUntil(java.util.function.BooleanSupplier finished) {
        checkThread();
        while (accepting && !finished.getAsBoolean()) processOne();
    }

    public void leaveNested(Object result) {
        checkThread();
        if (nested.isEmpty()) throw new IllegalStateException("No nested Glass event loop");
        nested.peek().result = result;
        nested.peek().done = true;
    }

    public void checkThread() {
        if (Thread.currentThread() != thread) throw new IllegalStateException("Not on JavaFX Application Thread");
    }

    private void run() {
        try { while (accepting) processOne(); }
        finally { stop(); }
    }

    private static final boolean TAP = "1".equals(System.getenv("GODOT_JFX_HOVER_TAP"));

    private void processOne() {
        // HOVER_TAP timestamps task flow on the FX thread. A long take()
        // means nobody posted work (pulse scheduler starved); a long
        // task run means the FX thread itself blocked inside a task.
        long takeStart = TAP ? System.nanoTime() : 0;
        Task task;
        try { task = queue.take(); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stop();
            return;
        }
        if (TAP) {
            long waitMs = (System.nanoTime() - takeStart) / 1_000_000;
            if (waitMs > 120) System.out.printf(java.util.Locale.ROOT,
                    "HOVER_TAP t=%.0fms FX_GAP take=%dms queued=%d%n",
                    (System.nanoTime() - GodotRenderCommands.tapEpoch()) / 1e6, waitMs, queue.size());
        }
        if (!accepting) {
            if (task.completion != null) task.completion.completeExceptionally(stopped());
            return;
        }
        long runStart = TAP ? System.nanoTime() : 0;
        try {
            task.action.run();
            if (task.completion != null) task.completion.complete(null);
        } catch (Throwable failure) {
            if (task.completion != null) task.completion.completeExceptionally(failure);
            else errorHandler.accept(failure);
        } finally {
            if (TAP) {
                long runMs = (System.nanoTime() - runStart) / 1_000_000;
                if (runMs > 120) System.out.printf(java.util.Locale.ROOT,
                        "HOVER_TAP t=%.0fms FX_TASK run=%dms task=%s queued=%d%n",
                        (System.nanoTime() - GodotRenderCommands.tapEpoch()) / 1e6, runMs,
                        task.action.getClass().getName(), queue.size());
            }
        }
    }

    public void stop() {
        synchronized (lifecycle) {
            started = true;
            accepting = false;
            Task task;
            while ((task = queue.poll()) != null) {
                if (task.completion != null) task.completion.completeExceptionally(stopped());
            }
            queue.add(new Task(() -> {}, null)); // Wake a blocked take().
        }
    }

    private static RejectedExecutionException stopped() {
        return new RejectedExecutionException("Godot Glass dispatcher is stopped");
    }
}
