/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.glass.ui.godot;

import com.sun.glass.events.ViewEvent;
import com.sun.glass.events.GestureEvent;
import com.sun.glass.events.MouseEvent;
import com.sun.glass.events.KeyEvent;
import com.sun.glass.events.TouchEvent;
import com.sun.glass.ui.TouchInputSupport;
import com.sun.glass.ui.Application;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.View;
import com.sun.javafx.tk.godot.GodotBridge;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** Glass view; distinct from the future public Godot scene embedding control. */
final class GodotView extends View {
    private static final AtomicLong ids = new AtomicLong();
    private long parent;
    private final TouchInputSupport touches = new TouchInputSupport(null,true);
    private final java.util.Set<Long> touchIds = new java.util.HashSet<>();
    private Long mouseTouch;
    private final java.util.Set<Integer> pressedKeys = new java.util.HashSet<>();
    private final java.util.Set<Integer> pressedButtons = new java.util.HashSet<>();
    private boolean canceling;

    int dragEnter(int x, int y, int sx, int sy, int action) { return notifyDragEnter(x,y,sx,sy,action); }
    int dragOver(int x, int y, int sx, int sy, int action) { return notifyDragOver(x,y,sx,sy,action); }
    int dragDrop(int x, int y, int sx, int sy, int action) { return notifyDragDrop(x,y,sx,sy,action); }
    void dragLeave() { notifyDragLeave(); }

    void cancelKeys() {
        var keys = java.util.Set.copyOf(pressedKeys); pressedKeys.clear();
        for (int key : keys) notifyKey(KeyEvent.RELEASE,key,new char[0],0);
    }
    void cancelInput() {
        if (canceling) return;
        canceling = true;
        try {
            cancelKeys();
            var buttons = new java.util.HashSet<>(pressedButtons); pressedButtons.clear();
            boolean synthesized = mouseTouch != null;
            if (synthesized) buttons.add(MouseEvent.BUTTON_LEFT);
            mouseTouch = null;
            var ids = java.util.Set.copyOf(touchIds); touchIds.clear();
            // JavaFX has no canceled mouse/touch event. Move outside the view
            // before balanced releases so Button cannot interpret cancellation
            // as an activation. The window loses focus before this method runs.
            int outside = -1000000;
            if (!buttons.isEmpty()) {
                notifyMouse(MouseEvent.EXIT,MouseEvent.BUTTON_NONE,outside,outside,outside,outside,0,false,synthesized);
            }
            if (!ids.isEmpty()) {
                touches.notifyBeginTouchEvent(this,0,true,ids.size());
                for (long id : ids) touches.notifyNextTouchEvent(this,TouchEvent.TOUCH_RELEASED,id,outside,outside,outside,outside);
                touches.notifyEndTouchEvent(this);
            }
            for (int button : buttons) notifyMouse(MouseEvent.UP,button,outside,outside,outside,outside,0,false,synthesized);
        } finally { canceling = false; }
    }
    private boolean imeEnabled, composing, finishingIme;
    private StringBuilder imePacket;
    private int imeLength, imeCaret, candidateX = Integer.MIN_VALUE, candidateY;

