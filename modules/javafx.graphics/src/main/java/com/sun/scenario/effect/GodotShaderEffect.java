/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.scenario.effect;

import com.sun.javafx.geom.Rectangle;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.prism.godot.GodotResourceFactory;
import com.sun.prism.godot.GodotShader;
import com.sun.scenario.effect.impl.Renderer;
import com.sun.scenario.effect.impl.prism.PrTexture;
import com.sun.scenario.effect.impl.prism.ps.PPSDrawable;
import com.sun.scenario.effect.impl.state.RenderState;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** GPU-only, per-node input pass. No global peer cache or shared mutable uniforms. */
public final class GodotShaderEffect extends FilterEffect<RenderState> {
    private final String source;
    private Map<String,GodotShader.Constant> parameters = Map.of();
    public GodotShaderEffect(String source) { super(DefaultInput); this.source = source; }
    public void setInput(Effect input) { setInput(0,input); }
    public void setParameters(Map<String,GodotShader.Constant> parameters) { this.parameters = Map.copyOf(parameters); }
    @Override public RenderState getRenderState(FilterContext context, BaseTransform transform, Rectangle clip, Object helper, Effect defaultInput) {
        return RenderState.UserSpaceRenderState;
    }
    @Override public boolean reducesOpaquePixels() { return true; }
    @Override public AccelType getAccelType(FilterContext context) { return AccelType.GODOT; }
    @Override protected ImageData filterImageDatas(FilterContext context, BaseTransform transform, Rectangle clip, RenderState state, ImageData... inputs) {
        Renderer renderer = Renderer.getRenderer(context);
        if (renderer.getAccelType() != AccelType.GODOT) throw new UnsupportedOperationException("GodotShaderEffect requires the Godot pipeline");
        ImageData input = inputs[0];
        Rectangle bounds = input.getTransformedBounds(null);
        Filterable src = input.getTransformedImage(null);
        if (src == null) return new ImageData(context,null,bounds);
        PPSDrawable dst = null;
        boolean success = false;
        try {
            dst = (PPSDrawable) renderer.getCompatibleImage(bounds.width,bounds.height);
            if (dst == null) return new ImageData(context,null,bounds);
            var graphics = dst.createGraphics();
            var factory = (GodotResourceFactory)graphics.getResourceFactory();
            var texture = ((PrTexture<?>)src).getTextureObject();
            var shader = factory.createShader("GodotNode",new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),Map.of("godot_node_input",0),Map.of(),0,false,false);
            try {
                shader.setConstant("godot_node_rect",(float)texture.getContentX()/texture.getPhysicalWidth(),(float)texture.getContentY()/texture.getPhysicalHeight(),(float)bounds.width/texture.getPhysicalWidth(),(float)bounds.height/texture.getPhysicalHeight());
                shader.setConstant("godot_node_pixel_size",1f/bounds.width,1f/bounds.height);
                for (var entry : parameters.entrySet()) {
                    int[] words = entry.getValue().words();
                    String name = entry.getKey();
                    if (entry.getValue().integer()) {
                        switch(words.length) {
                            case 1 -> shader.setConstant(name,words[0]);
                            case 2 -> shader.setConstant(name,words[0],words[1]);
                            case 3 -> shader.setConstant(name,words[0],words[1],words[2]);
                            case 4 -> shader.setConstant(name,words[0],words[1],words[2],words[3]);
                        }
                    } else {
                        float[] v = new float[words.length];
                        for (int i = 0; i < v.length; ++i) v[i] = Float.intBitsToFloat(words[i]);
                        switch(v.length) {
                            case 1 -> shader.setConstant(name,v[0]);
                            case 2 -> shader.setConstant(name,v[0],v[1]);
                            case 3 -> shader.setConstant(name,v[0],v[1],v[2]);
                            case 4 -> shader.setConstant(name,v[0],v[1],v[2],v[3]);
                        }
                    }
                }
                graphics.setExternalShader(shader);
                graphics.drawTextureRaw(texture,0,0,bounds.width,bounds.height,0,0,1,1);
                graphics.setExternalShader(null);
                graphics.sync();
            } finally { shader.dispose(); }
            success = true;
            return new ImageData(context,dst,bounds);
        } finally {
            input.releaseTransformedImage(src);
            if (!success && dst != null) renderer.releaseCompatibleImage(dst);
        }
    }
}
