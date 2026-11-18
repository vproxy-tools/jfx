/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.javafx.tk.godot;

import javafx.application.GodotRuntime.Options;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.function.Consumer;

/** Drives one engine on its platform event thread. */
final class LibraryGodotEngine implements GodotEngine {
    private final boolean appKit;
    private volatile Thread eventThread;
    private boolean stopRequested;
    private boolean loaded;
    private Thread thread;

    LibraryGodotEngine(boolean appKit) { this.appKit = appKit; }

    @Override public void start(Options config, Runnable ready, Consumer<Throwable> finished) {
        thread = Thread.ofPlatform().daemon().name("Godot library host").unstarted(() -> {
            try {
                String library = com.sun.glass.utils.NativeLibLoader.loadGodotEngine();
                Runnable loop = () -> {
                    eventThread = Thread.currentThread();
                    launch(config, ready, library);
                };
                if (appKit) runOnAppKitThread(loop);
                else loop.run();
            } catch (Throwable cause) {
                finished.accept(cause);
                return;
            }
            finished.accept(null);
        });
        thread.start();
    }

    @Override public boolean isEngineThread() {
        return Thread.currentThread() == eventThread;
    }

    @Override public void stop() throws InterruptedException {
        synchronized (this) {
            stopRequested = true;
            if (loaded) requestStop();
        }
        if (thread != null) {
            thread.join(10000);
            if (thread.isAlive()) throw new IllegalStateException("Timed out stopping the Godot event loop");
        }
    }

    private void launch(Options config, Runnable ready, String library) {
        synchronized (this) {
            loaded = true;
            if (stopRequested) return;
        }
        Path project = config.project();
        boolean directory = project != null && Files.isDirectory(project);
        if (project != null && !(directory ? Files.isRegularFile(project.resolve("project.godot")) : Files.isRegularFile(project))) {
            throw new IllegalArgumentException("project: missing Godot project or PCK at " + project);
        }
        Runnable publish = () -> {
            if (project == null) mountBundledScenes();
            ready.run();
        };
        run(GodotBridge.class, publish, library, project == null ? null : project.toString(), !directory,
                config.renderer(), config.startupWindow().name().toLowerCase(Locale.ROOT),
                Boolean.getBoolean("javafx.godot.disableVsync"),
                Integer.parseInt(System.getProperty("javafx.godot.maxFps", "0")),
                Boolean.getBoolean("javafx.godot.verbose"));
    }

    // The transient Java copy can be collected before the engine enters its event loop.
    private static void mountBundledScenes() {
        try (var stream = ClassLoader.getSystemResourceAsStream("META-INF/godot-jfx/scenes.pck")) {
            if (stream != null) mountScenePack(stream.readAllBytes());
        } catch (java.io.IOException error) {
            throw new java.io.UncheckedIOException("resources: cannot read bundled GodotView scenes.pck", error);
        }
    }

    private static native void runOnAppKitThread(Runnable callback);
    private static native void requestStop();
    private static native void mountScenePack(byte[] contents);
    private static native void run(Class<?> bridge, Runnable ready, String library,
            String project, boolean pack, String renderer, String startup, boolean disableVsync, int maxFps, boolean verbose);
}
