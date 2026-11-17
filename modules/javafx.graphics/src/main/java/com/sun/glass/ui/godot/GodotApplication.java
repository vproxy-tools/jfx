/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.glass.ui.godot;

import com.sun.glass.ui.Application;
import com.sun.glass.ui.Cursor;
import com.sun.glass.ui.GlassRobot;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.Screen;
import com.sun.glass.ui.Timer;
import com.sun.glass.ui.View;
import com.sun.glass.ui.Window;
import com.sun.glass.ui.Clipboard;
import com.sun.glass.events.KeyEvent;
import com.sun.glass.ui.headless.HeadlessApplication;
import com.sun.javafx.tk.godot.GodotBridge;
import com.sun.javafx.tk.godot.GodotHostStoppedException;

/** Godot event-thread adapter. Window/Prism bring-up is a separate layer. */
public final class GodotApplication extends HeadlessApplication {
    // Native Godot windows and Prism targets preserve per-pixel alpha.
    // Inheriting HeadlessApplication's false value strips Window.TRANSPARENT
    // before Quantum chooses its Scene clear color, producing a white backing.
    @Override protected boolean _supportsTransparentWindows() { return true; }
    // Reuse the upstream platform-neutral key mapping and pixel containers.
    // Override every window/presentation/robot route: no headless framebuffer
    // is allocated or used by this backend.
    private final GodotEventLoop loop = new GodotEventLoop(Application::reportException);
    // Quantum must finish its current render and Application shutdown before
    // Glass clears the dispatcher/application singleton. Direct terminate()
    // races PaintCollector and can strand Application.launch().
    private final GodotPulseScheduler scheduler = new GodotPulseScheduler(loop, javafx.application.Platform::exit, this::onFrame);
    private GodotWindow primary;
    private final java.util.ArrayList<GodotWindow> windows = new java.util.ArrayList<>();
    private GodotWindow focused, hovered, captured;
    private GodotWindow grabbed;
    private int mouseButtons;
    private boolean hostFocused;
    private final java.util.Set<GodotWindow> visibleWindows = new java.util.HashSet<>();
    private final java.util.Map<Integer,GodotWindow> touchTargets = new java.util.HashMap<>();
    private final java.util.Map<Integer,GodotWindow> keyTargets = new java.util.HashMap<>();
    private final java.util.Set<Integer> canceledKeys = new java.util.HashSet<>();
    private int canceledMouseButtons;
    private final java.util.Set<Runnable> popupBoundsListeners = new java.util.LinkedHashSet<>();
    private int[] lastPopupBounds;
    private int[] lastScreens;
    private final java.util.ArrayDeque<double[]> pendingInput = new java.util.ArrayDeque<>();
    private final java.util.concurrent.atomic.AtomicBoolean inputDrainQueued = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile boolean receivingInput;
    private Drag drag;
    private GodotView externalTarget; // OLE drag-in target while no local drag is active.
    private final GodotBridge.DragTargetHandler dragTargetHandler = this::externalDragTarget;

    public GodotApplication() {
        GodotBridge.dragTargetHandler = dragTargetHandler;
    }

    private static final class Drag {
        final GodotWindow source;
        final int actions, buttons;
        final java.util.function.IntConsumer completion;
        GodotView target;
        boolean finished;
        boolean external; // Handed to Windows OLE; completion arrives as input record kind 20.
        int result;
        Drag(GodotWindow source, int actions, int buttons, java.util.function.IntConsumer completion) {
            this.source = source; this.actions = actions; this.buttons = buttons; this.completion = completion;
        }
    }
    boolean dragActive() { return drag != null; }
    void runDrag(int actions, java.util.function.IntConsumer completion) {
        if (drag != null || captured == null || mouseButtons == 0)
            throw new IllegalStateException("Drag requires an active mouse gesture");
        Drag session = new Drag(captured, actions, mouseButtons, completion);
        drag = session;
        try {
            // Remaining records from the current host batch must be dispatched
            // inside the drag loop too, including an already queued release.
            Application.invokeLater(this::drainInput);
            loop.dispatchUntil(() -> session.finished);
        } finally {
            try { finishDrag(session, Clipboard.ACTION_NONE); }
            finally { drag = null; cancelInput(session.source); }
        }
    }
    private void finishDrag(Drag session, int action) {
        if (session.finished) return;
        session.finished = true;
        session.result = action;
        GodotView target = session.target;
        session.target = null;
        try { if (target != null) target.dragLeave(); }
        finally {
            // Notify before hide/close detaches the source Scene, and before a
            // balancing mouse release clears its gesture. Reentrancy sees the
            // finished flag and cannot deliver a second DRAG_DONE.
            session.completion.accept(action);
        }
    }
    private static int dragAction(int actions, int modifiers) {
        boolean control = (modifiers & KeyEvent.MODIFIER_CONTROL) != 0;
        boolean shift = (modifiers & KeyEvent.MODIFIER_SHIFT) != 0;
        int requested = control && shift ? Clipboard.ACTION_REFERENCE : control ? Clipboard.ACTION_COPY :
                shift ? Clipboard.ACTION_MOVE : 0;
        if (requested != 0) return (actions & requested) != 0 ? requested : Clipboard.ACTION_NONE;
        for (int action : new int[]{Clipboard.ACTION_MOVE, Clipboard.ACTION_COPY, Clipboard.ACTION_REFERENCE})
            if ((actions & action) != 0) return action;
        return Clipboard.ACTION_NONE;
    }
    private static int acceptedAction(int action, int supported) {
        return (action == Clipboard.ACTION_COPY || action == Clipboard.ACTION_MOVE || action == Clipboard.ACTION_REFERENCE)
                && (action & supported) != 0 ? action : Clipboard.ACTION_NONE;
    }
    private static byte[] terminated(String value, java.nio.charset.Charset charset) {
        byte[] bytes = value.getBytes(charset);
        return java.util.Arrays.copyOf(bytes, bytes.length + 1);
    }

