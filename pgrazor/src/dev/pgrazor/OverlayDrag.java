package dev.pgrazor;

import android.content.Context;
import android.content.SharedPreferences;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Makes an in-activity overlay draggable without losing its tap action.
 *
 * <p>The handle view is also the dragged target. A short movement threshold keeps a
 * tap from registering as a drag, so the handle's existing click listener still fires.
 * The resting place is stored as a fraction of the free track (parent size minus view
 * size) in an app-private {@code SharedPreferences}; fractions survive restarts and
 * keep the overlay on-screen after a rotation or any other relayout. An optional
 * follower (a status pill) rides with the handle: below it, or beside it as one card (the
 * pill tucks under the handle's right half and shows its text next to it, flipped to the
 * left side when there is no room on the right).
 */
public final class OverlayDrag implements View.OnTouchListener {
    private static final String PREFS = "pgrazor-overlays";
    private final ViewGroup parent;
    private final View view;
    private final View follower;
    private final SharedPreferences prefs;
    private final String key;
    private final int threshold, gap, inner;
    private final boolean beside;
    private final View.OnLayoutChangeListener parentLayout, viewLayout, followerLayout;
    private float downX, downY, originX, originY;
    private boolean moved, dragging;

    static OverlayDrag attach(ViewGroup parent, View view, String key) { return new OverlayDrag(parent, view, null, key, false); }
    static OverlayDrag attach(ViewGroup parent, View view, View follower, String key) { return new OverlayDrag(parent, view, follower, key, false); }
    /** The follower and the handle read as one card: the follower sits beside the handle, partly under it. */
    static OverlayDrag attachBeside(ViewGroup parent, View view, View follower, String key) { return new OverlayDrag(parent, view, follower, key, true); }

    private OverlayDrag(ViewGroup parent, View view, View follower, String key, boolean beside) {
        this.parent = parent; this.view = view; this.follower = follower; this.key = key; this.beside = beside;
        prefs = AccountProfiles.preferences(view.getContext().getApplicationContext(),PREFS);
        float density = view.getResources().getDisplayMetrics().density;
        threshold = android.view.ViewConfiguration.get(view.getContext()).getScaledTouchSlop(); gap = Math.round(6 * density); inner = Math.round(10 * density);
        view.setOnTouchListener(this);
        view.setEnabled(true);
        view.setElevation(8 * density);
        view.post(() -> { int[] xy = new int[2]; view.getLocationOnScreen(xy); Control.event("overlay-layout", key + " x=" + xy[0] + " y=" + xy[1] + " w=" + view.getWidth() + " h=" + view.getHeight() + " enabled=" + view.isEnabled()); });
        // Restore when the handle is (re)laid out: first show, a visibility flip, or a rotation.
        viewLayout = (v, l, t, r, b, ol, ot, or, ob) -> applySaved();
        view.addOnLayoutChangeListener(viewLayout);
        // A rotation or window resize relays out the parent; re-apply to the new bounds.
        parentLayout = (v, l, t, r, b, ol, ot, or, ob) -> { if (r - l != or - ol || b - t != ob - ot) applySaved(); };
        parent.addOnLayoutChangeListener(parentLayout);
        if (follower != null) { followerLayout = (v, l, t, r, b, ol, ot, or, ob) -> positionFollower(); follower.addOnLayoutChangeListener(followerLayout); }
        else followerLayout = null;
        if (follower != null && beside) {
            follower.setElevation(7 * density);  // just under the handle, which covers the follower's left end
            follower.setOnClickListener(v -> view.performClick());  // the whole card acts like the handle
            follower.setOnTouchListener(this);  // and drags like it: grabbing the text moves the card
        }
    }

