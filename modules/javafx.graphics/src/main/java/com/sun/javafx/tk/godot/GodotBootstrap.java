/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.javafx.tk.godot;

import javafx.application.GodotRuntime.Options;
import javafx.application.GodotRuntime.StartupWindow;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Coordinates explicit initialization and all JavaFX startup entry points. */
public final class GodotBootstrap {
    private enum State { NEW, STARTING, READY, STOPPING, STOPPED, FAILED }
    private static final Object lock = new Object();
    private static volatile State state = State.NEW;
    private static Options effective;
    private static GodotEngine engine;
    private static Thread initiator;
    private static Runnable toolkitShutdown;
    private static IllegalStateException failure;

    private GodotBootstrap() {}

    public static void ensureInitialized() {
        if (state != State.READY && GodotLibraries.selectBackend()) initialize(null);
    }

    private static Options resolve(Options options) {
        Path directory = options != null && options.runtimeDirectory() != null
                ? GodotLibraries.absolute(options.runtimeDirectory()) : GodotLibraries.defaultRoot();
        String configuredProject = System.getProperty("javafx.godot.project");
        Path project = options != null && options.project() != null ? options.project()
                : configuredProject == null ? null : Path.of(configuredProject);
        if (project != null) project = GodotLibraries.absolute(project);
        String renderer = options != null && options.renderer() != null ? options.renderer() : "mobile";
        if (!renderer.equals("mobile") && !renderer.equals("gl_compatibility")) {
            throw new IllegalArgumentException("Unsupported Godot renderer: " + renderer);
        }
        return new Options(directory, project, renderer,
                options != null && options.startupWindow() != null ? options.startupWindow() : StartupWindow.HIDDEN);
    }

    public static void initialize(Options options) {
        synchronized (lock) {
            if (state == State.FAILED) throw failure;
            if (state == State.STARTING && (Thread.currentThread() == initiator
                    || engine != null && engine.isEngineThread())) {
                throw new IllegalStateException("Recursive Godot initialization");
            }
            if (!GodotLibraries.selectBackend()) {
                throw new IllegalStateException("Godot init conflicts with glass.platform=" + System.getProperty("glass.platform")
                        + " and prism.order=" + System.getProperty("prism.order"));
            }
            Options requested = null;
            if (state == State.NEW) {
                state = State.STARTING;
                initiator = Thread.currentThread();
                try {
                    requested = resolve(options);
                    effective = requested;
                    GodotLibraries.bind(effective.runtimeDirectory());
                    engine = GodotEngine.forPlatform();
                    Runtime.getRuntime().addShutdownHook(new Thread(GodotBootstrap::shutdown, "Godot shutdown"));
                    engine.start(effective, GodotBootstrap::ready, GodotBootstrap::finished);
                } catch (Throwable cause) {
                    fail(cause);
                } finally {
                    initiator = null;
                }
            } else if (options != null) {
                requested = resolve(options);
            }
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (state == State.STARTING) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    fail(new IllegalStateException("Timed out waiting for host registration"));
                    break;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(lock, remaining);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted waiting for Godot initialization", ex);
                }
            }
            if (state == State.FAILED) throw failure;
            if (state != State.READY) throw new IllegalStateException("Godot host is " + state);
            if (options != null && !requested.equals(effective)) {
                throw new IllegalStateException("Godot already initialized with " + effective);
            }
        }
    }

    // Called by GodotBridge on the EXE host's JVM worker before application code.
    static void existingHost() {
        synchronized (lock) {
            if (state != State.NEW) throw new IllegalStateException("Godot host already selected");
            GodotLibraries.existingHost();
            String sdk = System.getProperty("javafx.godot.host.sdk");
            if (sdk != null) {
                effective = new Options(Path.of(sdk).toAbsolutePath().normalize(),
                        Path.of(System.getProperty("javafx.godot.host.project")).toAbsolutePath().normalize(),
                        System.getProperty("javafx.godot.host.renderer"),
                        StartupWindow.valueOf(System.getProperty("javafx.godot.host.startup")));
            }
            state = State.READY;
        }
    }

    private static void ready() {
        synchronized (lock) {
            if (state != State.STARTING) throw new IllegalStateException("Godot initialization ended in " + state);
            state = State.READY;
            lock.notifyAll();
        }
    }

    private static void fail(Throwable cause) {
        String location = effective == null || effective.runtimeDirectory() == null ? "packaged native libraries"
                : GodotLibraries.libraryAt(effective.runtimeDirectory(), "godot").toString();
        failure = new IllegalStateException("Godot initialization/lifecycle failed on " + System.getProperty("os.name")
                + " " + System.getProperty("os.arch") + " at " + location + ": " + cause.getMessage(), cause);
        state = State.FAILED;
        lock.notifyAll();
    }

    private static void finished(Throwable cause) {
        synchronized (lock) {
            if (state == State.STOPPING && cause == null) state = State.STOPPED;
            else if (state != State.FAILED) fail(cause == null ? new IllegalStateException("Godot loop exited unexpectedly") : cause);
            lock.notifyAll();
        }
    }

    /** Keep the engine running while Quantum's JVM shutdown hook drains Prism. */
    public static boolean takeShutdownHook(Runnable hook) {
        synchronized (lock) {
            if (engine == null) return false;
            toolkitShutdown = hook;
            return true;
        }
    }

    private static void shutdown() {
        Runnable dispose;
        synchronized (lock) { dispose = toolkitShutdown; }
        try {
            if (dispose != null) {
                // Complete pending FX startup and close Glass peers while JNI and
                // the renderer are still available. System.exit can also come
                // from the FX thread itself, so never wait indefinitely for it.
                Thread fxShutdown = Thread.ofPlatform().daemon().name("Godot Glass shutdown")
                        .start(com.sun.javafx.application.PlatformImpl::tkExit);
                fxShutdown.join(3000);
                dispose.run();
                GodotBridge.awaitRendererShutdown();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            stopEngine();
        }
    }

    private static void stopEngine() {
        synchronized (lock) {
            if (state != State.FAILED) state = State.STOPPING;
            lock.notifyAll();
        }
        try {
            engine.stop();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
