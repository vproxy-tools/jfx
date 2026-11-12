/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.prism.godot;

import java.util.LinkedHashMap;
import java.util.function.Supplier;

/** CPU text only; bounded per render thread, with no texture/material references. */
final class GodotShaderSourceCache {
    private static final boolean ENABLED = !"0".equals(System.getenv("GODOT_JFX_SHADER_SOURCE_CACHE"));
    private record Key(String source, Object variant) {}
    private static final ThreadLocal<GodotShaderSourceCache> LOCAL = ThreadLocal.withInitial(GodotShaderSourceCache::new);
    private final LinkedHashMap<Key,String> entries = new LinkedHashMap<>(16, .75f, true);
    private int characters;
    private static final int MAX_CHARACTERS = 524288;

    static String adapt(String source, Object variant, Supplier<String> conversion) {
        if (!ENABLED) return conversion.get();
        return LOCAL.get().lookup(source, variant, conversion);
    }

    private String lookup(String source, Object variant, Supplier<String> conversion) {
        Key key = new Key(source, variant);
        String cached = entries.get(key);
        if (cached != null) return cached;
        String converted = conversion.get();
        // The 2048 allowance bounds up to sixteen 128-character sampler names.
        int weight = source.length() + converted.length() + 2048;
        if (weight > MAX_CHARACTERS) return converted;
        while (entries.size() >= 64 || characters + weight > MAX_CHARACTERS) {
            var iterator = entries.entrySet().iterator();
            var oldest = iterator.next();
            characters -= oldest.getKey().source().length() + oldest.getValue().length() + 2048;
            iterator.remove();
        }
        entries.put(key, converted); characters += weight;
        return converted;
    }
}
