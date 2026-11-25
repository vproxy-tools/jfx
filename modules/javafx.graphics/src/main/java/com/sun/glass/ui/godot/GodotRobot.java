/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package com.sun.glass.ui.godot;

import com.sun.glass.ui.Application;
import com.sun.glass.ui.GlassRobot;
import com.sun.glass.ui.Screen;
import com.sun.glass.ui.win.WinPlatformFactory;
import com.sun.javafx.tk.godot.GodotBridge;
import java.util.Locale;
import javafx.scene.image.WritableImage;
import javafx.scene.image.PixelFormat;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.paint.Color;

/** Desktop Robot using the host's logical/physical monitor mapping. */
final class GodotRobot extends GlassRobot {
    private static GlassRobot createInputRobot() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return WinPlatformFactory.createInputRobot();
        if (os.contains("mac")) {
            // The hosted runtime never loads the native glass library, but the
            // macOS input robot lives there. MacRobot is package-private; the
            // godot glass package shares its module, so reflective access works
            // without extra JVM flags.
            com.sun.glass.utils.NativeLibLoader.loadLibrary("glass");
            try {
                var ctor = Class.forName("com.sun.glass.ui.mac.MacRobot").getDeclaredConstructor();
                ctor.setAccessible(true);
                return (GlassRobot) ctor.newInstance();
            } catch (ReflectiveOperationException e) {
                throw new UnsupportedOperationException("Cannot create the macOS input robot", e);
            }
        }
        throw new UnsupportedOperationException("No input robot for this platform: " + os);
    }
    private static final boolean LINUX = com.sun.javafx.PlatformUtil.isLinux();
    private final GlassRobot input = LINUX ? null : createInputRobot();
    private static final int MAX_PIXELS = 16 * 1024 * 1024;
    @Override public void create() {
        Application.checkEventThread();
        if (LINUX) GodotBridge.waylandRobotOpen(); else input.create();
    }
    @Override public void destroy() {
        Application.checkEventThread();
        if (!LINUX) input.destroy();
    }
    private static int linuxButton(MouseButton button) {
        return switch (button) {
            case PRIMARY -> 1;
            case MIDDLE -> 2;
            case SECONDARY -> 3;
            case BACK -> 4;
            case FORWARD -> 5;
            default -> throw new IllegalArgumentException("Unsupported Robot button: " + button);
        };
    }
    private void linuxButtons(MouseButton[] buttons, boolean pressed) {
        int mask = 0;
        for (var button : buttons) mask |= 1 << linuxButton(button);
        for (int i = 1; i <= 5; i++) {
            if ((mask & (1 << i)) != 0) GodotBridge.waylandRobotButton(i, pressed);
        }
    }
    @Override public void keyPress(KeyCode code) {
        Application.checkEventThread();
        if (LINUX) GodotBridge.waylandRobotKey(GodotKeyMap.robotKey(code.getCode()), true);
        else input.keyPress(code);
    }
    @Override public void keyRelease(KeyCode code) {
        Application.checkEventThread();
        if (LINUX) GodotBridge.waylandRobotKey(GodotKeyMap.robotKey(code.getCode()), false);
        else input.keyRelease(code);
    }
    @Override public void mousePress(MouseButton... buttons) {
        Application.checkEventThread();
        if (LINUX) linuxButtons(buttons, true); else input.mousePress(buttons);
    }
    @Override public void mouseRelease(MouseButton... buttons) {
        Application.checkEventThread();
        if (LINUX) linuxButtons(buttons, false); else input.mouseRelease(buttons);
    }
    @Override public void mouseWheel(int amount) {
        Application.checkEventThread();
        if (LINUX) GodotBridge.waylandRobotWheel(amount);
        else input.mouseWheel(amount);
    }

    private static void finite(double... values) {
        for (double value : values) if (!Double.isFinite(value) || Math.abs(value) > Integer.MAX_VALUE / 4.0)
            throw new IllegalArgumentException("Invalid Robot coordinates");
    }
    private static Screen screen(double x, double y, boolean physical) {
        Screen nearest = Screen.getMainScreen();
        double distance = Double.POSITIVE_INFINITY;
        for (Screen s : Screen.getScreens()) {
            double left = physical ? s.getPlatformX() : s.getX(), top = physical ? s.getPlatformY() : s.getY();
            double width = physical ? s.getPlatformWidth() : s.getWidth(), height = physical ? s.getPlatformHeight() : s.getHeight();
            double dx = Math.max(Math.max(left-x, 0), x-(left+width)), dy = Math.max(Math.max(top-y, 0), y-(top+height));
            if (x >= left && x < left+width && y >= top && y < top+height) return s;
            if (dx*dx+dy*dy < distance) { distance = dx*dx+dy*dy; nearest = s; }
        }
        return nearest;
    }
    private static final boolean MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
    @Override public void mouseMove(double x, double y) {
        Application.checkEventThread(); finite(x,y);
        Screen s = screen(x,y,false);
        if (MAC) {
            // CGWarpMouseCursorPosition alone delivers no motion events; the
            // Mac input robot posts real CGEvent mouse-moved/dragged events.
            input.mouseMove(s.toPlatformX((float)x), s.toPlatformY((float)y));
        } else {
            GodotBridge.robotMove(s.toPlatformX((float)x),s.toPlatformY((float)y));
        }
    }
    private double[] position() {
        Application.checkEventThread();
        int[] p = GodotBridge.robotPosition(); Screen s = screen(p[0],p[1],true);
        return new double[]{s.fromPlatformX(p[0]),s.fromPlatformY(p[1])};
    }
    @Override public double getMouseX() { return position()[0]; }
    @Override public double getMouseY() { return position()[1]; }
    @Override public Color getPixelColor(double x, double y) {
        Application.checkEventThread(); finite(x,y);
        Screen s = screen(x,y,false);
        return convertFromIntArgb(GodotBridge.robotCapture(s.toPlatformX((float)x),s.toPlatformY((float)y),1,1)[0]);
    }
    @Override public void getScreenCapture(int x, int y, int width, int height, int[] data, boolean scaleToFit) {
        Application.checkEventThread();
        int[] pixels = GodotBridge.robotCapture(x,y,width,height);
        if (data.length < pixels.length) throw new IllegalArgumentException("Capture destination too small");
        System.arraycopy(pixels,0,data,0,pixels.length);
    }
    @Override public WritableImage getScreenCapture(WritableImage image, double x, double y, double width, double height, boolean scaleToFit) {
        Application.checkEventThread(); finite(x,y,width,height,x+width,y+height);
        if (width < 1 || height < 1) throw new IllegalArgumentException("Capture dimensions must be positive");
        Screen origin = screen(x,y,false);
        double scaleX = scaleToFit ? 1 : origin.getPlatformScaleX(), scaleY = scaleToFit ? 1 : origin.getPlatformScaleY();
        double dw = Math.ceil(width*scaleX), dh = Math.ceil(height*scaleY);
        if (dw*dh > MAX_PIXELS) throw new IllegalArgumentException("Robot capture exceeds 16 million pixels");
        int w = (int)dw, h = (int)dh;
        int[] result = new int[w*h];
        // Each monitor is captured in physical pixels, then mapped into the
        // requested logical rectangle. Gaps between monitors stay transparent.
        for (Screen s : Screen.getScreens()) {
            double left = Math.max(x,s.getX()), top = Math.max(y,s.getY());
            double right = Math.min(x+width,s.getX()+(double)s.getWidth()), bottom = Math.min(y+height,s.getY()+(double)s.getHeight());
            if (right <= left || bottom <= top) continue;
            int px = s.toPlatformX((float)left), py = s.toPlatformY((float)top);
            int pw = Math.max(1,s.toPlatformX((float)right)-px), ph = Math.max(1,s.toPlatformY((float)bottom)-py);
            int[] pixels = GodotBridge.robotCapture(px,py,pw,ph);
            int l = Math.max(0,(int)Math.ceil((left-x)*scaleX)), t = Math.max(0,(int)Math.ceil((top-y)*scaleY));
            int r = Math.min(w,(int)Math.ceil((right-x)*scaleX)), b = Math.min(h,(int)Math.ceil((bottom-y)*scaleY));
            for (int iy=t; iy<b; ++iy) {
                int sy = Math.clamp(s.toPlatformY((float)(y+iy/scaleY))-py,0,ph-1);
                for (int ix=l; ix<r; ++ix) {
                    int sx = Math.clamp(s.toPlatformX((float)(x+ix/scaleX))-px,0,pw-1);
                    result[iy*w+ix] = pixels[sy*pw+sx];
                }
            }
        }
        if (image == null || image.getWidth() != w || image.getHeight() != h) image = new WritableImage(w,h);
        image.getPixelWriter().setPixels(0,0,w,h,PixelFormat.getIntArgbInstance(),result,0,w);
        return image;
    }
}
