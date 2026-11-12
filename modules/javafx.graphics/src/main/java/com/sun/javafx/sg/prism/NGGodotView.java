/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.javafx.sg.prism;

import com.sun.javafx.tk.RenderJob;
import com.sun.javafx.tk.Toolkit;
import com.sun.prism.Graphics;
import com.sun.prism.godot.GodotResourceFactory;
import com.sun.prism.godot.GodotSceneTexture;
import java.util.Objects;

/** Scene-graph peer; texture ownership and command emission stay on the render thread. */
public final class NGGodotView extends NGRegion {
    private String path, loadedPath;
    private float x,y,width,height;
    private GodotSceneTexture texture;
    private GodotResourceFactory factory;
    private record Input(int kind,int flags,int code,int unicode,float x,float y,float dx,float dy) {}
    private final java.util.ArrayDeque<Input> pendingInputs = new java.util.ArrayDeque<>();

    public void sendInput(int kind,int flags,int code,int unicode,float x,float y,float dx,float dy) {
        Input input = new Input(kind,flags,code,unicode,x,y,dx,dy);
        Toolkit.getToolkit().addRenderJob(new RenderJob(() -> {
            if (texture == null) {
                if (pendingInputs.size() >= 256) throw new IllegalStateException("GodotView input queue exceeded 256 events");
                pendingInputs.add(input);
            } else {
                texture.lock();
                try { encodeInput(input); factory.getContext().submit(); }
                finally { texture.unlock(); }
            }
        }));
    }
    private void encodeInput(Input input) {
        int w = texture.getPhysicalWidth(), h = texture.getPhysicalHeight();
        boolean wheel = input.kind == 1 && input.code >= 4 && input.code <= 7;
        factory.getContext().getCommands().sceneInput(texture.getNativeHandle(),input.kind,input.flags,input.code,input.unicode,input.x*w,input.y*h,wheel ? input.dx : input.dx*w,input.dy*h);
    }
    public void sendWheel(int flags,int button,float x,float y,float factor) {
        sendInput(1,flags|1,button,0,x,y,factor,0);
        sendInput(1,flags,button,0,x,y,factor,0);
    }

    public void configure(String path,float x,float y,float width,float height) {
        this.path = path; this.x = x; this.y = y; this.width = width; this.height = height;
        visualsChanged();
    }
    @Override protected boolean hasVisuals() { return path != null || super.hasVisuals(); }
    @Override protected void renderContent(Graphics graphics) {
        super.renderContent(graphics);
        if (path == null || width <= 0 || height <= 0) return;
        if (!(graphics.getResourceFactory() instanceof GodotResourceFactory current)) throw new UnsupportedOperationException("GodotView requires the Godot rendering pipeline");
        if (factory != current || !Objects.equals(path,loadedPath)) disposeTexture();
        factory = current;
        int w = Math.max(1,(int)Math.ceil(width)), h = Math.max(1,(int)Math.ceil(height));
        if (texture == null) {
            texture = factory.createSceneTexture(path,w,h);
            if (texture == null) throw new IllegalStateException("Cannot allocate GodotView scene texture");
            texture.makePermanent(); loadedPath = path;
        } else {
            texture.lock();
            if (texture.isSurfaceLost()) throw new IllegalStateException("GodotView scene texture was lost");
            if (texture.getPhysicalWidth() != w || texture.getPhysicalHeight() != h) {
                GodotSceneTexture replacement = factory.resizeSceneTexture(texture,w,h);
                if (replacement == null) { texture.unlock(); throw new IllegalStateException("Cannot resize GodotView scene texture"); }
                texture = replacement; texture.makePermanent();
            }
        }
        try {
            while (!pendingInputs.isEmpty()) encodeInput(pendingInputs.removeFirst());
            graphics.drawTexture(texture,x,y,width,height);
        }
        finally { texture.unlock(); }
    }
    private void disposeTexture() {
        if (texture != null) { texture.dispose(); texture = null; }
        loadedPath = null;
    }
    @Override public void release() {
        // Node calls release on the FX thread when it leaves its Scene.
        Toolkit.getToolkit().addRenderJob(new RenderJob(() -> {
            pendingInputs.clear();
            disposeTexture();
            if (factory != null) factory.getContext().submit();
        }));
    }
}
