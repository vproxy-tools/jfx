/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.prism.godot;

import com.sun.javafx.PlatformUtil;
import com.sun.javafx.tk.godot.GodotRenderCommands;
import com.sun.prism.PixelFormat;
import com.sun.prism.impl.BaseResourcePool;
import com.sun.prism.impl.TextureResourcePool;
import java.util.BitSet;

/** One pool per Godot rendering device, created on its Prism render thread. */
public final class GodotTexturePool extends BaseResourcePool<GodotTexture.Data> implements TextureResourcePool<GodotTexture.Data> {
    final GodotRenderCommands commands;
    final Runnable flushVertices;
    private final BitSet occupied = new BitSet(4097);
    private final int[] generations = new int[4097];

    public GodotTexturePool(GodotRenderCommands commands, Runnable flushVertices) {
        // On Linux, reclaim idle effect images earlier during VFX navigation.
        // BaseResourcePool can grow the target when live textures need more room.
        // The 1 GiB maximum matches the native frame protocol's texture budget.
        super((PlatformUtil.isLinux() ? 256L : 512L) * 1024 * 1024, 1024L * 1024 * 1024);
        this.commands = java.util.Objects.requireNonNull(commands);
        this.flushVertices = java.util.Objects.requireNonNull(flushVertices);
        occupied.set(0);
    }

    void checkThread() {
        if (!isManagerThread()) throw new IllegalStateException("Godot texture pool belongs to its render thread");
    }

    void ensureRoom(int bytes) {
        checkThread();
        if (commands.remainingBatchBytes() < bytes) commands.submit();
    }

    GodotTexture.Data allocate(int width, int height) {
        return allocate(width, height, false);
    }

    GodotTexture.Data allocate(int width, int height, boolean target) {
        return allocate(width,height,target,false);
    }

    GodotTexture.Data allocate(int width, int height, boolean target, boolean floating) {
        return allocate(width,height,target,floating,null);
    }

    GodotTexture.Data allocateScene(int width, int height, String path) {
        return allocate(width,height,false,false,java.util.Objects.requireNonNull(path));
    }

    private GodotTexture.Data allocate(int width, int height, boolean target, boolean floating, String scene) {
        return allocate(width,height,target,floating,scene,0);
    }

    GodotTexture.Data resizeScene(long source, int width, int height) {
        return allocate(width,height,false,false,null,source);
    }

    GodotTexture.Data allocateMediaPlane(int width,int height) {
        return allocate(width,height,false,false,null,0,true);
    }

    private GodotTexture.Data allocate(int width, int height, boolean target, boolean floating, String scene, long source) {
        return allocate(width,height,target,floating,scene,source,false);
    }

    private GodotTexture.Data allocate(int width, int height, boolean target, boolean floating, String scene, long source, boolean singleChannel) {
        checkThread();
        long bytes = (long) width * height * (floating ? 16 : singleChannel ? 1 : 4);
        if (!prepareForAllocation(bytes)) return null;
        int slot = occupied.nextClearBit(1);
        while (slot <= 4096 && generations[slot] == Integer.MAX_VALUE) slot = occupied.nextClearBit(slot + 1);
        if (slot > 4096) return null;
        long handle = ((long) (generations[slot] + 1) << 32) | slot;
        ensureRoom(scene == null ? 32 : 4124);
        if (source != 0) commands.resizeSceneTexture(handle,source,width,height);
        else if (scene != null) commands.allocateSceneTexture(handle,width,height,scene);
        else if (target) commands.allocateRenderTarget(handle, width, height);
        else if (singleChannel) commands.allocateVideoPlane(handle,width,height);
        else if (floating) commands.allocateFloatTexture(handle,width,height);
        else commands.allocateTexture(handle, width, height);
        generations[slot]++; occupied.set(slot);
        return new GodotTexture.Data(this, handle, bytes);
    }

    void release(long handle) { checkThread(); occupied.clear((int) handle); }
    @Override public long size(GodotTexture.Data data) { return data.bytes; }
    @Override public long estimateTextureSize(int width, int height, PixelFormat format) { return (long) width * height * (format == PixelFormat.FLOAT_XYZW ? 16 : 4); }
    @Override public long estimateRTTextureSize(int width, int height, boolean depth) { return (long) width * height * (depth ? 8 : 4); }
}
