/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.prism.godot;

import com.sun.prism.MediaFrame;
import com.sun.prism.PixelFormat;
import com.sun.prism.Texture;
import com.sun.prism.impl.BaseTexture;
import com.sun.prism.impl.Disposer;
import com.sun.prism.impl.DisposerManagedResource;
import com.sun.javafx.tk.godot.GodotHostStoppedException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.FloatBuffer;
import java.nio.ByteOrder;

/** Source-image/glyph texture backed by a generation-tagged Godot resource. */
public class GodotTexture extends BaseTexture<GodotTexture.Resource> {
    static final class Data implements Disposer.Record {
        final GodotTexturePool pool;
        final long handle, bytes;
        boolean disposed;
        Data(GodotTexturePool pool, long handle, long bytes) { this.pool = pool; this.handle = handle; this.bytes = bytes; }
        @Override public void dispose() {
            if (disposed) return;
            pool.checkThread();
            try {
                pool.flushVertices.run(); pool.ensureRoom(16);
                pool.commands.disposeTexture(handle);
            } catch (GodotHostStoppedException cancelled) {
                // The host owns final GPU teardown after admission closes.
                // Release Java accounting too, so one cancelled native disposal
                // cannot abort ManagedResource/pipeline cleanup for all textures.
            }
            pool.release(handle); disposed = true;
        }
    }
    static final class Resource extends DisposerManagedResource<Data> {
        Resource(Data data) { super(data, data.pool, data); }
        @Override public void free() { if (resource != null) resource.dispose(); }
    }

    private final GodotTexturePool pool;
    boolean belongsTo(GodotTexturePool candidate) { return pool == candidate; }
    protected GodotTexture(Data data, PixelFormat format, WrapMode wrap, int width, int height) {
        super(new Resource(data), format, wrap, width, height); pool = data.pool;
    }
    protected GodotTexture(GodotTexture source, WrapMode wrap) { super(source, wrap, false); pool = source.pool; }

    public static boolean supports(PixelFormat format) {
        return format == PixelFormat.INT_ARGB_PRE || format == PixelFormat.BYTE_BGRA_PRE ||
                format == PixelFormat.BYTE_RGB || format == PixelFormat.BYTE_GRAY || format == PixelFormat.BYTE_ALPHA || format == PixelFormat.FLOAT_XYZW;
    }

