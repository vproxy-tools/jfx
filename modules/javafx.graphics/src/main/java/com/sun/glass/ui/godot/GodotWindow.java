/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.glass.ui.godot;

import com.sun.glass.events.WindowEvent;
import com.sun.glass.ui.*;
import com.sun.javafx.tk.godot.GodotBridge;
import com.sun.javafx.tk.godot.GodotHostStoppedException;
import java.util.concurrent.atomic.AtomicLong;

/** The primary Stage uses the root Window; every subsequent Stage has its own native Window. */
final class GodotWindow extends Window {
    private static final AtomicLong ids = new AtomicLong();
    private final com.sun.javafx.tk.godot.GodotWindowLifecycle lifecycle;
    private final boolean primary;
    private float layerAlpha = 1;
    private int layerLevel = Level.NORMAL;
    private int pendingSequence;
    private long pendingNativeSequence;
    private int inFlightMode = -1;
    private int queuedMode = -1;
    private long modeWaitStart;
    private boolean requestedVisible;
    private boolean nativeFocused;
    private boolean fullscreen;
    private int modeBeforeFullscreen;
    private boolean focusable = true;
    private boolean inputEnabled = true;
    private int minWidth, minHeight;
    private int maxWidth = 8192, maxHeight = 8192;

    boolean acceptsInput() { return !isClosed() && isVisible() && inputEnabled; }
    boolean canFocus() { return acceptsInput() && focusable; }
    boolean startWindowMove() {
        if (!acceptsInput()) return false;
        command(29, 0, 0, null);
        return true;
    }
    boolean isPrimary() { return primary; }
    boolean hasNativeFocus() { return nativeFocused; }
    long imeCommand(int operation, int a, int b) {
        if (isClosed()) return 0;
        command(operation,a,b,null);
        return primary ? pendingSequence : pendingNativeSequence;
    }
    long imeAcknowledgement() { return primary ? GodotBridge.hostInfo()[11] : GodotBridge.nativeWindowAck(); }
    void fullscreen(boolean value) {
        if (fullscreen == value) return;
        if (value) modeBeforeFullscreen = isMaximized() ? 2 : 0;
        fullscreen = value;
        command(6,value ? 3 : modeBeforeFullscreen,0,null);
    }
    int compositionLevel() {
        if (primary) return Integer.MIN_VALUE;
        int level = layerLevel;
        for (Window owner = getOwner(); owner instanceof GodotWindow ancestor; owner = owner.getOwner()) {
            if (!ancestor.primary) level = Math.max(level,ancestor.layerLevel);
        }
        return level;
    }
    private GodotApplication app() { return (GodotApplication)Application.GetApplication(); }
    void focusChanged(boolean value) { notifyFocus(value ? WindowEvent.FOCUS_GAINED : WindowEvent.FOCUS_LOST); }
    void focusDisabled() { notifyFocusDisabled(); }
    void focusUngrabbed() { notifyFocusUngrab(); }
    boolean containsScreen(double x, double y) {
        double left = getX(), top = getY();
        return x >= left && y >= top && x < left+getWidth() && y < top+getHeight();
    }
    void composition(int z) {
        if (!primary && !isClosed()) lifecycle.setComposition(0,0,0,layerAlpha);
    }
    void closeRequested() { if (!isClosed()) notifyClose(); }

