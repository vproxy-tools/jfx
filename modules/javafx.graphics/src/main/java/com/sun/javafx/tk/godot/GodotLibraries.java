/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.javafx.tk.godot;

import java.nio.file.Path;

/** Selects the Glass and Prism backend together, including for native loads before Toolkit startup. */
public final class GodotLibraries {
    private static Boolean godot;

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

    static Path absolute(Path path) {
        try {
            return path.toRealPath();
        } catch (java.io.IOException ex) {
            return path.toAbsolutePath().normalize();
        }
    }
}
