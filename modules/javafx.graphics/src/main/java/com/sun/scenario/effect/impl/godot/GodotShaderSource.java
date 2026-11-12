/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.scenario.effect.impl.godot;

import com.sun.scenario.effect.Effect.AccelType;
import com.sun.scenario.effect.impl.hw.ShaderSource;
import java.io.InputStream;

public final class GodotShaderSource implements ShaderSource {
    @Override public InputStream loadSource(String name) {
        if (!name.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Invalid effect shader name");
        InputStream source = getClass().getResourceAsStream("shaders/" + name + ".gdshader");
        if (source == null) throw new UnsupportedOperationException("Missing generated Godot effect shader: " + name);
        return source;
    }
    @Override public AccelType getAccelType() { return AccelType.GODOT; }
}
