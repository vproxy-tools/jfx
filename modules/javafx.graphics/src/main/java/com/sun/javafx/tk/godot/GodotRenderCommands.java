/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.javafx.tk.godot;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import com.sun.prism.godot.GodotShader;

/** Render-thread encoder for owned native batches. No full-scene pixel upload. */
public final class GodotRenderCommands {
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final Object SUBMISSION_LOCK = new Object();
    private static long nextSequence = 1;
    private final Thread owner = Thread.currentThread();
    private ByteBuffer buffer = allocate(4096);
    private int count;
    private boolean failed;
    private boolean hostStopped;
    private long lastSubmittedSequence;
    private final java.util.LinkedHashMap<String,byte[]> shaderSources = new java.util.LinkedHashMap<>(16, .75f, true);
    private int shaderSourceBytes;
    private static final boolean CACHE_SHADER_SOURCES = !"0".equals(System.getenv("GODOT_JFX_SHADER_SOURCE_CACHE"));
    // Content-addressed geometry retains owned array snapshots: caller buffers
    // are mutable, and a 64-bit hash alone cannot establish equality.
    private static final boolean CACHE_GEOMETRY = !"0".equals(System.getenv("GODOT_JFX_GEOMETRY_CACHE"));
    private static final int GEOMETRY_CACHE_MAX = Math.max(0, Integer.parseInt(
            System.getenv().getOrDefault("GODOT_JFX_GEOMETRY_CACHE_MAX", "1024")));
    private static final long GEOMETRY_CACHE_BYTES = 32L * 1024 * 1024;
    private static final long GEOMETRY_IDLE_SUBMISSIONS = 64;
    private static final java.util.concurrent.atomic.AtomicLong NEXT_GEOMETRY = new java.util.concurrent.atomic.AtomicLong(1);
    private static final class GeometryEntry {
        final long handle;
        final float[] coordinates;
        final byte[] colors;
        long lastUsed;
        GeometryEntry(long handle, long lastUsed, float[] coordinates, byte[] colors, int vertices) {
            this.handle = handle;
            this.lastUsed = lastUsed;
            this.coordinates = java.util.Arrays.copyOf(coordinates, vertices * 7);
            this.colors = java.util.Arrays.copyOf(colors, vertices * 4);
        }
        long bytes() { return (long)coordinates.length * Float.BYTES + colors.length; }
        boolean matches(float[] coordinates, byte[] colors, int vertices) {
            return this.coordinates.length == vertices * 7
                    && java.util.Arrays.equals(this.coordinates, 0, this.coordinates.length, coordinates, 0, vertices * 7)
                    && java.util.Arrays.equals(this.colors, 0, this.colors.length, colors, 0, vertices * 4);
        }
    }
    private final java.util.LinkedHashMap<Long, GeometryEntry> geometryCache = new java.util.LinkedHashMap<>(16, 0.75f, true);
    private long geometryCacheBytes;
    private long lastEvictSubmission = -1;
    private long submissionClock;

    // Consecutive-state suppression. Opcodes 11/12 are stateful on the host
    // ("replaces shader state", clip persists), so an identical repeat only
    // costs serialization and host parsing. Tracking survives submit() but is
    // invalidated at every command that host-side resets draw state (target
    // selection, pass completion, frame begin, clear) and at texture disposal.
    // A second encoder in the process disables suppression entirely: streams
    // from two contexts interleave at submission time, and skipped states
    // would then apply to the wrong stream.
    private static final java.util.concurrent.atomic.AtomicInteger ENCODERS = new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicBoolean SUPPRESSION_ALLOWED = new java.util.concurrent.atomic.AtomicBoolean(true);
    private static final boolean SUPPRESS_STATE = !"0".equals(System.getenv("GODOT_JFX_STATE_SUPPRESS"));
    private GodotShader.Snapshot activeState;
    private long[] activeStateHandles;
    private boolean activeClip;
    private int activeClipX, activeClipY, activeClipW, activeClipH;