    GodotWindow(Window owner, Screen screen, int styleMask, boolean primary) {
        super(owner, screen, styleMask);
        this.primary = primary;
        lifecycle = com.sun.javafx.tk.godot.GodotWindowLifecycle.create(getNativeWindow(),primary);
        int[] info = GodotBridge.hostInfo();
        notifyMove(info[3], info[4]);
        notifyResize(WindowEvent.RESIZE, primary ? info[0] : 1, primary ? info[1] : 1);
        if (primary) {
            command(15, isDecorated() ? 0 : 1, 0, null);
            command(24, (styleMask & TRANSPARENT) != 0 ? 1 : 0, 0, null);
        }
        else {
            long nativeOwner = owner instanceof GodotWindow parent ? (parent.primary ? -1 : parent.getNativeWindow()) : 0;
            int flags = (isDecorated() ? 0 : 1) | ((styleMask & TRANSPARENT) != 0 ? 2 : 0);
            // Popups use xdg_popup; owned modal Stages keep their toplevel and advertise a dialog role.
            if (owner != null && (isPopup() || isModal()) && com.sun.javafx.PlatformUtil.isLinux()) {
                flags |= isPopup() ? 8 : 16;
            }
            pendingNativeSequence = GodotBridge.nativeWindowCommand(getNativeWindow(),nativeOwner,1,getX(),getY(),1,1,flags,false,"");
            // Creation must precede publication even when the renderer and main
            // thread interleave queue drains. This waits only on Godot, never FX.
            long deadline = System.nanoTime()+5_000_000_000L, tick = 0;
            while (GodotBridge.nativeWindowAck() < pendingNativeSequence) {
                tick = GodotBridge.awaitTick(tick,100);
                if (tick < 0 || System.nanoTime() > deadline) throw new IllegalStateException("Native Stage creation timed out");
            }
        }
    }
    private void command(int op, int a, int b, String text) {
        if (op == 6 && inFlightMode >= 0) {
            // Wayland configures the new toplevel mode asynchronously. Queue a second
            // request until the first transition is visible in the host snapshot.
            queuedMode = a == inFlightMode ? -1 : a;
            return;
        }
        // The touch keyboard (26/27) is a global OS facility, not per-window state.
        if (primary || op == 10 || op == 26 || op == 27) pendingSequence = GodotBridge.hostCommand(op, a, b, text);
        else if (op != 8 && op != 14) pendingNativeSequence = GodotBridge.nativeWindowCommand(getNativeWindow(),0,4,a,b,0,0,op,false,text);
        if (op == 6 && com.sun.javafx.PlatformUtil.isLinux()) {
            inFlightMode = a;
            modeWaitStart = 0;
        }
        // Native content opacity belongs to its presentation surface. Scene
        // clearing supplies the background, not the root host's background.
    }
    private void configureNative() {
        pendingNativeSequence = GodotBridge.nativeWindowCommand(getNativeWindow(),0,2,getX(),getY(),
                Math.max(1,getWidth()),Math.max(1,getHeight()),0,requestedVisible,null);
    }
    void refreshNative() {
        if (primary || isClosed()) return;
        long[] info = GodotBridge.nativeWindowInfo(getNativeWindow());
        if (info.length == 0 || info[10] < pendingNativeSequence) return;
        nativeFocused = info[8] != 0;
        if (getX() != info[3] || getY() != info[4]) notifyMove((int)info[3],(int)info[4]);
        refreshSizeAndMode((int)info[5], (int)info[6], (int)info[9]);
    }
    void refresh(int[] info) {
        if (isClosed() || info[11] < pendingSequence) return;
        nativeFocused = info[9] != 0;
        if (getX() != info[3] || getY() != info[4]) notifyMove(info[3], info[4]);
        refreshSizeAndMode(info[0], info[1], info[10]);
    }

