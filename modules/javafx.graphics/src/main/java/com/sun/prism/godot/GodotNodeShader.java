/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.prism.godot;

import java.util.regex.Pattern;

/** Adapts the node's normalized texture coordinates to a pooled input texture. */
public final class GodotNodeShader {
    private GodotNodeShader() {}
    public static String adapt(String source) {
        if (source == null || source.length() > 240000) throw new IllegalArgumentException("Invalid CanvasItem shader source");
        String code = source.replaceAll("(?s)/\\*.*?\\*/|//[^\\r\\n]*", "");
        if (!code.matches("(?s).*\\bshader_type\\s+canvas_item\\s*;.*") ||
                !code.matches("(?s).*\\bvoid\\s+fragment\\s*\\(\\s*\\).*")) throw new IllegalArgumentException("Expected a CanvasItem fragment shader");
        if (code.contains("godot_node_") || code.matches("(?s).*\\buniform\\s+sampler\\w*\\b.*")) throw new IllegalArgumentException("Reserved identifiers or additional texture samplers are unsupported");
        var modes = Pattern.compile("\\brender_mode\\s+([^;]+);").matcher(code);
        while (modes.find()) for (String mode : modes.group(1).split(",")) {
            if (!mode.trim().equals("unshaded") && !mode.trim().equals("blend_premul_alpha")) throw new IllegalArgumentException("Node effects require unshaded premultiplied blending");
        }
        code = code.replaceAll("\\bshader_type\\s+canvas_item\\s*;", "").replaceAll("\\brender_mode\\s+[^;]+;", "");
        code = code.replaceAll("\\btexture\\s*\\(\\s*TEXTURE\\s*,", "godot_node_sample(");
        if (code.matches("(?s).*\\bTEXTURE\\b.*")) throw new IllegalArgumentException("Use texture(TEXTURE, coordinates) for the node input");
        code = code.replaceAll("\\bTEXTURE_PIXEL_SIZE\\b", "godot_node_pixel_size");
        // CanvasItem normally provides vertex COLOR multiplied by TEXTURE on
        // fragment entry. Our input is a named pooled texture, not TEXTURE.
        code = code.replaceFirst("\\bvoid\\s+fragment\\s*\\(\\s*\\)\\s*\\{", "void fragment() { COLOR *= godot_node_sample(UV);");
        return "shader_type canvas_item;\nrender_mode unshaded, blend_premul_alpha;\n"
            + "uniform sampler2D godot_node_input;\nuniform vec4 godot_node_rect;\nuniform vec2 godot_node_pixel_size;\n"
            + "vec4 godot_node_sample(vec2 uv) { return texture(godot_node_input,godot_node_rect.xy+uv*godot_node_rect.zw); }\n" + code;
    }

    public static void validate(String source, String name, boolean integer, int count) {
        if (name == null || !name.matches("[A-Za-z_]\\w*") || name.startsWith("godot_node_") || count < 1 || count > 4) throw new IllegalArgumentException("Invalid shader parameter");
        String type = count == 1 ? (integer ? "int" : "float") : (integer ? "ivec" : "vec") + count;
        if (!Pattern.compile("\\buniform\\s+"+type+"\\s+"+Pattern.quote(name)+"\\s*(?:=[^;]+)?;").matcher(source).find()) throw new IllegalArgumentException("Shader parameter type/shape differs: "+name);
    }
}
