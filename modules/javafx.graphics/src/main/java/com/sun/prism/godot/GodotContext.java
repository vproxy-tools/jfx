/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.prism.godot;

import com.sun.glass.ui.Screen;
import com.sun.javafx.PlatformUtil;
import com.sun.javafx.tk.godot.GodotBridge;
import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.sg.prism.NGCamera;
import com.sun.javafx.sg.prism.NGPerspectiveCamera;
import com.sun.javafx.tk.godot.GodotRenderCommands;
import com.sun.javafx.tk.godot.GodotHostStoppedException;
import com.sun.prism.CompositeMode;
import com.sun.prism.RTTexture;
import com.sun.prism.RenderTarget;
import com.sun.prism.Texture;
import com.sun.prism.impl.ps.BaseShaderContext;
import com.sun.prism.ps.Shader;
import com.sun.prism.ps.ShaderFactory;

/** Prism vertex batching backed by the Godot command stream. */
public final class GodotContext extends BaseShaderContext {
    private static final boolean BATCH_PASSES = !"0".equals(System.getenv("GODOT_JFX_BATCH_PASSES"));
    private final Thread owner = Thread.currentThread();
    private final GodotRenderCommands commands = new GodotRenderCommands();
    private final GodotTexture[] textures = new GodotTexture[16];
	private final long[] textureHandles = new long[16];
    private final State targetState = new State();
    private final java.util.Map<Long, GodotRTTexture> windowTargets = new java.util.HashMap<>();
    private record WindowState(com.sun.javafx.tk.godot.GodotWindowLifecycle lifecycle, int width, int height) {}
    private final java.util.Map<Long, WindowState> windowStates = new java.util.HashMap<>();
    private RenderTarget target;
    private boolean needsTargetSelection;
    private GodotShader shader;
    private float[] localVertices = new float[0];
    private CompositeMode composite = CompositeMode.SRC_OVER;
	private Rectangle clip;
    private BaseTransform transform = BaseTransform.IDENTITY_TRANSFORM;

    final boolean preferSmallLcdCopies;

    public GodotContext(Screen screen, ShaderFactory factory) {
        super(screen, factory, 256);
        preferSmallLcdCopies = PlatformUtil.isLinux() && System.getenv("GODOT_JFX_LCD_REGION_COPY") == null
                && GodotBridge.prefersSmallLcdCopies();
    }

    // Mirrors the gate in GodotGraphics/GodotShader: the LCD second pass
    // samples the renderer backbuffer instead of the LCDBuffer.
    @Override public boolean lcdScreenSample() { return !"0".equals(System.getenv("GODOT_JFX_LCD_SCREEN")); }

    // Uniform modulation (node opacity) is carried as a draw parameter rather
    // than baked into vertex colors, so opacity animations leave batch content
    // byte-identical and geometry-deduplicable. One flush batch always carries
    // exactly one value: a change flushes first.
    private static final boolean DRAW_MODULATE = !"0".equals(System.getenv("GODOT_JFX_DRAW_MODULATE"));
    private float batchModulation = 1.0f;

    @Override protected float vertexColorModulation(float extraAlpha) {
        if (!DRAW_MODULATE) return extraAlpha;
        if (extraAlpha != batchModulation) {
            flushVertexBuffer();
            batchModulation = extraAlpha;
        }
        return 1.0f;
    }

