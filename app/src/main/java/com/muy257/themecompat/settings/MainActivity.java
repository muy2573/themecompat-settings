package com.muy257.themecompat.settings;

import com.muy257.themecompat.R;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * MIUIX-styled shell: three pages (修补 / Hook / 更多) behind an iOS-style
 * floating capsule bottom bar.  Page content scrolls beneath the bar.
 */
public final class MainActivity extends Activity {
    private static final int REQUEST_OPEN_MTZ = 1001;
    private static final int REQUEST_CREATE_MTZ = 1002;
    private static final int TAB_PATCH = 0;
    private static final int TAB_HOOK = 1;
    private static final int TAB_MORE = 2;
    private static final String[] TAB_TITLES = {"修补", "Hook", "更多"};
    private static final int[] TAB_ICONS = {
            R.drawable.ic_patch, R.drawable.ic_hook, R.drawable.ic_more};

    private FrameLayout pageContainer;
    private final View[] pages = new View[3];
    private final View[] tabItems = new View[3];
    private int currentTab = -1;

    // Floating glass capsule bottom bar (iOS 26-style, drag-to-switch).
    private FrameLayout barCapsule;
    private View pill;
    private LayerDrawable glassLayers;
    private ValueAnimator pressAnimator;
    private Runnable pendingGrow;
    private int growGen;
    private float pillPressScale = 1f;
    private int itemWidth;
    private int barHeight;
    private int capsulePad;

    // Damped-spring follower (Kyant0's DampedDragAnimation idea): the pill chases
    // the finger instead of sticking to it, which is what makes the bar feel viscous.
    private static final float SPRING_STIFFNESS = 260f;
    private static final float SPRING_DAMPING = 24f;
    private float pillX;
    private float pillV;
    private float pillTarget;
    private boolean springRunning;
    private long springLastNanos;
    private final Choreographer.FrameCallback springTick = new Choreographer.FrameCallback() {
        @Override
        public void doFrame(long frameTimeNanos) {
            if (!springRunning) return;
            if (springLastNanos != 0) {
                float dt = Math.min((frameTimeNanos - springLastNanos) / 1_000_000_000f, 0.032f);
                float accel = -SPRING_STIFFNESS * (pillX - pillTarget) - SPRING_DAMPING * pillV;
                pillV += accel * dt;
                pillX += pillV * dt;
                pill.setTranslationX(pillX);
                if (Math.abs(pillX - pillTarget) < 0.4f && Math.abs(pillV) < 12f) {
                    pillX = pillTarget;
                    pillV = 0f;
                    springRunning = false;
                    applyPillTransform(0f);
                    return;
                }
                float squash = Math.max(-0.16f, Math.min(0.16f, pillV / 10000f));
                applyPillTransform(squash);
            }
            springLastNanos = frameTimeNanos;
            Choreographer.getInstance().postFrameCallback(springTick);
        }
    };

    // Patch page state (kept here: it owns the SAF flows and patch thread).
    private Uri sourceUri;
    private String sourceName;
    private TextView selectedView;
    private Button patchButton;
    private ProgressBar progressBar;
    private TextView statusView;
    private LinearLayout reportSection;
    private TextView reportView;
    private TextView patchLogView;
    private ScrollView patchLogScroll;
    private final StringBuilder patchLog = new StringBuilder();
    private boolean rootAvailable;

    @Override
    public void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        // The swollen pill must be able to draw outside the bar capsule; clipChildren
        // is consulted on every ancestor, so it has to be off here too.
        root.setClipChildren(false);
        root.setBackgroundColor(Ui.pageColor(this));
        pageContainer = new FrameLayout(this);
        root.addView(pageContainer, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(buildBottomBar(), bottomBarParams());
        applyWindowInsets(root);
        setContentView(root);
        Ui.applyStatusBar(this);
        selectTab(TAB_PATCH);
        appendLog("就绪");
        checkRootInBackground();
    }