    // The pointer left every hosted window mid-drag. Snapshot the DND data and
    // hand the gesture to Windows OLE; in-app targets keep working through the
    // registered IDropTarget callbacks if the pointer returns.
    private void startExternalDrag(Drag session) {
        GodotDragClipboard clipboard = GodotDragClipboard.current();
        if (clipboard == null) throw new IllegalStateException("Drag clipboard is not initialized");
        var data = new java.util.HashMap<String,Object>();
        String[] mimes = clipboard.getMimeTypes();
        if (mimes != null) for (String mime : mimes) data.put(mime, clipboard.getData(mime));
        // The Quantum layer attaches the optional Dragboard view as opaque
        // buffers (big-endian w/h envelope + BGRA bytes). They feed the shell
        // drag image, not the transfer payload, so leave the mime loop below.
        byte[] dragImage = imageBuffer(data.remove(Clipboard.DRAG_IMAGE));
        byte[] dragImageOffset = imageBuffer(data.remove(Clipboard.DRAG_IMAGE_OFFSET));
        var customNames = new java.util.ArrayList<String>();
        var customData = new java.util.ArrayList<byte[]>();
        for (String mime : data.keySet()) {
            if (mime.startsWith(GodotSystemClipboard.CUSTOM_PREFIX)) {
                try {
                    customNames.add(mime);
                    customData.add(GodotSystemClipboard.customBytes(mime, data.get(mime)));
                } catch (IllegalArgumentException failure) {
                    finishDrag(session, Clipboard.ACTION_NONE);
                    throw failure;
                }
                continue;
            }
            if (!Clipboard.TEXT_TYPE.equals(mime) && !Clipboard.HTML_TYPE.equals(mime) && !Clipboard.RTF_TYPE.equals(mime)
                    && !Clipboard.URI_TYPE.equals(mime) && !Clipboard.FILE_LIST_TYPE.equals(mime)) {
                finishDrag(session, Clipboard.ACTION_NONE);
                throw pending("OLE drag format " + mime);
            }
        }
        String html = (String) data.get(Clipboard.HTML_TYPE);
        String rtf = (String) data.get(Clipboard.RTF_TYPE);
        String[] files = null;
        if (data.get(Clipboard.FILE_LIST_TYPE) instanceof String[] paths) files = paths; // Quantum layer stores paths, not Files.
        GodotBridge.dragStartExternal((String) data.get(Clipboard.TEXT_TYPE),
                html == null ? null : GodotHtmlCodec.encode(terminated(html, java.nio.charset.StandardCharsets.UTF_8)),
                rtf == null ? null : terminated(rtf, java.nio.charset.StandardCharsets.US_ASCII),
                (String) data.get(Clipboard.URI_TYPE), files,
                customNames.isEmpty() ? null : customNames.toArray(String[]::new),
                customData.isEmpty() ? null : customData.toArray(byte[][]::new),
                dragImage, dragImageOffset,
                session.actions, session.buttons,
                session.source.isPrimary() ? 0 : session.source.getNativeWindow());
        session.external = true;
    }

    /** Drag view payloads are ByteBuffers exactly as the Quantum layer writes
     * them (fully written, so the position sits at the end); a differently
     * typed value keeps the drag working without a preview instead of failing
     * the gesture. */
    private static byte[] imageBuffer(Object value) {
        if (!(value instanceof java.nio.ByteBuffer buffer)) return null;
        java.nio.ByteBuffer full = buffer.duplicate();
        full.rewind();
        byte[] bytes = new byte[full.remaining()];
        full.get(bytes);
        return bytes;
    }

