/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.prism.godot;

import com.sun.prism.ps.Shader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** Render-thread shader descriptor. Draws consume immutable parameter snapshots. */
public final class GodotShader implements Shader {
    private static final Pattern UNIFORM = Pattern.compile("uniform\\s+(sampler2D|float|vec[234]|int|ivec[234])\\s+(\\w+)(?:\\[(\\d+)\\])?\\s*(?:=[^;]+)?;");
    private record Declaration(boolean integer, int components, int arraySize) {}

    public record Constant(boolean integer, int components, boolean array, int[] words) {
        public Constant { words = words.clone(); }
        @Override public int[] words() { return words.clone(); }
    }
    public record Snapshot(String source, Map<String, Integer> samplerUnits, Map<String, Constant> constants) {
        public Snapshot { samplerUnits = Map.copyOf(samplerUnits); constants = Map.copyOf(constants); }
    }

    private final Thread owner = Thread.currentThread();
    private final String source;
    private final Map<String, Integer> samplers;
    private final Map<String, Declaration> declarations = new LinkedHashMap<>();
    private final Map<String, Constant> constants = new LinkedHashMap<>();
    private final Consumer<GodotShader> selection;
    private boolean disposed, enabled;
    private boolean videoYv12;

    public GodotShader(String source, Map<String, Integer> samplers, Map<String, Integer> parameters, Consumer<GodotShader> selection) {
        this.source = Objects.requireNonNull(source);
        this.samplers = Map.copyOf(samplers);
        this.selection = Objects.requireNonNull(selection);
        var matcher = UNIFORM.matcher(source);
        Map<String, Boolean> declaredSamplers = new LinkedHashMap<>();
        while (matcher.find()) {
            String type = matcher.group(1), name = matcher.group(2);
            if (type.equals("sampler2D")) { declaredSamplers.put(name, true); continue; }
            int components = type.equals("float") || type.equals("int") ? 1 : type.charAt(type.length() - 1) - '0';
            int arraySize = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
            if (arraySize < 0 || arraySize > 1024) throw new IllegalArgumentException("Shader uniform array is too large");
            declarations.put(name, new Declaration(type.startsWith("i"), components, arraySize));
        }
        for (var entry : this.samplers.entrySet()) {
            if (!declaredSamplers.containsKey(entry.getKey()) || entry.getValue() < 0 || entry.getValue() > 15) throw new IllegalArgumentException("Invalid shader sampler mapping: " + entry.getKey());
        }
        if (!this.samplers.keySet().equals(declaredSamplers.keySet())) throw new IllegalArgumentException("Shader sampler mapping is incomplete");
        for (String name : parameters.keySet()) if (!declarations.containsKey(name)) throw new IllegalArgumentException("Shader parameter is not declared: " + name);
    }

    public static GodotShader loadPrism(String name, Map<String, Integer> samplers, Map<String, Integer> parameters, Consumer<GodotShader> selection) {
        if (!name.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Invalid stock shader name");
        try (InputStream stream = GodotShader.class.getResourceAsStream("shaders/" + name + ".gdshader")) {
            if (stream == null) throw new IllegalArgumentException("Missing generated Godot shader: " + name);
            String code=new String(stream.readAllBytes(),StandardCharsets.UTF_8);
            if(name.equals("Solid_TextureYV12") || name.equals("Solid_TextureYV12_AlphaTest")) {
                // Media planes are R8; ordinary BYTE_ALPHA textures replicate
                // their value to every channel, so red also preserves that path.
                for(String coordinates:new String[]{"lumaAlphaScale.xy","lumaAlphaScale.zw","cbCrScale.xy","cbCrScale.zw"})
                    code=code.replace(coordinates+").a",coordinates+").r");
            }
            if ((name.equals("Solid_TextureSecondPassLCD") || name.equals("Solid_TextureSecondPassLCD_AlphaTest"))
                    && !"0".equals(System.getenv("GODOT_JFX_LCD_SCREEN"))) {
                // The renderer samples the destination through its backbuffer,
                // replacing the per-text-node LCDBuffer copy round-trip (one
                // render-target switch and offscreen pass per text node).
                // GodotGraphics begins a fresh pass at each text-node sample
                // boundary, so overlapping text sees preceding draws.
                if (code.contains("uniform sampler2D dstColor;") && code.contains("texture(dstColor, texCoord2)")
                        && code.contains("COLOR = paint(godot_uv0, godot_uv1, godot_vertex_color);")) {
                    code = code.replace("uniform sampler2D dstColor;",
                            "uniform sampler2D SCREEN_TEXTURE : hint_screen_texture, repeat_disable, filter_nearest;")
                            .replace("texture(dstColor, texCoord2)", "texture(SCREEN_TEXTURE, texCoord2)")
                            .replace("COLOR = paint(godot_uv0, godot_uv1, godot_vertex_color);",
                                    "COLOR = paint(godot_uv0, SCREEN_UV, godot_vertex_color);");
                    var units = new LinkedHashMap<String, Integer>();
                    for (var entry : samplers.entrySet()) if (!entry.getKey().equals("dstColor")) units.put(entry.getKey(), entry.getValue());
                    samplers = units;
                } else {
                    // The context has already disabled LCDBuffer allocation.
                    // Keeping dstColor here would silently select an unusable shader.
                    throw new IllegalStateException("Cannot enable LCD screen sampling for generated shader: " + name);
                }
            }
            var shader = new GodotShader(code, samplers, parameters, selection);
            shader.videoYv12 = name.equals("Solid_TextureYV12") || name.equals("Solid_TextureYV12_AlphaTest");
            return shader;
        } catch (IOException failure) { throw new IllegalStateException("Cannot load generated Godot shader", failure); }
    }

    private void check() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("Godot shader belongs to another render thread");
        if (disposed) throw new IllegalStateException("Godot shader is disposed");
    }

