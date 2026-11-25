/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.prism.godot;

import com.sun.javafx.PlatformUtil;
import com.sun.javafx.geom.BaseBounds;
import com.sun.javafx.geom.RectBounds;
import com.sun.prism.RenderTarget;
import com.sun.prism.impl.ps.BaseShaderGraphics;
import com.sun.prism.paint.Color;
import java.util.Map;

/** Uses upstream Prism geometry, paint shaders and vertex batching. */
public final class GodotGraphics extends BaseShaderGraphics {
    private static final boolean REGION_LCD_COPY = PlatformUtil.isLinux()
            && "1".equals(System.getenv("GODOT_JFX_LCD_REGION_COPY"));
    private final GodotContext godot;
    public GodotGraphics(GodotContext context, RenderTarget target) {
        super(context, target);
        godot = context;
        // The Godot second-pass LCD shader samples the destination through the
        // renderer's backbuffer, so the per-text-node LCDBuffer copy pass is
        // skipped (see GodotShader.loadPrism for the shader rewrite).
        lcdScreenSample = !"0".equals(System.getenv("GODOT_JFX_LCD_SCREEN"));
    }
    private final RectBounds deviceBounds = new RectBounds();

    /** Cull against this pass's viewport, including tighter clips set inside a dirty region. */
    public boolean isOutsideClip(BaseBounds boundsInParent) {
        var transform = getTransformNoClone();
        if (!boundsInParent.is2D() || boundsInParent.isEmpty() || !transform.is2D()
                || getRenderRoot() != null || getCameraNoClone() instanceof com.sun.javafx.sg.prism.NGPerspectiveCamera) {
            return false;
        }
        var bounds = transform.transform(boundsInParent, deviceBounds);
        var clip = getFinalClipNoClone();
        // Leave a two-device-pixel fringe for glyph snapping and antialiased edges.
        return bounds.getMaxX() < clip.getMinX() - 2 || bounds.getMinX() > clip.getMaxX() + 2
                || bounds.getMaxY() < clip.getMinY() - 2 || bounds.getMinY() > clip.getMaxY() + 2;
    }

    @Override protected void prepareLCDScreenSample() {
        godot.setRenderTarget(this);
        boolean smallUnclipped = godot.preferSmallLcdCopies && getClipRectNoClone() == null;
        if ((REGION_LCD_COPY || smallUnclipped) && getRenderTarget() instanceof GodotRTTexture && nodeBounds != null) {
            var bounds = new RectBounds();
            getTransformNoClone().transform(nodeBounds, bounds);
            int x = Math.max(0, (int)Math.floor(bounds.getMinX() - 1));
            int y = Math.max(0, (int)Math.floor(bounds.getMinY() - 1));
            int right = Math.min(getRenderTarget().getPhysicalWidth(), (int)Math.ceil(bounds.getMaxX() + 1));
            int bottom = Math.min(getRenderTarget().getPhysicalHeight(), (int)Math.ceil(bounds.getMaxY() + 1));
            // Small isolated text runs benefit from a region copy on Mesa D3D12.
            // Larger regions and clipped layers use the existing pass boundary.
            long area = (long) (right - x) * (bottom - y);
            long targetArea = (long) getRenderTarget().getPhysicalWidth() * getRenderTarget().getPhysicalHeight();
            if (right > x && bottom > y && (REGION_LCD_COPY || area <= targetArea / 32)) {
                godot.flushVertexBuffer();
                godot.getCommands().copyLCDBackground(x, y, right - x, bottom - y);
                return;
            }
        }
        godot.beginLCDSample();
    }
    @Override public void sync() { godot.submit(); }
    @Override public void clearQuad(float x1, float y1, float x2, float y2) {
        godot.flushVertexBuffer();
        clearGeometry(Color.TRANSPARENT, x1, y1, x2, y2, true);
        getRenderTarget().setOpaque(false);
    }
    @Override public void clear(Color color) {
        godot.flushVertexBuffer();
        var commands = godot.getCommands();
        if (getClipRectNoClone() == null) {
            commands.clear();
            getRenderTarget().setOpaque(color.isOpaque());
            if (color.getAlpha() == 0) return;
        } else if (!color.isOpaque()) getRenderTarget().setOpaque(false);
        clearGeometry(color, 0, 0, getRenderTarget().getPhysicalWidth(), getRenderTarget().getPhysicalHeight(), false);
    }
    private void clearGeometry(Color color, float x1, float y1, float x2, float y2, boolean transformed) {
        if (!Float.isFinite(x1) || !Float.isFinite(y1) || !Float.isFinite(x2) || !Float.isFinite(y2)) throw new IllegalArgumentException("Non-finite clear geometry");
        var commands = godot.getCommands();
        float[] coordinates = {x1,y1,0,0,0,0,0, x1,y2,0,0,0,0,0, x2,y1,0,0,0,0,0, x2,y2,0,0,0,0,0};
        double mxx = 1, mxy = 0, mxt = 0, myx = 0, myy = 1, myt = 0;
        if (transformed) {
            var transform = getTransformNoClone();
            if (!transform.is2D()) throw new UnsupportedOperationException("Godot 3D clear geometry is not implemented");
            mxx = transform.getMxx(); mxy = transform.getMxy(); mxt = transform.getMxt();
            myx = transform.getMyx(); myy = transform.getMyy(); myt = transform.getMyt();
        }
        byte[] colors = new byte[16];
        for (int i = 0; i < 4; ++i) {
            colors[i*4] = (byte)Math.round(color.getRed()*color.getAlpha()*255);
            colors[i*4+1] = (byte)Math.round(color.getGreen()*color.getAlpha()*255);
            colors[i*4+2] = (byte)Math.round(color.getBlue()*color.getAlpha()*255);
            colors[i*4+3] = (byte)Math.round(color.getAlpha()*255);
        }
        commands.shaderState(new GodotShader.Snapshot("shader_type canvas_item; render_mode unshaded, blend_disabled;", Map.of(), Map.of()));
        var clip = getClipRectNoClone();
        if (clip == null) commands.clip(false,0,0,0,0);
        else commands.clip(true,clip.x,clip.y,Math.max(0,clip.width),Math.max(0,clip.height));
        commands.drawQuads(coordinates, colors, 4, mxx, mxy, mxt, myx, myy, myt);
    }
    @Override public void setState3D(boolean value) {
        if (value) throw new UnsupportedOperationException("Godot 3D graphics is not implemented");
        super.setState3D(false);
    }
    @Override public void setup3DRendering() { throw new UnsupportedOperationException("Godot 3D graphics is not implemented"); }
}