    // Runs on the Godot main thread inside an OLE callback. Marshal to the FX
    // event thread with a bounded wait so a stalled FX loop degrades to
    // DROPEFFECT_NONE instead of deadlocking the host.
    private int externalDragTarget(int kind, long id, int sx, int sy, int effects, int keyState,
            String mimes, String text, byte[] html, byte[] rtf, String url, String files,
            String[] customNames, byte[][] customData) {
        if (Application.isEventThread())
            return dispatchDragTarget(kind, id, sx, sy, effects, keyState, mimes, text, html, rtf, url, files, customNames, customData);
        var result = new java.util.concurrent.FutureTask<Integer>(() ->
                dispatchDragTarget(kind, id, sx, sy, effects, keyState, mimes, text, html, rtf, url, files, customNames, customData));
        try {
            loop.invokeLater(result);
        } catch (RuntimeException stopped) { return Clipboard.ACTION_NONE; }
        try { return result.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); return cancelExternalDragRequest(result); }
        catch (java.util.concurrent.TimeoutException timeout) { return cancelExternalDragRequest(result); }
        catch (java.util.concurrent.ExecutionException failure) {
            Application.reportException(failure.getCause());
            return Clipboard.ACTION_NONE;
        }
    }

    private int cancelExternalDragRequest(java.util.concurrent.FutureTask<Integer> request) {
        // A queued FutureTask cannot start after cancellation. Do not interrupt
        // the shared FX thread if application code has already started running.
        request.cancel(false);
        try {
            loop.invokeLater(() -> dispatchDragTarget(3, 0, 0, 0, 0, 0,
                    null, null, null, null, null, null, null, null));
        } catch (java.util.concurrent.RejectedExecutionException stopped) { /* Host owns cleanup. */ }
        return Clipboard.ACTION_NONE;
    }

    private int dispatchDragTarget(int kind, long id, int sx, int sy, int effects, int keyState,
            String mimes, String text, byte[] html, byte[] rtf, String url, String files,
            String[] customNames, byte[][] customData) {
        GodotWindow window = inputWindow(id);
        Drag session = drag != null && drag.external ? drag : null;
        if (kind == 3) { // DragLeave stays valid after the window stopped accepting input.
            if (session != null) {
                GodotView target = session.target; session.target = null;
                if (target != null) target.dragLeave();
            } else if (externalTarget != null) {
                GodotView target = externalTarget; externalTarget = null;
                try { target.dragLeave(); } finally { clearExternalDragData(); }
            }
            return Clipboard.ACTION_NONE;
        }
        GodotView view = window != null && window.isVisible() && window.acceptsInput()
                && window.getView() instanceof GodotView candidate ? candidate : null;
        if (view == null) return Clipboard.ACTION_NONE;
        int x = sx - window.getX(), y = sy - window.getY();
        int modifiers = ((keyState & 0x08) != 0 ? KeyEvent.MODIFIER_CONTROL : 0) | ((keyState & 0x04) != 0 ? KeyEvent.MODIFIER_SHIFT : 0);
        if (session != null) {
            int action = dragAction(session.actions, modifiers);
            if (kind == 4) {
                session.target = null; // notifyDragDrop closes the target assistant itself.
                int performed = acceptedAction(view.dragDrop(x, y, sx, sy, action), session.actions);
                finishDrag(session, performed);
                return performed;
            }
            if (view != session.target) {
                GodotView previous = session.target; session.target = null;
                if (previous != null) previous.dragLeave();
                if (session.finished) return Clipboard.ACTION_NONE;
                session.target = view;
                action = view.dragEnter(x, y, sx, sy, action);
            } else action = view.dragOver(x, y, sx, sy, action);
            if (session.finished) return Clipboard.ACTION_NONE;
            return acceptedAction(action, session.actions);
        }
        if (kind == 4) {
            setExternalDragData(text, html, rtf, url, files, customNames, customData, effects);
            try {
                externalTarget = null;
                return acceptedAction(view.dragDrop(x, y, sx, sy, dragAction(effects, modifiers)), effects);
            } finally { clearExternalDragData(); }
        }
        if (view != externalTarget) {
            GodotView previous = externalTarget; externalTarget = null;
            if (previous != null) { previous.dragLeave(); clearExternalDragData(); }
            setExternalDragData(text, html, rtf, url, files, customNames, customData, effects);
            externalTarget = view;
            return acceptedAction(view.dragEnter(x, y, sx, sy, dragAction(effects, modifiers)), effects);
        }
        return acceptedAction(view.dragOver(x, y, sx, sy, dragAction(effects, modifiers)), effects);
    }

    private void setExternalDragData(String text, byte[] html, byte[] rtf, String url, String files,
            String[] customNames, byte[][] customData, int actions) {
        var data = new java.util.HashMap<String,Object>();
        if (text != null) data.put(Clipboard.TEXT_TYPE, text);
        if (html != null) data.put(Clipboard.HTML_TYPE,
                new String(GodotHtmlCodec.decode(html), java.nio.charset.StandardCharsets.UTF_8));
        if (rtf != null && rtf.length > 0) data.put(Clipboard.RTF_TYPE, new String(rtf, 0, rtf.length - 1, java.nio.charset.StandardCharsets.US_ASCII));
        if (url != null) data.put(Clipboard.URI_TYPE, url);
        if (files != null) {
            var list = new java.util.ArrayList<String>();
            for (String path : files.split("\n")) if (!path.isEmpty()) list.add(path);
            data.put(Clipboard.FILE_LIST_TYPE, list.toArray(new String[0])); // Quantum layer expects String[] paths.
        }
        if (customNames != null && customData != null && customNames.length == customData.length) {
            for (int i = 0; i < customNames.length; ++i) {
                // Wire payloads are NUL-terminated; applications see the payload only.
                if (customNames[i] != null && customData[i] != null && customData[i].length > 0)
                    data.put(customNames[i], java.util.Arrays.copyOf(customData[i], customData[i].length - 1));
            }
        }
        GodotDragClipboard.current().setExternalData(data, actions);
    }
    private void clearExternalDragData() {
        GodotDragClipboard clipboard = GodotDragClipboard.current();
        if (clipboard != null) clipboard.setExternalData(new java.util.HashMap<>(), Clipboard.ACTION_NONE);
    }
    private boolean dispatchDrag(double[] event) {
        Drag session = drag;
        if (session == null || session.finished) return false;
        int kind = (int)event[0];
        if (kind == 20) {
            // OLE drag completion; the record carries the performed effect.
            if (session.external) finishDrag(session, (int)event[1]);
            return true;
        }
        if (session.external) return true; // OLE owns the pointer until that completion arrives.
        if (kind == 5 && GodotKeyMap.glassKey((int)event[1]) == KeyEvent.VK_ESCAPE) {
            finishDrag(session, Clipboard.ACTION_NONE); return true;
        }
        if (kind == 5 || kind == 6) return true;
        if (kind != 1 && kind != 2 && kind != 3 && kind != 4 && kind != 7 && kind != 8) return false;
        if (kind == 4 || kind == 8) return true;
        int sx = (int)event[4], sy = (int)event[5];
        GodotWindow window = null;
        for (int n = windows.size()-1; n >= 0; --n) {
            GodotWindow candidate = windows.get(n);
            if (candidate.isVisible() && !candidate.isClosed() && candidate.containsHost(sx-hostX(), sy-hostY())) {
                if (candidate.acceptsInput()) window = candidate;
                break; // A disabled/modal-blocked top window still occludes its owner.
            }
        }
        if (kind == 1 && window == null) {
            // The pointer left every hosted window mid-drag: hand the gesture to
            // Windows OLE so external applications can receive the drop. Re-entry
            // arrives through the registered IDropTarget callbacks instead.
            startExternalDrag(session);
            return true;
        }
        GodotView target = window != null && window.getView() instanceof GodotView view ? view : null;
        int action = dragAction(session.actions, (int)event[6]);
        int x = window == null ? 0 : sx-window.getX(), y = window == null ? 0 : sy-window.getY();
        if (target != session.target) {
            GodotView previous = session.target;
            session.target = null;
            if (previous != null) previous.dragLeave();
            if (session.finished) return true;
            session.target = target;
            if (target != null) action = target.dragEnter(x,y,sx,sy,action);
        } else if (target != null) action = target.dragOver(x,y,sx,sy,action);
        if (session.finished) return true; // A callback may close the source/target.
        action = acceptedAction(action, session.actions);
        if (kind == 3 && (session.buttons & (1 << (int)event[1])) != 0) {
            int performed = Clipboard.ACTION_NONE;
            if (target != null && action != Clipboard.ACTION_NONE) {
                session.target = null; // notifyDragDrop closes the target assistant itself.
                performed = acceptedAction(target.dragDrop(x,y,sx,sy,action), session.actions);
            }
            finishDrag(session, performed);
        }
        return true;
    }

    @Override public void addPopupBoundsListener(Runnable listener) {
        Application.checkEventThread();
        popupBoundsListeners.add(java.util.Objects.requireNonNull(listener));
    }
    @Override public void removePopupBoundsListener(Runnable listener) {
        Application.checkEventThread();
        popupBoundsListeners.remove(listener);
    }
    private void updatePopupBounds() {
        if (popupBoundsListeners.isEmpty()) { lastPopupBounds = null; return; }
        int[] bounds = getPopupBounds();
        if (java.util.Arrays.equals(lastPopupBounds,bounds)) return;
        lastPopupBounds = bounds;
        // Popup callbacks may change geometry, hide or disable autoFix. Iterate
        // a snapshot, and skip listeners removed by an earlier callback.
        for (Runnable listener : java.util.List.copyOf(popupBoundsListeners))
            if (popupBoundsListeners.contains(listener)) listener.run();
    }

    private void cancelKeys(GodotWindow window) {
        keyTargets.entrySet().removeIf(entry -> {
            if (entry.getValue() != window) return false;
            canceledKeys.add(entry.getKey()); return true;
        });
        if (window.getView() instanceof GodotView view) view.cancelKeys();
    }
    private void cancelInput(GodotWindow window) {
        // The drag's finally block balances source input after DRAG_DONE.
        if (drag != null && drag.source == window) return;
        cancelKeys(window);
        if (captured == window) { canceledMouseButtons |= mouseButtons; captured = null; mouseButtons = 0; }
        touchTargets.values().removeIf(value -> value == window);
        if (window.getView() instanceof GodotView view) view.cancelInput();
    }

    int hostX() { return primary == null || primary.isClosed() ? GodotBridge.hostInfo()[3] : primary.getX(); }
    int hostY() { return primary == null || primary.isClosed() ? GodotBridge.hostInfo()[4] : primary.getY(); }
    @Override public int[] getPopupBounds() {
        Application.checkEventThread();
        return null; // Independent native popups use the normal monitor work area.
    }
    void visibility(GodotWindow window, boolean visible) {
        if (!window.isPrimary()) return;
        // Only the primary Stage controls the existing root HWND. Secondary
        // native windows receive visibility through their identity-aware queue.
        boolean wasVisible = !visibleWindows.isEmpty();
        if (visible) visibleWindows.add(window); else visibleWindows.remove(window);
        boolean nowVisible = !visibleWindows.isEmpty();
        if (wasVisible != nowVisible) GodotBridge.hostCommand(5,nowVisible ? 1 : 0,0,null);
    }
    boolean grab(GodotWindow window) {
        if (!window.canFocus() || !window.isFocused()) return false;
        if (grabbed != null && grabbed != window) ungrab(grabbed);
        grabbed = window;
        return true;
    }
    void ungrab(GodotWindow window) {
        if (grabbed != window) return;
        grabbed = null; // Clear first: popup hiding can recursively ungrab.
        if (!window.isClosed()) window.focusUngrabbed();
    }
    private boolean belongsToGrab(GodotWindow window) {
        for (Window current = window; current != null; current = current.getOwner()) if (current == grabbed) return true;
        return false;
    }
    void restack() {
        var pending = new java.util.ArrayList<>(windows);
        pending.sort(java.util.Comparator.comparingInt(GodotWindow::compositionLevel));
        windows.clear();
        // An owned window must remain above its owner, even when the owner is
        // raised later. Choose in stable level order subject to that constraint.
        while (!pending.isEmpty()) {
            int selected = -1;
            for (int i = 0; i < pending.size(); ++i) {
                if (!pending.contains(pending.get(i).getOwner())) { selected = i; break; }
            }
            if (selected < 0) throw new IllegalStateException("Cyclic logical window ownership");
            windows.add(pending.remove(selected));
        }
        for (int i = 0; i < windows.size(); ++i) windows.get(i).composition(i);
    }
    void raise(GodotWindow window) {
        if (!window.isPrimary() && windows.remove(window)) windows.add(window);
        restack();
    }
    void lower(GodotWindow window) {
        if (!window.isPrimary() && windows.remove(window)) windows.add(0,window);
        restack();
    }
    void focus(GodotWindow window) {
        if (window == null || !window.canFocus()) return;
        if (focused != window) {
            if (grabbed != null && grabbed != window) ungrab(grabbed);
            // Focus can move between an owner and its native owned window
            // before a separate OS focus-loss record reaches this queue.
            // Cancel held pointer state as well, so a later release cannot
            // activate a control in the window that has already lost focus.
            if (focused != null && !focused.isClosed()) { focused.focusChanged(false); cancelInput(focused); }
            focused = window;
        }
        raise(window);
        window.focusChanged(hostFocused);
    }
    void hidden(GodotWindow window) {
        if (drag != null && (drag.source == window || (drag.target != null && drag.target.getWindow() == window)))
            finishDrag(drag, Clipboard.ACTION_NONE);
        if (grabbed == window) ungrab(window);
        if (hovered == window) hovered = null;
        window.focusChanged(false);
        cancelInput(window);
        if (focused == window) {
            window.focusChanged(false); focused = null;
            for (int i = windows.size()-1; i >= 0; --i) {
                GodotWindow candidate = windows.get(i);
                if (candidate != window && candidate.canFocus()) { focus(candidate); break; }
            }
        }
    }
    void closing(GodotWindow window) {
        for (GodotWindow child : java.util.List.copyOf(windows)) if (child.getOwner() == window && !child.isClosed()) child.close();
        hidden(window); visibility(window,false); windows.remove(window); restack();
    }
    private GodotWindow inputWindow(long id) {
        if (id == 0) return primary != null && !primary.isClosed() ? primary : null;
        for (GodotWindow window : windows)
            if (!window.isClosed() && window.getNativeWindow() == id) return window;
        return null; // Delayed events must not target a replacement peer.
    }
    private void deliver(GodotWindow window, double[] event) {
        if (window == null || !window.acceptsInput() || !(window.getView() instanceof GodotView view)) return;
        int kind = (int)event[0];
        if (kind <= 4 || (kind >= 7 && kind <= 12)) {
            event[2] -= window.getX()-hostX(); event[3] -= window.getY()-hostY();
        }
        view.dispatchInput(event,0);
    }

    private void onFrame() {
        int[] screens = GodotBridge.screenInfo();
        if (lastScreens != null && !java.util.Arrays.equals(lastScreens,screens)) Screen.notifySettingsChanged();
        lastScreens = screens;
        int[] info = GodotBridge.hostInfo();
        if (primary != null && !primary.isClosed()) primary.refresh(info);
        for (GodotWindow window : java.util.List.copyOf(windows)) window.refreshNative();
        for (GodotWindow window : java.util.List.copyOf(windows)) window.refreshScreen();
        updatePopupBounds();
        GodotWindow nativeFocused = null;
        for (GodotWindow window : windows) if (window.hasNativeFocus() && window.canFocus()) nativeFocused = window;
        boolean nativeFocus = nativeFocused != null;
        if (hostFocused && !nativeFocus) {
            if (drag != null) finishDrag(drag, Clipboard.ACTION_NONE);
            if (grabbed != null) ungrab(grabbed);
            for (GodotWindow window : java.util.List.copyOf(windows)) {
                window.focusChanged(false); cancelInput(window);
            }
        }
        hostFocused = nativeFocus;
        restack();
        if (nativeFocused != null && nativeFocused != focused) focus(nativeFocused);
        if (focused != null && !focused.isClosed()) focused.focusChanged(hostFocused && focused.acceptsInput());
        drainInput();
        if (focused != null && focused.acceptsInput() && focused.getView() instanceof GodotView view) view.updateImePosition();
    }

    private void collectInput() {
        double[] events = GodotBridge.pollWindowInputV2();
        if (events.length % GodotInputPacket.STRIDE != 0)
            throw new IllegalStateException("Invalid Godot window input record length");
        for (int i = 0; i < events.length; i += GodotInputPacket.STRIDE)
            pendingInput.add(java.util.Arrays.copyOfRange(events,i,i+GodotInputPacket.STRIDE));
    }

    // Focus changes must finish the old control's IME text synchronously, but
    // must not reenter unrelated mouse/key callbacks inside a mouse press.
    void drainImeInput(GodotWindow window) {
        Application.checkEventThread();
        collectInput();
        var completion = new java.util.ArrayList<double[]>();
        var iterator = pendingInput.iterator();
        while (iterator.hasNext()) {
            double[] packet = iterator.next();
            int kind = (int)packet[0];
            boolean text = kind == 13 || kind == 14
                    || ((kind == 5 || kind == 6) && packet[1] == 0);
            if (text && inputWindow((long)packet[9]) == window) {
                completion.add(packet);
                iterator.remove();
            }
        }
        // Do not retain a queue iterator across application callbacks.
        for (double[] packet : completion) deliver(window, packet);
    }

    void drainInput() {
        collectInput();
        while (!pendingInput.isEmpty()) {
            // Return to flush so it can report DRAG_DONE before a following
            // queued key/mouse release resets Scene's gesture state.
            if (drag != null && drag.finished) return;
            double[] packet = pendingInput.removeFirst();
            if (windows.isEmpty()) break;
            int kind = (int)packet[0];
            GodotWindow source = inputWindow((long)packet[9]);
            if (source == null) continue;
            if (kind == 9) {
                if (source.acceptsInput()) source.closeRequested();
                continue;
            }
            if (kind == 15) { hostFocused = true; focus(source); continue; }
            if (kind == 16) {
                source.focusChanged(false); cancelInput(source);
                if (focused == source) focused = null;
                continue;
            }
            double[] event = packet; // Already removed from the queue; preserve wheel metadata without another copy.
            try {
                if (dispatchDrag(event)) continue;
            } catch (Throwable failure) {
                System.out.println("EXTERNAL_DISPATCH_FAIL t=" + (System.currentTimeMillis() % 100000));
                failure.printStackTrace(System.out);
                throw failure;
            }
            if (kind == 20) continue; // Stale OLE completion without an active drag.
            // Native input is window-local. The existing capture/delivery code
            // uses root-relative coordinates so dragging across windows keeps
            // the captured View's local coordinates correct.
            if (kind <= 4 || (kind >= 7 && kind <= 12)) {
                event[2] = event[4]-hostX(); event[3] = event[5]-hostY();
            }
            if (kind >= 13) { deliver(source,event); continue; }
            if (kind == 5 || kind == 6) {
                int key = (int)event[1];
                if (key == 0) { deliver(source,event); continue; } // IME/Unicode commits have no physical key.
                if (canceledKeys.contains(key)) {
                    if (kind == 6) { canceledKeys.remove(key); continue; }
                    if (event[7] != 0) continue; // Ignore repeats from the canceled hold.
                    canceledKeys.remove(key); // A fresh press is valid even if focus loss swallowed key-up.
                }
                GodotWindow keyTarget;
                if (kind == 5) {
                    keyTarget = keyTargets.getOrDefault(key,source);
                    if (keyTarget != null) keyTargets.put(key,keyTarget);
                } else keyTarget = keyTargets.remove(key);
                deliver(keyTarget,event); continue;
            }
            if ((kind == 2 || kind == 10) && grabbed != null && !belongsToGrab(source)) ungrab(grabbed);
            GodotWindow target;
            if (kind >= 10 && kind <= 12) {
                int id = (int)event[1];
                if (kind == 10) {
                    target = source;
                    if (target != null && target.acceptsInput()) { touchTargets.put(id,target); focus(target); }
                } else target = touchTargets.get(id);
                if (kind == 12) touchTargets.remove(id);
                deliver(target,event);
                continue;
            }
            target = captured != null ? captured : source;
            int buttonBit = 1 << (int)event[1];
            if (kind == 2) canceledMouseButtons &= ~buttonBit;
            if (kind == 3 && ((canceledMouseButtons & buttonBit) != 0 || (mouseButtons & buttonBit) == 0)) {
                canceledMouseButtons &= ~buttonBit; continue;
            }
            if (kind == 1 && canceledMouseButtons != 0) {
                int flags = (int)event[6];
                int[] buttons = {1,2,3,8,9};
                for (int b = 0; b < buttons.length; ++b) if ((canceledMouseButtons & (1 << buttons[b])) != 0) flags &= ~(32 << b);
                event[6] = flags;
            }
            if (kind == 8) {
                if (hovered != source) continue;
                target = null;
            }
            if (kind == 1 || kind == 7 || kind == 8) {
                if (hovered != target) {
                    double[] exit = event.clone(); exit[0] = 8; deliver(hovered,exit);
                    hovered = target;
                    double[] enter = event.clone(); enter[0] = 7; deliver(hovered,enter);
                }
                if (kind != 1) continue;
            }
            if (kind == 2 && target != null) {
                if (!target.acceptsInput()) { target.focusDisabled(); continue; }
                focus(target); captured = target; mouseButtons |= 1 << (int)event[1];
            }
            // A release handler can enter showAndWait and dispatch more input
            // before returning. Commit release ownership before that callback,
            // or its nested loop keeps routing clicks to the old capture.
            if (kind == 3) { mouseButtons &= ~(1 << (int)event[1]); if (mouseButtons == 0) captured = null; }
            deliver(target,event);
        }
    }

    @Override protected void runLoop(Runnable launchable) {
        setEventThread(loop.thread());
        loop.start(() -> {
            scheduler.start();
            receivingInput = true;
            Thread inputReceiver = new Thread(this::receiveInput, "Godot input receiver");
            inputReceiver.setDaemon(true);
            inputReceiver.start();
            launchable.run();
        });
    }
    // Input arrives between host frame ticks; draining it immediately lets the
    // window commands it produces (for example title-bar drag moves) reach the
    // same host frame that delivered the event. The flag reset precedes the
    // drain so a pulse arriving mid-drain queues a follow-up instead of being lost.
    private void receiveInput() {
        long tick = 0;
        while (receivingInput) {
            long next = GodotBridge.awaitInputTick(tick, 1000);
            // Glass may terminate while the Godot host continues running.
            // The native wait is bounded; Java interrupt does not wake it.
            if (!receivingInput || next < 0) return;
            if (next == tick) continue;
            tick = next;
            if (inputDrainQueued.compareAndSet(false, true)) {
                try {
                    loop.invokeLater(() -> {
                        inputDrainQueued.set(false);
                        if (receivingInput) drainInput();
                    });
                } catch (java.util.concurrent.RejectedExecutionException stopped) { return; }
            }
        }
    }
    @Override protected void _invokeLater(Runnable runnable) { loop.invokeLater(runnable); }
    @Override protected void _invokeAndWait(Runnable runnable) { loop.invokeAndWait(runnable); }
    @Override protected Object _enterNestedEventLoop() { return loop.enterNested(); }
    @Override protected void _leaveNestedEventLoop(Object value) { loop.leaveNested(value); }
    @Override public void terminate() {
        Application.checkEventThread();
        try {
            var peers = java.util.List.copyOf(Window.getWindows());
            for (Window peer : peers) terminatePeer(() -> peer.setVisible(false));
            for (Window peer : peers) terminatePeer(peer::close);
        } finally {
            finishTerminating();
        }
    }
    private static void terminatePeer(Runnable operation) {
        try { operation.run(); }
        catch (GodotHostStoppedException cancelled) {
            // Native admission has ended. Continue closing every Java peer;
            // the host owns destruction of the corresponding native windows.
        }
        catch (Throwable failure) { Application.reportException(failure); }
    }
    @Override protected void finishTerminating() {
        receivingInput = false;
        // The bridge outlives Glass. Do not retain this application and its
        // window/input graph through a static bound-method callback.
        if (GodotBridge.dragTargetHandler == dragTargetHandler) GodotBridge.dragTargetHandler = null;
        scheduler.close();
        loop.stop();
        setEventThread(null);
        super.finishTerminating();
    }
    @Override public Timer createTimer(Runnable runnable) { return new GodotTimer(runnable, scheduler); }
    @Override protected int staticTimer_getMinPeriod() { return 1; }
    @Override protected int staticTimer_getMaxPeriod() { return 1_000_000; }
    @Override protected double staticScreen_getVideoRefreshPeriod() { return 0; }
    @Override protected Screen[] staticScreen_getScreens() {
        int[] data = GodotBridge.screenInfo();
        if (data.length == 0 || data.length % 10 != 0) throw new IllegalStateException("Invalid Godot screen snapshot");
        lastScreens = data;
        return GodotScreenLayout.create(data, com.sun.prism.impl.PrismSettings.allowHiDPIScaling);
    }
    @Override public Window createWindow(Window owner, Screen screen, int styleMask) {
        boolean main = primary == null || (primary.isClosed() && windows.isEmpty());
        if (windows.size() >= 64) throw new IllegalStateException("Godot supports at most 64 Glass windows");
        GodotWindow window = new GodotWindow(owner, screen, styleMask, main);
        if (main) primary = window;
        windows.add(window); restack();
        return window;
    }
    @Override public View createView() { return new GodotView(); }
    private boolean chooserOpen;
    private String[] choose(Window owner, int mode, String folder, String filename, String title,
            com.sun.glass.ui.CommonDialogs.ExtensionFilter[] filters, int selected) {
        Application.checkEventThread();
        if (chooserOpen) throw new IllegalStateException("A file chooser is already open");
        if (owner != null && (!(owner instanceof GodotWindow) || owner.isClosed()))
            throw new IllegalArgumentException("File chooser owner is not a live Godot window");
        String[] encoded = new String[filters == null ? 0 : filters.length*2];
        if (filters != null) for (int i=0; i<filters.length; ++i) {
            encoded[i*2] = filters[i].getDescription();
            encoded[i*2+1] = String.join(";",filters[i].getExtensions());
        }
        long id = owner == null ? -1 : ((GodotWindow)owner).isPrimary() ? 0 : owner.getNativeWindow();
        String[][] result = new String[1][];
        boolean submitted = false;
        var disabled = new java.util.ArrayList<Window>();
        chooserOpen = true;
        try {
            // FileChooser blocks the entire owner chain. Balance Glass's
            // counters so pre-existing modal disables survive restoration.
            for (Window window = owner; window != null; window = window.getOwner()) {
                window.setEnabled(false);
                disabled.add(window);
            }
            GodotBridge.dialogSubmit(id,mode,folder,filename,title,encoded,selected);
            submitted = true;
            loop.dispatchUntil(() -> {
                if (owner != null && (owner.isClosed() || !owner.isVisible())) GodotBridge.dialogCancel();
                result[0] = GodotBridge.dialogPoll();
                return result[0] != null;
            });
            return result[0] == null ? new String[]{"-1"} : result[0];
        } finally {
            if (submitted && result[0] == null) GodotBridge.dialogCancel();
            if (Application.isEventThread()) {
                for (int i = disabled.size() - 1; i >= 0; --i) {
                    Window window = disabled.get(i);
                    if (window.isClosed()) continue;
                    try { window.setEnabled(true); }
                    catch (GodotHostStoppedException stopped) { /* Host already owns cleanup. */ }
                }
            }
            chooserOpen = false;
        }
    }
    @Override protected com.sun.glass.ui.CommonDialogs.FileChooserResult staticCommonDialogs_showFileChooser(
            Window owner, String folder, String filename, String title, int type, boolean multiple,
            com.sun.glass.ui.CommonDialogs.ExtensionFilter[] filters, int selected) {
        String[] result = choose(owner,type == com.sun.glass.ui.CommonDialogs.Type.SAVE ? 2 : multiple ? 1 : 0,
                folder,filename,title,filters,selected);
        int index = Integer.parseInt(result[0]);
        var files = new java.util.ArrayList<java.io.File>();
        for (int i=1; i<result.length; ++i) files.add(new java.io.File(result[i]));
        return new com.sun.glass.ui.CommonDialogs.FileChooserResult(files,
                filters != null && index >= 0 && index < filters.length ? filters[index] : null);
    }
    @Override protected java.io.File staticCommonDialogs_showFolderChooser(Window owner, String folder, String title) {
        String[] result = choose(owner,3,folder,"",title,null,0);
        return result.length > 1 ? new java.io.File(result[1]) : null;
    }
    @Override public GlassRobot createRobot() { return new GodotRobot(); }
    @Override public Cursor createCursor(int type) { return new GodotCursor(type); }
    @Override public Cursor createCursor(int x, int y, Pixels pixels) { return new GodotCursor(x,y,pixels); }
    @Override protected com.sun.glass.ui.Size staticCursor_getBestSize(int width, int height) {
        return new com.sun.glass.ui.Size(Math.max(1,Math.min(256,width)),Math.max(1,Math.min(256,height)));
    }
    @Override protected void staticCursor_setVisible(boolean visible) { GodotBridge.hostCommand(11, visible ? 1 : 0, 0, null); }
    @Override protected boolean _supportsInputMethods() { return true; }

    static UnsupportedOperationException pending(String feature) {
        return new UnsupportedOperationException("Glass-Godot " + feature + " adapter is not implemented yet");
    }
}
