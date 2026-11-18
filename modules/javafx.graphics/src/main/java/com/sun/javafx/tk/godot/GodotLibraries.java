/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.javafx.tk.godot;

import com.sun.javafx.PlatformUtil;
import java.nio.file.Path;

/** Selects one source for native dependencies, including loads before Toolkit startup. */
public final class GodotLibraries {
    private static Path root;
    private static boolean bound;
    private static Boolean godot;
    private static boolean hosted;

    private GodotLibraries() {}

    public static synchronized boolean selectBackend() {
        if (godot != null) return godot;
        String glass = System.getProperty("glass.platform");
        String prism = System.getProperty("prism.order");
        boolean godotGlass = "Godot".equalsIgnoreCase(glass);
        boolean godotPrism = "godot".equalsIgnoreCase(prism);
        if (prism != null && java.util.Arrays.stream(prism.split(","))
                .anyMatch(p -> p.trim().equalsIgnoreCase("godot")) && !godotPrism
                || godotGlass && prism != null && !godotPrism || godotPrism && glass != null && !godotGlass) {
            throw new IllegalStateException("Conflicting glass.platform=" + glass + " and prism.order=" + prism);
        }
        godot = godotGlass || godotPrism || glass == null && prism == null;
        if (godot) {
            System.setProperty("glass.platform", "Godot");
            System.setProperty("prism.order", "godot");
        }
        return godot;
    }

    static Path defaultRoot() {
        String value = System.getProperty("javafx.godot.runtime");
        return value == null || value.isBlank() ? null : absolute(Path.of(value));
    }

    static Path absolute(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException ex) {
            return path.toAbsolutePath().normalize();
        }
    }

    static synchronized void bind(Path directory) {
        if (bound && !java.util.Objects.equals(root, directory)) {
            throw new IllegalStateException("Native libraries already selected from " + root + ", requested " + directory);
        }
        root = directory;
        bound = true;
    }

    static synchronized void existingHost() {
        // A Java-launched scene has already bound its native library source.
        // The executable host supplies SDK paths before any Java-side bind.
        hosted = !bound;
    }

    public static synchronized Path library(String name) {
        if (hosted || !selectBackend()) return null;
        if (!bound) bind(defaultRoot());
        return root == null ? null : libraryAt(root, name);
    }

    public static synchronized boolean usesPackagedLibraries() {
        return !hosted && selectBackend() && bound && root == null;
    }

    static Path libraryAt(Path directory, String name) {
        return directory.resolve(PlatformUtil.isWindows() ? "bin" : "lib").resolve(System.mapLibraryName(name));
    }
}
