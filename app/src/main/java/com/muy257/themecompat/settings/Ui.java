package com.muy257.themecompat.settings;

import com.muy257.themecompat.R;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * HyperOS / MIUIX design tokens and small view factories, hand-rolled so the
 * module keeps a plain-Java, dependency-free build.  Colours follow the
 * system dark mode: page #F2F3F5 with white cards by day, near-black pages
 * with elevated cards by night, HyperOS blue accent throughout.
 */
final class Ui {
    static final int ACCENT = 0xFF3482FF;

    private Ui() { }

    static boolean night(Context context) {
        return (context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    static int pageColor(Context c) { return night(c) ? 0xFF111113 : 0xFFF2F3F5; }

    static int cardColor(Context c) { return night(c) ? 0xFF1E1F22 : 0xFFFFFFFF; }

    static int textPrimary(Context c) { return night(c) ? 0xFFF2F2F4 : 0xFF181819; }

    static int textSecondary(Context c) { return night(c) ? 0xFF9B9B9F : 0xFF8A8A8E; }

    static int separatorColor(Context c) { return night(c) ? 0xFF2C2D30 : 0xFFF0F0F2; }

    static int barColor(Context c) { return night(c) ? 0xF01E1F22 : 0xF2FFFFFF; }

    static int tonalButtonColor(Context c) { return night(c) ? 0xFF2A2B2F : 0xFFEDEFF3; }

    static int trackColor(Context c) { return night(c) ? 0xFF3A3B40 : 0xFFE3E5EA; }

    static int dp(Context c, int value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable round(int color, float radiusDp, Context c) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        drawable.setCornerRadius(dp(c, (int) radiusDp));
        return drawable;
    }


    private static RippleDrawable ripple(int color, Drawable content) {
        return new RippleDrawable(new android.content.res.ColorStateList(
                new int[][]{new int[]{}}, new int[]{color}), content, null);
    }

    static RippleDrawable card(Context c) {
        return ripple(Color.argb(40, 0x88, 0x88, 0x88), round(cardColor(c), 22, c));
    }

    static RippleDrawable filledButton(Context c) {
        return ripple(Color.argb(45, 255, 255, 255), round(ACCENT, 15, c));
    }

    static RippleDrawable tonalButton(Context c) {
        return ripple(Color.argb(35, 0x10, 0x10, 0x10), round(tonalButtonColor(c), 15, c));
    }

    static TextView title(Context c, String text, float sizeSp) {
        TextView view = new TextView(c);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(textPrimary(c));
        view.setTypeface(Typeface.DEFAULT_BOLD);
        return view;
    }

    static TextView body(Context c, String text, float sizeSp, int color) {
        TextView view = new TextView(c);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp);
        view.setTextColor(color);
        view.setLineSpacing(dp(c, 3), 1f);
        return view;
    }

    /** A single MIUIX list row: optional icon, title, summary, chevron. */
    static LinearLayout row(Context c, int iconRes, String titleText, String summaryText,
                            boolean chevron, View.OnClickListener click) {
        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c, 16), dp(c, 13), dp(c, 14), dp(c, 13));
        row.setBackground(touchable(c));
        row.setClickable(click != null);
        row.setFocusable(click != null);
        if (click != null) row.setOnClickListener(click);
        if (iconRes != 0) {
            ImageView icon = new ImageView(c);
            icon.setImageResource(iconRes);
            icon.setColorFilter(textSecondary(c));
            LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22));
            iconParams.rightMargin = dp(c, 13);
            row.addView(icon, iconParams);
        }
        LinearLayout textColumn = new LinearLayout(c);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        TextView titleView = title(c, titleText, 16);
        textColumn.addView(titleView);
        if (summaryText != null && !summaryText.isEmpty()) {
            TextView summaryView = body(c, summaryText, 12.5f, textSecondary(c));
            summaryView.setPadding(0, dp(c, 2), 0, 0);
            textColumn.addView(summaryView);
        }
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(textColumn, textParams);
        if (chevron) {
            ImageView arrow = new ImageView(c);
            arrow.setImageResource(R.drawable.ic_chevron);
            arrow.setColorFilter(night(c) ? 0xFF6E6E73 : 0xFFC6C6CB);
            arrow.setTag("chevron");
            row.addView(arrow, new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));
        }
        return row;
    }

    /** Press feedback that fades in on touch and fades back out on release. MIUI's
     *  patched ripples ignore explicit radii and shrink to a small circle, so the
     *  highlight is drawn directly: full rounded-rect coverage, no ripple. */
    static Drawable touchable(Context c) {
        return new FadeHighlight(c);
    }

    private static final class FadeHighlight extends GradientDrawable {
        private final ValueAnimator animator = new ValueAnimator();

        FadeHighlight(Context c) {
            setShape(GradientDrawable.RECTANGLE);
            setCornerRadius(dp(c, 22));
            setColor(0xFF606060);
            setAlpha(0);
        }

        @Override
        protected boolean onStateChange(int[] state) {
            boolean pressed = false;
            for (int item : state) {
                if (item == android.R.attr.state_pressed) pressed = true;
            }
            animateTo(pressed ? 46 : 0, pressed ? 80 : 240);
            return true;
        }

        private void animateTo(int target, long duration) {
            animator.cancel();
            animator.setDuration(duration);
            animator.setIntValues(getAlpha(), target);
            animator.setInterpolator(new android.view.animation.DecelerateInterpolator());
            animator.removeAllUpdateListeners();
            animator.addUpdateListener(animation -> {
                setAlpha((int) animation.getAnimatedValue());
                invalidateSelf();
            });
            animator.start();
        }

        @Override
        public boolean isStateful() {
            return true;
        }
    }

    static View separator(Context c, int leftInsetDp) {
        View view = new View(c);
        view.setBackgroundColor(separatorColor(c));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(c, 1)));
        params.leftMargin = dp(c, leftInsetDp);
        view.setLayoutParams(params);
        return view;
    }

    static LinearLayout cardContainer(Context c) {
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(card(c));
        return card;
    }

    static LinearLayout.LayoutParams cardParams(Context c) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(c, 16);
        params.rightMargin = dp(c, 16);
        params.topMargin = dp(c, 10);
        return params;
    }

    static TextView sectionTitle(Context c, String text) {
        TextView view = new TextView(c);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(textSecondary(c));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = dp(c, 28);
        params.topMargin = dp(c, 18);
        params.bottomMargin = dp(c, 2);
        view.setLayoutParams(params);
        return view;
    }


    /** Sub-pages scroll under the status bar (targetSdk 35 edge-to-edge). */
    static void applyTopInset(android.app.Activity activity, View content) {
        content.setOnApplyWindowInsetsListener((view, insets) -> {
            content.setPadding(0, insets.getSystemWindowInsetTop(), 0, 0);
            return insets;
        });
    }

    /** Light status-bar icons in daylight so they stay readable. */
    @SuppressWarnings("deprecation")
    static void applyStatusBar(Activity activity) {
        View decor = activity.getWindow().getDecorView();
        int flags = decor.getSystemUiVisibility();
        if (night(activity)) flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        else flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        decor.setSystemUiVisibility(flags);
    }
    static String systemVersionLine() {
        String miuiOs = systemProperty("ro.mi.os.version.name");
        String incremental = systemProperty("ro.build.version.incremental");
        String base = !miuiOs.isEmpty() ? miuiOs : (!incremental.isEmpty() ? incremental : Build.VERSION.RELEASE);
        return "HyperOS " + base + " · Android " + Build.VERSION.RELEASE
                + " · " + Build.MODEL + " (" + Build.DEVICE + ")";
    }

    private static String systemProperty(String key) {
        try {
            Class<?> props = Class.forName("android.os.SystemProperties");
            return (String) props.getMethod("get", String.class).invoke(null, key);
        } catch (Throwable error) {
            return "";
        }
    }

    /** Interface shared by the two hand-drawn MIUIX toggle views below. */
    interface OnCheckedChange {
        void onChange(boolean checked);
    }

    /**
     * ScrollView with the MIUI rubber-band, tuned to the system
     * SpringBackLayout's published behaviour: a drag past an edge moves the
     * content 1:1 at first then resists along the miuix curve
     * (d³/3 − d² + d) × page height (at most a third of the screen), and
     * release settles through an underdamped spring (ζ=0.5, 0.4s period)
     * that visibly overshoots once before resting.  A fling that runs into
     * an edge bounces with its remaining velocity.
     */
    static ScrollView bounceScrollView(Context c) {
        return new BounceScrollView(c);
    }

    static final class BounceScrollView extends ScrollView {
        // SpringBackLayout's return spring (miuix.springback.view.d): the
        // constructor's first argument is the damping ratio and it is fed
        // 1.0 — critically damped — so the page glides back to the edge in
        // one decelerating motion and never crosses it.  Period 0.4s,
        // softened to 0.55s above 5000px/s; the step is clamped to one
        // 60Hz frame like the original integrator.
        private static final float PERIOD_SLOW = 0.4f;
        private static final float PERIOD_FAST = 0.55f;
        private static final float FAST_RELEASE_VELOCITY = 5000f;
        private static final float FLING_BOUNCE_VELOCITY = 800f;

        private static final int MODE_FREE = 0;
        private static final int MODE_OVERSCROLL = 1;
        private static final int MODE_CONTENT = 2;

        private static final boolean DEBUG = false;

        private static void dbg(String message) {
            if (DEBUG) android.util.Log.println(android.util.Log.INFO, "TCB", message);
        }

        private static String actionName(int action) {
            switch (action) {
                case android.view.MotionEvent.ACTION_DOWN: return "DOWN";
                case android.view.MotionEvent.ACTION_UP: return "UP";
                case android.view.MotionEvent.ACTION_MOVE: return "MOVE";
                case android.view.MotionEvent.ACTION_CANCEL: return "CANCEL";
                case android.view.MotionEvent.ACTION_POINTER_DOWN: return "PTR_DOWN";
                case android.view.MotionEvent.ACTION_POINTER_UP: return "PTR_UP";
                default: return "A" + action;
            }
        }

        private float translation;
        private float dragDistance;
        private int overscrollSide;
        private int touchMode;
        private boolean sawDown;
        private int contentStartY;
        private boolean springing;
        private float springX;
        private float springStart;
        private float springV;
        private float springV0;
        private float springK;
        private float springC;
        private long springLast;
        private float lastY;
        private float interceptDownY;
        private boolean selfTakeover;
        private long lastEventTime;
        private float releaseVelocity;
        private boolean flingTracking;
        private float flingLastY;
        private long flingLastTime;
        private float flingVelocity;

        BounceScrollView(Context c) {
            super(c);
            setOverScrollMode(View.OVER_SCROLL_NEVER);
            setFillViewport(true);
        }

        private void applyTranslation() {
            View content = getChildAt(0);
            if (content != null) content.setTranslationY(translation);
        }

        /** The miuix resistance curve: 1:1 near rest, asymptote at ⅓ page. */
        private float dampingDistance(float drag) {
            float range = Math.max(getHeight(), 1);
            float d = Math.min(Math.abs(drag) / range, 1f);
            float distance = (d * d * d / 3f - d * d + d) * range;
            return drag < 0 ? -distance : distance;
        }

        /** Inverts the resistance curve so a caught bounce resumes from its pose. */
        private float inverseDamping(float distance) {
            float range = Math.max(getHeight(), 1);
            float target = Math.min(Math.abs(distance) / range, 1f / 3f);
            float lo = 0f, hi = 1f;
            for (int i = 0; i < 24; i++) {
                float mid = (lo + hi) / 2f;
                float value = mid * mid * mid / 3f - mid * mid + mid;
                if (value < target) lo = mid; else hi = mid;
            }
            float d = (lo + hi) / 2f;
            return distance < 0 ? -d * range : d * range;
        }

        private int scrollRange() {
            return Math.max(0, computeVerticalScrollRange() - getHeight());
        }

        private void stopSpring() {
            springing = false;
        }

        /** Starts the critically damped return (semi-implicit Euler, frame-driven). */
        private void startSpring(float from, float velocity) {
            float period = Math.abs(velocity) > FAST_RELEASE_VELOCITY ? PERIOD_FAST : PERIOD_SLOW;
            float omega = (float) (Math.PI * 2.0 / period);
            springK = omega * omega;
            springC = 2f * omega;
            springX = from;
            springStart = from;
            springV = velocity;
            springV0 = velocity;
            springing = true;
            springLast = android.os.SystemClock.uptimeMillis();
            postOnAnimation(springTick);
        }

        private final Runnable springTick = new Runnable() {
            @Override
            public void run() {
                if (!springing) return;
                long now = android.os.SystemClock.uptimeMillis();
                float dt = (now - springLast) / 1000f;
                springLast = now;
                if (dt <= 0f || dt > 0.016f) dt = 0.016f;
                springV += (-springC * springV - springK * springX) * dt;
                springX += springV * dt;
                // SpringBackLayout settles the moment the edge is reached
                // (within 1px) or crossed — snap there.  Semi-implicit Euler
                // rings around rest at this step size, so the crossing test
                // is what kills the oscillation: for a bounce launched from
                // rest at the edge (fling clamp) the launch velocity's sign
                // is the reference, otherwise the start position's.
                boolean crossed = (springStart > 0f && springX < 0f)
                        || (springStart < 0f && springX > 0f)
                        || (springStart == 0f && springV0 * springX < 0f);
                if (crossed || Math.abs(springX) < 1f) {
                    springing = false;
                    translation = 0f;
                    applyTranslation();
                    return;
                }
                translation = springX;
                applyTranslation();
                postOnAnimation(this);
            }
        };

        @Override
        public boolean onInterceptTouchEvent(android.view.MotionEvent event) {
            boolean intercept = super.onInterceptTouchEvent(event);
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                interceptDownY = event.getY();
                selfTakeover = false;
            } else if (action == android.view.MotionEvent.ACTION_MOVE && !intercept) {
                // When a clickable child consumed the DOWN, ScrollView's own
                // slop check never arms — its pointer bookkeeping never saw a
                // DOWN either, so a drag starting on a row would never scroll.
                // Take the vertical drag over at the standard slop instead.
                float slop = android.view.ViewConfiguration.get(getContext())
                        .getScaledTouchSlop();
                if (Math.abs(event.getY() - interceptDownY) > slop) {
                    intercept = true;
                    selfTakeover = true;
                    dbg("TAKEOVER at y=" + (int) event.getY());
                }
            }
            dbg("INTERCEPT " + actionName(action)
                    + " y=" + (int) event.getY() + " -> " + intercept);
            return intercept;
        }

        @Override
        public boolean onTouchEvent(android.view.MotionEvent event) {
            float y = event.getY();
            dbg(actionName(event.getActionMasked())
                    + " y=" + (int) y + " lastY=" + (int) lastY
                    + " mode=" + touchMode + " sawDown=" + sawDown
                    + " scrollY=" + getScrollY() + " range=" + scrollRange()
                    + " tr=" + (int) translation);
            switch (event.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN: {
                    // Grabbing the page stops any bounce in flight but keeps
                    // its pose, like SpringBackLayout halting its scroller:
                    // the drag continues from where the page sits.
                    stopSpring();
                    sawDown = true;
                    releaseVelocity = 0f;
                    lastEventTime = event.getEventTime();
                    lastY = y;
                    if (translation != 0f) {
                        touchMode = MODE_OVERSCROLL;
                        // The edge the page is resting on decides the side,
                        // not the translation's sign (a caught bounce can be
                        // mid-swing).
                        if (getScrollY() <= 0) overscrollSide = 1;
                        else if (getScrollY() >= scrollRange()) overscrollSide = -1;
                        else overscrollSide = translation > 0f ? 1 : -1;
                        dragDistance = inverseDamping(translation);
                    } else {
                        touchMode = MODE_FREE;
                        dragDistance = 0;
                    }
                    return super.onTouchEvent(event);
                }
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL: {
                    lastY = y;
                    sawDown = false;
                    if (touchMode == MODE_OVERSCROLL) {
                        touchMode = MODE_FREE;
                        // SpringBackLayout springs back with no carry-over
                        // velocity on a plain release.
                        startSpring(translation, 0f);
                        return true;
                    }
                    if (touchMode == MODE_CONTENT) {
                        touchMode = MODE_FREE;
                        int velocity = (int) -releaseVelocity;
                        if (Math.abs(velocity) > android.view.ViewConfiguration
                                .get(getContext()).getScaledMinimumFlingVelocity()) {
                            fling(velocity);
                        }
                        return true;
                    }
                    return super.onTouchEvent(event);
                }
                case android.view.MotionEvent.ACTION_MOVE:
                    break;
                default:
                    return super.onTouchEvent(event);
            }
            if (!sawDown) {
                // A clickable child consumed the DOWN and the ScrollView
                // intercepted the drag mid-gesture, so this stream arrives
                // without a DOWN.  Rebuild the per-gesture state from the
                // current pose; otherwise lastY is stale and the first
                // frame's travel teleports the page.
                sawDown = true;
                lastY = y;
                lastEventTime = event.getEventTime();
                releaseVelocity = 0f;
                stopSpring();
                if (translation != 0f) {
                    touchMode = MODE_OVERSCROLL;
                    if (getScrollY() <= 0) overscrollSide = 1;
                    else if (getScrollY() >= scrollRange()) overscrollSide = -1;
                    else overscrollSide = translation > 0f ? 1 : -1;
                    dragDistance = inverseDamping(translation);
                    return true;
                }
                if (selfTakeover) {
                    // The drag was taken over from a clickable child: scroll
                    // the content here, where the per-frame state is ours,
                    // instead of through ScrollView's stale drag state.
                    touchMode = MODE_CONTENT;
                    contentStartY = getScrollY();
                    dragDistance = 0;
                    return true;
                }
                touchMode = MODE_FREE;
                dragDistance = 0;
                return super.onTouchEvent(event);
            }
            float fingerDown = y - lastY;
            long eventTime = event.getEventTime();
            float dtMs = eventTime - lastEventTime;
            lastEventTime = eventTime;
            lastY = y;
            if (dtMs > 0) {
                releaseVelocity = 0.7f * releaseVelocity
                        + 0.3f * (fingerDown / dtMs * 1000f);
            }
            if (touchMode == MODE_OVERSCROLL) {
                dragDistance += fingerDown;
                if (dragDistance * overscrollSide < 0) {
                    // Dragged back past the edge: the leftover travel becomes
                    // plain content scrolling, still owned here so the page
                    // follows the finger without a jump.
                    touchMode = MODE_CONTENT;
                    contentStartY = getScrollY();
                    translation = 0f;
                    applyTranslation();
                } else {
                    translation = dampingDistance(dragDistance);
                    applyTranslation();
                    return true;
                }
            } else if (touchMode == MODE_CONTENT) {
                dragDistance += fingerDown;
            }
            if (touchMode == MODE_CONTENT) {
                int target = contentStartY - (int) dragDistance;
                int range = scrollRange();
                if (target < 0) {
                    overscrollSide = 1;
                    touchMode = MODE_OVERSCROLL;
                    dragDistance = -target;
                    translation = dampingDistance(dragDistance);
                    applyTranslation();
                    scrollTo(0, 0);
                } else if (target > range) {
                    overscrollSide = -1;
                    touchMode = MODE_OVERSCROLL;
                    dragDistance = -(target - range);
                    translation = dampingDistance(dragDistance);
                    applyTranslation();
                    scrollTo(0, range);
                } else {
                    scrollTo(0, target);
                }
                return true;
            }
            boolean handled = super.onTouchEvent(event);
            int after = getScrollY();
            if (after <= 0 && fingerDown > 0) {
                touchMode = MODE_OVERSCROLL;
                overscrollSide = 1;
                dragDistance = fingerDown;
                translation = dampingDistance(dragDistance);
                applyTranslation();
            } else if (after >= scrollRange() && fingerDown < 0) {
                touchMode = MODE_OVERSCROLL;
                overscrollSide = -1;
                dragDistance = fingerDown;
                translation = dampingDistance(dragDistance);
                applyTranslation();
            }
            return handled;
        }

        @Override
        public void fling(int velocityY) {
            flingTracking = velocityY != 0;
            flingVelocity = velocityY;
            flingLastY = getScrollY();
            flingLastTime = android.os.SystemClock.uptimeMillis();
            super.fling(velocityY);
        }

        @Override
        public void computeScroll() {
            super.computeScroll();
            if (!flingTracking) return;
            int y = getScrollY();
            float delta = y - flingLastY;
            if (delta != 0) {
                long now = android.os.SystemClock.uptimeMillis();
                float dt = Math.max(now - flingLastTime, 1);
                flingVelocity = delta * 1000f / dt;
                flingLastY = y;
                flingLastTime = now;
                return;
            }
            // The fling stopped advancing: either it finished or it clamped
            // against an edge with velocity still left, which bounces.
            flingTracking = false;
            if (Math.abs(flingVelocity) < FLING_BOUNCE_VELOCITY) return;
            if (y <= 0 && flingVelocity < 0) {
                // The bounce continues the fling's motion: past the top the
                // content travels down, so the launch velocity flips sign.
                startSpring(0, -flingVelocity);
            } else if (y >= scrollRange() && flingVelocity > 0) {
                startSpring(0, -flingVelocity);
            }
        }
    }

    /**
     * MIUIX-style switch, drawn to the miuix library's published metrics:
     * a 49×28dp capsule track and a 20dp white thumb resting 4dp from the
     * start edge and travelling to 25dp when checked.  Checked track is the
     * HyperOS blue, unchecked the theme's secondary grey; the thumb glides
     * with a short animation like the system control.
     */
    static final class MiuixSwitch extends View {
        private static final int CHECKED_TRACK_LIGHT = 0xFF3482FF;
        private static final int CHECKED_TRACK_DARK = 0xFF277AF7;
        // The real bar colours from miuix_appcompat_sliding_button_bar_off_*:
        // 10% black over light pages, 20% white over dark ones.
        private static final int UNCHECKED_TRACK_LIGHT = 0x1A000000;
        private static final int UNCHECKED_TRACK_DARK = 0x33FFFFFF;

        private final int checkedTrack;
        private final int uncheckedTrack;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final ValueAnimator animator;
        private OnCheckedChange listener;
        private boolean checked;
        private float progress;

        MiuixSwitch(Context c) {
            super(c);
            setClickable(true);
            boolean dark = night(c);
            checkedTrack = dark ? CHECKED_TRACK_DARK : CHECKED_TRACK_LIGHT;
            uncheckedTrack = dark ? UNCHECKED_TRACK_DARK : UNCHECKED_TRACK_LIGHT;
            animator = ValueAnimator.ofFloat(0f, 1f);
            animator.setDuration(150);
            animator.addUpdateListener(animation -> {
                progress = (float) animation.getAnimatedValue();
                invalidate();
            });
        }

        void setChecked(boolean value) {
            if (checked == value) return;
            checked = value;
            animator.cancel();
            animator.setFloatValues(progress, checked ? 1f : 0f);
            animator.start();
        }

        boolean isChecked() {
            return checked;
        }

        void setOnCheckedChange(OnCheckedChange listener) {
            this.listener = listener;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(dp(getContext(), 49), dp(getContext(), 28));
        }

        @Override
        public boolean performClick() {
            setChecked(!checked);
            if (listener != null) listener.onChange(checked);
            return super.performClick();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float radius = getHeight() / 2f;
            paint.setColor(lerpColor(uncheckedTrack, checkedTrack, progress));
            canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, paint);
            float thumb = dp(getContext(), 20);
            float travel = dp(getContext(), 25) - dp(getContext(), 4);
            paint.setColor(0xFFFFFFFF);
            canvas.drawCircle(dp(getContext(), 4) + thumb / 2 + travel * progress,
                    radius, thumb / 2, paint);
        }
    }

    /**
     * MIUIX-style checkbox built to the widget the device itself ships
     * (securitycenter's miuix_appcompat_btn_checkbox_* vectors): a 22dp
     * circle, theme blue with a white check when on, theme grey when off.
     * The mark is the miuix path M5,9.4 L10.3,14.9 L17.9,5.1 trimmed to
     * 0.186..0.803 — the segment miuix actually draws, which is what keeps
     * it centred; stroke 2/23 of the size with round caps.  A partial
     * selection draws a same-style centred dash for the select-all control.
     */
    static final class MiuixCheckBox extends View {
        static final int STATE_OFF = 0;
        static final int STATE_ON = 1;
        static final int STATE_PARTIAL = 2;

        private static final int CHECKED_LIGHT = 0xFF3482FF;
        private static final int CHECKED_DARK = 0xFF277AF7;
        private static final int UNCHECKED_LIGHT = 0xFFE6E6E6;
        private static final int UNCHECKED_DARK = 0xFF505050;

        private final int checkedColor;
        private final int uncheckedColor;
        private final android.graphics.Paint paint = new android.graphics.Paint(
                android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Path mark = new android.graphics.Path();
        private OnCheckedChange listener;
        private int state = STATE_OFF;

        MiuixCheckBox(Context c) {
            super(c);
            setClickable(true);
            boolean dark = night(c);
            checkedColor = dark ? CHECKED_DARK : CHECKED_LIGHT;
            uncheckedColor = dark ? UNCHECKED_DARK : UNCHECKED_LIGHT;
        }

        void setState(int value) {
            if (state == value) return;
            state = value;
            invalidate();
        }

        int getState() {
            return state;
        }

        void setOnCheckedChange(OnCheckedChange listener) {
            this.listener = listener;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            setMeasuredDimension(dp(getContext(), 22), dp(getContext(), 22));
        }

        @Override
        public boolean performClick() {
            boolean checked = state != STATE_ON;
            setState(checked ? STATE_ON : STATE_OFF);
            if (listener != null) listener.onChange(checked);
            return super.performClick();
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            float size = getWidth();
            paint.setColor(state == STATE_OFF ? uncheckedColor : checkedColor);
            canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint);
            if (state == STATE_OFF) return;
            float scale = size / 23f;
            float stroke = 2f * scale;
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(stroke);
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
            paint.setColor(0xFFFFFFFF);
            mark.reset();
            if (state == STATE_PARTIAL) {
                mark.moveTo(7.6f * scale, 11.5f * scale);
                mark.lineTo(15.4f * scale, 11.5f * scale);
            } else {
                mark.moveTo(7.59f * scale, 12.09f * scale);
                mark.lineTo(10.3f * scale, 14.9f * scale);
                mark.lineTo(15.48f * scale, 8.22f * scale);
            }
            canvas.drawPath(mark, paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
        }
    }

    private static int lerpColor(int from, int to, float progress) {
        int a = (int) (Color.alpha(from) + (Color.alpha(to) - Color.alpha(from)) * progress);
        int r = (int) (Color.red(from) + (Color.red(to) - Color.red(from)) * progress);
        int g = (int) (Color.green(from) + (Color.green(to) - Color.green(from)) * progress);
        int b = (int) (Color.blue(from) + (Color.blue(to) - Color.blue(from)) * progress);
        return Color.argb(a, r, g, b);
    }
}