    public boolean onTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                Control.event("overlay-touch", key + " down " + e.getRawX() + "," + e.getRawY());
                downX = e.getRawX(); downY = e.getRawY(); originX = view.getX(); originY = view.getY(); moved = false; dragging = true; view.setPressed(true); return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                moved = true; view.setPressed(false); return true;
            case MotionEvent.ACTION_MOVE:
                float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                if (!moved && Math.hypot(dx, dy) > threshold) moved = true;
                if (moved) { view.setPressed(false); view.setX(clampX(originX + dx)); view.setY(clampY(originY + dy)); positionFollower(); }
                return true;
            case MotionEvent.ACTION_UP:
                Control.event("overlay-touch", key + " up moved=" + moved + " x=" + view.getX() + " y=" + view.getY());
                dragging = false; view.setPressed(false);
                if (moved) save(); else view.performClick(); // tap below the threshold (icon or its text) still opens history / toggles VGP
                return true;
            case MotionEvent.ACTION_CANCEL:
                dragging = false; view.setPressed(false); if (moved) save(); return true;
        }
        return true;
    }

    /** Pull the saved fraction back onto the current bounds; a no-op until the user first drags. */
    private void applySaved() {
        if (dragging || !prefs.contains(key + ".fx")) return;
        int w = visualWidth(view), h = visualHeight(view);
        if (w == 0 || h == 0 || parent.getWidth() == 0 || parent.getHeight() == 0) return;
        android.graphics.Rect safe=UiViewport.bounds(parent);
        // The top-left corner is what is remembered, so a card that grows or shrinks (the quick menu opening
        // inside the panel, a status line appearing) keeps its place instead of sliding; it only moves when
        // it would otherwise leave the screen.
        view.setX(clampX(safe.left+safe.width()*clampUnit(prefs.getFloat(key+".fx",0f))));
        view.setY(clampY(safe.top+safe.height()*clampUnit(prefs.getFloat(key+".fy",0f))));
        positionFollower();
    }

    private void save() {
        android.graphics.Rect safe=UiViewport.bounds(parent);
        float fx=safe.width()>0?clampUnit((view.getX()-safe.left)/safe.width()):0f;
        float fy=safe.height()>0?clampUnit((view.getY()-safe.top)/safe.height()):0f;
        prefs.edit().putFloat(key + ".fx", fx).putFloat(key + ".fy", fy).apply();
    }

    /** Keep the status pill directly below the handle, clamped on-screen, flipped above when there is no room. */
    private void positionFollower() {
        if (follower == null) return;
        int fw = visualWidth(follower), fh = visualHeight(follower);
        if (fw == 0 || fh == 0) return; // hidden or not yet measured; its own layout listener will catch up
        if (beside) { positionBeside(fw, fh); return; }
        float x = view.getX(), y = view.getY() + visualHeight(view) + gap * view.getScaleY();
        android.graphics.Rect safe=UiViewport.bounds(parent);
        if(y+fh>safe.bottom)y=view.getY()-gap-fh;
        follower.setX(UiViewportPolicy.clamp(x,safe.left,safe.right,fw));
        follower.setY(UiViewportPolicy.clamp(y,safe.top,safe.bottom,fh));
    }

    /** One card: the follower starts under the handle's centre and shows its text past the handle's edge. */
    private void positionBeside(int fw, int fh) {
        android.graphics.Rect safe=UiViewport.bounds(parent);
        int vw = visualWidth(view), vh = visualHeight(view);
        float centre = view.getX() + vw / 2f, scale = follower.getScaleX() > 0 ? follower.getScaleX() : 1f;
        boolean right = centre + fw <= safe.right || centre - fw < safe.left;
        int under = Math.round((vw / 2f) / scale) + inner;
        int left = right ? under : inner, end = right ? inner : under;
        if (follower.getPaddingLeft() != left || follower.getPaddingRight() != end) {
            follower.setPadding(left, follower.getPaddingTop(), end, follower.getPaddingBottom());
            return;  // relayout; the follower's layout listener places it with its new width
        }
        float x = right ? centre : centre - fw;
        follower.setX(UiViewportPolicy.clamp(x,safe.left,safe.right,fw));
        follower.setY(UiViewportPolicy.clamp(view.getY() + (vh - fh) / 2f,safe.top,safe.bottom,fh));
    }

    private float clampX(float x){
        android.graphics.Rect safe=UiViewport.bounds(parent);
        return UiViewportPolicy.clamp(x,safe.left,safe.right,visualWidth(view));
    }
    private float clampY(float y){
        android.graphics.Rect safe=UiViewport.bounds(parent);
        return UiViewportPolicy.clamp(y,safe.top,safe.bottom,visualHeight(view));
    }
    static int visualWidth(View view){return Math.round(view.getWidth()*view.getScaleX());}
    static int visualHeight(View view){return Math.round(view.getHeight()*view.getScaleY());}
    void refreshBounds(){applySaved();view.setX(clampX(view.getX()));view.setY(clampY(view.getY()));positionFollower();}
    private static float clampUnit(float f) { return Float.isFinite(f) ? Math.max(0f, Math.min(1f, f)) : 0f; }

    /** Drop the hierarchy listeners when the pad is torn down so the decor view keeps no stale references. */
    void detach() {
        parent.removeOnLayoutChangeListener(parentLayout);
        view.removeOnLayoutChangeListener(viewLayout);
        view.setOnTouchListener(null);
        if (follower != null) { follower.removeOnLayoutChangeListener(followerLayout); if (beside) { follower.setOnClickListener(null); follower.setOnTouchListener(null); } }
    }
}
