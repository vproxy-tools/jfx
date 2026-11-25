/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.prism.godot;

import com.sun.glass.ui.Screen;
import com.sun.prism.*;
import com.sun.prism.impl.TextureResourcePool;
import com.sun.prism.impl.ps.BaseShaderFactory;
import com.sun.prism.ps.Shader;
import com.sun.prism.ps.ShaderFactory;
import java.io.InputStream;
import java.util.Map;

/** Host-device resources. Offscreen and swap-chain support is implemented separately. */
public final class GodotResourceFactory extends BaseShaderFactory {
    private final GodotContext context;
    private final GodotTexturePool pool;
    public GodotResourceFactory(Screen screen) {
        context = new GodotContext(screen, this);
        pool = new GodotTexturePool(context.getCommands(), context::flushVertexBuffer);
    }
    public GodotContext getContext() { return context; }
    public GodotSceneTexture createSceneTexture(String path, int width, int height) {
        return GodotSceneTexture.create(pool,path,width,height);
    }
    public GodotSceneTexture resizeSceneTexture(GodotSceneTexture texture, int width, int height) {
        if (texture == null || !texture.belongsTo(pool) || texture.getLockCount() != 1 || width < 1 || height < 1 || width > 8192 || height > 8192) throw new IllegalArgumentException("Invalid scene resize/resource locks");
        texture.getNativeHandle();
        if (texture.getPhysicalWidth() == width && texture.getPhysicalHeight() == height) return texture;
        context.finishPassForSceneResize();
        return texture.resize(pool,width,height);
    }
    @Override public TextureResourcePool getTextureResourcePool() { return pool; }
    @Override public Texture createTexture(PixelFormat format, Texture.Usage usage, Texture.WrapMode wrap, int w, int h) {
        return GodotTexture.create(pool, format, wrap, w, h);
    }
    @Override public Texture createTexture(PixelFormat format, Texture.Usage usage, Texture.WrapMode wrap, int w, int h, boolean mipmap) {
        if (mipmap) throw new UnsupportedOperationException("Godot mipmap textures are not implemented");
        return createTexture(format, usage, wrap, w, h);
    }
    @Override public Texture createTexture(MediaFrame frame) {
        if (frame.getPixelFormat() == PixelFormat.MULTI_YCbCr_420) {
            frame.holdFrame();
            MultiTexture video = null;
            try {
                int width = frame.getEncodedWidth(), height = frame.getEncodedHeight();
                int planes = frame.planeCount();
                if (width < 2 || height < 2 || width > getMaximumTextureSize() || height > getMaximumTextureSize()
                        || frame.getWidth() < 1 || frame.getHeight() < 1
                        || frame.getWidth() > width || frame.getHeight() > height || planes < 3 || planes > 4)
                    throw new IllegalArgumentException("Invalid YCbCr video frame dimensions or plane count");
                video = new MultiTexture(frame.getPixelFormat(), Texture.WrapMode.CLAMP_TO_EDGE, frame.getWidth(), frame.getHeight());
                for (int plane = 0; plane < planes; plane++) {
                    boolean chroma = plane == 1 || plane == 2;
                    Texture texture = GodotTexture.createMediaPlane(pool, chroma ? width / 2 : width, chroma ? height / 2 : height);
                    if (texture == null) { video.dispose(); return null; }
                    video.setTexture(texture, plane);
                }
                // PrismMediaFrameHandler uploads the frame after allocation.
                return video;
            } catch (RuntimeException | Error failure) {
                if (video != null) video.dispose();
                throw failure;
            } finally { frame.releaseFrame(); }
        }
        frame.holdFrame();
        try {
            // Packed formats without a Godot texture mapping (e.g. AVFoundation
            // BYTE_APPLE_422) fall back to an ARGB conversion, like J2D/SW.
            if (!GodotTexture.supports(frame.getPixelFormat())) {
                MediaFrame converted = frame.convertToFormat(PixelFormat.INT_ARGB_PRE);
                if (converted == null) return null;
                frame.releaseFrame();
                frame = converted;
                frame.holdFrame();
            }
            int width = frame.getWidth(), height = frame.getHeight();
            if (frame.planeCount() != 1 || width < 1 || height < 1
                    || width > frame.getEncodedWidth() || height > frame.getEncodedHeight())
                throw new IllegalArgumentException("Invalid packed video frame dimensions or plane count");
            // Packed uploads already honor the decoder's row stride and copy
            // only visible pixels. Allocate that extent so scaled edge samples
            // clamp to picture pixels rather than transparent decoder padding.
            // The media handler performs the upload after allocation.
            return createTexture(frame.getPixelFormat(), Texture.Usage.DYNAMIC, Texture.WrapMode.CLAMP_TO_EDGE, width, height);
        } finally { frame.releaseFrame(); }
    }
    @Override public boolean isFormatSupported(PixelFormat format) { return format == PixelFormat.MULTI_YCbCr_420 || GodotTexture.supports(format); }
    @Override public int getMaximumTextureSize() { return 8192; }
    @Override public boolean isCompatibleTexture(Texture texture) {
        if (texture instanceof MultiTexture video) {
            if (video.textureCount() < 3 || video.textureCount() > 4) return false;
            for (Texture plane : video.getTextures())
                if (!(plane instanceof GodotTexture godot) || !godot.belongsTo(pool)) return false;
            return video.getPixelFormat() == PixelFormat.MULTI_YCbCr_420;
        }
        return texture instanceof GodotTexture godot && godot.belongsTo(pool);
    }
    @Override public int getRTTWidth(int width, Texture.WrapMode wrap) { return width; }
    @Override public int getRTTHeight(int height, Texture.WrapMode wrap) { return height; }
    @Override public RTTexture createRTTexture(int w, int h, Texture.WrapMode wrap) { return createRTTexture(w,h,wrap,false); }
    @Override public RTTexture createRTTexture(int w, int h, Texture.WrapMode wrap, boolean msaa) {
        if (msaa) throw new UnsupportedOperationException("Godot multisample targets are not implemented");
        return GodotRTTexture.create(pool, context, wrap, w, h);
    }
    @Override public Presentable createPresentable(PresentableState state) {
        return new GodotPresentable(context, state);
    }
    public GodotShader createShader(GodotShader.Program program) {
        return new GodotShader(program, context::selectShader);
    }

