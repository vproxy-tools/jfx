/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package javafx.application;

import com.sun.javafx.tk.godot.GodotBootstrap;
import java.nio.file.Path;
import java.util.Objects;

/** Initializes the single Godot engine used by JavaFX in this process. */
public final class GodotRuntime {
    public static volatile boolean PRISM_GODOT_ENABLED = false;

    /** Controls visibility while the first Stage is prepared. */
    public enum StartupWindow { NORMAL, HIDDEN, LOGO }

    /**
     * Startup configuration. The default host uses packaged native libraries
     * and an empty scene tree. A scene project can be supplied as an imported
     * directory or a PCK file. A null driver uses the javafx.godot.driver
     * property, then the platform default. Windows accepts d3d12 (default),
     * vulkan and opengl. macOS accepts metal (default) and opengl.
     */
    public record Options(Path project, String driver, StartupWindow startupWindow) {}

    /** Prepares the default host, or reuses the host already selected. */
    public static void init() {
        GodotBootstrap.initialize(null);
    }

    /** Prepares the host before Toolkit startup. Conflicting settings are rejected. */
    public static void init(Options options) {
        GodotBootstrap.initialize(Objects.requireNonNull(options));
    }

    private GodotRuntime() {}
}
