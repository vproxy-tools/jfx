/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.scenario.effect.compiler.backend.hw;

import com.sun.scenario.effect.compiler.JSLParser;
import com.sun.scenario.effect.compiler.tree.JSLVisitor;

/** Emits CanvasItem shaders from the same JSL AST as Prism's other GPU backends. */
public final class GodotBackend extends GLSLBackend {
    public GodotBackend(JSLParser parser, JSLVisitor visitor) { super(parser, visitor); }

    @Override protected String getVar(String variable) {
        return switch (variable) {
            case "pos0" -> "godot_uv0";
            case "pos1" -> "godot_uv1";
            case "color" -> "COLOR";
            case "jsl_vertexColor" -> "godot_vertex_color";
            case "E" -> "jsl_E"; // Godot reserves Euler's constant.
            default -> variable;
        };
    }

    @Override protected String getDeclarationName(String name) { return "E".equals(name) ? "jsl_E" : name; }

    @Override protected String getFuncName(String function) {
        return "sample".equals(function) ? "texture" : super.getFuncName(function);
    }

    @Override protected String getHeader() {
        StringBuilder code = new StringBuilder("shader_type canvas_item;\nrender_mode unshaded, blend_premul_alpha;\n");
        if (maxTexCoordIndex >= 0) code.append("varying vec2 godot_uv0;\n");
        if (maxTexCoordIndex >= 1) code.append("varying vec2 godot_uv1;\n");
        if (isVertexColorReferenced) code.append("varying vec4 godot_vertex_color;\n");
        code.append("void vertex() {\n");
        if (maxTexCoordIndex >= 0) code.append("godot_uv0 = UV;\n");
        if (maxTexCoordIndex >= 1) code.append("godot_uv1 = CUSTOM0.xy;\n");
        if (isVertexColorReferenced) code.append("godot_vertex_color = COLOR;\n");
        return code.append("}\n").toString();
    }

    @Override public String getShader() {
        String shader = super.getShader();
        if (isPixcoordReferenced) {
            shader = shader.replace(MAIN + PIXCOORD, MAIN + "vec2 pixcoord = SCREEN_UV / SCREEN_PIXEL_SIZE;\n");
        }
        return shader.replace(MAIN, "void fragment() {\n");
    }
}
