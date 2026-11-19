package dev.pgrazor;

import android.content.Context;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;

/**
 * The compact panel that groups PGRazor's map overlays (launcher, history timer, encounters, VGP, gym,
 * transfer, local time, automation status) into one card.
 *
 * <p>Taps reach the rows as usual; a drag that starts anywhere on the card, rows included, moves the
 * whole card. The drag itself (threshold, clamping, saved position) is {@link OverlayDrag}'s, attached
 * to this view: once a finger travels past the touch slop the card takes the gesture from the row,
 * replays the original press into the drag and handles the rest.
 */
final class HudDock extends LinearLayout {
    private final int slop;
    private MotionEvent down;
    private OverlayDrag drag;

    HudDock(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setClickable(true);
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void setDrag(OverlayDrag drag) { this.drag = drag; }

    @Override public boolean onInterceptTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                forget();
                down = MotionEvent.obtain(e);
                return false;
            case MotionEvent.ACTION_MOVE:
                if (down == null || drag == null) return false;
                if (Math.hypot(e.getRawX() - down.getRawX(), e.getRawY() - down.getRawY()) <= slop) return false;
                drag.onTouch(this, down);  // the drag starts from where the finger first landed
                forget();
                return true;               // the row gets a cancel; the rest of the gesture moves the card
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                forget();
                return false;
        }
        return false;
    }

    private void forget() {
        if (down != null) { down.recycle(); down = null; }
    }
}
