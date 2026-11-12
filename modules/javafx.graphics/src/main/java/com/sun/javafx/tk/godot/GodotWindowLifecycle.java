/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.javafx.tk.godot;

import com.sun.javafx.tk.RenderJob;
import com.sun.javafx.tk.Toolkit;
import java.util.concurrent.ConcurrentHashMap;

/** Shared lifecycle token: queued Presentables retain the closed token, not an ID lookup. */
public final class GodotWindowLifecycle {
    private static final ConcurrentHashMap<Long, GodotWindowLifecycle> windows = new ConcurrentHashMap<>();
    public record Snapshot(boolean visible, boolean closed, float x, float y, int z, float opacity) {}
    private final long id;
    private final boolean primary;
    private volatile Snapshot snapshot = new Snapshot(false, false, 0, 0, 0, 1);
    private volatile Runnable renderUpdate;

    private GodotWindowLifecycle(long id, boolean primary) { this.id = id; this.primary = primary; }
    public static GodotWindowLifecycle create(long id) {
        return create(id, true);
    }
    public static GodotWindowLifecycle create(long id, boolean primary) {
        var state = new GodotWindowLifecycle(id, primary);
        if (id <= 0 || windows.putIfAbsent(id, state) != null) throw new IllegalArgumentException("Duplicate window identity");
        return state;
    }
    public static GodotWindowLifecycle get(long id) {
        var state = windows.get(id);
        if (state == null) throw new IllegalStateException("Glass window is closed or unknown: " + id);
        return state;
    }
    public Snapshot snapshot() { return snapshot; }
    public boolean isPrimary() { return primary; }
    public void setRenderUpdate(Runnable update) { renderUpdate = update; }
    public void clearRenderUpdate() { renderUpdate = null; }

    // Glass event-thread mutations; the callback only queues render-thread work.
    public void setVisible(boolean visible) {
        if (snapshot.closed()) return;
        Snapshot old = snapshot;
        snapshot = new Snapshot(visible, false, old.x(), old.y(), old.z(), old.opacity());
        enqueueUpdate();
    }
    /** Host-local geometry; primary position/alpha are owned by the native host. */
    public void setComposition(float x, float y, int z, float opacity) {
        if (!Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(opacity) || opacity < 0 || opacity > 1)
            throw new IllegalArgumentException("Invalid window composition");
        if (primary && (x != 0 || y != 0 || z != 0 || opacity != 1))
            throw new IllegalArgumentException("Primary composition belongs to the native host");
        Snapshot old = snapshot;
        if (old.closed()) return;
        if (old.x() == x && old.y() == y && old.z() == z && old.opacity() == opacity) return;
        snapshot = new Snapshot(old.visible(), false, x, y, z, opacity);
        enqueueUpdate();
    }
    public void close() {
        Snapshot old = snapshot;
        snapshot = new Snapshot(false, true, old.x(), old.y(), old.z(), old.opacity());
        windows.remove(id, this);
        enqueueUpdate();
    }
    private void enqueueUpdate() {
        Runnable update = renderUpdate;
        if (update != null) Toolkit.getToolkit().addRenderJob(new RenderJob(update));
    }
}