    private Declaration declaration(String name, boolean integer, int components, boolean array) {
        check();
        Declaration declaration = declarations.get(name);
        if (declaration == null || declaration.integer != integer || declaration.components != components || (declaration.arraySize != 0) != array) {
            throw new IllegalArgumentException("Shader uniform type/shape differs: " + name);
        }
        return declaration;
    }

    private void scalar(String name, boolean integer, int... words) {
        declaration(name, integer, words.length, false);
        constants.put(name, new Constant(integer, words.length, false, words));
    }

    private void floating(String name, float... values) {
        int[] words = new int[values.length];
        for (int i = 0; i < values.length; ++i) {
            if (!Float.isFinite(values[i])) throw new IllegalArgumentException("Non-finite shader constant");
            words[i] = Float.floatToRawIntBits(values[i]);
        }
        scalar(name, false, words);
    }

    private void array(String name, boolean integer, int[] words, int count) {
        Declaration declaration = declaration(name, integer, 4, true);
        if (count < 1 || count > declaration.arraySize) throw new IllegalArgumentException("Shader array count exceeds declaration");
        Constant previous = constants.get(name);
        int[] merged = previous == null ? new int[declaration.arraySize * 4] : previous.words();
        System.arraycopy(words, 0, merged, 0, words.length);
        constants.put(name, new Constant(integer, 4, true, merged));
    }

    public Snapshot snapshot() {
        check();
        // The stock shader never samples alpha when its scale is zero, but
        // Godot still requires a valid binding for every declared sampler.
        Constant scale = constants.get("lumaAlphaScale");
        if (videoYv12 && scale != null && Float.intBitsToFloat(scale.words()[2]) == 0f) {
            var bindings = new LinkedHashMap<>(samplers);
            bindings.put("alphaTex", samplers.get("lumaTex"));
            return new Snapshot(source, bindings, constants);
        }
        return new Snapshot(source, samplers, constants);
    }
    @Override public void enable() { check(); selection.accept(this); enabled = true; }
    @Override public void disable() { check(); if (enabled) selection.accept(null); enabled = false; }
    @Override public boolean isValid() { return !disposed; }
    @Override public void dispose() { if (disposed) return; check(); disable(); disposed = true; constants.clear(); }
    @Override public void setConstant(String name, int x) { scalar(name, true, x); }
    @Override public void setConstant(String name, int x, int y) { scalar(name, true, x, y); }
    @Override public void setConstant(String name, int x, int y, int z) { scalar(name, true, x, y, z); }
    @Override public void setConstant(String name, int x, int y, int z, int w) { scalar(name, true, x, y, z, w); }
    @Override public void setConstant(String name, float x) { floating(name, x); }
    @Override public void setConstant(String name, float x, float y) { floating(name, x, y); }
    @Override public void setConstant(String name, float x, float y, float z) { floating(name, x, y, z); }
    @Override public void setConstant(String name, float x, float y, float z, float w) { floating(name, x, y, z, w); }

    // off/count address vec4 registers, matching the D3D Shader contract.
    // Buffer position is preserved; off is absolute rather than position-relative.
    @Override public void setConstants(String name, FloatBuffer values, int off, int count) {
        check(); Objects.requireNonNull(values);
        if (off < 0 || count < 1 || count > 1024 || ((long) off + count) * 4 > values.limit()) throw new IllegalArgumentException("Invalid shader array source range");
        int[] words = new int[count * 4];
        for (int i = 0; i < words.length; ++i) {
            float value = values.get(off * 4 + i);
            if (!Float.isFinite(value)) throw new IllegalArgumentException("Non-finite shader array value");
            words[i] = Float.floatToRawIntBits(value);
        }
        array(name, false, words, count);
    }
    @Override public void setConstants(String name, IntBuffer values, int off, int count) {
        check(); Objects.requireNonNull(values);
        if (off < 0 || count < 1 || count > 1024 || ((long) off + count) * 4 > values.limit()) throw new IllegalArgumentException("Invalid shader array source range");
        int[] words = new int[count * 4];
        for (int i = 0; i < words.length; ++i) words[i] = values.get(off * 4 + i);
        array(name, true, words, count);
    }
}