    private void checkThread() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Godot context belongs to another render thread");
    }

    public GodotRenderCommands getCommands() { checkThread(); return commands; }

    /** Called by the resource factory's Shader selection callback. */
    public void selectShader(GodotShader selected) {
        checkThread();
        // BaseShaderContext flushes before selecting a different shader.
        shader = selected;
    }

    public long submit() { checkThread(); flushVertexBuffer(); return commands.submit(); }

    int[] readPixels(GodotRTTexture source, int x, int y, int width, int height) {
        checkThread();
        flushVertexBuffer();
        long handle = source.getNativeHandle();
        long sequence;
        if (target != null && !needsTargetSelection) {
            sequence = commands.endTargetPass();
        } else {
            commands.submit();
            sequence = commands.getLastSubmittedSequence();
        }
        releaseRenderTarget();
        target = null;
        needsTargetSelection = true;
        return com.sun.javafx.tk.godot.GodotBridge.readPixels(handle, sequence, x, y, width, height);
    }

    void beginLCDSample() {
        checkThread();
        flushVertexBuffer();
        // A new text node must see all preceding draws, including overlapping
        // text. Keep commands batched; only the native pass boundary changes.
        if (BATCH_PASSES) commands.appendEndTargetPass();
        else commands.endTargetPass();
        commands.selectTarget(target instanceof GodotRTTexture rt ? rt.getNativeHandle() : 0);
    }

    void finishPassForSceneResize() {
        checkThread(); flushVertexBuffer();
        commands.endTargetPass();
        // Continue drawing into the same target after the host's post-draw fence.
        commands.selectTarget(target instanceof GodotRTTexture rt ? rt.getNativeHandle() : 0);
    }

    void beginFrame() {
        checkThread(); flushVertexBuffer(); releaseRenderTarget(); target = null;
        commands.beginPresent();
    }

    void releaseHostTarget(RenderTarget released) {
        checkThread();
        if (target == released) {
            try { flushVertexBuffer(); }
            catch (GodotHostStoppedException cancelled) {
                // A cancelled flush leaves VertexBuffer's index unchanged.
                // Its vertices cannot be replayed after host admission ends.
                getVertexBuffer().rewind();
            }
            releaseRenderTarget();
            target = null;
        }
    }

    void publishWindow(long window, com.sun.javafx.tk.godot.GodotWindowLifecycle lifecycle, GodotRTTexture buffer, int outputWidth, int outputHeight) {
        checkThread(); flushVertexBuffer();
        lifecycle.setRenderUpdate(() -> syncWindowState(window));
        var state = lifecycle.snapshot();
        // A stale prepared frame must never visibly restore a hidden/closed
        // window. Zero opacity also safely completes its pending GPU pass.
        float opacity = state.visible() && !state.closed() ? state.opacity() : 0;
        if (lifecycle.isPrimary()) commands.presentWindow(window, state.x(), state.y(), outputWidth, outputHeight, state.z(), opacity);
        else commands.presentNativeWindow(window, outputWidth, outputHeight, opacity);
        // Presentation closes the native target pass. A snapshot can be the
        // next render job without beginFrame(), so invalidate Prism's target
        // cache here rather than trying to end the published pass a second time.
        releaseRenderTarget();
        target = null;
        needsTargetSelection = true;
        GodotRTTexture previous = windowTargets.put(window, buffer);
        windowStates.put(window, new WindowState(lifecycle, outputWidth, outputHeight));
        // Admission has atomically replaced the window's target. The native
        // consumer fences composition before consuming this disposal batch.
        if (previous != null && previous != buffer) { previous.dispose(); commands.submit(); }
        if (state.closed()) releaseWindow(window);
    }

    private void syncWindowState(long window) {
        checkThread();
        WindowState owned = windowStates.get(window);
        if (owned == null) return;
        var state = owned.lifecycle().snapshot();
        try {
            if (state.closed()) releaseWindow(window);
            else commands.configureWindow(window, state.x(), state.y(), owned.width(), owned.height(), state.z(), state.opacity(), state.visible());
        } catch (GodotHostStoppedException cancelled) {
            releaseWindow(window);
        }
    }

    void releaseWindow(long window) {
        checkThread();
        GodotRTTexture previous = windowTargets.remove(window);
        WindowState state = windowStates.remove(window);
        if (state != null) state.lifecycle().clearRenderUpdate();
        if (previous != null) {
            releaseHostTarget(previous);
            try { commands.removeWindow(window); }
            catch (GodotHostStoppedException cancelled) {
                // Native windows are host-owned at cancellation. Still release
                // the retained Java target and its pool charge below.
            }
            previous.dispose();
            try { commands.submit(); }
            catch (GodotHostStoppedException cancelled) { /* No remaining native admission. */ }
        }
    }

    boolean retainsWindowTarget(long window, GodotRTTexture buffer) {
        checkThread(); return windowTargets.get(window) == buffer;
    }

    @Override protected State updateRenderTarget(RenderTarget next, NGCamera camera, boolean depthTest) {
        checkThread();
        if (depthTest || camera instanceof NGPerspectiveCamera) {
            throw new UnsupportedOperationException("Godot depth and perspective targets are not implemented");
        }
        if (next instanceof RTTexture && (!(next instanceof GodotRTTexture) || !getResourceFactory().isCompatibleTexture((Texture)next))) throw new IllegalArgumentException("Foreign render target");
        if (next != target || needsTargetSelection) {
            if (target != null && !needsTargetSelection) {
                if (BATCH_PASSES) commands.appendEndTargetPass();
                else commands.endTargetPass();
            }
            if (next instanceof GodotRTTexture rt) commands.selectTarget(rt.getNativeHandle());
            else if (target != null || needsTargetSelection) commands.selectTarget(0);
        }
        needsTargetSelection = false;
        target = next;
        if (!(next instanceof RTTexture)) commands.resize(next.getPhysicalWidth(), next.getPhysicalHeight());
        return targetState;
    }

    @Override protected void updateTexture(int unit, Texture texture) {
        checkThread();
        if (unit < 0 || unit >= textures.length || (texture != null && !getResourceFactory().isCompatibleTexture(texture))) {
            throw new IllegalArgumentException("Invalid Godot texture unit/resource");
        }
        textures[unit] = (GodotTexture) texture;
        // Prism may unlock a cached texture after recording vertices but before
        // the batch flush. Capture its handle while setTexture holds the lock;
        // the resource disposer flushes vertices before invalidating that handle.
        textureHandles[unit] = texture == null ? 0 : ((GodotTexture)texture).getNativeHandle();
    }

    @Override protected void updateShaderTransform(Shader selected, BaseTransform next) {
        checkThread();
        if (!next.is2D()) throw new UnsupportedOperationException("Godot 3D vertex transforms are not implemented");
        transform = next.copy();
    }

    @Override protected void updateWorldTransform(BaseTransform next) {
        if (next != null && !next.isIdentity()) throw new UnsupportedOperationException("Godot world transforms are not implemented");
    }

    @Override protected void updateClipRect(Rectangle clip) {
        checkThread();
        this.clip = clip == null ? null : new Rectangle(clip);
    }

    @Override protected void updateCompositeMode(CompositeMode mode) {
        checkThread();
        composite = java.util.Objects.requireNonNull(mode);
    }

    @Override protected void renderQuads(float[] coordinates, byte[] colors, int vertices) {
        checkThread();
        if (shader == null) throw new IllegalStateException("Prism draw has no Godot shader");
        long[] handles = new long[textures.length];
        var snapshot = GodotComposite.apply(GodotSampler.bind(shader.snapshot(), textures), composite);
        for (int unit : snapshot.samplerUnits().values()) {
            if (textures[unit] == null) throw new IllegalStateException("Prism sampler has no texture: " + unit);
            handles[unit] = textureHandles[unit];
        }
        // Vertices stay in local space; the host applies this transform at
        // draw time. Unchanged batches under a moving parent then arrive
        // byte-identical and the host reuses its cached geometry instead of
        // rebuilding it every frame.
        // State remains active across batches. The encoder flushes as needed
        // before the state; geometry can follow in the next batch if necessary.
        commands.shaderState(snapshot, handles);
        if (clip == null) commands.clip(false, 0, 0, 0, 0);
        else commands.clip(true, clip.x, clip.y, Math.max(0, clip.width), Math.max(0, clip.height));
        double tx = transform.getMxt(), ty = transform.getMyt();
        // Prism batches glyphs and simple translated draws in device coordinates.
        // Stock shaders only use UVs and colors as local attributes. Carry an exact
        // integer translation in the draw command so these meshes survive scrolling.
        if (shader.isStockShader() && transform.isIdentity()) {
            float originX = (float)Math.floor(coordinates[0]);
            float originY = (float)Math.floor(coordinates[1]);
            if ((originX != 0 || originY != 0) && makeLocalVertices(coordinates, vertices, originX, originY)) {
                coordinates = localVertices;
                tx = originX;
                ty = originY;
            }
        }
        commands.drawQuads(coordinates, colors, vertices,
                transform.getMxx(), transform.getMxy(), tx,
                transform.getMyx(), transform.getMyy(), ty,
                DRAW_MODULATE ? batchModulation : 1.0f);
    }

    private boolean makeLocalVertices(float[] coordinates, int vertices, float originX, float originY) {
        int length = vertices * 7;
        if (localVertices.length < length) localVertices = new float[length];
        System.arraycopy(coordinates, 0, localVertices, 0, length);
        for (int i = 0; i < length; i += 7) {
            float x = coordinates[i] - originX;
            float y = coordinates[i + 1] - originY;
            if ((double)x + originX != coordinates[i] || (double)y + originY != coordinates[i + 1]) {
                return false;
            }
            localVertices[i] = x;
            localVertices[i + 1] = y;
        }
        return true;
    }

    @Override public void blit(RTTexture source, RTTexture destination, int sx0, int sy0, int sx1, int sy1,
                               int dx0, int dy0, int dx1, int dy1) {
        checkThread();
        if (!(source instanceof GodotRTTexture src) || !getResourceFactory().isCompatibleTexture(src))
            throw new IllegalArgumentException("Foreign blit source");
        RenderTarget dst = destination == null ? target : destination;
        if (dst == null || (destination != null && (!(destination instanceof GodotRTTexture) ||
                !getResourceFactory().isCompatibleTexture(destination))))
            throw new IllegalArgumentException("Invalid blit destination");
        long handle = src.getNativeHandle();
        if (dst instanceof GodotRTTexture rt && rt.getNativeHandle() == handle)
            throw new IllegalArgumentException("Overlapping render-target storage cannot be blitted");
        flushVertexBuffer();
        // BaseShaderGraphics has selected the destination. Blits ignore drawing
        // transform, clip, opacity, composite and external shader state, just as
        // the native framebuffer copy does. Subsequent Prism draws re-emit their
        // state in renderQuads, so the context's cached state is left intact.
        if (dst != target) throw new IllegalStateException("Blit destination is not selected");
        if (sx0 == sx1 || sy0 == sy1 || dx0 == dx1 || dy0 == dy1) return;
        var state = GodotSampler.bind(new GodotShader.Snapshot(
                "shader_type canvas_item; render_mode unshaded, blend_disabled;\n" +
                "uniform sampler2D blit_source;\nvoid fragment() { COLOR = texture(blit_source, UV); }",
                java.util.Map.of("blit_source", 0), java.util.Map.of()), new GodotTexture[]{src});
        // Framebuffer blits use linear filtering and clamp at the physical edge,
        // independently of the source texture's draw-time sampler preferences.
        var constants = new java.util.LinkedHashMap<>(state.constants());
        constants.put("godot_sample_blit_source_linear", new GodotShader.Constant(true,1,false,new int[]{1}));
        constants.put("godot_sample_blit_source_wrap", new GodotShader.Constant(true,1,false,new int[]{0}));
        commands.shaderState(new GodotShader.Snapshot(state.source(), state.samplerUnits(), constants), new long[]{handle});
        commands.clip(false, 0, 0, 0, 0);
        float u0 = (float)sx0 / src.getPhysicalWidth(), u1 = (float)sx1 / src.getPhysicalWidth();
        float v0 = (float)sy0 / src.getPhysicalHeight(), v1 = (float)sy1 / src.getPhysicalHeight();
        byte[] white = new byte[16];
        java.util.Arrays.fill(white, (byte)255);
        if (commands.remainingBatchBytes() < 164) commands.submit();
        commands.drawQuads(new float[]{dx0,dy0,0,u0,v0,0,0, dx0,dy1,0,u0,v1,0,0,
                dx1,dy0,0,u1,v0,0,0, dx1,dy1,0,u1,v1,0,0}, white, 4, 1, 0, 0, 0, 1, 0);
    }
}
