/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.javafx.tk.godot;

import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.imageio.ImageIO;

/** Translates the Linux Glass clipboard formats to offers on the Godot window's seat. */
final class WaylandClipboard extends Clipboard {
    private static final String TEXT = "text/plain;charset=utf-8";
    private static final String FILES = "text/uri-list";
    private static final String PNG = "image/png";

    private Map<String, byte[]> pending;

    WaylandClipboard() {
        super("Wayland clipboard");
    }

    static Map<String, byte[]> encode(Transferable source) {
        Map<String, byte[]> offer = new LinkedHashMap<>();
        try {
            for (var flavor : source.getTransferDataFlavors()) {
                Object value = source.getTransferData(flavor);
                if (flavor.equals(DataFlavor.stringFlavor)) {
                    byte[] text = ((String)value).getBytes(StandardCharsets.UTF_8);
                    offer.put(TEXT, text);
                    offer.put("text/plain", text);
                } else if (flavor.equals(DataFlavor.javaFileListFlavor)) {
                    var uris = new StringBuilder();
                    for (Object file : (List<?>)value) {
                        uris.append(((File)file).toPath().toUri().toASCIIString()).append("\r\n");
                    }
                    offer.put(FILES, uris.toString().getBytes(StandardCharsets.UTF_8));
                } else if (flavor.equals(DataFlavor.imageFlavor)) {
                    var out = new ByteArrayOutputStream();
                    if (!ImageIO.write((BufferedImage)value, "png", out)) throw new IOException("Missing PNG writer");
                    offer.put(PNG, out.toByteArray());
                } else {
                    try (var stream = (InputStream)value) {
                        offer.put(flavor.getPrimaryType() + "/" + flavor.getSubType(), stream.readAllBytes());
                    }
                }
            }
        } catch (IOException | UnsupportedFlavorException e) {
            throw new IllegalStateException("Could not encode Wayland clipboard offer", e);
        }
        return offer;
    }

    @Override public synchronized void setContents(Transferable source, ClipboardOwner owner) {
        Map<String, byte[]> offer = encode(source);
        // One dispatch may replace the clipboard repeatedly under the same
        // input serial. Publish its final offer when control returns to Glass.
        if (pending == null) javafx.application.Platform.runLater(this::flush);
        pending = offer;
    }

    synchronized void flush() {
        Map<String, byte[]> offer = pending;
        if (offer == null) return;
        pending = null;
        GodotBridge.waylandClipboardWrite(offer.keySet().toArray(String[]::new), offer.values().toArray(byte[][]::new));
    }

    private static List<File> fileList(byte[] bytes) {
        var files = new ArrayList<File>();
        if (bytes == null) return files;
        var lines = new String(bytes, StandardCharsets.UTF_8).lines().iterator();
        while (lines.hasNext()) {
            String line = lines.next();
            if (line.isBlank() || line.startsWith("#")) continue;
            try {
                URI uri = URI.create(line);
                if (!"file".equalsIgnoreCase(uri.getScheme()) || uri.isOpaque() ||
                        uri.getQuery() != null || uri.getFragment() != null) continue;
                String authority = uri.getRawAuthority();
                if (authority != null && !"localhost".equalsIgnoreCase(authority)) continue;
                String path = uri.getPath();
                if (path != null && path.startsWith("/") && path.indexOf(0) < 0) files.add(new File(path));
            } catch (IllegalArgumentException malformed) {
                // A foreign offer can contain malformed entries alongside valid local files.
            }
        }
        return files;
    }

    @Override public synchronized Transferable getContents(Object requestor) {
        Map<String, byte[]> local = pending;
        return local != null ? decode(local.keySet(), local::get)
                : decode(List.of(GodotBridge.waylandClipboardTypes()), GodotBridge::waylandClipboardRead);
    }

    static Transferable decode(Collection<String> names, Function<String, byte[]> read) {
        Map<DataFlavor, String> types = new LinkedHashMap<>();
        for (String mime : names) {
            switch (mime) {
                case TEXT -> types.put(DataFlavor.stringFlavor, mime);
                case "text/plain", "UTF8_STRING" -> types.putIfAbsent(DataFlavor.stringFlavor, mime);
                case "text/html" -> types.put(LinuxClipboard.HTML, mime);
                case "text/rtf" -> types.put(LinuxClipboard.RTF, mime);
                case "application/x-godot-url" -> types.put(LinuxClipboard.URL, mime);
                case FILES -> {
                    types.put(LinuxClipboard.URIS, mime);
                    types.put(DataFlavor.javaFileListFlavor, mime);
                }
                case PNG -> types.put(DataFlavor.imageFlavor, mime);
                default -> {
                    if (mime.length() <= 128 && mime.matches("application/x-godot-custom-[a-z0-9._-]+")) {
                        types.put(LinuxClipboard.custom(mime), mime);
                    }
                }
            }
        }
        return new Transferable() {
            @Override public DataFlavor[] getTransferDataFlavors() {
                return types.keySet().stream().filter(this::isDataFlavorSupported).toArray(DataFlavor[]::new);
            }
            @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
                if (flavor.equals(DataFlavor.javaFileListFlavor) && types.containsKey(flavor)) {
                    return !fileList(read.apply(FILES)).isEmpty();
                }
                return types.containsKey(flavor);
            }
            @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException, IOException {
                String mime = types.get(flavor);
                if (mime == null) throw new UnsupportedFlavorException(flavor);
                byte[] bytes = read.apply(mime);
                if (bytes == null) throw new UnsupportedFlavorException(flavor);
                if (flavor.equals(DataFlavor.stringFlavor)) return new String(bytes, StandardCharsets.UTF_8);
                if (flavor.equals(DataFlavor.javaFileListFlavor)) {
                    var files = fileList(bytes);
                    if (files.isEmpty()) throw new UnsupportedFlavorException(flavor);
                    return files;
                }
                if (flavor.equals(DataFlavor.imageFlavor)) {
                    var input = ByteBuffer.wrap(bytes);
                    if (bytes.length < 24 || input.getLong() != 0x89504e470d0a1a0aL ||
                            input.getInt() != 13 || input.getInt() != 0x49484452) throw new IOException("Invalid PNG header");
                    int width = input.getInt(), height = input.getInt();
                    if (width <= 0 || height <= 0 || width > 16384 || height > 16384 ||
                            (long)width * height * 4 + 40 > 64L * 1024 * 1024) throw new IOException("Clipboard image too large");
                    var image = ImageIO.read(new ByteArrayInputStream(bytes));
                    if (image == null) throw new IOException("Cannot decode PNG clipboard image");
                    return image;
                }
                return new ByteArrayInputStream(bytes);
            }
        };
    }
}