    @Override public Shader createShader(String name, InputStream stream, Map<String,Integer> samplers,
            Map<String,Integer> params, int maxTexCoord, boolean pixcoord, boolean vertexColor) {
        if (stream != null) {
            try (stream) {
                byte[] bytes = stream.readNBytes(262145);
                if (bytes.length > 262144) throw new IllegalArgumentException("Godot shader source exceeds 256 KiB");
                String source = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                if (!source.contains("shader_type canvas_item")) throw new IllegalArgumentException("Expected Godot CanvasItem shader source");
                return new GodotShader(source, samplers, params, context::selectShader);
            } catch (java.io.IOException failure) { throw new IllegalStateException("Cannot read Godot shader source", failure); }
        }
        return createShader(name, samplers, params, maxTexCoord, pixcoord, vertexColor);
    }
    @Override public Shader createShader(String name, Map<String,Integer> samplers, Map<String,Integer> params,
            int maxTexCoord, boolean pixcoord, boolean vertexColor) {
        return GodotShader.loadPrism(name, samplers, params, context::selectShader);
    }
    @Override public Shader createStockShader(String name) {
        if (!name.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Invalid shader name");
        try {
            return (Shader) Class.forName("com.sun.prism.shader." + name + "_Loader")
                    .getMethod("loadShader", ShaderFactory.class, String.class, InputStream.class)
                    .invoke(null, this, name, null);
        } catch (ReflectiveOperationException failure) { throw new IllegalStateException("Cannot load Godot stock shader " + name, failure); }
    }
    @Override public void dispose() { context.dispose(); }
    @Override public PhongMaterial createPhongMaterial() { throw new UnsupportedOperationException("Godot 3D material is not implemented"); }
    @Override public Mesh createMesh() { throw new UnsupportedOperationException("Godot 3D mesh is not implemented"); }
    @Override public MeshView createMeshView(Mesh mesh) { throw new UnsupportedOperationException("Godot 3D mesh view is not implemented"); }
}
