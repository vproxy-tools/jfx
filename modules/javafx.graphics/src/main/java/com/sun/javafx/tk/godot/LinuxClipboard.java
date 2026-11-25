package com.sun.javafx.tk.godot;

import com.sun.glass.ui.godot.GodotHtmlCodec;

import java.awt.Image;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LinuxClipboard {
    private static final int MAX_BYTES = 16 * 1024 * 1024;
    private static final String PREFIX = "application/x-godot-custom-";
    static final DataFlavor HTML = binary("text/html;charset=UTF-8", "text/html");
    static final DataFlavor RTF = binary("text/rtf", "text/rtf");
    static final DataFlavor URL = binary("application/x-godot-url", "application/x-godot-url");
    static final DataFlavor URIS = binary("text/uri-list;charset=UTF-8", "text/uri-list");

    private static DataFlavor binary(String mime, String target) {
        return new DataFlavor(mime + ";class=java.io.InputStream", target);
    }

    static Clipboard wayland = new WaylandClipboard();

    private static Object read(DataFlavor flavor) {
        try {
            return wayland.getData(flavor);
        } catch (UnsupportedFlavorException absent) {
            return null;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read Linux clipboard " + flavor, error);
        }
    }

    private static byte[] bytes(DataFlavor flavor) {
        if (!(read(flavor) instanceof InputStream stream)) return null;
        try (stream) {
            byte[] data = stream.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) throw new IllegalArgumentException("Clipboard payload exceeds 16 MiB");
            return data;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot read Linux clipboard bytes", error);
        }
    }

    private static void checkText(String value) {
        if (value != null && (value.indexOf(0) >= 0 || value.length() > MAX_BYTES / 2))
            throw new IllegalArgumentException("Invalid clipboard string");
    }

    private static byte[] payload(byte[] wire) {
        if (wire.length == 0 || wire.length > MAX_BYTES || wire[wire.length - 1] != 0)
            throw new IllegalArgumentException("Invalid terminated clipboard payload");
        for (int index = 0; index < wire.length - 1; index++) {
            if (wire[index] == 0) throw new IllegalArgumentException("Embedded NUL in clipboard payload");
        }
        return Arrays.copyOf(wire, wire.length - 1);
    }

    private static byte[] terminated(byte[] data) {
        if (data == null) return null;
        return Arrays.copyOf(data, data.length + 1);
    }

    static DataFlavor custom(String name) {
        if (name == null || name.length() > 128 || !name.matches("application/x-godot-custom-[a-z0-9._-]+"))
            throw new IllegalArgumentException("Invalid custom clipboard MIME");
        return binary(name, name);
    }

    private record Contents(Map<DataFlavor, Object> values) implements Transferable {
        public DataFlavor[] getTransferDataFlavors() { return values.keySet().toArray(DataFlavor[]::new); }
        public boolean isDataFlavorSupported(DataFlavor flavor) { return values.containsKey(flavor); }
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            Object value = values.get(flavor);
            if (value == null) throw new UnsupportedFlavorException(flavor);
            return value instanceof byte[] data ? new ByteArrayInputStream(data) : value;
        }
    }

    static void write(String text, byte[] html, byte[] rtf, String url, String[] files, int[] pixels,
                      int width, int height, String[] customNames, byte[][] customData) {
        Map<DataFlavor, Object> values = new LinkedHashMap<>();
        checkText(text);
        checkText(url);
        if (text != null) values.put(DataFlavor.stringFlavor, text);
        if (html != null) {
            payload(html);
            values.put(HTML, GodotHtmlCodec.decode(html));
        }
        if (rtf != null) values.put(RTF, payload(rtf));
        if (url != null) {
            values.put(URL, url.getBytes(StandardCharsets.UTF_8));
            if (files == null) values.put(URIS, (url + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        if (files != null) {
            if (files.length == 0 || files.length > 4096) throw new IllegalArgumentException("Invalid file count");
            List<File> paths = new ArrayList<>(files.length);
            for (String file : files) {
                checkText(file);
                if (file == null || file.isEmpty() || file.indexOf('\n') >= 0) throw new IllegalArgumentException("Invalid file path");
                paths.add(new File(file));
            }
            values.put(DataFlavor.javaFileListFlavor, List.copyOf(paths));
        }
        if (pixels != null) {
            long count = (long) width * height;
            if (width <= 0 || height <= 0 || width > 16384 || height > 16384 ||
                    count * 4 + 40 > 64L * 1024 * 1024 || count != pixels.length)
                throw new IllegalArgumentException("Invalid clipboard image");
            var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
            System.arraycopy(pixels, 0, ((DataBufferInt)image.getRaster().getDataBuffer()).getData(), 0, pixels.length);
            values.put(DataFlavor.imageFlavor, image);
        }
        if (customNames != null || customData != null) {
            if (customNames == null || customData == null || customNames.length != customData.length || customNames.length > 32)
                throw new IllegalArgumentException("Invalid custom clipboard arrays");
            for (int index = 0; index < customNames.length; index++) {
                if (customData[index] == null || values.put(custom(customNames[index]), payload(customData[index])) != null)
                    throw new IllegalArgumentException("Missing or duplicate custom clipboard value");
            }
        }
        wayland.setContents(new Contents(values), null);
    }

    static String text() { return (String)read(DataFlavor.stringFlavor); }
    static String url() {
        byte[] own = bytes(URL);
        if (own != null) return new String(own, StandardCharsets.UTF_8);
        byte[] list = bytes(URIS);
        if (list == null) return null;
        return new String(list, StandardCharsets.UTF_8).lines().filter(line -> !line.isBlank() && !line.startsWith("#")).findFirst().orElse(null);
    }
    static byte[] rich(int kind) {
        if (kind != 0 && kind != 1) throw new IllegalArgumentException("Unknown rich clipboard format");
        byte[] value = bytes(kind == 0 ? HTML : RTF);
        if (value == null) return null;
        return kind == 0 ? GodotHtmlCodec.encode(terminated(value)) : terminated(value);
    }
    static String[] files() {
        if (!(read(DataFlavor.javaFileListFlavor) instanceof List<?> items) || items.isEmpty()) return null;
        return items.stream().map(File.class::cast).map(File::getPath).toArray(String[]::new);
    }
    static int[] image() {
        if (!(read(DataFlavor.imageFlavor) instanceof Image source)) return null;
        int width = source.getWidth(null), height = source.getHeight(null);
        if (width <= 0 || height <= 0 || width > 16384 || height > 16384 || (long)width * height * 4 + 40 > 64L * 1024 * 1024)
            throw new IllegalArgumentException("Invalid clipboard image dimensions");
        var converted = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB_PRE);
        if (source instanceof BufferedImage buffered) {
            // The Wayland PNG decoder already owns these pixels. Creating a
            // Copy pixels directly without opening a desktop graphics context.
            int[] row = new int[width];
            for (int y = 0; y < height; y++) {
                buffered.getRGB(0, y, width, 1, row, 0, width);
                converted.setRGB(0, y, width, 1, row, 0, width);
            }
        } else {
            var graphics = converted.createGraphics();
            try {
                graphics.setComposite(java.awt.AlphaComposite.Src);
                if (!graphics.drawImage(source, 0, 0, null)) throw new IllegalStateException("Incomplete clipboard image");
            } finally { graphics.dispose(); }
        }
        int[] values = new int[2 + width * height];
        values[0] = width;
        values[1] = height;
        System.arraycopy(((DataBufferInt)converted.getRaster().getDataBuffer()).getData(), 0, values, 2, values.length - 2);
        return values;
    }
    static byte[] customData(String name) { return terminated(bytes(custom(name))); }
    static String[] customNames() {
        var names = new java.util.TreeSet<String>();
        for (DataFlavor flavor : wayland.getAvailableDataFlavors()) {
            String mime = flavor.getPrimaryType() + "/" + flavor.getSubType();
            if (mime.startsWith(PREFIX)) names.add(mime);
        }
        return names.toArray(String[]::new);
    }
    static int formats() {
        var clipboard = wayland;
        return (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor) ? 1 : 0)
                | (clipboard.isDataFlavorAvailable(HTML) ? 2 : 0) | (clipboard.isDataFlavorAvailable(RTF) ? 4 : 0)
                | (clipboard.isDataFlavorAvailable(URL) || clipboard.isDataFlavorAvailable(URIS) ? 8 : 0)
                | (clipboard.isDataFlavorAvailable(DataFlavor.javaFileListFlavor) ? 16 : 0)
                | (clipboard.isDataFlavorAvailable(DataFlavor.imageFlavor) ? 32 : 0);
    }
}
