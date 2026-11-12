/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.javafx.scene.layout;

import com.sun.javafx.sg.prism.NGNode;
import com.sun.javafx.sg.prism.NGGodotView;
import com.sun.javafx.util.Utils;
import javafx.scene.Node;
import javafx.scene.layout.GodotView;

public final class GodotViewHelper extends RegionHelper {
    private static final GodotViewHelper INSTANCE = new GodotViewHelper();
    private static Accessor accessor;
    static { Utils.forceInit(GodotView.class); }
    public static void initHelper(GodotView view) { setHelper(view,INSTANCE); }
    @Override protected NGNode createPeerImpl(Node node) { return new NGGodotView(); }
    @Override protected void updatePeerImpl(Node node) {
        super.updatePeerImpl(node);
        accessor.update((GodotView)node);
    }
    public static void setAccessor(Accessor value) {
        if (accessor != null) throw new IllegalStateException("GodotView accessor already installed");
        accessor = value;
    }
    public interface Accessor { void update(GodotView view); }
}
