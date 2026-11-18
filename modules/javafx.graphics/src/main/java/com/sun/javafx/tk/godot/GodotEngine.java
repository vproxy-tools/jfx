/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.javafx.tk.godot;

import com.sun.javafx.PlatformUtil;
import javafx.application.GodotRuntime.Options;
import java.util.function.Consumer;

/** The platform owns scheduling, including any thread required by its window system. */
interface GodotEngine {
    void start(Options options, Runnable ready, Consumer<Throwable> finished);
    boolean isEngineThread();
    void stop() throws InterruptedException;

    static GodotEngine forPlatform() {
        String arch = System.getProperty("os.arch");
        if (PlatformUtil.isWindows() && ("amd64".equals(arch) || "x86_64".equals(arch))) {
            return new LibraryGodotEngine(false);
        }
        if (PlatformUtil.isMac() && "aarch64".equals(arch)) return new LibraryGodotEngine(true);
        throw new UnsupportedOperationException("Java-launched Godot requires a platform event-loop implementation");
    }
}