    private boolean suppressible() { return SUPPRESS_STATE && SUPPRESSION_ALLOWED.get(); }

    private void invalidateActiveState() {
        activeState = null;
        activeStateHandles = null;
        activeClip = false;
        activeClipX = activeClipY = activeClipW = activeClipH = 0;
    }

    private byte[] encodedShaderSource(String source) {
        byte[] cached = shaderSources.get(source);
        if (cached != null) return cached;
        if (source.isEmpty() || source.length() > 262144) throw new IllegalArgumentException("Invalid shader source length");
        for (int i = 0; i < source.length(); ++i) {
            char c = source.charAt(i);
            if (c == 0 || c > 127) throw new IllegalArgumentException("Shader transport requires ASCII source");
        }
        byte[] encoded = source.getBytes(StandardCharsets.US_ASCII);
        if (!CACHE_SHADER_SOURCES) return encoded;
        while (shaderSources.size() >= 64 || shaderSourceBytes + encoded.length > 524288) {
            var iterator = shaderSources.entrySet().iterator();
            shaderSourceBytes -= iterator.next().getValue().length;
            iterator.remove();
        }
        shaderSources.put(source, encoded); shaderSourceBytes += encoded.length;
        return encoded;
    }

    private static boolean shaderIdentifier(String name) {
        if (name.isEmpty() || name.length() > 128) return false;
        for (int i = 0; i < name.length(); ++i) {
            char c = name.charAt(i);
            if (c != '_' && !(c >= 'A' && c <= 'Z') && !(c >= 'a' && c <= 'z') &&
                    !(i > 0 && c >= '0' && c <= '9')) return false;
        }
        return true;
    }

    public long getLastSubmittedSequence() { checkOwner(); return lastSubmittedSequence; }

    public GodotRenderCommands() {
        buffer.position(24);
        if (ENCODERS.incrementAndGet() > 1) SUPPRESSION_ALLOWED.set(false);
    }

    public int remainingBatchBytes() { checkOwner(); return MAX_BYTES - buffer.position(); }

    private static ByteBuffer allocate(int size) {
        return ByteBuffer.allocateDirect(size).order(ByteOrder.LITTLE_ENDIAN);
    }

