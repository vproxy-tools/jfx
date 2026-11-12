/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.prism.godot;

import com.sun.glass.ui.Screen;
import com.sun.javafx.geom.Rectangle;
import com.sun.prism.*;

/** GPU publication keyed by Glass identity into its root or independent native surface. */
final class GodotPresentable implements Presentable, GraphicsResource {
    private final GodotContext context;
    private final long view;
    private final long window;
    private final com.sun.javafx.tk.godot.GodotWindowLifecycle lifecycle;
    private int outputWidth, outputHeight;
    private boolean replacing;
    private int width, height;
    private float scaleX, scaleY;
    private boolean opaque, disposed, prepared;
	private final GodotRTTexture backBuffer;
	private boolean locked = true;

    GodotPresentable(GodotContext context, PresentableState state) {
        this.context = context;
        view = state.getNativeView();
        window = state.getNativeWindow();
        lifecycle = com.sun.javafx.tk.godot.GodotWindowLifecycle.get(window);
        updateSize(state);
        backBuffer = (GodotRTTexture)context.getResourceFactory().createRTTexture(width,height,Texture.WrapMode.CLAMP_NOT_NEEDED);
        if (backBuffer == null) throw new IllegalStateException("Cannot allocate Godot backbuffer");
        backBuffer.makePermanent();
    }
    private void updateSize(PresentableState state) {
        if (state.isMSAA()) throw new UnsupportedOperationException("Godot MSAA presentable is not implemented");
        width = state.getRenderWidth(); height = state.getRenderHeight();
        scaleX = state.getRenderScaleX(); scaleY = state.getRenderScaleY();
        outputWidth = state.getOutputWidth(); outputHeight = state.getOutputHeight();
        if (lifecycle.isPrimary()) context.getCommands().resize(outputWidth, outputHeight);
    }
    @Override public boolean lockResources(PresentableState state) {
        if (disposed || view != state.getNativeView() || window != state.getNativeWindow()) return true;
        if (width != state.getRenderWidth() || height != state.getRenderHeight() || scaleX != state.getRenderScaleX() || scaleY != state.getRenderScaleY()) {
            replacing = true; return true;
        }
        outputWidth = state.getOutputWidth(); outputHeight = state.getOutputHeight();
        if (!locked) { backBuffer.lock(); locked = true; }
        prepared = false;
        return false;
    }
    @Override public Graphics createGraphics() {
        if (disposed) throw new IllegalStateException("Godot presentable is disposed");
        context.beginFrame();
        Graphics graphics = backBuffer.createGraphics();
        graphics.scale(scaleX, scaleY);
        return graphics;
    }
    @Override public boolean prepare(Rectangle dirty) {
        if (disposed) return false;
        context.flushVertexBuffer(); prepared = true;
        if (locked) { backBuffer.unlock(); locked = false; }
        return true;
    }
    @Override public boolean present() {
        if (disposed || !prepared) return false;
        // Success means queue admission, not GPU completion. Godot consumes on
        // its main thread and presents on its own rendering schedule.
        context.flushVertexBuffer();
        context.publishWindow(window, lifecycle, backBuffer, outputWidth, outputHeight); prepared = false;
        return true;
    }
    @Override public void dispose() {
        if (!disposed) {
            context.releaseHostTarget(backBuffer);
            if (context.retainsWindowTarget(window, backBuffer)) {
                if (!replacing) context.releaseWindow(window);
            } else backBuffer.dispose();
            disposed = true;
            // A closed/replaced Presentable may never publish another frame.
            // In the unretained branch disposal only appends to the encoder;
            // flush it now rather than keeping its native target until another
            // Stage happens to draw on this context.
            try { context.submit(); }
            catch (com.sun.javafx.tk.godot.GodotHostStoppedException cancelled) {
                // Host teardown owns GPU release once admission is closed.
            }
        }
    }
    @Override public Screen getAssociatedScreen() { return context.getAssociatedScreen(); }
    @Override public int getPhysicalWidth() { return width; }
    @Override public int getPhysicalHeight() { return height; }
    @Override public int getContentWidth() { return width; }
    @Override public int getContentHeight() { return height; }
    @Override public int getContentX() { return 0; }
    @Override public int getContentY() { return 0; }
    @Override public float getPixelScaleFactorX() { return scaleX; }
    @Override public float getPixelScaleFactorY() { return scaleY; }
    @Override public boolean isOpaque() { return opaque; }
    @Override public void setOpaque(boolean value) { opaque = value; }
    @Override public boolean isMSAA() { return false; }
}
