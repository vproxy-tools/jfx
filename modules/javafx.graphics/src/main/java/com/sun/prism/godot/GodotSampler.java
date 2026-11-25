/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.prism.godot;

import com.sun.prism.Texture;
import java.util.LinkedHashMap;
import java.util.regex.Pattern;

/** Exact Prism level-zero sampling, including transparent bilinear borders. */
final class GodotSampler {
    // Wrap and filter flags use the same immutable integer shader constants.
    private static final GodotShader.Constant ZERO = integerFlag(0);
    private static final GodotShader.Constant ONE = integerFlag(1);
    private static final GodotShader.Constant TWO = integerFlag(2);

    private static GodotShader.Constant integerFlag(int value) {
        return new GodotShader.Constant(true, 1, false, new int[]{value});
    }

    static GodotShader.Snapshot bind(GodotShader.Snapshot original, GodotTexture[] textures) {
        if (original.samplerUnits().isEmpty()) return original;
        String source = GodotShaderSourceCache.adapt(original.source(), original.samplerUnits(), () -> adaptSource(original));
        var constants = new LinkedHashMap<>(original.constants());
        for (var entry : original.samplerUnits().entrySet()) {
            String name = entry.getKey(), prefix = "godot_sample_" + name;
            Texture texture = textures[entry.getValue()];
            if (texture == null) throw new IllegalStateException("Unbound sampler: " + name);
            var wrap = switch (texture.getWrapMode()) {
                case CLAMP_TO_ZERO -> ONE;
                case REPEAT -> TWO;
                default -> ZERO; // Simulated modes already include their guard texels.
            };
            constants.put(prefix + "_wrap", wrap);
            constants.put(prefix + "_linear", texture.getLinearFiltering() ? ONE : ZERO);
        }
        return new GodotShader.Snapshot(source,original.samplerUnits(),constants);
    }

    private static String adaptSource(GodotShader.Snapshot original) {
        String source = original.source();
        for (String name : original.samplerUnits().keySet()) {
            String prefix = "godot_sample_" + name;
            source = source.replaceAll("\\btexture\\s*\\(\\s*" + Pattern.quote(name) + "\\s*,", prefix + "(");
            var declaration = Pattern.compile("\\buniform\\s+sampler2D\\s+" + Pattern.quote(name) + "\\s*;").matcher(source);
            if (!declaration.find()) throw new UnsupportedOperationException("Sampler declaration cannot be adapted: " + name);
            String helper = "\nuniform int " + prefix + "_wrap;\nuniform int " + prefix + "_linear;\n"
                + "vec4 " + prefix + "_fetch(ivec2 p) {\n"
                + "ivec2 s = textureSize(" + name + ",0);\n"
                + "if (" + prefix + "_wrap == 2) { p = ivec2(mod(vec2(p),vec2(s))); }\n"
                + "else if (" + prefix + "_wrap == 1 && (any(lessThan(p,ivec2(0))) || any(greaterThanEqual(p,s)))) { return vec4(0.0); }\n"
                + "p = clamp(p,ivec2(0),s-ivec2(1)); return texelFetch(" + name + ",p,0); }\n"
                + "vec4 " + prefix + "(vec2 uv) {\n"
                + "vec2 p = uv * vec2(textureSize(" + name + ",0));\n"
                + "if (" + prefix + "_linear == 0) { return " + prefix + "_fetch(ivec2(floor(p))); }\n"
                + "p -= vec2(0.5); ivec2 b = ivec2(floor(p)); vec2 f = fract(p);\n"
                + "return mix(mix(" + prefix + "_fetch(b)," + prefix + "_fetch(b+ivec2(1,0)),f.x),"
                + "mix(" + prefix + "_fetch(b+ivec2(0,1))," + prefix + "_fetch(b+ivec2(1,1)),f.x),f.y); }\n";
            source = source.substring(0,declaration.end()) + helper + source.substring(declaration.end());
        }
        return source;
    }
}
