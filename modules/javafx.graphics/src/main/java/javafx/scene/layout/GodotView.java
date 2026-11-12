/* SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0 */
package javafx.scene.layout;

import javafx.animation.AnimationTimer;
import javafx.beans.value.ChangeListener;
import javafx.stage.Window;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TouchEvent;
import javafx.scene.input.TouchPoint;
import javafx.scene.input.ScrollEvent;
import javafx.geometry.Point2D;
import com.sun.javafx.scene.DirtyBits;
import com.sun.javafx.scene.NodeHelper;
import com.sun.javafx.scene.layout.GodotViewHelper;
import com.sun.javafx.sg.prism.NGGodotView;
import com.sun.javafx.tk.Toolkit;

/**
 * A resizable GPU-backed Godot 2D scene embedded in the JavaFX scene graph.
 * Requires the Godot rendering pipeline. loadScene accepts a res:// PackedScene.
 * Layout changes resize the SubViewport without reloading its scene. close or
 * removal from the JavaFX Scene releases the native scene on the render thread.
 */
public final class GodotView extends Region implements AutoCloseable {
    static { GodotViewHelper.setAccessor(GodotView::updatePeer); }
    private String scenePath;
    private Window observedWindow;
    private Point2D mousePosition;
    private final java.util.Map<Integer,Point2D> touches = new java.util.HashMap<>();
    private final AnimationTimer pulse = new AnimationTimer() {
        @Override public void handle(long now) {
            if (NodeHelper.isTreeVisible(GodotView.this)) NodeHelper.markDirty(GodotView.this,DirtyBits.NODE_CONTENTS);
        }
    };
    private final ChangeListener<Boolean> showing = (o,old,value) -> refreshPulse();
    private final ChangeListener<Window> window = (o,old,value) -> observeWindow(value);

