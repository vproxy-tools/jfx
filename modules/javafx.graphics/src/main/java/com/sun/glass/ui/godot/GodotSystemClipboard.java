/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.glass.ui.godot;

import com.sun.glass.ui.Application;
import com.sun.glass.ui.ClipboardAssistance;
import com.sun.glass.ui.Pixels;
import com.sun.glass.ui.SystemClipboard;
import com.sun.javafx.tk.godot.GodotBridge;
import java.util.HashMap;
import java.util.ArrayList;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import com.sun.glass.ui.win.WinHTMLCodec;

/** Immediate Windows text/HTML/RTF/URL/files/image transfer using the existing Godot HWND. */
final class GodotSystemClipboard extends SystemClipboard {
    /** Custom formats map to same-named registered Windows formats; see windows_custom_format.h. */
    static final String CUSTOM_PREFIX = "application/x-godot-custom-";
    private static final int MAX_CUSTOM_BYTES = 16 * 1024 * 1024 - 1;
    GodotSystemClipboard(String name) { super(name); }

    // Always query Windows: an external application's update must invalidate
    // Java-side data even when our application originally owned the clipboard.
    @Override protected boolean isOwner() { return false; }
    @Override public void flush(ClipboardAssistance source, HashMap<String,Object> data, int actions) {
        Application.checkEventThread();
        pushToSystem(data, actions);
        setSharedData(source, data, actions);
        contentChanged();
    }
    @Override protected void pushToSystem(HashMap<String,Object> data, int actions) {
        String[] files = null;
        int[] image = null;
        int imageWidth = 0, imageHeight = 0;
        var customNames = new ArrayList<String>();
        var customData = new ArrayList<byte[]>();
        for (String mime : data.keySet()) {
            if (FILE_LIST_TYPE.equals(mime)) {
                if (!(data.get(mime) instanceof String[] paths) || paths.length == 0)
                    throw new IllegalArgumentException("Clipboard file list requires a non-empty String[]");
                for (String path : paths) {
                    if (path == null || path.isEmpty() || path.indexOf('\0') >= 0 || path.indexOf('\n') >= 0)
                        throw new IllegalArgumentException("Clipboard file list contains an invalid path");
                }
                files = paths;
                continue;
            }
            if (RAW_IMAGE_TYPE.equals(mime)) {
                if (!(data.get(mime) instanceof Pixels pixels))
                    throw new IllegalArgumentException("Clipboard image requires a Glass Pixels value");
                image = argbPixels(pixels);
                imageWidth = pixels.getWidth();
                imageHeight = pixels.getHeight();
                continue;
            }
            if (mime.startsWith(CUSTOM_PREFIX)) {
                customNames.add(mime);
                customData.add(customBytes(mime, data.get(mime)));
                continue;
            }
            if (!TEXT_TYPE.equals(mime) && !HTML_TYPE.equals(mime) && !RTF_TYPE.equals(mime) && !URI_TYPE.equals(mime))
                throw GodotApplication.pending("Clipboard format " + mime);
            if (!(data.get(mime) instanceof String value)) throw new IllegalArgumentException("Clipboard text formats require String values");
            if (value.indexOf('\0') >= 0) throw new IllegalArgumentException("Clipboard strings cannot contain NUL");
        }
        String html = (String)data.get(HTML_TYPE);
        String rtf = (String)data.get(RTF_TYPE);
        byte[] htmlBytes = html == null ? null : WinHTMLCodec.encode(terminated(html,StandardCharsets.UTF_8));
        byte[] rtfBytes = rtf == null ? null : terminated(rtf,StandardCharsets.US_ASCII);
        GodotBridge.clipboardWriteFull((String)data.get(TEXT_TYPE),htmlBytes,rtfBytes,(String)data.get(URI_TYPE),files,image,imageWidth,imageHeight,
                customNames.isEmpty() ? null : customNames.toArray(String[]::new),
                customData.isEmpty() ? null : customData.toArray(byte[][]::new));
    }
    /** String values travel as UTF-8; byte[]/ByteBuffer pass through. Always NUL-terminated. */
    static byte[] customBytes(String mime, Object value) {
        byte[] bytes;
        if (value instanceof String string) bytes = customUtf8(string);
        else if (value instanceof byte[] array) bytes = array;
        else if (value instanceof ByteBuffer buffer) {
            ByteBuffer view = buffer.duplicate().order(ByteOrder.BIG_ENDIAN);
            int start = view.position(), remaining = view.remaining();
            boolean serialized = remaining >= 5 && view.getInt(start) == 0xACED0005
                    && (view.get(start + 4) == 0x74 || view.get(start + 4) == 0x7C);
            if (serialized) {
                int header = view.get(start + 4) == 0x74 ? 7 : 13;
                if (remaining < header) throw new IllegalArgumentException("Truncated serialized clipboard string");
                long encoded = header == 7 ? Short.toUnsignedInt(view.getShort(start + 5)) : view.getLong(start + 5);
                // Modified UTF-8 can use three bytes per UTF-16 code unit.
                // Permit that overhead, but never trust a declared long-string
                // length that differs from the actual buffer before decoding.
                if (encoded != remaining - header || encoded > 3L * MAX_CUSTOM_BYTES)
                    throw new IllegalArgumentException("Invalid serialized clipboard string length");
            } else if (remaining > MAX_CUSTOM_BYTES) {
                throw new IllegalArgumentException("Clipboard format " + mime + " data must be at most 16 MiB");
            }
            bytes = new byte[remaining];
            view.get(bytes);
            // Quantum serializes Serializable values (including Strings) before
            // Glass sees them; unwrap a serialized String so the wire carries
            // its UTF-8 form like direct Glass callers. Other serialized
            // payloads pass through as-is.
            String unwrapped = deserializeString(bytes);
            if (unwrapped != null) bytes = customUtf8(unwrapped);
        } else throw new IllegalArgumentException("Clipboard format " + mime + " requires a String, byte[] or ByteBuffer value");
        if (bytes.length == 0 || bytes.length > MAX_CUSTOM_BYTES)
            throw new IllegalArgumentException("Clipboard format " + mime + " data must be at most 16 MiB");
        for (byte b : bytes) if (b == 0) throw new IllegalArgumentException("Clipboard format " + mime + " data cannot contain NUL");
        return Arrays.copyOf(bytes, bytes.length + 1);
    }
    private static byte[] customUtf8(String value) {
        if (value.length() > MAX_CUSTOM_BYTES)
            throw new IllegalArgumentException("Clipboard string exceeds 16 MiB");
        return value.getBytes(StandardCharsets.UTF_8);
    }
    /** Restrictive String-only read of QuantumClipboard's serialized form, or null. */
    private static String deserializeString(byte[] bytes) {
        if (bytes.length < 5 || (bytes[0] & 0xFF) != 0xAC || (bytes[1] & 0xFF) != 0xED
                || bytes[2] != 0 || bytes[3] != 5 || (bytes[4] != 0x74 && bytes[4] != 0x7C)) return null;
        try (var in = new java.io.ObjectInputStream(new java.io.ByteArrayInputStream(bytes)) {
                @Override protected Class<?> resolveClass(java.io.ObjectStreamClass desc) throws java.io.IOException {
                    throw new java.io.InvalidClassException("Resolving classes is unsupported");
                }
                @Override protected Class<?> resolveProxyClass(String[] interfaces) throws java.io.IOException {
                    throw new java.io.InvalidClassException("Resolving interfaces is unsupported");
                }
            }) {
            in.setObjectInputFilter(info -> java.io.ObjectInputFilter.Status.REJECTED);
            if (in.readObject() instanceof String string && in.read() == -1) return string;
        } catch (java.io.IOException | ClassNotFoundException invalidSerialization) { }
        return null;
    }
    // INT_ARGB_PRE ints and BYTE_BGRA_PRE bytes share one little-endian layout.
    static int imagePixelCount(int width, int height) {
        // Match windows_clipboard.h's DIB publication budget, including its
        // 40-byte BITMAPINFOHEADER, before allocating the Java staging copy.
        long count = (long) width * height;
        if (width < 1 || height < 1 || width > 16384 || height > 16384
                || count * Integer.BYTES + 40 > 64L * 1024 * 1024) {
            throw new IllegalArgumentException("Clipboard image exceeds native DIB dimensions or 64 MiB budget");
        }
        return (int) count;
    }