    @Override protected long _create(Map capabilities) { return ids.incrementAndGet(); }
    @Override protected long _getNativeView(long ptr) { return ptr; }
    @Override protected int _getX(long ptr) { return 0; }
    @Override protected int _getY(long ptr) { return 0; }
    @Override protected void _setParent(long ptr, long value) {
        if (parent != 0) {
            if (getWindow() instanceof GodotWindow window) window.focusChanged(false);
            cancelInput();
            notifyView(ViewEvent.REMOVE);
        }
        parent = value;
        if (parent != 0) notifyView(ViewEvent.ADD);
    }
    @Override protected boolean _close(long ptr) { parent = 0; return true; }
    @Override protected void _scheduleRepaint(long ptr) {
        Application.invokeLater(() -> notifyRepaint(0, 0, getWidth(), getHeight()));
    }
    // Prism-Godot serializes commands and owns synchronization. There is no
    // native GL context to bind/unbind on Java's rendering thread.
    @Override protected void _begin(long ptr) {}
    @Override protected void _end(long ptr) {}
    @Override protected long _getNativeFrameBuffer(long ptr) { return 0; }
    @Override protected void _uploadPixels(long ptr, Pixels pixels) {
        throw new UnsupportedOperationException("Glass-Godot requires Prism-Godot GPU commands; bitmap presentation is unsupported");
    }
    @Override protected void _enableInputMethodEvents(long ptr, boolean enable) {
        if (imeEnabled == enable) return;
        if (!enable && composing) finishCompositionText();
        imeEnabled = enable;
        if (getWindow() instanceof GodotWindow window) window.imeCommand(16,enable ? 1 : 0,0);
        candidateX = Integer.MIN_VALUE;
        updateImePosition();
    }
    @Override protected void _finishInputMethodComposition(long ptr) {
        if (!imeEnabled || !composing || finishingIme) return;
        if (!(getWindow() instanceof GodotWindow window)) { finishCompositionText(); return; }
        finishingIme = true;
        try {
            long sequence = window.imeCommand(18,0,0);
            long tick = 0, deadline = System.nanoTime() + 2_000_000_000L;
            // Scene invokes this before changing focus. Deliver Windows' IME
            // completion while the old text control is still the event target.
            while (window.imeAcknowledgement() < sequence) {
                if (System.nanoTime() > deadline) throw new IllegalStateException("Godot IME completion timed out");
                tick = GodotBridge.awaitTick(tick,100);
                if (tick < 0) throw new IllegalStateException("Godot stopped during IME completion");
            }
            ((GodotApplication)Application.GetApplication()).drainImeInput(window);
            if (composing) finishCompositionText();
        } finally { finishingIme = false; }
    }
    void updateImePosition() {
        if (!imeEnabled || !(getWindow() instanceof GodotWindow window)) return;
        double[] point = notifyInputMethodCandidatePosRequest(0);
        int x = (int)Math.clamp(point[0] - window.getX(), -1000000, 1000000);
        int y = (int)Math.clamp(point[1] - window.getY(), -1000000, 1000000);
        if (x != candidateX || y != candidateY) {
            candidateX = x; candidateY = y;
            window.imeCommand(17,x,y);
        }
    }
    private void finishCompositionText() {
        composing = false;
        notifyInputMethod("", null, null, null, 0, 0, 0);
    }
    private void deliverComposition() {
        String text = imePacket.toString(); imePacket = null;
        if (imeEnabled) {
            composing = !text.isEmpty();
            notifyInputMethod(text, null, null, null, 0, imeCaret, 0);
        }
    }
    @Override protected boolean _enterFullscreen(long ptr, boolean animate, boolean keepRatio, boolean hideCursor) {
        if (!(getWindow() instanceof GodotWindow window)) return false;
        window.fullscreen(true);
        notifyView(ViewEvent.FULLSCREEN_ENTER);
        return true;
    }
    @Override protected void _exitFullscreen(long ptr, boolean animate) {
        if (getWindow() instanceof GodotWindow window) window.fullscreen(false);
        notifyView(ViewEvent.FULLSCREEN_EXIT);
    }
    void resized(int width, int height) { notifyResize(width, height); }

