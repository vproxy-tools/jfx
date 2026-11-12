/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.glass.ui.godot;
import com.sun.glass.ui.Cursor;
import com.sun.glass.ui.Pixels;

final class GodotCursor extends Cursor {
    private int width, height, hotspotX, hotspotY;
    private byte[] bgra;
    GodotCursor(int type) { super(type); }
    GodotCursor(int x, int y, Pixels pixels) { super(x,y,pixels); }
    @Override protected long _createCursor(int x, int y, Pixels pixels) {
        width=pixels.getWidth(); height=pixels.getHeight();
        if(width<1 || height<1 || width>256 || height>256 || x<0 || y<0 || x>=width || y>=height)
            throw new IllegalArgumentException("Invalid custom cursor dimensions/hotspot");
        hotspotX=x; hotspotY=y; bgra=new byte[width*height*4];
        var buffer=pixels.getBuffer();
        if(buffer instanceof java.nio.ByteBuffer bytes) bytes.duplicate().rewind().get(bgra);
        else if(buffer instanceof java.nio.IntBuffer ints) {
            var copy=ints.duplicate().rewind();
            for(int p=0;p<bgra.length;p+=4) {
                int argb=copy.get();
                bgra[p]=(byte)argb; bgra[p+1]=(byte)(argb>>>8); bgra[p+2]=(byte)(argb>>>16); bgra[p+3]=(byte)(argb>>>24);
            }
        } else throw new IllegalArgumentException("Unsupported cursor pixels");
        // Glass requires a nonzero custom handle. The immutable payload is
        // Java-owned; the host only creates an OS cursor when this is selected.
        return 1;
    }
    int apply() { return com.sun.javafx.tk.godot.GodotBridge.customCursor(width,height,hotspotX,hotspotY,bgra); }
}