    private void refreshSizeAndMode(int width, int height, int nativeMode) {
        int mode = nativeMode;
        if (inFlightMode >= 0) {
            // The host acknowledges the command before the compositor sends configure.
            // Keep the current Glass mode across stale snapshots, then reconcile
            // a request the compositor declined after a bounded wait.
            long now = System.nanoTime();
            if (modeWaitStart == 0) modeWaitStart = now;
            if (mode == inFlightMode || now - modeWaitStart >= 1_000_000_000L) {
                inFlightMode = -1;
                int next = queuedMode;
                queuedMode = -1;
                if (next >= 0 && next != mode) command(6, next, 0, null);
            }
            if (inFlightMode >= 0) {
                mode = isMinimized() ? 1 : isMaximized() ? 2 : 0;
            }
        }
        // Glass tracks fullscreen separately from the maximized state.
        if (com.sun.javafx.PlatformUtil.isLinux() && fullscreen && (mode == 3 || mode == 4)) {
            mode = modeBeforeFullscreen;
        }
        boolean changed = getWidth() != width || getHeight() != height;
        int type = switch (mode) {
            case 1 -> WindowEvent.MINIMIZE;
            case 2 -> WindowEvent.MAXIMIZE;
            // RESIZE updates dimensions only at the Quantum boundary. RESTORE
            // also clears the public Stage iconified/maximized properties when
            // the native host (for example the OS taskbar) restores the window.
            default -> isMinimized() || isMaximized() ? WindowEvent.RESTORE : WindowEvent.RESIZE;
        };
        if (changed || (mode == 1) != isMinimized() || (mode == 2) != isMaximized()) {
            notifyResize(type, width, height);
            if (getView() instanceof GodotView view) view.resized(width, height);
        }
    }
    void refreshScreen() {
        if (isClosed()) return;
        Screen best = Screen.getMainScreen();
        float overlap = -1;
        double distance = Double.POSITIVE_INFINITY;
        double cx = getX() + getWidth()/2.0, cy = getY() + getHeight()/2.0;
        for (Screen candidate : Screen.getScreens()) {
            float area = candidate.portionIntersectsPlatformRect(getX(),getY(),getWidth(),getHeight());
            double dx = Math.max(candidate.getPlatformX()-cx, Math.max(0,cx-candidate.getPlatformX()-candidate.getPlatformWidth()));
            double dy = Math.max(candidate.getPlatformY()-cy, Math.max(0,cy-candidate.getPlatformY()-candidate.getPlatformHeight()));
            double squared = dx*dx + dy*dy;
            if (area > overlap || (area == overlap && squared < distance)) {
                best = candidate; overlap = area; distance = squared;
            }
        }
        if (getScreen() != best) notifyMoveToAnotherScreen(best);
        if (getPlatformScaleX()!=best.getPlatformScaleX() || getPlatformScaleY()!=best.getPlatformScaleY()) {
            notifyScaleChanged(best.getPlatformScaleX(),best.getPlatformScaleY(),
                    best.getRecommendedOutputScaleX(),best.getRecommendedOutputScaleY());
            if (getView() instanceof GodotView view) view.resized(getWidth(),getHeight());
        }
    }
    @Override protected long _createWindow(long owner, long screen, int mask) { return ids.incrementAndGet(); }
    @Override protected boolean _close(long ptr) {
        lifecycle.close();
        try {
            app().closing(this);
            if (!primary) GodotBridge.nativeWindowCommand(ptr,0,3,0,0,0,0,0,false,null);
        } catch (GodotHostStoppedException cancelled) {
            // No native command can be accepted after host cancellation. This
            // releases the Java peer only; native destruction remains host-owned.
        }
        notifyDestroy();
        return true;
    }
    @Override protected boolean _setView(long ptr, View view) {
        if (view != null && !(view instanceof GodotView)) throw new IllegalArgumentException("Not a Godot view");
        return true;
    }
    @Override protected void _updateViewSize(long ptr) {
        if (getView() instanceof GodotView view) view.resized(getWidth(), getHeight());
    }
    @Override protected boolean _setMenubar(long ptr, long menu) { throw GodotApplication.pending("System menu"); }
    @Override protected boolean _minimize(long ptr, boolean value) {
        command(6, value ? 1 : 0, 0, null);
        notifyResize(value ? WindowEvent.MINIMIZE : WindowEvent.RESTORE, getWidth(), getHeight());
        return true;
    }
    @Override protected boolean _maximize(long ptr, boolean value, boolean previous) {
        command(6, value ? 2 : 0, 0, null);
        notifyResize(value ? WindowEvent.MAXIMIZE : WindowEvent.RESTORE, getWidth(), getHeight());
        return true;
    }
    @Override protected void _setBounds(long ptr, int x, int y, boolean xSet, boolean ySet,
            int w, int h, int cw, int ch, float xGravity, float yGravity) {
        int width = Math.clamp(w > 0 ? w : cw > 0 ? cw : getWidth(), minWidth, maxWidth);
        int height = Math.clamp(h > 0 ? h : ch > 0 ? ch : getHeight(), minHeight, maxHeight);
        if (width > 0 && height > 0) {
            if (primary) command(2, width, height, null);
            notifyResize(WindowEvent.RESIZE, width, height);
            if (getView() instanceof GodotView view) view.resized(width, height);
        }
        if (xSet || ySet) {
            int nx = xSet ? x : getX(), ny = ySet ? y : getY();
            if (primary) command(3, nx, ny, null);
            notifyMove(nx, ny);
        }
        if (!primary) configureNative();
        app().restack();
    }
    @Override protected boolean _setVisible(long ptr, boolean value) {
        if (!value && com.sun.javafx.PlatformUtil.isLinux()) GodotBridge.flushLinuxClipboard();
        lifecycle.setVisible(value);
        requestedVisible = value;
        if (!primary) configureNative();
        if (value) Application.invokeLater(() -> app().focus(this)); else app().hidden(this);
        app().visibility(this,value); return value;
    }
    @Override protected boolean _setResizable(long ptr, boolean value) { command(7, value ? 1 : 0, 0, null); return true; }
    @Override protected boolean _requestFocus(long ptr, int event) {
        if (!focusable || !inputEnabled) return false;
        app().focus(this);
        command(4, 0, 0, null);
        return true; // Actual focus notification arrives from the host snapshot.
    }
    @Override protected void _setFocusable(long ptr, boolean value) { focusable = value; command(20,value ? 1 : 0,0,null); }
    @Override protected boolean _grabFocus(long ptr) { return app().grab(this); }
    @Override protected void _ungrabFocus(long ptr) { app().ungrab(this); }
    @Override protected boolean _setTitle(long ptr, String title) { command(1, 0, 0, title); return true; }
    @Override protected void _setLevel(long ptr, int level) { layerLevel = level; command(9, level == Level.NORMAL ? 0 : 1, 0, null); app().restack(); }
    @Override protected void _setAlpha(long ptr, float alpha) { layerAlpha = alpha; command(8, Math.round(alpha * 1_000_000), 0, null); app().restack(); }
    @Override protected boolean _setBackground(long ptr, float r, float g, float b) {
        int rgb = Math.round(Math.clamp(r, 0f, 1f) * 255) << 16 |
                Math.round(Math.clamp(g, 0f, 1f) * 255) << 8 | Math.round(Math.clamp(b, 0f, 1f) * 255);
        command(14, rgb, 0, null);
        return true;
    }
    @Override protected void _setEnabled(long ptr, boolean value) {
        inputEnabled = value;
        command(19,value ? 1 : 0,0,null);
        if (!value) app().hidden(this);
    }
    @Override protected boolean _setMinimumSize(long ptr, int w, int h) {
        command(12, w, h, null); minWidth = w; minHeight = h; return true;
    }
    @Override protected boolean _setMaximumSize(long ptr, int w, int h) {
        command(13, Math.max(w, 0), Math.max(h, 0), null);
        maxWidth = w < 0 ? 8192 : w; maxHeight = h < 0 ? 8192 : h; return true;
    }
    @Override protected void _setIcon(long ptr, Pixels pixels) {
        int width = pixels == null ? 0 : pixels.getWidth();
        int height = pixels == null ? 0 : pixels.getHeight();
        if (width < 0 || height < 0 || width > 256 || height > 256)
            throw new IllegalArgumentException("Window icon dimensions exceed 256 pixels");
        byte[] bgra = new byte[width * height * 4];
        if (pixels != null) {
            var buffer = pixels.getBuffer();
            if (buffer instanceof java.nio.ByteBuffer bytes) bytes.duplicate().rewind().get(bgra);
            else if (buffer instanceof java.nio.IntBuffer ints) {
                var copy = ints.duplicate().rewind();
                for (int p = 0; p < bgra.length; p += 4) {
                    int argb = copy.get();
                    bgra[p] = (byte)argb; bgra[p+1] = (byte)(argb >>> 8);
                    bgra[p+2] = (byte)(argb >>> 16); bgra[p+3] = (byte)(argb >>> 24);
                }
            } else throw new IllegalArgumentException("Unsupported window icon pixels");
        }
        long sequence = GodotBridge.windowIcon(primary ? 0 : ptr, width, height, bgra);
        if (primary) pendingSequence = Math.toIntExact(sequence);
        else pendingNativeSequence = sequence;
    }
    @Override protected void _setCursor(long ptr, Cursor cursor) {
        if(cursor.getType()==Cursor.CURSOR_CUSTOM) pendingSequence=((GodotCursor)cursor).apply();
        else command(10, cursor.getType(), 0, null);
    }
    @Override protected void _toFront(long ptr) { app().raise(this); command(23, 0, 0, null); }
    @Override protected void _toBack(long ptr) {
        app().lower(this);
        command(22, 0, 0, null);
    }
    // The touch keyboard is summoned for the whole session (TabTip); per-control
    // geometry/transform hints do not apply to a floating OS keyboard.
    @Override protected void _requestInput(long ptr, String text, int type, double width, double height,
            double mxx, double mxy, double mxz, double mxt, double myx, double myy, double myz, double myt,
            double mzx, double mzy, double mzz, double mzt) { command(26, 0, 0, null); }
    @Override protected void _releaseInput(long ptr) { command(27, 0, 0, null); }
}