    void dispatchInput(double[] events, int i) {
        Application.checkEventThread();
            int kind = (int) events[i], rawButton = (int) events[i + 1];
            if (kind == 13) {
                if (imePacket != null || rawButton < 0 || rawButton > 16384) throw new IllegalStateException("Invalid IME packet header");
                imeLength = rawButton; imeCaret = (int)events[i+2];
                if (imeCaret < 0 || imeCaret > imeLength) throw new IllegalStateException("Invalid IME caret");
                imePacket = new StringBuilder(imeLength);
                if (imeLength == 0) deliverComposition();
                return;
            }
            if (kind == 14) {
                if (imePacket == null) throw new IllegalStateException("IME packet without header");
                for (int n = 1; n <= 8 && imePacket.length() < imeLength; ++n) imePacket.append((char)events[i+n]);
                if (imePacket.length() == imeLength) deliverComposition();
                return;
            }
            int x = (int) events[i + 2], y = (int) events[i + 3];
            int sx = (int) events[i + 4], sy = (int) events[i + 5], modifiers = (int) events[i + 6];
            if (kind >= 10 && kind <= 12) {
                long id = (long)rawButton+1;
                if (kind == 10 && touchIds.isEmpty()) mouseTouch = id;
                if (kind == 10 ? !touchIds.add(id) : !touchIds.contains(id)) throw new IllegalStateException("Unbalanced Godot touch sequence: "+id);
                int state = kind == 10 ? TouchEvent.TOUCH_PRESSED : kind == 11 ? TouchEvent.TOUCH_MOVED : TouchEvent.TOUCH_RELEASED;
                touches.notifyBeginTouchEvent(this,modifiers,true,1);
                touches.notifyNextTouchEvent(this,state,id,x,y,sx,sy);
                touches.notifyEndTouchEvent(this);
                // Glass backends provide mouse promotion for ordinary controls;
                // Quantum does not synthesize it from notifyTouch alone.
                if (mouseTouch != null && mouseTouch == id) {
                    if (kind == 10) notifyMouse(MouseEvent.MOVE,MouseEvent.BUTTON_NONE,x,y,sx,sy,modifiers,false,true);
                    notifyMouse(kind == 10 ? MouseEvent.DOWN : kind == 11 ? MouseEvent.DRAG : MouseEvent.UP,
                        MouseEvent.BUTTON_LEFT,x,y,sx,sy,modifiers|(kind == 12 ? 0 : 32),false,true);
                    if (kind == 12) mouseTouch = null;
                }
                if (kind == 12) touchIds.remove(id);
                return;
            }
            if (kind == 5 || kind == 6) {
                int codePoint = (int) events[i + 2];
                // Windows delivers committed IME characters as ordinary WM_CHAR
                // input through Godot. Remove preedit before the typed event.
                if (kind == 5 && codePoint > 0 && composing) finishCompositionText();
                char[] text = codePoint > 0 && Character.isValidCodePoint(codePoint) ? Character.toChars(codePoint) : new char[0];
                int key = GodotKeyMap.glassKey(rawButton);
                if (kind == 5) pressedKeys.add(key); else pressedKeys.remove(key);
                notifyKey(kind == 5 ? KeyEvent.PRESS : KeyEvent.RELEASE, key, text, modifiers);
                if (kind == 5 && text.length > 0) notifyKey(KeyEvent.TYPED, KeyEvent.VK_UNDEFINED, text, modifiers);
                if (kind == 5 && getWindow() != null && (key == KeyEvent.VK_CONTEXT_MENU ||
                        (key == KeyEvent.VK_F10 && (modifiers & KeyEvent.MODIFIER_SHIFT) != 0))) {
                    notifyMenu(0, 0, getWindow().getX(), getWindow().getY(), true);
                }
                return;
            }
            if (kind == 4) {
                // Match Win Glass's UINT-to-jint interpretation, including -1
                // for page scrolling; each axis has its own lossless field.
                int lines = GodotInputPacket.scrollSetting(events[i + 10]);
                int chars = GodotInputPacket.scrollSetting(events[i + 11]);
                int defaultUnits = com.sun.javafx.PlatformUtil.isLinux() ? 0 : 3;
                notifyScroll(x, y, sx, sy, events[i + 7], events[i + 8], modifiers,
                        lines, chars, defaultUnits, defaultUnits, 40.0, 40.0);
                return;
            }
            if (kind == 17) {
                // macOS scroll contract: native Glass routes wheel ticks and
                // trackpad pan through MacGestureSupport with a 10px multiplier
                // (the host already applies the 0.1 point conversion to pan
                // deltas). Godot reports no gesture phase or momentum flags,
                // so only performed (SCROLL) events exist, without inertia.
                double dx = events[i + 7], dy = events[i + 8];
                notifyScrollGestureEvent(GestureEvent.GESTURE_PERFORMED, modifiers, false,
                        false, 0, x, y, sx, sy, dx, dy, dx, dy, 10.0, 10.0);
                return;
            }
            if (kind == 1) {
                rawButton = (modifiers & 32) != 0 ? 1 : (modifiers & 64) != 0 ? 2 :
                        (modifiers & 128) != 0 ? 3 : (modifiers & 256) != 0 ? 8 : (modifiers & 512) != 0 ? 9 : 0;
            }
            int button = switch (rawButton) {
                case 1 -> MouseEvent.BUTTON_LEFT;
                case 2 -> MouseEvent.BUTTON_RIGHT;
                case 3 -> MouseEvent.BUTTON_OTHER;
                case 8 -> MouseEvent.BUTTON_BACK;
                case 9 -> MouseEvent.BUTTON_FORWARD;
                default -> MouseEvent.BUTTON_NONE;
            };
            int type = kind == 7 ? MouseEvent.ENTER : kind == 8 ? MouseEvent.EXIT : kind == 2 ? MouseEvent.DOWN : kind == 3 ? MouseEvent.UP :
                    rawButton != 0 ? MouseEvent.DRAG : MouseEvent.MOVE;
            if (kind == 2) pressedButtons.add(button); else if (kind == 3) pressedButtons.remove(button);
            notifyMouse(type, button, x, y, sx, sy, modifiers,
                    kind == 3 && button == MouseEvent.BUTTON_RIGHT, false);
            // Glass menu events are distinct from MouseEvent.popupTrigger;
            // Quantum uses this callback to dispatch CONTEXT_MENU_REQUESTED.
            if (kind == 3 && button == MouseEvent.BUTTON_RIGHT && getWindow() != null) notifyMenu(x,y,sx,sy,false);
    }
}
