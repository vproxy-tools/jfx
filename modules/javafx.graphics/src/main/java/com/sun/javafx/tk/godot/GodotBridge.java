/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.javafx.tk.godot;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import javafx.application.Platform;

/** Internal batch boundary. Native methods are registered by the Godot host. */
public final class GodotBridge {
    /** One bounded native chooser request; null poll result means still open. */
    public static native void dialogSubmit(long owner, int mode, String folder, String filename, String title, String[] filters, int selected);
    public static native String[] dialogPoll();
    public static native void dialogCancel();
    /** Physical desktop coordinates; these calls do not access Godot Nodes. */
    public static native void robotMove(int x, int y);
    public static native int[] robotPosition();
    public static native int[] robotCapture(int x, int y, int width, int height);
    private GodotBridge() {}
    private static volatile Thread renderThread;

    /** The pipeline's worker is daemonized by Quantum; the host must drain it before stopping JNI admission. */
    public static void registerRenderThread(Thread thread) { renderThread = thread; }

    /** Returns false for bounded-queue backpressure; retries must keep sequence. */
    public static native boolean submit(ByteBuffer frame, int byteLength);

    /** Coalesced Godot frame number, or -1 after host shutdown. */
    public static native long awaitTick(long after, int timeoutMillis);
    public static native long awaitFrameTick(long after, int timeoutMillis);
    /** Host input-queue generation; wakes immediately when input arrives between frames. */
    public static native long awaitInputTick(long after, int timeoutMillis);

    /** Explicit GPU readback after the supplied completed pass; tightly packed ARGB_PRE. */
    public static native int[] readPixels(long handle, long sequence, int x, int y, int width, int height);

    /** Coherent main-thread snapshot: size, DPI, position, monitor rect, focus, mode, ack. */
    public static native int[] hostInfo();
    public static native int[] screenInfo();

    /** Queues a low-frequency window operation; returns its acknowledgement sequence. */
    public static native int hostCommand(int operation, int a, int b, String text);
    /** Bounded BGRA_PRE cursor asset; creates/selects it only on the Godot main thread. */
    public static native int customCursor(int width, int height, int x, int y, byte[] bgra);
    public static native long windowIcon(long id, int width, int height, byte[] bgra);
    public static native void clipboardWriteFormats(String text, byte[] html, byte[] rtf);
    public static native void clipboardWriteUrlFormats(String text, byte[] html, byte[] rtf, String url);
    /** Full publication: CF_HDROP from file paths, CF_DIB from ARGB pixels and registered
     * custom formats (application/x-godot-custom-* names, NUL-terminated data) join the text formats. */
    public static native void clipboardWriteFull(String text, byte[] html, byte[] rtf, String url, String[] files, int[] image, int imageWidth, int imageHeight, String[] customNames, byte[][] customData);
    public static native String clipboardReadUrl();
    public static native byte[] clipboardReadRich(int kind);
    /** Reads CF_HDROP as path strings, or null when absent. */
    public static native String[] clipboardReadFiles();
    /** Reads CF_DIB as {width, height, ARGB...}, or null when absent. */
    public static native int[] clipboardReadImage();
    /** Reads one application/x-godot-custom-* format as NUL-terminated bytes, or null when absent. */
    public static native byte[] clipboardReadCustom(String name);
    /** Lists present custom clipboard format names (application/x-godot-custom-* convention). */
    public static native String[] clipboardCustomFormats();
    public static native int clipboardFormats();

    /** Ordered native-window operations: 1=create hidden, 2=geometry/visibility, 3=destroy owner tree, 4=property.
     * Flags: 1 borderless, 2 transparent, 4 popup. IDs are positive and never reused.
     * For operation 4, flags is the host property number (1 title, 4 focus, 6 mode,
     * 7 resizable, 9 always-on-top, 12 minimum size, 13 maximum size, 15 borderless,
     * 16 IME enabled, 17 IME candidate position, 18 finish composition,
     * 19 input enabled, 20 focusable);
     * x/y carry its arguments and title carries its text.
     */
    public static native long nativeWindowCommand(long id, long owner, int operation,
            int x, int y, int width, int height, int flags, boolean visible, String title);
    /** Empty before creation/after destruction; otherwise id, owner, native ID, x/y, w/h, visible, focus, mode, ack. */
    public static native long[] nativeWindowInfo(long id);
    public static native long nativeWindowAck();
    /** True when all admitted host/window/render commands have completed on Godot. */
    private static native boolean isShutdownDrained();