    public static GodotTexture create(GodotTexturePool pool, PixelFormat format, WrapMode wrap, int width, int height) {
        if (!supports(format)) throw new UnsupportedOperationException("Godot texture format: " + format);
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || wrap == null) throw new IllegalArgumentException("Invalid Godot texture dimensions/wrap");
        Data data = pool.allocate(width, height, false, format == PixelFormat.FLOAT_XYZW);
        return data == null ? null : new GodotTexture(data, format, wrap, width, height);
    }

    static GodotTexture createMediaPlane(GodotTexturePool pool,int width,int height) {
        Data data=pool.allocateMediaPlane(width,height);
        return data==null ? null : new GodotTexture(data,PixelFormat.BYTE_ALPHA,WrapMode.CLAMP_TO_EDGE,width,height);
    }

    public long getNativeHandle() {
        pool.checkThread(); assertLocked();
        if (isSurfaceLost()) throw new IllegalStateException("Godot texture was disposed");
        return resource.getResource().handle;
    }

    @Override protected Texture createSharedTexture(WrapMode wrap) { return new GodotTexture(this, wrap); }

    @Override public void setLinearFiltering(boolean linear) {
        pool.checkThread();
        // Pending vertices must retain the sampling state they were drawn with.
        if (getLinearFiltering() != linear) pool.flushVertices.run();
        super.setLinearFiltering(linear);
    }

    @Override public void update(Buffer source, PixelFormat format, int dx, int dy, int sx, int sy,
            int width, int height, int stride, boolean skipFlush) {
        long handle = getNativeHandle();
        int bpp = format == null ? 0 : format.getBytesPerPixelUnit();
        if (format != getPixelFormat() || source == null || dx < 0 || dy < 0 || sx < 0 || sy < 0 || width <= 0 || height <= 0 ||
                (long) dx + width > getContentWidth() || (long) dy + height > getContentHeight() || stride <= 0 ||
                bpp == 0 || stride % bpp != 0 || ((long) sx + width) * bpp > stride) throw new IllegalArgumentException("Invalid texture upload region");
        int elementBytes = format.getDataType().getSizeInBytes();
        long needed = (long) sy * stride + (long) (height - 1) * stride + ((long) sx + width) * bpp;
        if (needed > (long) source.remaining() * elementBytes ||
                (format == PixelFormat.FLOAT_XYZW ? !(source instanceof FloatBuffer) : format == PixelFormat.INT_ARGB_PRE ? !(source instanceof IntBuffer) : !(source instanceof ByteBuffer))) {
            throw new IllegalArgumentException("Texture source type/limit does not cover upload");
        }
        if (!skipFlush) pool.flushVertices.run();
        if(format==PixelFormat.BYTE_ALPHA){
            // A 1080p YUV frame must fit in one bounded transfer. Expanding its
            // planes to RGBA here quadruples traffic and forces mid-frame waits.
            int rowsPerChunk=Math.max(1,(4*1024*1024-56)/width);
            for(int row=0;row<height;row+=rowsPerChunk){
                int rows=Math.min(rowsPerChunk,height-row);
                pool.ensureRoom(32+width*rows);
                pool.commands.updateAlphaTextureRegion(handle,dx,dy+row,width,rows,(ByteBuffer)source,sx,sy+row,stride);
            }
            return;
        }
        if (format == PixelFormat.FLOAT_XYZW) {
            int rowsPerChunk = Math.max(1,(256*1024)/(width*16));
            FloatBuffer values = (FloatBuffer) source;
            // Validate all rows before emitting the first patch.
            for (int y = 0; y < height; ++y) {
                int at = source.position() + (sy+y)*(stride/4) + sx*4;
                for (int x = 0; x < width*4; ++x) if (!Float.isFinite(values.get(at+x))) throw new IllegalArgumentException("Non-finite float texture value");
            }
            for (int row = 0; row < height; row += rowsPerChunk) {
                int rows = Math.min(rowsPerChunk,height-row);
                ByteBuffer bytes = ByteBuffer.allocate(width*rows*16).order(ByteOrder.LITTLE_ENDIAN);
                for (int y = 0; y < rows; ++y) {
                    int at = source.position() + (sy+row+y)*(stride/4) + sx*4;
                    for (int x = 0; x < width*4; ++x) bytes.putFloat(values.get(at+x));
                }
                pool.ensureRoom(32+bytes.capacity());
                pool.commands.updateFloatTextureRegion(handle,dx,dy+row,width,rows,bytes.array());
            }
            return;
        }
        int rowsPerChunk = Math.max(1, (256 * 1024) / (width * 4));
        for (int row = 0; row < height; row += rowsPerChunk) {
            int rows = Math.min(rowsPerChunk, height - row);
            byte[] rgba = new byte[width * rows * 4];
            for (int y = 0; y < rows; ++y) {
                int at = source.position() + (int) (((long) (sy + row + y) * stride + (long) sx * bpp) / elementBytes);
                for (int x = 0; x < width; ++x) {
                    int r, g, b, a;
                    if (format == PixelFormat.INT_ARGB_PRE) {
                        int argb = ((IntBuffer) source).get(at + x);
                        a = argb >>> 24; r = (argb >>> 16) & 255; g = (argb >>> 8) & 255; b = argb & 255;
                    } else {
                        ByteBuffer bytes = (ByteBuffer) source;
                        int pixel = at + x * bpp;
                        if (format == PixelFormat.BYTE_BGRA_PRE) {
                            b = bytes.get(pixel) & 255; g = bytes.get(pixel + 1) & 255; r = bytes.get(pixel + 2) & 255; a = bytes.get(pixel + 3) & 255;
                        } else if (format == PixelFormat.BYTE_RGB) {
                            r = bytes.get(pixel) & 255; g = bytes.get(pixel + 1) & 255; b = bytes.get(pixel + 2) & 255; a = 255;
                        } else {
                            r = g = b = bytes.get(pixel) & 255;
                            a = format == PixelFormat.BYTE_ALPHA ? r : 255;
                        }
                    }
                    int out = (y * width + x) * 4;
                    rgba[out] = (byte) r; rgba[out + 1] = (byte) g; rgba[out + 2] = (byte) b; rgba[out + 3] = (byte) a;
                }
            }
            pool.ensureRoom(32 + rgba.length);
            pool.commands.updateTextureRegion(handle, dx, dy + row, width, rows, rgba);
        }
    }

    @Override public void update(MediaFrame frame, boolean skipFlush) {
        if (frame.planeCount() != 1) throw new UnsupportedOperationException("Godot planar/media format conversion is not implemented");
        if (frame.getPixelFormat() != getPixelFormat()) {
            // Packed formats without a Godot texture mapping (e.g. AVFoundation
            // BYTE_APPLE_422) convert to the texture's format, like J2D/SW.
            frame.holdFrame();
            MediaFrame converted = frame.convertToFormat(getPixelFormat());
            frame.releaseFrame();
            if (converted == null) throw new UnsupportedOperationException("Godot planar/media format conversion is not implemented");
            frame = converted;
        } else {
            frame.holdFrame();
        }
        try {
            ByteBuffer bytes = frame.getBufferForPlane(0).duplicate();
            Buffer pixels = getPixelFormat() == PixelFormat.INT_ARGB_PRE ? bytes.order(java.nio.ByteOrder.nativeOrder()).asIntBuffer() : bytes;
            update(pixels, getPixelFormat(), 0, 0, 0, 0, frame.getWidth(), frame.getHeight(), frame.strideForPlane(0), skipFlush);
        } finally { frame.releaseFrame(); }
    }
}
