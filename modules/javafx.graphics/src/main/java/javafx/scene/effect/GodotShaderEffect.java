/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package javafx.scene.effect;

import javafx.beans.property.ObjectProperty;
import javafx.scene.Node;
import com.sun.javafx.effect.EffectDirtyBits;
import com.sun.javafx.geom.BaseBounds;
import com.sun.javafx.geom.transform.BaseTransform;
import com.sun.javafx.scene.BoundsAccessor;
import com.sun.prism.godot.GodotNodeShader;
import com.sun.prism.godot.GodotShader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Applies a Godot CanvasItem shader to this node or subtree through the normal
 * JavaFX Effect chain. Requires the Godot pipeline. UV is normalized to the
 * complete input bounds; TEXTURE contains premultiplied input pixels. Output
 * COLOR must also be premultiplied. The effect preserves the input bounds.
 * Numeric float/int uniforms of one to four components can be updated with
 * setFloat/setInt on the FX thread once attached to a live scene.
 * Additional sampler uniforms and non-premultiplied blend modes are rejected.
 * This extension does not provide a software or other-pipeline fallback.
 */
public final class GodotShaderEffect extends Effect {
    private final String source, adapted;
    private final Map<String,GodotShader.Constant> parameters = new LinkedHashMap<>();
    private ObjectProperty<Effect> input;

    public GodotShaderEffect(String source) {
        adapted = GodotNodeShader.adapt(source);
        this.source = source;
    }
    public String getSource() { return source; }
    public void setFloat(String name, float... values) {
        GodotNodeShader.validate(adapted,name,false,values.length);
        int[] words = new int[values.length];
        for (int i = 0; i < values.length; ++i) {
            if (!Float.isFinite(values[i])) throw new IllegalArgumentException("Non-finite shader parameter");
            words[i] = Float.floatToRawIntBits(values[i]);
        }
        parameters.put(name,new GodotShader.Constant(false,values.length,false,words));
        markDirty(EffectDirtyBits.EFFECT_DIRTY);
    }
    public void setInt(String name, int... values) {
        GodotNodeShader.validate(adapted,name,true,values.length);
        parameters.put(name,new GodotShader.Constant(true,values.length,false,values));
        markDirty(EffectDirtyBits.EFFECT_DIRTY);
    }
    public final void setInput(Effect value) { inputProperty().set(value); }
    public final Effect getInput() { return input == null ? null : input.get(); }
    public final ObjectProperty<Effect> inputProperty() {
        if (input == null) input = new EffectInputProperty("input");
        return input;
    }
    @Override boolean checkChainContains(Effect e) {
        Effect in = getInput();
        return in != null && (in == e || in.checkChainContains(e));
    }
    @Override com.sun.scenario.effect.GodotShaderEffect createPeer() {
        return new com.sun.scenario.effect.GodotShaderEffect(adapted);
    }
    @Override void update() {
        Effect in = getInput();
        if (in != null) in.sync();
        var peer = (com.sun.scenario.effect.GodotShaderEffect)getPeer();
        peer.setInput(in == null ? null : in.getPeer());
        peer.setParameters(Map.copyOf(parameters));
    }
    @Override BaseBounds getBounds(BaseBounds bounds, BaseTransform tx, Node node, BoundsAccessor accessor) {
        return getInputBounds(bounds,tx,node,accessor,getInput());
    }
    @Override Effect copy() {
        GodotShaderEffect effect = new GodotShaderEffect(source);
        effect.parameters.putAll(parameters);
        effect.setInput(getInput());
        return effect;
    }
}
