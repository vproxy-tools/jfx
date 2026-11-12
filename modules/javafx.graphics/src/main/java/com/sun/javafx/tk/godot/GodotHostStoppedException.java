/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.javafx.tk.godot;

import java.util.concurrent.CancellationException;

/** The host has stopped accepting work; retrying cannot restart its transport. */
public final class GodotHostStoppedException extends CancellationException {
    public GodotHostStoppedException(String message) {
        super(message);
    }
}
