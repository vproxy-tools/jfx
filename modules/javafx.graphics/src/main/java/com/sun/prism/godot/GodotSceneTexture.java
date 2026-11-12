/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.prism.godot;

import com.sun.prism.Texture;
import com.sun.prism.PixelFormat;
import com.sun.prism.MediaFrame;
import java.nio.Buffer;

/** Sampleable GPU texture whose native owner is a live scene-tree SubViewport. */
public final class GodotSceneTexture extends GodotTexture {
    private GodotSceneTexture(Data data, int width, int height) {
        super(data,PixelFormat.INT_ARGB_PRE,WrapMode.CLAMP_TO_ZERO,width,height);
    }
    private GodotSceneTexture(GodotSceneTexture source, WrapMode wrap) { super(source,wrap); }
    static GodotSceneTexture create(GodotTexturePool pool, String path, int width, int height) {
        if (width < 1 || height < 1 || width > 8192 || height > 8192) throw new IllegalArgumentException("Invalid scene dimensions");
        Data data = pool.allocateScene(width,height,path);
        return data == null ? null : new GodotSceneTexture(data,width,height);
    }
    @Override protected Texture createSharedTexture(WrapMode wrap) { return new GodotSceneTexture(this,wrap); }
    GodotSceneTexture resize(GodotTexturePool pool, int width, int height) {
        Data next = pool.resizeScene(getNativeHandle(),width,height);
        if (next == null) return null;
        GodotSceneTexture replacement = new GodotSceneTexture(next,width,height);
        replacement.setLinearFiltering(getLinearFiltering());
        // Command 21 consumes the old handle; ordinary disposal must not emit
        // a second native release. All aliases become lost with this resource.
        Data previous = resource.getResource();
        previous.disposed = true;
        pool.release(previous.handle);
        dispose();
        return replacement;
    }
    @Override public void update(Buffer pixels, PixelFormat format, int dx,int dy,int sx,int sy,int width,int height,int stride,boolean skipFlush) {
        throw new UnsupportedOperationException("Scene textures cannot receive CPU image uploads");
    }
    @Override public void update(MediaFrame frame, boolean skipFlush) {
        throw new UnsupportedOperationException("Scene textures cannot receive media uploads");
    }
}
