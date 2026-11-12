/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.glass.ui.godot;

import com.sun.glass.ui.Screen;

/** Windows screen geometry, using the anchoring rules in GlassScreen.cpp. */
public final class GodotScreenLayout {
    private GodotScreenLayout() {}

    private static final class Monitor {
        final int id, x, y, w, h, wx, wy, ww, wh, dpi;
        final float scale;
        int lx, ly, lw, lh, pass;
        Monitor(int[] data, int n, boolean hidpi) {
            id=data[n]; x=data[n+1]; y=data[n+2]; w=data[n+3]; h=data[n+4];
            wx=data[n+5]; wy=data[n+6]; ww=data[n+7]; wh=data[n+8]; dpi=data[n+9];
            if(w<=0 || h<=0 || dpi<=0) throw new IllegalArgumentException("Invalid screen dimensions/DPI");
            // DisplayServerWindows::screen_get_scale uses the same DPI / 96.
            scale=hidpi ? dpi/96f : 1f;
            lw=Math.round(w/scale); lh=Math.round(h/scale);
        }
        void anchor(int x, boolean beforeX, int y, boolean beforeY, int pass) {
            lx=x-(beforeX?lw:0); ly=y-(beforeY?lh:0); this.pass=pass;
        }
        Screen screen() {
            int left=Math.round((wx-x)/scale), top=Math.round((wy-y)/scale);
            int right=Math.round((wx+ww-x)/scale), bottom=Math.round((wy+wh-y)/scale);
            int resolution=Math.round(dpi/scale);
            return new Screen(id,32,lx,ly,lw,lh,x,y,w,h,lx+left,ly+top,right-left,bottom-top,
                    resolution,resolution,scale,scale,scale,scale);
        }
    }

    private static int offset(int a0,int a1,int b0,int b1,float as,float bs) {
        if(a0==b0) return 0;
        float mid=a1==b1 ? a1 : (Math.max(a0,b0)+Math.min(a1,b1))/2f;
        return Math.round((mid-a0)/as-(mid-b0)/bs);
    }
    private static boolean leftOf(Monitor a,Monitor b) {
        return a.x+a.w==b.x && a.y<b.y+b.h && a.y+a.h>b.y;
    }
    private static boolean above(Monitor a,Monitor b) {
        return a.y+a.h==b.y && a.x<b.x+b.w && a.x+a.w>b.x;
    }
    private static void propagate(Monitor a,Monitor[] monitors,int pass) {
        for(Monitor b:monitors) {
            if(b.pass!=0) continue;
            if(leftOf(a,b) || leftOf(b,a)) {
                boolean before=leftOf(b,a);
                b.anchor(before?a.lx:a.lx+a.lw,before,
                        a.ly+offset(a.y,a.y+a.h,b.y,b.y+b.h,a.scale,b.scale),false,pass);
            } else if(above(a,b) || above(b,a)) {
                boolean before=above(b,a);
                b.anchor(a.lx+offset(a.x,a.x+a.w,b.x,b.x+b.w,a.scale,b.scale),false,
                        before?a.ly:a.ly+a.lh,before,pass);
            }
        }
    }

    /** Input is primary-first, with ten native integers per monitor. */
    public static Screen[] create(int[] data,boolean hidpi) {
        if(data.length==0 || data.length%10!=0) throw new IllegalArgumentException("Invalid screen snapshot");
        Monitor[] monitors=new Monitor[data.length/10];
        for(int i=0;i<monitors.length;i++) monitors[i]=new Monitor(data,i*10,hidpi);
        int pass=1;
        monitors[0].anchor(monitors[0].x,false,monitors[0].y,false,pass);
        while(true) {
            boolean propagated=false;
            for(Monitor m:monitors) if(m.pass==pass) { propagate(m,monitors,pass+1); propagated=true; }
            if(propagated) { pass++; continue; }
            Monitor next=null;
            for(Monitor m:monitors) if(m.pass==0) { next=m; break; }
            if(next==null) break;
            next.anchor(next.x,false,next.y,false,pass);
        }
        Screen[] result=new Screen[monitors.length];
        for(int i=0;i<result.length;i++) result[i]=monitors[i].screen();
        return result;
    }
}