    private static int[] argbPixels(Pixels pixels) {
        int count = imagePixelCount(pixels.getWidth(), pixels.getHeight());
        Buffer buffer = pixels.getPixels();
        int[] argb = new int[count];
        if (buffer instanceof IntBuffer ints) {
            if (ints.capacity() < count) throw new IllegalArgumentException("Clipboard image buffer is smaller than its dimensions");
            for (int i = 0; i < count; ++i) argb[i] = ints.get(i);
        } else if (buffer instanceof ByteBuffer bytes) {
            if (bytes.capacity() < count * 4) throw new IllegalArgumentException("Clipboard image buffer is smaller than its dimensions");
            ByteBuffer view = bytes.duplicate().order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < count; ++i) argb[i] = view.getInt(i * 4);
        } else {
            throw new IllegalArgumentException("Unsupported clipboard image buffer");
        }
        return argb;
    }
    private static byte[] terminated(String value, java.nio.charset.Charset charset) {
        byte[] bytes = value.getBytes(charset);
        return Arrays.copyOf(bytes,bytes.length+1);
    }
    @Override protected Object popFromSystem(String mime) {
        if (TEXT_TYPE.equals(mime)) return GodotBridge.clipboardReadText();
        if (URI_TYPE.equals(mime)) return GodotBridge.clipboardReadUrl();
        if (FILE_LIST_TYPE.equals(mime)) return GodotBridge.clipboardReadFiles();
        if (HTML_TYPE.equals(mime)) {
            byte[] bytes=GodotBridge.clipboardReadRich(0);
            return bytes == null ? null : new String(WinHTMLCodec.decode(bytes),StandardCharsets.UTF_8);
        }
        if (RTF_TYPE.equals(mime)) {
            byte[] bytes=GodotBridge.clipboardReadRich(1);
            return bytes == null ? null : new String(bytes,0,bytes.length-1,StandardCharsets.US_ASCII);
        }
        if (RAW_IMAGE_TYPE.equals(mime)) {
            int[] data=GodotBridge.clipboardReadImage();
            if (data == null || data.length < 2 || data.length != 2 + (long)data[0] * data[1]) return null;
            return Application.GetApplication().createPixels(data[0],data[1],IntBuffer.wrap(data,2,data.length-2));
        }
        if (mime.startsWith(CUSTOM_PREFIX)) {
            byte[] bytes=GodotBridge.clipboardReadCustom(mime);
            // The wire form is NUL-terminated; applications see the payload only.
            return bytes == null ? null : Arrays.copyOf(bytes,bytes.length-1);
        }
        return null;
    }
    @Override protected String[] mimesFromSystem() {
        int formats=GodotBridge.clipboardFormats();
        var mimes=new ArrayList<String>(5);
        if ((formats&1)!=0) mimes.add(TEXT_TYPE);
        if ((formats&2)!=0) mimes.add(HTML_TYPE);
        if ((formats&4)!=0) mimes.add(RTF_TYPE);
        if ((formats&8)!=0) mimes.add(URI_TYPE);
        if ((formats&16)!=0) mimes.add(FILE_LIST_TYPE);
        if ((formats&32)!=0) mimes.add(RAW_IMAGE_TYPE);
        String[] custom=GodotBridge.clipboardCustomFormats();
        if (custom != null) for (String name : custom) mimes.add(name);
        return mimes.toArray(String[]::new);
    }
    @Override protected int supportedSourceActionsFromSystem() { return ACTION_COPY; }
    @Override protected void pushTargetActionToSystem(int action) { actionPerformed(action); }
}
