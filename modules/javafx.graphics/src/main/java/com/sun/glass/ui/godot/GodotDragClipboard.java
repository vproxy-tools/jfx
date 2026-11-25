/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.glass.ui.godot;

import com.sun.glass.ui.Application;
import com.sun.glass.ui.Clipboard;
import com.sun.glass.ui.ClipboardAssistance;
import java.util.HashMap;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Application-local drag data. The system clipboard is never modified. */
final class GodotDragClipboard extends Clipboard {
    GodotDragClipboard() { super(DND); }
    static GodotDragClipboard current() {
        // Clipboard.remove evicts the shared entry when its last assistant
        // closes. A second static cache could retain that detached instance.
        return (GodotDragClipboard) get(DND);
    }

    // Glass passes an opaque private Wayland MIME value as bytes. The final
    // byte is a JNI envelope terminator and is removed before the pipe write.
    static byte[] waylandBytes(String mime, Object value) {
        final int limit = 16 * 1024 * 1024 - 1;
        ByteBuffer bytes;
        if (value instanceof ByteBuffer buffer) bytes = buffer.duplicate();
        else if (value instanceof byte[] array) bytes = ByteBuffer.wrap(array);
        else if (value instanceof String text && text.length() <= limit) bytes = StandardCharsets.UTF_8.encode(text);
        else throw new IllegalArgumentException("Unsupported Wayland drag payload for " + mime);
        if (bytes.remaining() > limit) throw new IllegalArgumentException("Wayland drag payload exceeds 16 MiB for " + mime);
        byte[] result = new byte[bytes.remaining() + 1];
        bytes.get(result, 0, result.length - 1);
        return result;
    }

    /** Publishes externally sourced drag data for target-side reads. Never valid
     * while a local drag owns the shared data; an empty map clears it again. */
    void setExternalData(HashMap<String, Object> data, int actions) {
        Application.checkEventThread();
        if (((GodotApplication) Application.GetApplication()).dragActive())
            throw new IllegalStateException("A local drag owns the drag clipboard");
        setSharedData(null, data, data.isEmpty() ? ACTION_NONE : actions);
    }

    @Override public void flush(ClipboardAssistance source, HashMap<String, Object> data, int actions) {
        Application.checkEventThread();
        var app = (GodotApplication) Application.GetApplication();
        if (app.dragActive()) throw new IllegalStateException("A drag is already active");
        setSharedData(source, data, actions);
        try {
            app.runDrag(actions, this::actionPerformed);
        } finally {
            setSharedData(null, new HashMap<>(), ACTION_NONE);
        }
    }

    // Completion is reported exactly once when the gesture finishes, not when
    // a potential target advertises a transfer action.
    @Override public void setTargetAction(int action) { Application.checkEventThread(); }
}
