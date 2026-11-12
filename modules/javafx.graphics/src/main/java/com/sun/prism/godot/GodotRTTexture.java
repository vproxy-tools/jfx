/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.prism.godot;

import com.sun.glass.ui.Screen;
import com.sun.prism.*;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** Managed GPU render-target allocation; CPU uploads are never accepted. */
public final class GodotRTTexture extends GodotTexture implements RTTexture {
    private final GodotContext context;
    private boolean opaque;
    private GodotRTTexture(Data data, GodotContext context, WrapMode wrap, int width, int height) {
        super(data, PixelFormat.INT_ARGB_PRE, wrap, width, height);
        this.context = context;
    }
    private GodotRTTexture(GodotRTTexture source, WrapMode wrap) {
        super(source, wrap); context = source.context; opaque = source.opaque;
    }
    static GodotRTTexture create(GodotTexturePool pool, GodotContext context, WrapMode wrap, int width, int height) {
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || wrap == null) throw new IllegalArgumentException("Invalid render-target dimensions/wrap");
        Data data = pool.allocate(width, height, true);
        return data == null ? null : new GodotRTTexture(data, context, wrap, width, height);
    }
    @Override protected Texture createSharedTexture(WrapMode wrap) { return new GodotRTTexture(this, wrap); }
    @Override public Graphics createGraphics() { return new GodotGraphics(context, this); }
    @Override public Screen getAssociatedScreen() { return context.getAssociatedScreen(); }
    @Override public boolean isOpaque() { return opaque; }
    @Override public void setOpaque(boolean value) { opaque = value; }
    @Override public boolean isMSAA() { return false; }
    @Override public boolean isVolatile() { return false; }
    @Override public int[] getPixels() { return null; }
    @Override public boolean readPixels(Buffer pixels) {
        return readPixels(pixels, getContentX(), getContentY(), getContentWidth(), getContentHeight());
    }
    @Override public boolean readPixels(Buffer pixels, int x, int y, int width, int height) {
        java.util.Objects.requireNonNull(pixels);
        if (!(pixels instanceof ByteBuffer) && !(pixels instanceof IntBuffer))
            throw new IllegalArgumentException("Readback requires ByteBuffer or IntBuffer");
        if (pixels.isReadOnly()) throw new java.nio.ReadOnlyBufferException();
        if (x < 0 || y < 0 || width < 0 || height < 0 || (long)x + width > getPhysicalWidth() || (long)y + height > getPhysicalHeight())
            throw new IllegalArgumentException("Readback rectangle exceeds render target");
        long count = (long)width * height;
        if (count * (pixels instanceof ByteBuffer ? 4 : 1) > pixels.remaining())
            throw new IllegalArgumentException("Readback buffer has insufficient remaining space");
        if (count == 0) return true;
        int[] data = context.readPixels(this, x, y, width, height);
        if (data == null) return false;
        if (data.length != count) throw new IllegalStateException("Incomplete render-target readback");
        // Write through duplicates, preserving caller position/limit and slices.
        if (pixels instanceof IntBuffer ints) ints.duplicate().put(data);
        else {
            var bytes = ((ByteBuffer)pixels).duplicate();
            for (int argb : data) {
                bytes.put((byte)argb).put((byte)(argb >>> 8)).put((byte)(argb >>> 16)).put((byte)(argb >>> 24));
            }
        }
        return true;
    }
    @Override public void update(Buffer buffer, PixelFormat format, int dx, int dy, int sx, int sy, int width, int height, int stride, boolean skipFlush) {
        throw new UnsupportedOperationException("CPU upload to a Godot render target is unsupported");
    }
    @Override public void update(MediaFrame frame, boolean skipFlush) { throw new UnsupportedOperationException("Media upload to a Godot render target is unsupported"); }
}