    private void checkOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Godot command encoder belongs to another thread");
        if (hostStopped) throw new GodotHostStoppedException("Godot host has stopped accepting render commands");
        if (failed) throw new IllegalStateException("Godot command encoder failed; recreate the render context");
    }

    private void reserve(int bytes) {
        checkOwner();
        if (count == 65536 || bytes > MAX_BYTES - buffer.position()) {
            throw new IllegalArgumentException("Godot command batch limit exceeded");
        }
        if (buffer.remaining() < bytes) {
            int size = Math.min(MAX_BYTES, Math.max(buffer.capacity() * 2, buffer.position() + bytes));
            ByteBuffer larger = allocate(size);
            buffer.flip();
            larger.put(buffer);
            buffer = larger;
        }
    }

    public void clear() {
        reserve(8);
        buffer.putInt(1).putInt(0);
        count++;
        invalidateActiveState();
    }

    public void beginPresent() {
        if (remainingBatchBytes() < 8 || count == 65536) submit();
        reserve(8); buffer.putInt(13).putInt(0); ++count;
        invalidateActiveState();
    }

    public long present() {
        if (remainingBatchBytes() < 8 || count == 65536) submit();
        reserve(8); buffer.putInt(14).putInt(0); ++count;
        invalidateActiveState();
        return submit();
    }

    /** Publishes the selected GPU target as one retained host-local window layer. */
    // HOVER_TAP=1 timestamps every window presentation so a user-visible
    // stall can be told apart from a skipped present on the Java side.
    private static final boolean HOVER_TAP = "1".equals(System.getenv("GODOT_JFX_HOVER_TAP"));
    private static long tapStart = System.nanoTime();
    public static long tapEpoch() { return tapStart; }

    public long presentWindow(long window, float x, float y, float width, float height,
                              int z, float opacity) {
        if (HOVER_TAP) System.out.printf(java.util.Locale.ROOT, "HOVER_TAP t=%.0fms PRESENT window=%d%n",
                (System.nanoTime() - tapStart) / 1e6, window);
        checkWindow(window, x, y, width, height, opacity);
        if (remainingBatchBytes() < 40 || count == 65536) submit();
        reserve(40); buffer.putInt(23).putInt(32).putLong(window);
        buffer.putFloat(x).putFloat(y).putFloat(width).putFloat(height).putInt(z).putFloat(opacity);
        ++count;
        invalidateActiveState();
        return submit();
    }

    /** Publishes into an independently created native window, never an embedded layer. */
    public long presentNativeWindow(long window, float width, float height, float opacity) {
        checkWindow(window, 0, 0, width, height, opacity);
        if (remainingBatchBytes() < 40 || count == 65536) submit();
        reserve(40); buffer.putInt(26).putInt(32).putLong(window);
        buffer.putFloat(0).putFloat(0).putFloat(width).putFloat(height).putInt(0).putFloat(opacity);
        ++count;
        invalidateActiveState();
        return submit();
    }

    private static void checkWindow(long window, float x, float y, float width, float height, float opacity) {
        if (window <= 0 || !Float.isFinite(x) || !Float.isFinite(y)
                || !Float.isFinite(width) || !Float.isFinite(height)
                || width <= 0 || height <= 0 || width > 8192 || height > 8192
                || !Float.isFinite(opacity) || opacity < 0 || opacity > 1) {
            throw new IllegalArgumentException("Invalid window geometry/opacity");
        }
    }

    /** Updates a published layer without redrawing or reallocating its GPU content. */
    public long configureWindow(long window, float x, float y, float width, float height,
                                int z, float opacity, boolean visible) {
        checkWindow(window, x, y, width, height, opacity);
        if (remainingBatchBytes() < 44 || count == 65536) submit();
        reserve(44); buffer.putInt(25).putInt(36).putLong(window);
        buffer.putFloat(x).putFloat(y).putFloat(width).putFloat(height).putInt(z).putFloat(opacity);
        buffer.putInt(visible ? 1 : 0);
        ++count;
        return submit();
    }

    /** Releases the layer before its target may be disposed. Ends this batch. */
    public long removeWindow(long window) {
        if (window <= 0) throw new IllegalArgumentException("Invalid window identity");
        if (remainingBatchBytes() < 16 || count == 65536) submit();
        reserve(16); buffer.putInt(24).putInt(8).putLong(window); ++count;
        invalidateActiveState();
        return submit();
    }

    public void selectTarget(long handle) {
        if (handle != 0) checkHandle(handle);
        if (remainingBatchBytes() < 16 || count == 65536) submit();
        reserve(16); buffer.putInt(16).putInt(8).putLong(handle); ++count;
        invalidateActiveState();
    }

    public long endTargetPass() {
        appendEndTargetPass();
        return submit();
    }

    /** Keeps dependent passes in one bounded transfer; publication/readback submits. */
    public void appendEndTargetPass() {
        if (remainingBatchBytes() < 8 || count == 65536) submit();
        reserve(8); buffer.putInt(17).putInt(0); ++count;
        invalidateActiveState();
    }

    /** Replaces shader state; previously recorded geometry retains its state. */
    public void shaderState(GodotShader.Snapshot state) {
        shaderState(state, new long[0]);
    }

    public void shaderState(GodotShader.Snapshot state, long[] textureUnits) {
        checkOwner();
        textureUnits = textureUnits.clone();
        String source = state.source();
        byte[] sourceBytes = encodedShaderSource(source);
        if (state.constants().size() > 1024) throw new IllegalArgumentException("Too many shader constants");
        int bytes = 12 + source.length();
        if (state.samplerUnits().size() > 16) throw new IllegalArgumentException("Too many shader samplers");
        for (var entry : state.samplerUnits().entrySet()) {
            if (!shaderIdentifier(entry.getKey()) || state.constants().containsKey(entry.getKey()) || entry.getValue() < 0 || entry.getValue() >= textureUnits.length || entry.getValue() > 15) {
                throw new IllegalArgumentException("Invalid or unbound shader sampler");
            }
            checkHandle(textureUnits[entry.getValue()]);
            bytes += 12 + entry.getKey().length();
        }
        for (var entry : state.constants().entrySet()) {
            String name = entry.getKey();
            var value = entry.getValue();
            int[] words = value.words();
            if (!shaderIdentifier(name) || value.components() < 1 || value.components() > 4 ||
                    (value.array() ? value.components() != 4 || words.length < 4 || words.length > 4096 || words.length % 4 != 0 : words.length != value.components())) {
                throw new IllegalArgumentException("Invalid shader constant shape/name");
            }
            if (!value.integer()) for (int word : words) if (!Float.isFinite(Float.intBitsToFloat(word))) throw new IllegalArgumentException("Non-finite shader constant");
            bytes = Math.addExact(bytes, 16 + name.length() + words.length * 4);
        }
        if (suppressible() && state.equals(activeState) && java.util.Arrays.equals(textureUnits, activeStateHandles)) return;
        if (bytes + 8 <= MAX_BYTES - 24 && count > 0 && (count == 65536 || remainingBatchBytes() < bytes + 8)) submit();
        reserve(8 + bytes);
        buffer.putInt(11).putInt(bytes).putInt(source.length()).put(sourceBytes);
        buffer.putInt(state.constants().size());
        for (var entry : state.constants().entrySet()) {
            var value = entry.getValue();
            int[] words = value.words();
            byte[] name = entry.getKey().getBytes(StandardCharsets.US_ASCII);
            buffer.putInt(name.length).put(name).putInt(value.integer() ? 1 : 0).putInt(value.components()).putInt(value.array() ? words.length / 4 : 0);
            for (int word : words) buffer.putInt(word);
        }
        buffer.putInt(state.samplerUnits().size());
        for (var entry : state.samplerUnits().entrySet()) {
            byte[] name = entry.getKey().getBytes(StandardCharsets.US_ASCII);
            buffer.putInt(name.length).put(name).putLong(textureUnits[entry.getValue()]);
        }
        ++count;
        activeState = state;
        activeStateHandles = textureUnits;
    }

    public void resize(int width, int height) {
        if (width <= 0 || height <= 0 || width > 8192 || height > 8192) throw new IllegalArgumentException("Invalid surface dimensions");
        reserve(16);
        buffer.putInt(3).putInt(8).putInt(width).putInt(height);
        count++;
    }

    public void clip(boolean enabled, int x, int y, int width, int height) {
        checkOwner();
        if (width < 0 || height < 0 || (!enabled && (x != 0 || y != 0 || width != 0 || height != 0))) throw new IllegalArgumentException("Invalid clip rectangle");
        if (suppressible() && enabled == activeClip && (!enabled
                || (x == activeClipX && y == activeClipY && width == activeClipW && height == activeClipH))) return;
        if (count > 0 && (count == 65536 || remainingBatchBytes() < 28)) submit();
        reserve(28);
        buffer.putInt(12).putInt(20).putInt(enabled ? 1 : 0).putInt(x).putInt(y).putInt(width).putInt(height);
        ++count;
        activeClip = enabled;
        activeClipX = x; activeClipY = y; activeClipW = width; activeClipH = height;
    }

    /**
     * Accepts the arrays supplied by BaseContext.renderQuads / VertexBuffer.
     * Vertices stay in local space; the 2D affine (mxx, mxy, mxt, myx, myy,
     * myt) is applied by the host at draw time, so an unchanged batch under a
     * moving parent stays byte-identical and the host can reuse its geometry.
     */
    public void drawQuads(float[] coordinates, byte[] colors, int vertices,
                          double mxx, double mxy, double mxt, double myx, double myy, double myt) {
        drawQuads(0, coordinates, colors, vertices, mxx, mxy, mxt, myx, myy, myt, 1);
    }

    /** As {@link #drawQuads} with the uniform draw modulation carried separately. */
    public void drawQuads(float[] coordinates, byte[] colors, int vertices,
                          double mxx, double mxy, double mxt, double myx, double myy, double myt, float alpha) {
        drawQuads(0, coordinates, colors, vertices, mxx, mxy, mxt, myx, myy, myt, alpha);
    }

    public void drawTexturedQuads(long texture, float[] coordinates, byte[] colors, int vertices,
                                  double mxx, double mxy, double mxt, double myx, double myy, double myt) {
        checkHandle(texture);
        drawQuads(texture, coordinates, colors, vertices, mxx, mxy, mxt, myx, myy, myt, 1);
    }

    public void drawTexturedQuads(long texture, float[] coordinates, byte[] colors, int vertices,
                                  double mxx, double mxy, double mxt, double myx, double myy, double myt, float alpha) {
        checkHandle(texture);
        drawQuads(texture, coordinates, colors, vertices, mxx, mxy, mxt, myx, myy, myt, alpha);
    }

    private static void checkHandle(long handle) {
        long slot = handle & 0xffffffffL, generation = handle >>> 32;
        if (slot < 1 || slot > 4096 || generation < 1 || generation > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid texture handle");
    }

    public void createTexture(long handle, int width, int height, byte[] premultipliedRgba) {
        textureImage(5, handle, width, height, premultipliedRgba);
    }

    public void updateTexture(long handle, int width, int height, byte[] premultipliedRgba) {
        textureImage(6, handle, width, height, premultipliedRgba);
    }

    public void allocateTexture(long handle, int width, int height) {
        allocateTexture(handle, width, height, 9);
    }

    public void allocateRenderTarget(long handle, int width, int height) {
        allocateTexture(handle, width, height, 15);
    }

    public void allocateVideoPlane(long handle,int width,int height) {
        allocateTexture(handle,width,height,28);
    }

    public void allocateFloatTexture(long handle, int width, int height) {
        allocateTexture(handle, width, height, 18);
    }

    public void allocateSceneTexture(long handle, int width, int height, String path) {
        checkOwner(); checkHandle(handle);
        if (path == null || !path.startsWith("res://") || path.length() < 7 || path.indexOf('\0') >= 0) throw new IllegalArgumentException("Expected a res:// scene path");
        byte[] utf8 = path.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (utf8.length > 4096 || width < 1 || height < 1 || width > 8192 || height > 8192) throw new IllegalArgumentException("Invalid scene path length/dimensions");
        reserve(28+utf8.length);
        buffer.putInt(20).putInt(20+utf8.length).putLong(handle).putInt(width).putInt(height).putInt(utf8.length).put(utf8);
        count++;
    }

    public void resizeSceneTexture(long handle, long source, int width, int height) {
        checkOwner(); checkHandle(handle); checkHandle(source);
        if (handle == source || width < 1 || height < 1 || width > 8192 || height > 8192) throw new IllegalArgumentException("Invalid scene resize");
        reserve(32);
        buffer.putInt(21).putInt(24).putLong(handle).putLong(source).putInt(width).putInt(height);
        count++;
    }

    public void sceneInput(long handle,int kind,int flags,int code,int unicode,float x,float y,float dx,float dy) {
        checkOwner(); checkHandle(handle);
        if (kind < 0 || kind > 5 || flags < 0 || flags > 63 || code < 0 || unicode < 0 || unicode > 0x10ffff || (unicode >= 0xd800 && unicode <= 0xdfff) || (kind == 1 && (code < 1 || code > 9)) || !Float.isFinite(x) || !Float.isFinite(y) || !Float.isFinite(dx) || !Float.isFinite(dy)) throw new IllegalArgumentException("Invalid scene input");
        reserve(48);
        buffer.putInt(22).putInt(40).putLong(handle).putInt(kind).putInt(flags).putInt(code).putInt(unicode).putFloat(x).putFloat(y).putFloat(dx).putFloat(dy);
        count++;
    }

    private void allocateTexture(long handle, int width, int height, int opcode) {
        checkHandle(handle);
        if (width < 1 || height < 1 || width > 8192 || height > 8192) throw new IllegalArgumentException("Invalid texture dimensions");
        reserve(24);
        buffer.putInt(opcode).putInt(16).putLong(handle).putInt(width).putInt(height);
        count++;
    }

    public void updateTextureRegion(long handle, int x, int y, int width, int height, byte[] pixels) {
        updateTextureRegion(handle,x,y,width,height,pixels,false);
    }

    /** Copies single-channel planes into the owned batch without RGBA expansion. */
    public void updateAlphaTextureRegion(long handle,int x,int y,int width,int height,
                                         ByteBuffer source,int sx,int sy,int stride) {
        checkOwner();checkHandle(handle);
        long length=(long)width*height;
        long needed=(long)sy*stride+(long)(height-1)*stride+sx+width;
        if(x<0 || y<0 || x>8192 || y>8192 || width<1 || height<1 || width>8192 || height>8192 ||
                source==null || sx<0 || sy<0 || stride<1 || (long)sx+width>stride ||
                length>MAX_BYTES-56 || needed>source.remaining())
            throw new IllegalArgumentException("Invalid alpha texture region/source");
        reserve(32+(int)length);
        buffer.putInt(27).putInt(24+(int)length).putLong(handle).putInt(x).putInt(y).putInt(width).putInt(height);
        int start=source.position()+sy*stride+sx;
        if(stride==width)buffer.put(source.slice(start,(int)length));
        else for(int row=0;row<height;row++)buffer.put(source.slice(start+row*stride,width));
        count++;
    }

    public void updateFloatTextureRegion(long handle, int x, int y, int width, int height, byte[] pixels) {
        updateTextureRegion(handle,x,y,width,height,pixels,true);
    }

    private void updateTextureRegion(long handle, int x, int y, int width, int height, byte[] pixels, boolean floating) {
        checkOwner(); checkHandle(handle);
        if (x < 0 || y < 0 || x > 8192 || y > 8192 || width < 1 || height < 1 || width > 8192 || height > 8192 ||
                pixels == null || (long) width * height * (floating ? 16 : 4) != pixels.length || pixels.length > MAX_BYTES - 56) {
            throw new IllegalArgumentException("Invalid texture region dimensions or bytes");
        }
        if (floating) {
            var values = ByteBuffer.wrap(pixels).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
            while (values.hasRemaining()) if (!Float.isFinite(values.get())) throw new IllegalArgumentException("Non-finite texture value");
        }
        reserve(32 + pixels.length);
        buffer.putInt(floating ? 19 : 10).putInt(24 + pixels.length).putLong(handle).putInt(x).putInt(y).putInt(width).putInt(height).put(pixels);
        count++;
    }

    private void textureImage(int opcode, long handle, int width, int height, byte[] pixels) {
        checkOwner(); checkHandle(handle);
        if (width < 1 || height < 1 || width > 8192 || height > 8192 || pixels == null ||
                (long) width * height * 4 != pixels.length || pixels.length > MAX_BYTES - 48) {
            throw new IllegalArgumentException("Invalid texture image dimensions or byte length");
        }
        reserve(24 + pixels.length);
        buffer.putInt(opcode).putInt(16 + pixels.length).putLong(handle).putInt(width).putInt(height).put(pixels);
        count++;
    }

    public void disposeTexture(long handle) {
        checkHandle(handle); reserve(16);
        buffer.putInt(7).putInt(8).putLong(handle);
        count++;
        // The active host state may retain this texture; force a fresh state.
        activeState = null;
        activeStateHandles = null;
    }

    private static long geometryHash(float[] coordinates, byte[] colors, int vertices) {
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < vertices * 7; ++i) {
            float value = coordinates[i];
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite Prism vertex attribute");
            if (i % 7 == 2 && value != 0) throw new IllegalArgumentException("3D Prism projection is not implemented");
            if (CACHE_GEOMETRY) hash = (hash ^ Float.floatToRawIntBits(value)) * 0x100000001b3L;
        }
        if (!CACHE_GEOMETRY) return 0;
        for (int i = 0; i < vertices * 4; ++i)
            hash = (hash ^ colors[i]) * 0x100000001b3L;
        return (hash ^ ((long) vertices << 32)) * 0x100000001b3L;
    }

    /** Emits GEOMETRY_FREE for entries idle well past any queued frame. */
    private void evictIdleGeometry(long requiredBytes) {
        if (geometryCache.size() < GEOMETRY_CACHE_MAX && geometryCacheBytes + requiredBytes <= GEOMETRY_CACHE_BYTES) return;
        // Scan at most once per submission. If no old entries are eligible,
        // admission falls back to inline geometry instead of growing the cache.
        if (lastEvictSubmission == submissionClock) return;
        lastEvictSubmission = submissionClock;
        long idleFloor = submissionClock - GEOMETRY_IDLE_SUBMISSIONS;
        var iterator = geometryCache.entrySet().iterator(); // access order: LRU head first
        while (iterator.hasNext() && (geometryCache.size() >= GEOMETRY_CACHE_MAX || geometryCacheBytes + requiredBytes > GEOMETRY_CACHE_BYTES)) {
            GeometryEntry entry = iterator.next().getValue();
            if (entry.lastUsed >= idleFloor) break; // access order: rest is newer
            if (count == 65536 || remainingBatchBytes() < 16) submit();
            reserve(16);
            buffer.putInt(30).putInt(8).putLong(entry.handle);
            count++;
            geometryCacheBytes -= entry.bytes();
            iterator.remove();
        }
    }

    private void drawQuads(long texture, float[] coordinates, byte[] colors, int vertices,
                           double mxx, double mxy, double mxt, double myx, double myy, double myt, float alpha) {
        checkOwner();
        if (vertices <= 0 || vertices > 65536 || vertices % 4 != 0 ||
                coordinates == null || colors == null || coordinates.length < vertices * 7 || colors.length < vertices * 4) {
            throw new IllegalArgumentException("Invalid Prism quad arrays");
        }
        if (!Float.isFinite(alpha) || alpha < 0 || alpha > 1) {
            throw new IllegalArgumentException("Invalid Prism draw modulation");
        }
        if (!Double.isFinite(mxx) || !Double.isFinite(mxy) || !Double.isFinite(mxt) ||
                !Double.isFinite(myx) || !Double.isFinite(myy) || !Double.isFinite(myt)) {
            throw new IllegalArgumentException("Non-finite Prism draw transform");
        }
        float fmxx = (float) mxx, fmxy = (float) mxy, fmxt = (float) mxt;
        float fmyx = (float) myx, fmyy = (float) myy, fmyt = (float) myt;
        if (!Float.isFinite(fmxx) || !Float.isFinite(fmxy) || !Float.isFinite(fmxt) ||
                !Float.isFinite(fmyx) || !Float.isFinite(fmyy) || !Float.isFinite(fmyt)) {
            throw new IllegalArgumentException("Prism draw transform exceeds float range");
        }
        // Validate before modifying the pending batch or geometry cache: a bad command cannot
        // leave a truncated record that poisons otherwise valid geometry.
        // Validate and hash in one traversal; disabled caches only validate.
        long content = geometryHash(coordinates, colors, vertices);
        long handle = 0;
        if (CACHE_GEOMETRY) {
            GeometryEntry entry = geometryCache.get(content);
            // get() updates access order even on a hash collision; keep age
            // order consistent so eviction can stop at the first recent entry.
            if (entry != null) entry.lastUsed = submissionClock;
            if (entry != null && entry.matches(coordinates, colors, vertices)) {
                // QUADS_REF: redraw registered geometry without its vertices.
                if (count == 65536 || remainingBatchBytes() < 52) submit();
                reserve(52);
                buffer.putInt(29).putInt(44).putLong(entry.handle).putLong(texture)
                      .putFloat(fmxx).putFloat(fmxy).putFloat(fmxt)
                      .putFloat(fmyx).putFloat(fmyy).putFloat(fmyt)
                      .putFloat(alpha);
                count++;
                return;
            }
            long bytes = (long)vertices * 32;
            evictIdleGeometry(bytes);
            // Never evict recently referenced handles just to meet a budget.
            // Full caches and hash collisions use ordinary inline geometry.
            if (!geometryCache.containsKey(content) && geometryCache.size() < GEOMETRY_CACHE_MAX
                    && geometryCacheBytes + bytes <= GEOMETRY_CACHE_BYTES) {
                handle = NEXT_GEOMETRY.getAndIncrement();
            }
        }
        int prefix = texture == 0 ? 0 : 8;
        int commandBytes = 12 + prefix + 8 + vertices * 32 + 28;
        if (count == 65536 || remainingBatchBytes() < commandBytes) submit();
        reserve(commandBytes);
        buffer.putInt(texture == 0 ? 4 : 8).putInt(12 + prefix + vertices * 32 + 28);
        if (texture != 0) buffer.putLong(texture);
        buffer.putLong(handle).putInt(vertices);
        for (int vertex = 0; vertex < vertices; ++vertex) {
            for (int attribute = 0; attribute < 7; ++attribute) buffer.putFloat(coordinates[vertex * 7 + attribute]);
            buffer.put(colors, vertex * 4, 4);
        }
        buffer.putFloat(fmxx).putFloat(fmxy).putFloat(fmxt)
              .putFloat(fmyx).putFloat(fmyy).putFloat(fmyt)
              .putFloat(alpha);
        count++;
        if (handle != 0) {
            GeometryEntry entry = new GeometryEntry(handle, submissionClock, coordinates, colors, vertices);
            geometryCache.put(content, entry);
            geometryCacheBytes += entry.bytes();
        }
    }

    /** Enqueues the complete batch; returns its sequence, not a GPU completion fence. */
    public long submit() {
        checkOwner();
        if (count == 0) return 0;
        // Multiple contexts may encode independently, but native admission
        // must preserve one increasing sequence even during backpressure.
        synchronized (SUBMISSION_LOCK) {
            if (nextSequence == Long.MAX_VALUE) throw new IllegalStateException("Godot submission sequence exhausted");
            long sequence = nextSequence++;
            int length = buffer.position();
            buffer.putInt(0, 0x31465847).putInt(4, 1).putInt(8, length).putInt(12, count).putLong(16, sequence);
            long deadline = System.nanoTime() + 10_000_000_000L;
            long tick = 0;
            try {
                while (!GodotBridge.submit(buffer, length)) {
                    if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Godot submission interrupted");
                    if (System.nanoTime() >= deadline) throw new IllegalStateException("Godot submission timed out under backpressure");
                    tick = GodotBridge.awaitTick(tick, 1000);
                    if (tick < 0) throw new GodotHostStoppedException("Godot stopped during submission");
                }
            } catch (GodotHostStoppedException cancellation) {
                hostStopped = true;
                throw cancellation;
            } catch (RuntimeException | Error failure) {
                failed = true;
                throw failure;
            }
            // JNI has taken an owned copy; reuse storage without waiting on GPU.
            buffer.clear().position(24);
            count = 0;
            ++submissionClock;
            lastSubmittedSequence = sequence;
            return sequence;
        }
    }
}
