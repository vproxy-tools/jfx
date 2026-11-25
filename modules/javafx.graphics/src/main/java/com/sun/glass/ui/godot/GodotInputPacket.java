/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.glass.ui.godot;

/** Internal native input record contract; not an application API. */
public final class GodotInputPacket {
    public static final int STRIDE = 12;
    static final int KEY_SOURCE_FOCUSED = 3; // Wayland key press, sampled by the native source window.
    private GodotInputPacket() {}

    public static int scrollSetting(double value) {
        if (!Double.isFinite(value) || value < 0 || value > 0xffff_ffffL || value != Math.rint(value))
            throw new IllegalArgumentException("Invalid unsigned Windows scroll setting");
        return (int)(long)value;
    }
}