    public GodotView() {
        GodotViewHelper.initHelper(this);
        setPrefSize(320,200);
        setFocusTraversable(true);
        addEventHandler(MouseEvent.ANY,this::mouseInput);
        addEventHandler(KeyEvent.ANY,this::keyInput);
        addEventHandler(TouchEvent.ANY,this::touchInput);
        addEventHandler(ScrollEvent.SCROLL,this::scrollInput);
        focusedProperty().addListener((o,old,value) -> {
            if (!value) { touches.clear(); mousePosition = null; }
            forward(5,value ? 1 : 0,0,0,0,0,0,0);
        });
        sceneProperty().addListener((o,old,value) -> {
            if (old != null) old.windowProperty().removeListener(window);
            if (value != null) value.windowProperty().addListener(window);
            observeWindow(value == null ? null : value.getWindow());
        });
    }
    public String getScenePath() { return scenePath; }
    private static int modifiers(boolean shift,boolean control,boolean alt,boolean meta) {
        return (shift ? 2 : 0)|(control ? 4 : 0)|(alt ? 8 : 0)|(meta ? 16 : 0);
    }
    private Point2D normalized(double x,double y) {
        var insets = getInsets();
        return new Point2D((x-insets.getLeft())/Math.max(1,getWidth()-insets.getLeft()-insets.getRight()),
            (y-insets.getTop())/Math.max(1,getHeight()-insets.getTop()-insets.getBottom()));
    }
    private void forward(int kind,int flags,int code,int unicode,double x,double y,double dx,double dy) {
        if (scenePath == null) return;
        NGGodotView peer = NodeHelper.getPeer(this);
        peer.sendInput(kind,flags,code,unicode,(float)x,(float)y,(float)dx,(float)dy);
    }
    private void mouseInput(MouseEvent event) {
        if (scenePath == null || event.isSynthesized()) return;
        boolean pressed = event.getEventType() == MouseEvent.MOUSE_PRESSED;
        boolean released = event.getEventType() == MouseEvent.MOUSE_RELEASED;
        boolean motion = event.getEventType() == MouseEvent.MOUSE_MOVED || event.getEventType() == MouseEvent.MOUSE_DRAGGED;
        if (!pressed && !released && !motion) return;
        if (pressed) requestFocus();
        Point2D point = normalized(event.getX(),event.getY());
        Point2D delta = mousePosition == null ? Point2D.ZERO : point.subtract(mousePosition);
        mousePosition = point;
        int flags = modifiers(event.isShiftDown(),event.isControlDown(),event.isAltDown(),event.isMetaDown()) | (pressed ? 1 : 0) | (event.getClickCount() == 2 ? 32 : 0);
        int button = switch(event.getButton()) { case PRIMARY -> 1; case SECONDARY -> 2; case MIDDLE -> 3; case BACK -> 8; case FORWARD -> 9; default -> 0; };
        if (motion || button != 0) forward(motion ? 0 : 1,flags,button,0,point.getX(),point.getY(),delta.getX(),delta.getY());
        event.consume();
    }
    private void keyInput(KeyEvent event) {
        if (scenePath == null || !isFocused() || event.getCode() == KeyCode.TAB) return;
        if (event.getEventType() != KeyEvent.KEY_TYPED) {
            boolean pressed = event.getEventType() == KeyEvent.KEY_PRESSED;
            String text = event.getText();
            int unicode = text != null && text.codePointCount(0,text.length()) == 1 ? text.codePointAt(0) : 0;
            forward(2,modifiers(event.isShiftDown(),event.isControlDown(),event.isAltDown(),event.isMetaDown())|(pressed ? 1 : 0),event.getCode().getCode(),unicode,0,0,0,0);
        }
        // Glass supplies text on PRESS as well as TYPED; forwarding both doubles input.
        event.consume();
    }
    private void scrollInput(ScrollEvent event) {
        if (scenePath == null) return;
        Point2D point = normalized(event.getX(),event.getY());
        int flags = modifiers(event.isShiftDown(),event.isControlDown(),event.isAltDown(),event.isMetaDown());
        double[] deltas = {event.getDeltaY(),event.getDeltaX()};
        for (int axis = 0; axis < 2; ++axis) if (deltas[axis] != 0) {
            int button = (axis == 0 ? 4 : 6)+(deltas[axis] < 0 ? 1 : 0);
            // The x-relative field carries wheel factor, not a spatial delta.
            double multiplier = Math.abs(axis == 0 ? event.getMultiplierY() : event.getMultiplierX());
            double factor = Math.abs(deltas[axis])/(multiplier > 0 ? multiplier : 1);
            NGGodotView peer = NodeHelper.getPeer(this);
            peer.sendWheel(flags,button,(float)point.getX(),(float)point.getY(),(float)factor);
        }
        event.consume();
    }
    private void touchInput(TouchEvent event) {
        if (scenePath == null) return;
        TouchPoint touch = event.getTouchPoint();
        if (touch.getState() == TouchPoint.State.STATIONARY) return;
        boolean pressed = touch.getState() == TouchPoint.State.PRESSED;
        boolean moved = touch.getState() == TouchPoint.State.MOVED;
        if (!pressed && !touches.containsKey(touch.getId())) { event.consume(); return; }
        if (pressed) requestFocus();
        Point2D point = normalized(touch.getX(),touch.getY());
        Point2D previous = touches.getOrDefault(touch.getId(),point);
        if (touch.getState() == TouchPoint.State.RELEASED) touches.remove(touch.getId()); else touches.put(touch.getId(),point);
        forward(moved ? 4 : 3,modifiers(event.isShiftDown(),event.isControlDown(),event.isAltDown(),event.isMetaDown())|(pressed ? 1 : 0),touch.getId(),0,point.getX(),point.getY(),point.getX()-previous.getX(),point.getY()-previous.getY());
        event.consume();
    }
    public void loadScene(String path) {
        Toolkit.getToolkit().checkFxUserThread();
        if (path == null || !path.startsWith("res://") || path.length() < 7 || path.indexOf('\0') >= 0 || path.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 4096) throw new IllegalArgumentException("Expected a res:// PackedScene path");
        scenePath = path;
        NodeHelper.markDirty(this,DirtyBits.NODE_CONTENTS);
        refreshPulse();
    }
    @Override public void close() {
        Toolkit.getToolkit().checkFxUserThread();
        scenePath = null; pulse.stop();
        NodeHelper.markDirty(this,DirtyBits.NODE_CONTENTS);
        NodeHelper.getPeer(this).release();
    }
    private void observeWindow(Window value) {
        if (observedWindow != null) observedWindow.showingProperty().removeListener(showing);
        observedWindow = value;
        if (observedWindow != null) observedWindow.showingProperty().addListener(showing);
        refreshPulse();
    }
    private void refreshPulse() {
        if (scenePath != null && observedWindow != null && observedWindow.isShowing()) pulse.start(); else pulse.stop();
    }
    private void updatePeer() {
        NGGodotView peer = NodeHelper.getPeer(this);
        var insets = getInsets();
        peer.configure(scenePath,(float)insets.getLeft(),(float)insets.getTop(),
            (float)Math.max(0,getWidth()-insets.getLeft()-insets.getRight()),
            (float)Math.max(0,getHeight()-insets.getTop()-insets.getBottom()));
    }
}
