/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package javafx.application;

import com.sun.javafx.tk.godot.GodotBootstrap;
import com.sun.javafx.tk.godot.GodotBridge;
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
     * Linux x64 accepts vulkan (default) and opengl.
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

    /**
     * Queues a named integer message from the FX Application Thread. The root of the
     * current Godot scene must implement {@code on_javafx_message(topic, value)}.
     * The host selects the scene when draining its FIFO and schedules that method
     * on the Godot main thread. Returning means the queue accepted the message.
     * The topic must contain 1 to 128 UTF-16 code units.
     */
    public static void postSceneMessage(String topic, int value) {
        if (!Platform.isFxApplicationThread() || !PRISM_GODOT_ENABLED) {
            throw new IllegalStateException("Scene messages require the Godot backend and the FX Application Thread");
        }
        Objects.requireNonNull(topic, "topic");
        if (topic.isEmpty() || topic.length() > 128) {
            throw new IllegalArgumentException("Scene message topic length must be 1 to 128");
        }
        GodotBridge.hostCommand(28, value, 0, topic);
    }

    /**
     * Requests a compositor-controlled move for a visible Godot Wayland Stage.
     * Call on the FX thread from a primary mouse gesture on the window decoration.
     * Returns true when the request is queued. The compositor decides whether to accept it.
     */
    public static boolean startWindowMove(javafx.stage.Stage stage) {
        Objects.requireNonNull(stage);
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("Window moves require the FX Application Thread");
        }
        if (!PRISM_GODOT_ENABLED || !stage.isShowing()) return false;
        var peer = com.sun.javafx.stage.WindowHelper.getPeer(stage);
        return peer instanceof com.sun.javafx.tk.quantum.WindowStage window
                && com.sun.glass.ui.godot.GodotApplication.startWindowMove(window.getPlatformWindow());
    }

    private GodotRuntime() {}
}
