/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.prism.godot;

import com.sun.prism.CompositeMode;

/** Adapts premultiplied Prism output to Godot's fixed-function blend factors. */
final class GodotComposite {
    static GodotShader.Snapshot apply(GodotShader.Snapshot shader, CompositeMode mode) {
        if (mode == CompositeMode.SRC_OVER) return shader;
        String source = GodotShaderSourceCache.adapt(shader.source(), mode, () -> adaptSource(shader.source(), mode));
        return new GodotShader.Snapshot(source, shader.samplerUnits(), shader.constants());
    }

    private static String adaptSource(String source, CompositeMode mode) {
        if (!source.contains("blend_premul_alpha"))
            throw new UnsupportedOperationException(mode + " requires a Prism premultiplied shader");
        String blend = switch (mode) {
            case SRC, CLEAR -> "blend_disabled";
            case DST_OUT -> "blend_mul";
            case ADD -> "blend_add";
            default -> throw new AssertionError(mode);
        };
        source = source.replace("blend_premul_alpha", blend);
        if (mode != CompositeMode.SRC) {
            var fragment = java.util.regex.Pattern.compile("\\bvoid\\s+fragment\\s*\\(\\s*\\)\\s*\\{").matcher(source);
            if (!fragment.find()) throw new IllegalArgumentException("Composite shader has no fragment entry point");
            String output = switch (mode) {
                case CLEAR -> "COLOR = vec4(0.0);";
                // MUL multiplies destination RGBA by source RGBA.
                case DST_OUT -> "COLOR = vec4(1.0 - COLOR.a);";
                // ADD uses SRC_ALPHA for both RGB and alpha. With t=sqrt(a),
                // (rgb/t,t)*t gives exactly the premultiplied (rgb,a) addend.
                case ADD -> "float t = sqrt(max(COLOR.a, 0.0)); COLOR = t > 0.0 ? vec4(COLOR.rgb / t, t) : vec4(0.0);";
                default -> throw new AssertionError(mode);
            };
            // Keep fragment builtins in their entry-point scope. Godot forbids
            // returns in processor functions; braces/comments in user code must not
            // cause the epilogue to be inserted into a helper or nested block.
            var tokens = java.util.regex.Pattern.compile("//[^\\r\\n]*|/\\*[\\s\\S]*?\\*/|[{}]").matcher(source);
            tokens.region(fragment.end(), source.length());
            var adapted = new StringBuilder(source.substring(0, fragment.end()));
            int depth = 1, cursor = fragment.end();
            boolean closed = false;
            while (tokens.find()) {
                String token = tokens.group();
                if (token.startsWith("/")) continue;
                if (token.equals("{")) ++depth;
                else if (token.equals("}") && --depth == 0) {
                    adapted.append(source, cursor, tokens.start()).append("{ ").append(output).append(" }\n");
                    adapted.append(source.substring(tokens.start()));
                    closed = true;
                    break;
                }
            }
            if (!closed) throw new IllegalArgumentException("Unclosed composite fragment");
            source = adapted.toString();
        }
        return source;
    }
}