    private FrameLayout.LayoutParams bottomBarParams() {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(
                Ui.dp(this, 268), barHeight,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        params.bottomMargin = Ui.dp(this, 18);
        return params;
    }

    private void applyWindowInsets(View root) {
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            int top = insets.getSystemWindowInsetTop();
            int bottom = insets.getSystemWindowInsetBottom();
            pageContainer.setPadding(0, top, 0, 0);
            View bar = view.findViewWithTag("bottom_bar");
            if (bar != null) {
                bar.setTranslationY(-bottom);
            }
            return insets.consumeSystemWindowInsets();
        });
        root.setFitsSystemWindows(true);
    }

    // ------------------------------------------------------------------
    // Floating bottom bar: translucent glass capsule with a sliding
    // selection pill; collapses to an icon-only inline pill while the
    // page scrolls down (iOS 26 expanded/inline behaviour).
    // ------------------------------------------------------------------

    /** Full-bounds press feedback for the whole capsule; also hosts the drag-to-switch
     *  gesture ported from Kyant0's LiquidBottomTabs: the pill is a damped spring that
     *  chases the finger, squashes with its own velocity, and snaps to the nearest slot
     *  on release. */
    private final class BarCapsule extends FrameLayout {
        private float downX;
        private float downY;
        private int hapticSlot;
        private boolean dragging;

        BarCapsule(android.content.Context context) { super(context); }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = event.getX();
                    downY = event.getY();
                    dragging = false;
                    return false;
                case MotionEvent.ACTION_MOVE:
                    float dx = event.getX() - downX;
                    float dy = event.getY() - downY;
                    if (!dragging && Math.abs(dx) > ViewConfiguration.get(getContext())
                            .getScaledTouchSlop() && Math.abs(dx) > Math.abs(dy)) {
                        dragging = true;
                        hapticSlot = currentTab;
                        // Fresh generation so the child's CANCEL (which no longer
                        // cancels) cannot race this swell away; the pill always
                        // swells shortly after a drag starts.
                        growGen++;
                        scheduleGrow(100);
                        requestDisallowInterceptTouchEvent(true);
                    }
                    return dragging;
                default:
                    return dragging;
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    if (!dragging) return false;
                    // Always chase the finger itself; the spring lag is the stickiness
                    // and the pill can never get stranded away from the finger.
                    float raw = event.getX() - capsulePad - itemWidth / 2f;
                    pillTarget = Math.max(0f, Math.min(itemWidth * 2, raw));
                    startSpring();
                    // Tick when the pill itself crosses a slot boundary, not the finger.
                    int slot = Math.round(pillX / itemWidth);
                    if (slot != hapticSlot) {
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                        hapticSlot = slot;
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    if (!dragging) return false;
                    dragging = false;
                    growGen++;
                    cancelPendingGrow();
                    growPill(false);
                    int nearest = Math.max(0, Math.min(2,
                            Math.round(pillTarget / itemWidth)));
                    selectTab(nearest);
                    return true;
                }
                default:
                    return super.onTouchEvent(event);
            }
        }
    }

    private View buildBottomBar() {
        barHeight = Ui.dp(this, 62);
        itemWidth = Ui.dp(this, 84);
        int pad = Ui.dp(this, 8);
        capsulePad = pad;
        int capsuleRadius = Ui.dp(this, 31);

        barCapsule = new BarCapsule(this);
        barCapsule.setTag("bottom_bar");
        barCapsule.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View view, android.graphics.Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(),
                        view.getHeight() / 2f);
            }
        });
        // The pill must be free to swell past the capsule edge when pressed
        // (Kyant0's LiquidBottomTabs grows 78dp inside a 56dp bar), so no clipping.
        // clipToPadding matters just as much: it silently clips children to the
        // padded box, which squared off the swollen pill at the edges.
        barCapsule.setClipChildren(false);
        barCapsule.setClipToOutline(false);
        barCapsule.setClipToPadding(false);
        barCapsule.setElevation(Ui.dp(this, 8));
        barCapsule.setPadding(pad, Ui.dp(this, 4), pad, Ui.dp(this, 4));
        barCapsule.setBackground(glassBackground(capsuleRadius));

        pill = new View(this);
        pill.setBackground(Ui.round(Ui.night(this) ? 0x38FFFFFF : 0xFFECECF1,
                Ui.dp(this, 27), this));
        FrameLayout.LayoutParams pillParams = new FrameLayout.LayoutParams(
                itemWidth, ViewGroup.LayoutParams.MATCH_PARENT,
                Gravity.START | Gravity.CENTER_VERTICAL);
        pill.setLayoutParams(pillParams);
        barCapsule.addView(pill);

        LinearLayout items = new LinearLayout(this);
        items.setOrientation(LinearLayout.HORIZONTAL);
        for (int index = 0; index < 3; index++) {
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            ImageView icon = new ImageView(this);
            icon.setImageResource(TAB_ICONS[index]);
            icon.setColorFilter(Ui.textSecondary(this));
            item.addView(icon, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView label = new TextView(this);
            label.setText(TAB_TITLES[index]);
            label.setTextSize(10.5f);
            label.setTextColor(Ui.textSecondary(this));
            label.setPadding(0, Ui.dp(this, 2), 0, 0);
            label.setTag("tab_label");
            item.addView(label, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            // No press highlight here: the pill is the only capsule on the bar.
            item.setClickable(true);
            item.setFocusable(true);
            item.setOnTouchListener((view, event) -> {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    // Slide to the finger's exact position first (not the slot centre);
                    // swell only after arrival. Release recentres on the nearest tab.
                    growGen++;
                    int tabIndex = (Integer) view.getTag();
                    float finger = Math.max(0f, Math.min(itemWidth * 2,
                            tabIndex * itemWidth + event.getX() - itemWidth / 2f));
                    selectTab(tabIndex, finger);
                    scheduleGrow(130);
                } else if (action == MotionEvent.ACTION_UP) {
                    growGen++;
                    cancelPendingGrow();
                    growPill(false);
                } else if (action == MotionEvent.ACTION_CANCEL) {
                    // Our own intercept (drag starting) reschedules the swell itself,
                    // so cancelling here would kill it. If some other component stole
                    // the gesture, relax soon; the guard skips while a drag is live.
                    barCapsule.postDelayed(() -> {
                        if (!(barCapsule instanceof BarCapsule)
                                || !((BarCapsule) barCapsule).dragging) {
                            growGen++;
                            cancelPendingGrow();
                            growPill(false);
                        }
                    }, 600);
                }
                return false;
            });
            item.setOnClickListener(view -> selectTab((Integer) view.getTag()));
            item.setTag(index);
            items.addView(item, new LinearLayout.LayoutParams(itemWidth,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            tabItems[index] = item;
        }
        barCapsule.addView(items, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return barCapsule;
    }

    /** Liquid-glass backdrop: translucent fill + top sheen + hairline stroke. */
    private Drawable glassBackground(int radiusDp) {
        boolean night = Ui.night(this);
        GradientDrawable fill = Ui.round(night ? 0xC71B1C20 : 0xCDFFFFFF, radiusDp, this);
        GradientDrawable sheen = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x30FFFFFF, 0x00FFFFFF});
        sheen.setCornerRadius(Ui.dp(this, radiusDp));
        GradientDrawable stroke = new GradientDrawable();
        stroke.setColor(Color.TRANSPARENT);
        stroke.setCornerRadius(Ui.dp(this, radiusDp));
        stroke.setStroke(Math.max(1, Ui.dp(this, 1)), night ? 0x24FFFFFF : 0x5CFFFFFF);
        glassLayers = new LayerDrawable(new Drawable[]{fill, sheen, stroke});
        return glassLayers;
    }

    private void setGlassRadius(float radiusPx) {
        if (glassLayers == null) return;
        for (int index = 0; index < glassLayers.getNumberOfLayers(); index++) {
            Drawable layer = glassLayers.getDrawable(index);
            if (layer instanceof GradientDrawable) {
                ((GradientDrawable) layer).setCornerRadius(radiusPx);
            }
        }
    }

    private void styleTabs() {
        for (int index = 0; index < tabItems.length; index++) {
            LinearLayout item = (LinearLayout) tabItems[index];
            boolean active = index == currentTab;
            int color = active ? Ui.ACCENT : Ui.textSecondary(this);
            ImageView icon = (ImageView) item.getChildAt(0);
            icon.setColorFilter(color);
            TextView label = (TextView) item.getChildAt(1);
            label.setTextColor(color);
            label.setTypeface(active
                    ? Typeface.DEFAULT_BOLD
                    : Typeface.DEFAULT);
        }
    }

    private void setSpringTarget(float target) {
        pillTarget = target;
        if (!springRunning) {
            pillX = pill.getTranslationX();
            pillV = 0f;
        }
        startSpring();
    }

    private void startSpring() {
        if (springRunning) return;
        springRunning = true;
        springLastNanos = 0;
        Choreographer.getInstance().postFrameCallback(springTick);
    }

    private void cancelPendingGrow() {
        if (pendingGrow != null && barCapsule != null) {
            barCapsule.removeCallbacks(pendingGrow);
            pendingGrow = null;
        }
    }

    /** Gen-tagged so a late swell from a superseded gesture never fires. */
    private void scheduleGrow(long delay) {
        cancelPendingGrow();
        final int gen = growGen;
        pendingGrow = () -> {
            if (gen != growGen) return;
            pendingGrow = null;
            growPill(true);
        };
        barCapsule.postDelayed(pendingGrow, delay);
    }

    /** Kyant0-style press swell: the pill grows ~1.25x and just clears the
     *  capsule edge, then relaxes back on release. */
    private void growPill(boolean grow) {
        if (pressAnimator != null) pressAnimator.cancel();
        pressAnimator = ValueAnimator.ofFloat(pillPressScale, grow ? 1.25f : 1f);
        pressAnimator.setDuration(grow ? 140 : 220);
        pressAnimator.setInterpolator(new DecelerateInterpolator());
        pressAnimator.addUpdateListener(animation -> {
            pillPressScale = (float) animation.getAnimatedValue();
            if (barCapsule instanceof BarCapsule && !((BarCapsule) barCapsule).dragging) {
                applyPillTransform(0f);
            }
        });
        pressAnimator.start();
    }

    private void applyPillTransform(float squash) {
        pill.setScaleX(pillPressScale * (1f + squash));
        pill.setScaleY(pillPressScale * (1f - squash * 0.4f));
    }

    private void selectTab(int tab) {
        selectTab(tab, tab * itemWidth);
    }

    /** tab 为切换目标；targetPx 允许药丸先停在手指实际点击处而不是槽位中心。 */
    private void selectTab(int tab, float targetPx) {
        if (tab == currentTab) {
            setSpringTarget(targetPx);
            return;
        }
        currentTab = tab;
        if (pages[tab] == null) {
            View page = tab == TAB_PATCH ? buildPatchPage()
                    : tab == TAB_HOOK ? buildHookPage() : buildMorePage();
            pages[tab] = page;
            pageContainer.addView(page, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        for (int index = 0; index < pages.length; index++) {
            if (pages[index] != null) {
                pages[index].setVisibility(index == tab ? View.VISIBLE : View.GONE);
            }
        }
        styleTabs();
        setSpringTarget(targetPx);
    }

    // ------------------------------------------------------------------
    // 修补 page
    // ------------------------------------------------------------------

    private View buildPatchPage() {
        ScrollView scroll = Ui.bounceScrollView(this);
        LinearLayout page = column(this);
        int bottomPad = Ui.dp(this, 120);
        page.setPadding(0, Ui.dp(this, 14), 0, bottomPad);

        TextView title = Ui.title(this, "weeazn 澎湃3主题适配", 26);
        title.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), 0);
        page.addView(title);

        LinearLayout patchCard = Ui.cardContainer(this);
        LinearLayout patchBody = new LinearLayout(this);
        patchBody.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dp(this, 18);
        patchBody.setPadding(pad, pad, pad, pad);
        patchCard.addView(patchBody);

        TextView cardTitle = Ui.title(this, "主题包修补", 17);
        patchBody.addView(cardTitle);
        selectedView = Ui.body(this, "尚未选择主题包", 13, Ui.textSecondary(this));
        selectedView.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 12));
        patchBody.addView(selectedView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button selectButton = new Button(this);
        selectButton.setAllCaps(false);
        selectButton.setText("选择");
        selectButton.setTextSize(15);
        selectButton.setTextColor(Ui.textPrimary(this));
        selectButton.setBackground(Ui.tonalButton(this));
        selectButton.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        selectButton.setOnClickListener(view -> chooseInput());
        LinearLayout.LayoutParams selectParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        selectParams.rightMargin = Ui.dp(this, 10);
        buttons.addView(selectButton, selectParams);

        patchButton = new Button(this);
        patchButton.setAllCaps(false);
        patchButton.setText("修补");
        patchButton.setTextSize(15);
        patchButton.setTextColor(0xFFFFFFFF);
        patchButton.setBackground(Ui.filledButton(this));
        patchButton.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 12));
        patchButton.setEnabled(false);
        patchButton.setAlpha(0.45f);
        patchButton.setOnClickListener(view -> chooseOutput());
        buttons.addView(patchButton, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        patchBody.addView(buttons, fullWidth());

        progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        // Space is always reserved (INVISIBLE, not GONE): a disappearing bar
        // would resize the card and make the log box below jump around.
        progressBar.setVisibility(View.INVISIBLE);
        progressBar.setIndeterminate(true);
        progressBar.getProgressDrawable().setColorFilter(Ui.ACCENT,
                android.graphics.PorterDuff.Mode.SRC_IN);
        LinearLayout.LayoutParams barParams = fullWidth();
        barParams.topMargin = Ui.dp(this, 14);
        progressBar.setLayoutParams(barParams);
        patchBody.addView(progressBar);

        statusView = Ui.body(this, "就绪", 13, Ui.textSecondary(this));
        statusView.setPadding(0, Ui.dp(this, 12), 0, 0);
        // Owner-pinned five-line slot: patch status lines vary in length, and
        // a resizing card shoves the log viewport around while patching.
        statusView.setMinLines(5);
        statusView.setMaxLines(5);
        statusView.setEllipsize(TextUtils.TruncateAt.END);
        patchBody.addView(statusView);
        page.addView(patchCard, Ui.cardParams(this));

        LinearLayout logCard = Ui.cardContainer(this);
        patchLogView = new TextView(this);
        patchLogView.setTextSize(11.5f);
        patchLogView.setTextColor(Ui.textSecondary(this));
        patchLogView.setTypeface(android.graphics.Typeface.MONOSPACE);
        // Not selectable: selectable text swallows drag gestures, and then the
        // inner scroll never receives them — the outer page scrolled instead.
        patchLogView.setTextIsSelectable(false);
        int logPad = Ui.dp(this, 14);
        patchLogView.setPadding(logPad, logPad, logPad, logPad);
        patchLogScroll = Ui.bounceScrollView(this);
        patchLogScroll.setFillViewport(true);
        patchLogScroll.addView(patchLogView, fullWidth());
        patchLogScroll.setOnTouchListener((view, event) -> {
            view.getParent().requestDisallowInterceptTouchEvent(true);
            return false;
        });
        logCard.addView(patchLogScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(this, 190)));
        page.addView(logCard, Ui.cardParams(this));

        reportSection = new LinearLayout(this);
        reportSection.setOrientation(LinearLayout.VERTICAL);
        reportSection.addView(Ui.sectionTitle(this, "本次修补摘要"));
        LinearLayout reportCard = Ui.cardContainer(this);
        reportView = Ui.body(this, "", 13, Ui.textPrimary(this));
        int reportPad = Ui.dp(this, 16);
        reportView.setPadding(reportPad, reportPad, reportPad, reportPad);
        reportCard.addView(reportView, fullWidth());
        reportSection.addView(reportCard, Ui.cardParams(this));
        reportSection.setVisibility(View.GONE);
        page.addView(reportSection);
        scroll.addView(page);
        return scroll;
    }

    private LinearLayout column(Activity activity) {
        LinearLayout column = new LinearLayout(activity);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    // ------------------------------------------------------------------
    // Hook page
    // ------------------------------------------------------------------

    private View buildHookPage() {
        ScrollView scroll = Ui.bounceScrollView(this);
        LinearLayout page = column(this);
        page.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 120));

        TextView title = Ui.title(this, "Hook", 26);
        title.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), Ui.dp(this, 2));
        page.addView(title);
        TextView status = Ui.body(this, ThemeCompatApplication.scopeStatusLine(), 13,
                Ui.textSecondary(this));
        status.setPadding(Ui.dp(this, 22), Ui.dp(this, 4), Ui.dp(this, 22), Ui.dp(this, 2));
        page.addView(status);

        LinearLayout appsCard = Ui.cardContainer(this);
        appsCard.addView(Ui.row(this, R.drawable.ic_hook, "Hook 软件", null, true,
                view -> startActivity(new Intent(this, HookAppsActivity.class))));
        page.addView(appsCard, Ui.cardParams(this));

        LinearLayout tuneCard = Ui.cardContainer(this);
        tuneCard.addView(Ui.row(this, R.drawable.ic_tune, "卡片透明度调节", null, true,
                view -> startActivity(new Intent(this, AlphaActivity.class))));
        page.addView(tuneCard, Ui.cardParams(this));

        LinearLayout restartCard = Ui.cardContainer(this);
        restartCard.addView(Ui.row(this, R.drawable.ic_refresh, "重启作用域",
                "勾选需要重启的作用域应用，或刷新列表", true,
                view -> startActivity(new Intent(this, RestartScopeActivity.class))));
        page.addView(restartCard, Ui.cardParams(this));
        scroll.addView(page);
        return scroll;
    }

    // ------------------------------------------------------------------
    // 更多 page
    // ------------------------------------------------------------------

    private View buildMorePage() {
        ScrollView scroll = Ui.bounceScrollView(this);
        LinearLayout page = column(this);
        page.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 120));

        TextView title = Ui.title(this, "更多", 26);
        title.setPadding(Ui.dp(this, 22), 0, Ui.dp(this, 22), Ui.dp(this, 2));
        page.addView(title);

        page.addView(Ui.sectionTitle(this, "主题包"));
        LinearLayout docsCard = Ui.cardContainer(this);
        docsCard.addView(Ui.row(this, R.drawable.ic_folder, "适用范围", null, true,
                view -> startActivity(textPage("适用范围",
                        new String[]{"适用主题包类型", "已验证环境"},
                        new String[]{
                                "适用于 weeazn 制作的澎湃OS3 主题包（HyperOS 3 适配版）。"
                                        + "修补只复用主题包内已有素材，不修改原始文件。",
                                Ui.systemVersionLine() + "。其他系统版本未经验证。"}))));
        docsCard.addView(Ui.separator(this, 51));
        docsCard.addView(Ui.row(this, R.drawable.ic_patch, "修补详情", null, true,
                view -> startActivity(new Intent(this, DetailActivity.class))));
        page.addView(docsCard, Ui.cardParams(this));

        page.addView(Ui.sectionTitle(this, "帮助"));
        LinearLayout helpCard = Ui.cardContainer(this);
        helpCard.addView(Ui.row(this, R.drawable.ic_info, "使用说明", null, true,
                view -> startActivity(textPage("使用说明",
                        new String[]{"使用步骤", "卡片透明度"},
                        new String[]{USAGE_STEPS, ALPHA_NOTE}))));
        helpCard.addView(Ui.separator(this, 51));
        helpCard.addView(Ui.row(this, R.drawable.ic_more, "作用域", null, true,
                view -> startActivity(textPage("作用域", null,
                        new String[]{ThemeCompatApplication.scopeSummary()}))));
        page.addView(helpCard, Ui.cardParams(this));
        scroll.addView(page);
        return scroll;
    }

    private Intent textPage(String title, String[] heads, String[] bodies) {
        return new Intent(this, TextPageActivity.class)
                .putExtra("title", title)
                .putExtra("heads", heads)
                .putExtra("bodies", bodies);
    }

    private static final String USAGE_STEPS =
            "① 在「修补」页点击选择，挑选 weeazn 澎湃OS3 的 .mtz / .zip 主题包。\n"
            + "② 点击修补并选择保存位置，生成适配主题。\n"
            + "③ 在主题商店导入生成的主题并应用。\n"
            + "④ 首次使用：在 LSPosed 中勾选作用域，再打开「Hook」页的“重启作用域”，"
            + "勾选需要重启的应用后点击重启（系统界面也可在此重启）。\n"
            + "⑤ 「Hook 软件」列表内每个应用都有开关，控制该应用的 Hook 是否加载"
            + "（系统界面默认关闭）；开关在对应进程下次启动时生效。";

    private static final String ALPHA_NOTE =
            "数值为不透明度：0% 全透明，100% 实心。默认浅色 0x48、深色 0x3D（已验证参考值）。"
            + "修改保存后，已 Hook 的应用会在数秒内自动生效，无需重启模块。";

    // ------------------------------------------------------------------
    // Patch flows (unchanged behaviour, restyled surfaces)
    // ------------------------------------------------------------------

    private void chooseInput() {
        appendLog("正在打开主题包选择器…");
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // SAF providers disagree on the MIME type for .mtz: most report ZIP,
        // some report octet-stream, and some do not expose a type at all.
        // Show all documents and validate description.xml before generating.
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_OPEN_MTZ);
    }

    private void chooseOutput() {
        if (sourceUri == null) return;
        appendLog("正在选择生成位置…");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/octet-stream");
        intent.putExtra(Intent.EXTRA_TITLE, outputName());
        startActivityForResult(intent, REQUEST_CREATE_MTZ);
    }

    @Override
    @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_OPEN_MTZ) {
            sourceUri = uri;
            sourceName = displayName(uri);
            try {
                getContentResolver().takePersistableUriPermission(uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } catch (SecurityException ignored) {
                // A one-shot document grant remains sufficient for this run.
            }
            selectedView.setText("已选择：" + sourceName);
            patchButton.setEnabled(true);
            patchButton.setAlpha(1f);
            showStatus("可以开始修补");
            appendLog("已选择输入：" + sourceName);
        } else if (requestCode == REQUEST_CREATE_MTZ) {
            appendLog("已选择输出位置，开始验证主题包。");
            generate(uri);
        }
    }

    private void generate(Uri destination) {
        if (sourceUri == null) return;
        setWorking(true, "正在验证并修补主题包…");
        final Uri input = sourceUri;
        new Thread(() -> {
            try {
                MtzCompatibilityPatcher.Result result = MtzCompatibilityPatcher.patch(
                        getApplicationContext(), input, destination,
                        message -> runOnUiThread(() -> updateProgress(message)));
                runOnUiThread(() -> {
                    setWorking(false, "已生成：" + result.outputName);
                    reportView.setText(result.report);
                    reportSection.setVisibility(View.VISIBLE);
                    appendLog("完成：主题已生成；详情见下方摘要。");
                    Toast.makeText(this, "适配主题已生成", Toast.LENGTH_LONG).show();
                });
            } catch (Throwable error) {
                runOnUiThread(() -> {
                    String message = "生成失败：" + concise(error);
                    setWorking(false, message);
                    reportView.setText("原主题没有被修改。请保留错误信息后发给我。\n" + error);
                    reportSection.setVisibility(View.VISIBLE);
                });
            }
        }, "ThemeCompatMtzWriter").start();
    }

    private void showStatus(String message) {
        // Never hides: the reserved slot keeps the card height constant.
        statusView.setText(TextUtils.isEmpty(message) ? "就绪" : message);
    }

    private void setWorking(boolean working, String status) {
        boolean enabled = !working && sourceUri != null;
        patchButton.setEnabled(enabled);
        patchButton.setAlpha(enabled ? 1f : 0.45f);
        progressBar.setVisibility(working ? View.VISIBLE : View.INVISIBLE);
        showStatus(status);
        appendLog(status);
    }

    private void updateProgress(String message) {
        showStatus(message);
        appendLog(message);
    }

    private void appendLog(String message) {
        if (patchLogView == null || TextUtils.isEmpty(message)) return;
        final boolean followNewLines = isLogAtBottom();
        String stamp = new SimpleDateFormat("HH:mm:ss", Locale.ROOT).format(new Date());
        patchLog.append('[').append(stamp).append("] ").append(message).append('\n');
        patchLogView.setText(patchLog.toString());
        if (followNewLines && patchLogScroll != null) {
            patchLogScroll.post(() -> patchLogScroll.fullScroll(View.FOCUS_DOWN));
        }
    }

    private boolean isLogAtBottom() {
        if (patchLogScroll == null || patchLogScroll.getChildCount() == 0) return true;
        View content = patchLogScroll.getChildAt(0);
        int maxScroll = Math.max(0, content.getHeight() - patchLogScroll.getHeight());
        return patchLogScroll.getScrollY() >= maxScroll - Ui.dp(this, 2);
    }

    private void checkRootInBackground() {
        appendLog("正在检测 Root（su）…");
        new Thread(() -> {
            RootScopeProcessRestarter.RootStatus root = RootScopeProcessRestarter.checkRoot();
            rootAvailable = root.available;
            runOnUiThread(() -> appendLog(root.message));
        }, "ThemeCompatRootCheck").start();
    }

    private String outputName() {
        String base = TextUtils.isEmpty(sourceName) ? "theme" : sourceName;
        if (base.toLowerCase(Locale.ROOT).endsWith(".mtz")) {
            base = base.substring(0, base.length() - 4);
        }
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date());
        return base + "-K70U适配-" + stamp + ".mtz";
    }

    private String displayName(Uri uri) {
        ContentResolver resolver = getContentResolver();
        try (Cursor cursor = resolver.query(uri, new String[]{OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (column >= 0) return cursor.getString(column);
            }
        } catch (Throwable ignored) { }
        return "已选择主题.mtz";
    }

    private LinearLayout.LayoutParams fullWidth() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private static String concise(Throwable error) {
        String message = error.getMessage();
        return error.getClass().getSimpleName() + (TextUtils.isEmpty(message) ? "" : "：" + message);
    }
}