    /** Drains ordered input records: kind, button, x/y, screen x/y, modifiers, wheel x/y. */
    public static native double[] pollInput();
    /** Same ordered queue as pollInput: field 9 is the native Glass ID (zero for root),
     * fields 10/11 carry unsigned Windows wheel lines/chars. Records have 12 doubles.
     * Kinds 15/16 report focus gained/lost; kind 20 completes an OLE drag with its effect.
     * Exactly one consumer must drain this queue.
     */
    public static native double[] pollWindowInputV2();

    /** Immediate Unicode text transfer; null clears the Windows clipboard. */
    public static native void clipboardWriteText(String text);
    public static native String clipboardReadText();
    public static native boolean clipboardHasText();

    /** Hands the active in-app drag to Windows OLE for cross-application transfer.
     * Effects/buttons use Glass action and (1 << buttonIndex) masks; sourceId is the
     * Glass window id (0 for the root host). Completion arrives as input record kind 20
     * carrying the performed effect. The request is consumed on the Godot main thread.
     * Custom entries publish application/x-godot-custom-* registered formats with
     * NUL-terminated byte payloads, matching the system clipboard convention.
     * dragImage is the optional Dragboard view (big-endian w/h envelope + BGRA
     * bytes, with a matching big-endian offset pair or null). */
    public static native void dragStartExternal(String text, byte[] html, byte[] rtf, String url, String[] files,
            String[] customNames, byte[][] customData, byte[] dragImage, byte[] dragImageOffset, int effects, int buttons, long sourceId);

    /** OLE drop-target callback entry. Invoked on the Godot main thread, never on the FX thread.
     * kind: 1 enter, 2 over, 3 leave, 4 drop. Returns the chosen Glass action (== DROPEFFECT bits).
     * Custom names/data are parallel arrays inside the application/x-godot-custom-* convention. */
    public interface DragTargetHandler {
        int onDragTarget(int kind, long windowId, int sx, int sy, int effects, int keyState,
                String mimes, String text, byte[] html, byte[] rtf, String url, String files,
                String[] customNames, byte[][] customData);
    }
    public static volatile DragTargetHandler dragTargetHandler;
    static int dragTargetCallback(int kind, long windowId, int sx, int sy, int effects, int keyState,
            String mimes, String text, byte[] html, byte[] rtf, String url, String files,
            String[] customNames, byte[][] customData) {
        DragTargetHandler handler = dragTargetHandler;
        if (handler == null) return 0;
        try {
            return handler.onDragTarget(kind, windowId, sx, sy, effects, keyState, mimes, text, html, rtf, url, files, customNames, customData);
        } catch (Throwable failure) {
            com.sun.glass.ui.Application.reportException(failure);
            return 0;
        }
    }

    // Invoked through JNI on the JVM bootstrap thread, never the Godot thread.
    public static void launch(String mainClass) throws Throwable {
        Thread stopWatcher = new Thread(() -> {
            long tick = 0;
            while ((tick = awaitTick(tick, 1000)) >= 0) { }
            Platform.exit();
        }, "Godot JavaFX shutdown watcher");
        stopWatcher.setDaemon(true);
        stopWatcher.start();
        try {
            Class.forName(mainClass).getMethod("main", String[].class)
                    .invoke(null, (Object) new String[0]);
            Thread renderer = renderThread;
            if (renderer != null && renderer != Thread.currentThread()) renderer.join();
        } catch (Throwable exception) {
            Throwable original = exception instanceof InvocationTargetException invocation ? invocation.getCause() : exception;
            try {
                // Keep JNI queues open while FX hides windows and the renderer
                // disposes their targets. Only then report the original failure
                // to the native worker, which closes queue admission.
                Platform.exit();
                Thread renderer = renderThread;
                if (renderer != null && renderer != Thread.currentThread()) {
                    renderer.join(10_000);
                    if (renderer.isAlive()) throw new IllegalStateException("Renderer did not stop after application failure");
                }
                long tick = 0, deadline = System.nanoTime()+10_000_000_000L;
                while (!isShutdownDrained()) {
                    if (System.nanoTime()>deadline) throw new IllegalStateException("Godot failure cleanup did not drain");
                    tick=awaitTick(tick,100);
                    if (tick<0) throw new IllegalStateException("Godot stopped before failure cleanup drained");
                }
            } catch (Throwable cleanup) { original.addSuppressed(cleanup); }
            throw original;
        }
    }
}
