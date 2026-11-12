/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.glass.ui.godot;

import com.sun.glass.ui.*;
import com.sun.glass.ui.delegate.*;

public final class GodotPlatformFactory extends PlatformFactory {
    @Override public Application createApplication() { return new GodotApplication(); }
    @Override public MenuBarDelegate createMenuBarDelegate(MenuBar menubar) { throw GodotApplication.pending("System menu"); }
    @Override public MenuDelegate createMenuDelegate(Menu menu) { throw GodotApplication.pending("System menu"); }
    @Override public MenuItemDelegate createMenuItemDelegate(MenuItem item) { throw GodotApplication.pending("System menu"); }
    @Override public ClipboardDelegate createClipboardDelegate() {
        return name -> {
            if (Clipboard.SYSTEM.equals(name)) return new GodotSystemClipboard(name);
            if (Clipboard.DND.equals(name)) return new GodotDragClipboard();
            throw GodotApplication.pending("Clipboard");
        };
    }

}
