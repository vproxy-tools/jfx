/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.prism.godot;

import com.sun.glass.ui.Screen;
import com.sun.javafx.tk.godot.GodotBridge;
import com.sun.prism.GraphicsPipeline;
import com.sun.prism.ResourceFactory;
import java.util.List;

/** Single Godot host device; no secondary native rendering window. */
public final class GODOTPipeline extends GraphicsPipeline {
    private static final GODOTPipeline INSTANCE = new GODOTPipeline();
    private GodotResourceFactory factory;
    public static GODOTPipeline getInstance() { return INSTANCE; }
    private GODOTPipeline() {}
    @Override public boolean init() {
        try {
            if (GodotBridge.hostInfo().length < 16) return false;
            GodotBridge.registerRenderThread(Thread.currentThread());
            return true;
        }
        catch (UnsatisfiedLinkError unavailable) { return false; }
    }
    @Override public int getAdapterOrdinal(Screen screen) { return 0; }
    @Override public ResourceFactory getResourceFactory(Screen screen) {
        if (factory == null) factory = new GodotResourceFactory(screen);
        return factory;
    }
    @Override public ResourceFactory getDefaultResourceFactory(List<Screen> screens) {
        return getResourceFactory(Screen.getMainScreen());
    }
    @Override public boolean is3DSupported() { return false; }
    @Override public boolean isMSAASupported() { return false; }
    @Override public boolean isVsyncSupported() { return false; }
    @Override public boolean supportsShaderType(ShaderType type) { return type == ShaderType.GLSL; }
    @Override public boolean supportsShaderModel(ShaderModel model) { return model == ShaderModel.SM3; }
    @Override public void dispose() {
        if (factory != null) { factory.dispose(); factory = null; }
        super.dispose();
    }
}
