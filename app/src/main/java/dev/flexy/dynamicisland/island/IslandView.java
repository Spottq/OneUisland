package dev.flexy.dynamicisland.island;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Region;
import android.graphics.Shader;
import android.graphics.PathMeasure;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

@SuppressLint("ViewConstructor")
public final class IslandView extends View implements Choreographer.FrameCallback {

    public interface Host {
        void onIslandFrame();

        void onOpenItem(IslandItem item, RectF fromBounds);

        void onItemAction(IslandItem item, IslandItem.Action action);

        void onExpandedChanged(boolean expanded);

        void onCycle(int direction);

        void onShadeDragStart(float downX, float downY, long downTime);

        void onShadeDragEvent(MotionEvent e);

        void onMinimize(IslandItem item);

        void onRestore();

        boolean onShadeFade(float alpha, float scale, float px, float py);

        boolean hasMinimized();

        void onWindowHeightNeeded(int px);

        AudioLevels audio();
    }

    public interface CardPainter {
        void paintCard(Object owner, Canvas canvas, float viewLeft, float viewTop);
    }

    public interface BlurSink {
        boolean available();

        void setPainter(CardPainter painter);

        void beginFrame();

        void place(Object owner, RectF bounds, float corner, float alpha, int radius);

        void endFrame();

        void release(Object owner);
    }

    public static final int HINT_NONE = 0;
    public static final int HINT_MINIMIZE = 1;
    public static final int HINT_RESTORE = 2;

    private enum Mode { HIDDEN, COMPACT, EXPANDED }

    private static final long OUT_MS = 110;
    private static final long IN_MS = 200;
    private static final long IN_DELAY_MS = 115;
    private static final long ROW_STAGGER_MS = 30;
    private static final long CARD_STAGGER_MS = 70;
    private static final int MAX_CARDS = 4;

    private final Host host;
    private BlurSink blur;
    private final float dp;
    private final Choreographer choreographer = Choreographer.getInstance();
    private final Random random = new Random();

    private final float pillH;
    private final float padding;
    private final float holeGap;
    private final float innerGap;
    private final float leadSize;
    private final float bubbleR;
    private final float bubbleGap;
    private final float minPillW;
    private final float ringSize;
    private final float touchSlop;
    private boolean forwardingShade;
    private final float waveBarW;
    private final float waveGap;
    private final float outlineW;
    private float cardMargin;
    private float cardMaxW;
    private final float cardRadius;
    private final float cardGap;

    private int screenW;
    private float cx;
    private float cy;
    private float holeD;
    private float maxPillW;
    private boolean tablet;
    private float anchorLeft = Float.NaN;
    private boolean glassPill;
    private static final Object PILL_BLUR = new Object();
    private static final Object BUBBLE_BLUR = new Object();

    /**
     * Landscape with a side notch: the whole island (pill and expanded cards) turns 90 degrees around
     * the notch. The compact pill sits vertically on the notch, and expanded cards stack to the side
     * as a horizontal row instead of running off the bottom of the screen.
     */
    private boolean sideRot;
    private float sideDeg;
    private float sideCx;
    private float sideCy;

    /** Pivot is the notch center in view coordinates. */
    public void setSideRotation(boolean on, float degrees, float pivotX, float pivotY) {
        if (sideRot == on && sideDeg == degrees && sideCx == pivotX && sideCy == pivotY) return;
        sideRot = on;
        sideDeg = degrees;
        sideCx = pivotX;
        sideCy = pivotY;
        invalidate();
        scheduleFrame();
    }

    private boolean rotatedNow() {
        return sideRot;
    }

    private float centerFor(float w) {
        if (rotatedNow()) return sideCx;
        return Float.isNaN(anchorLeft) ? cx : anchorLeft + w / 2f;
    }

    private final Spring width;
    private final Spring height;
    private final Spring visibility;
    private final Spring suppress;
    private final Spring press;
    private final Spring pulse;
    private final Spring offsetX;
    private final Spring split;
    private final Spring[] waveBars = new Spring[4];
    private final Spring bubbleExtra = new Spring(0f, 0.36f, 0.82f).setValueThreshold(0.3f);
    private final float[] waveLevel = new float[4];
    private float shade;
    private long lastFrameNanos;
    private boolean frameScheduled;

    private Mode mode = Mode.HIDDEN;
    private final List<IslandItem> items = new ArrayList<>();
    private IslandItem primary;
    private IslandItem secondary;
    private boolean suppressed;

    private Layer mainIn;
    private Layer mainOut;
    private Layer bubbleIn;
    private Layer bubbleOut;
    private final List<Card> cards = new ArrayList<>();
    private IslandItem shadeItem;
    private float shadeWidth;
    private CardBody shadeBody;

    private final Paint shapePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cardStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint trailPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint smallPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint titlePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bodyPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bigTimePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint buttonPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mainRect = new RectF();
    private final RectF bubbleRect = new RectF();
    private final RectF cardRect = new RectF();
    private final RectF tmpRect = new RectF();
    private final Path clipPath = new Path();
    private final Path neckPath = new Path();

    private float downX;
    private float downY;
    private boolean downOnMain;
    private boolean downOnBubble;
    private Card downCard;
    private boolean downOnHole;
    private boolean dragging;
    private boolean ignoreGesture;
    private MediaBody seekBody;
    private float seekFraction;
    private final Spring stackDx = new Spring(0f, 0.45f, 0.55f).setValueThreshold(0.3f);
    private final Spring stackDy = new Spring(0f, 0.45f, 0.55f).setValueThreshold(0.3f);
    private boolean stackDragging;
    private int dragAxis;

    private static float rubber(float d, float limit) {
        float a = Math.abs(d);
        float r = limit * (1f - 1f / (a * 0.55f / limit + 1f));
        return Math.signum(d) * r;
    }

    private android.view.VelocityTracker tracker;
    private float flingBoost;

    private float flingVelocity() {
        if (tracker == null) return 0f;
        tracker.computeCurrentVelocity(1000);
        return tracker.getYVelocity();
    }

    private void releaseStack() {
        dragAxis = 0;
        for (Card c : cards) {
            if (c.dx.value() != 0f || c.dx.target() != 0f) c.dx.animateTo(0f, 0.42f, 0.5f);
        }
        if (!stackDragging && stackDx.value() == 0f && stackDy.value() == 0f) {
            scheduleFrame();
            return;
        }
        stackDragging = false;
        stackDx.animateTo(0f, 0.42f, 0.5f);
        stackDy.animateTo(0f, 0.46f, 0.48f);
        scheduleFrame();
    }

    private float cardContentLeft(Card c) {
        float left = centerFor(c.body.w) - c.body.w / 2f;
        if (c.body.w < screenW) left = Math.max(Math.min(left, screenW - cardMargin - c.body.w), cardMargin);
        if (!c.leaving) left += stackDx.value() * (1f - 0.08f * c.order) + c.dx.value();
        return left;
    }
    private final Runnable secondTick = this::onSecondTick;
    private boolean tickPosted;

    public IslandView(Context context, Host host) {
        super(context);
        this.host = host;
        dp = context.getResources().getDisplayMetrics().density;
        pillH = dp(30);
        padding = dp(9);
        holeGap = dp(6);
        innerGap = dp(8);
        leadSize = dp(20);
        bubbleR = dp(15);
        bubbleGap = dp(6);
        minPillW = dp(64);
        ringSize = dp(17);
        waveBarW = dp(3.8f);
        waveGap = dp(2.6f);
        outlineW = Math.max(1.5f, dp(0.7f));
        cardMargin = dp(12);
        cardMaxW = dp(420);
        cardRadius = dp(28);
        cardGap = dp(8);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        width = new Spring(0f, 0.4f, 0.8f).setValueThreshold(0.4f);
        height = new Spring(0f, 0.4f, 0.8f).setValueThreshold(0.4f);
        visibility = new Spring(0f, 0.2f, 1f).setValueThreshold(0.002f);
        suppress = new Spring(1f, 0.25f, 1f).setValueThreshold(0.002f);
        press = new Spring(1f, 0.2f, 0.9f).setValueThreshold(0.0005f);
        pulse = new Spring(0f, 0.3f, 0.55f).setValueThreshold(0.0005f);
        offsetX = new Spring(0f, 0.35f, 0.7f).setValueThreshold(0.3f);
        split = new Spring(0f, 0.42f, 0.62f).setValueThreshold(0.002f);
        for (int i = 0; i < waveBars.length; i++) {
            waveBars[i] = new Spring(0.2f, 0.3f, 1f).setValueThreshold(0.003f);
        }

        shapePaint.setColor(0xFF000000);
        outlinePaint.setColor(0x47FFFFFF);
        cardStroke.setStyle(Paint.Style.STROKE);
        cardStroke.setStrokeWidth(outlineW);
        applyFonts();
        trailPaint.setTextSize(sp(14.5f));
        trailPaint.setFontFeatureSettings("tnum");
        smallPaint.setTextSize(sp(12f));
        smallPaint.setFontFeatureSettings("tnum");
        titlePaint.setTextSize(sp(18f));
        titlePaint.setColor(0xFFFFFFFF);
        bodyPaint.setTextSize(sp(14.5f));
        bodyPaint.setColor(0xD9FFFFFF);
        bigTimePaint.setTextSize(sp(30f));
        bigTimePaint.setFontFeatureSettings("tnum");
        bigTimePaint.setColor(0xFFFFFFFF);
        buttonPaint.setTextSize(sp(14f));
        buttonPaint.setTextAlign(Paint.Align.CENTER);
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        setWillNotDraw(false);
    }

    void setBlurSink(BlurSink sink) {
        blur = sink;
        sink.setPainter((owner, canvas, viewLeft, viewTop) -> {
            if (!(owner instanceof Card)) return;
            Card c = (Card) owner;
            computeCard(c, cardRect);
            int save = canvas.save();
            canvas.translate(-viewLeft, -viewTop);
            drawCardInner(canvas, c, now(), expansion(c));
            canvas.restoreToCount(save);
        });
    }

    private android.view.ViewGroup remoteLayer;
    private final java.util.Map<String, View> remoteViews = new java.util.HashMap<>();
    private final java.util.Map<String, android.widget.RemoteViews> remoteSources = new java.util.HashMap<>();

    void setRemoteLayer(android.view.ViewGroup layer) {
        remoteLayer = layer;
    }

    private View remoteViewFor(IslandItem item) {
        View v = remoteViews.get(item.key);
        android.widget.RemoteViews src = remoteSources.get(item.key);
        if (v != null && src == item.remoteView) return v;
        if (v != null && canReapply(src, item.remoteView)) {
            try {
                item.remoteView.reapply(nightContext(getContext()), v);
                remoteSources.put(item.key, item.remoteView);
                return v;
            } catch (Throwable ignored) {
            }
        }
        if (v != null) remoteLayer.removeView(v);
        android.content.Context night = nightContext(getContext());
        v = item.remoteView.apply(night, new android.widget.FrameLayout(night));
        v.setVisibility(GONE);
        remoteLayer.addView(v, new android.widget.FrameLayout.LayoutParams(1, 1));
        remoteViews.put(item.key, v);
        remoteSources.put(item.key, item.remoteView);
        return v;
    }

    private static final java.util.Map<android.content.Context, Object[]> NIGHT_CONTEXTS = new java.util.WeakHashMap<>();

    public static android.content.Context nightContext(android.content.Context base) {
        android.content.res.Configuration cfg = base.getResources().getConfiguration();
        if ((cfg.uiMode & android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES) {
            return base;
        }
        Object[] cached = NIGHT_CONTEXTS.get(base);
        if (cached != null && cfg.equals(cached[0])) return (android.content.Context) cached[1];
        android.content.res.Configuration night = new android.content.res.Configuration(cfg);
        night.uiMode = (cfg.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                | android.content.res.Configuration.UI_MODE_NIGHT_YES;
        android.content.Context out = base.createConfigurationContext(night);
        NIGHT_CONTEXTS.put(base, new Object[]{new android.content.res.Configuration(cfg), out});
        return out;
    }

    public static boolean canReapply(android.widget.RemoteViews old, android.widget.RemoteViews next) {
        if (old == null || next == null || old.getLayoutId() != next.getLayoutId()) return false;
        return !addsViews(next, 0);
    }

    private static boolean addsViews(Object rv, int depth) {
        if (rv == null || depth > 8) return false;
        for (String name : new String[]{"mLandscape", "mPortrait"})
            if (addsViews(dev.flexy.dynamicisland.xposed.Refl.get(rv, name), depth + 1)) return true;
        Object sized = dev.flexy.dynamicisland.xposed.Refl.get(rv, "mSizedRemoteViews");
        if (sized instanceof java.util.List) for (Object s : (java.util.List<?>) sized)
            if (s != rv && addsViews(s, depth + 1)) return true;
        Object actions = dev.flexy.dynamicisland.xposed.Refl.get(rv, "mActions");
        if (!(actions instanceof java.util.List)) return sized == null;
        for (Object a : (java.util.List<?>) actions) {
            if (a == null) continue;
            String n = a.getClass().getSimpleName();
            if (n.contains("ViewGroupActionAdd") || n.contains("ViewGroupActionRemove")
                    || n.contains("RemoteCollection") || n.contains("SetRemoteCollection")) return true;
        }
        return false;
    }

    private final java.util.Set<View> shownRemote = new java.util.HashSet<>();

    private void updateRemote() {
        if (remoteLayer == null || remoteViews.isEmpty()) return;
        shownRemote.clear();
        long t = now();
        float vis = visibleAlpha();
        for (Card c : cards) {
            if (!(c.body instanceof RemoteBody)) continue;
            RemoteBody rb = (RemoteBody) c.body;
            computeCard(c, cardRect);
            float a = contentAlpha(c, t, expansion(c)) * vis;
            if (a <= 0.01f) continue;
            rb.view.setTranslationX(cardContentLeft(c) + rb.pad);
            rb.view.setTranslationY(cardRect.top + rb.pad);
            rb.view.setAlpha(a);
            if (rb.view.getVisibility() != VISIBLE) rb.view.setVisibility(VISIBLE);
            shownRemote.add(rb.view);
        }
        Set<String> alive = new HashSet<>();
        for (IslandItem it : items) alive.add(it.key);
        for (IslandItem it : hiddenExtras) alive.add(it.key);
        for (Card c : cards) alive.add(c.item.key);
        for (java.util.Iterator<java.util.Map.Entry<String, View>> i = remoteViews.entrySet().iterator(); i.hasNext(); ) {
            java.util.Map.Entry<String, View> e = i.next();
            View v = e.getValue();
            if (!alive.contains(e.getKey())) {
                remoteLayer.removeView(v);
                remoteSources.remove(e.getKey());
                i.remove();
            } else if (!shownRemote.contains(v) && v.getVisibility() != GONE) {
                v.setVisibility(GONE);
            }
        }
    }


    public void setGeometry(int screenWidth, float centerX, float centerY, float holeDiameter, boolean isTablet,
                            float anchor, boolean glass, float limit) {
        if (glassPill != glass) {
            glassPill = glass;
            invalidate();
            scheduleFrame();
        }
        rightLimit = limit;
        boolean sameAnchor = Float.isNaN(anchor) ? Float.isNaN(anchorLeft)
                : !Float.isNaN(anchorLeft) && Math.abs(anchor - anchorLeft) <= 1.5f;
        boolean moved = !sameAnchor || Math.abs(cx - centerX) > 1.5f;
        boolean changed = screenW != screenWidth || Math.abs(cy - centerY) > 1.5f
                || Math.abs(holeD - holeDiameter) > 1.5f || tablet != isTablet;
        if (!changed && !moved) {
            refreshLimit();
            return;
        }
        float pillW = Math.max(0f, width.value());
        float before = centerFor(pillW);
        boolean animate = moved && screenW == screenWidth && screenW > 0;
        cx = centerX;
        anchorLeft = anchor;
        if (animate) {
            offsetX.snapTo(offsetX.value() + before - centerFor(pillW));
            offsetX.animateTo(0f, 0.42f, 0.78f);
            scheduleFrame();
        }
        if (!changed) {
            refreshLimit();
            invalidate();
            notifyHost();
            return;
        }
        screenW = screenWidth;
        cy = centerY;
        holeD = holeDiameter;
        tablet = isTablet;
        applyPillLimit();
        IslandItem oldSecondary = secondary;
        if (!bubbleFits(primary)) secondary = null;
        if (oldSecondary != secondary) updateBubble(oldSecondary, secondary);
        cardMargin = dp(isTablet ? 16 : 12);
        cardMaxW = dp(isTablet ? 380 : 420);
        if (mode == Mode.COMPACT && primary != null) {
            mainIn = mainIn == null ? Layer.shown(new CompactContent(primary))
                    : Layer.shownLike(new CompactContent(primary), mainIn);
            width.animateTo(mainIn.content.desiredWidth(), 0.30f, 0.80f);
            scheduleFrame();
        } else if (mode == Mode.EXPANDED) {
            collapse();
        }
        if (secondary != null) bubbleIn = Layer.shown(new BubbleContent(secondary));
        invalidate();
        notifyHost();
    }

    private float rightLimit = Float.NaN;

    private boolean applyPillLimit() {
        float base = Math.min(screenW * 0.72f, dp(tablet ? 340 : 300));
        if (!Float.isNaN(anchorLeft) && !Float.isNaN(rightLimit)) {
            base = Math.max(minPillW, Math.min(base, rightLimit - anchorLeft));
        }
        boolean changed = Math.abs(base - maxPillW) > 0.5f;
        maxPillW = base;
        return changed;
    }

    private boolean bubbleFits(IslandItem main) {
        if (main == null || Float.isNaN(anchorLeft) || Float.isNaN(rightLimit)) return true;
        float w = new CompactContent(main).desiredWidth();
        return anchorLeft + w + bubbleGap + bubbleR * 2f + Math.max(0f, bubbleExtra.target()) <= rightLimit;
    }

    private void refreshLimit() {
        if (screenW <= 0) return;
        boolean changed = applyPillLimit();
        if (changed && mode == Mode.COMPACT && primary != null) {
            mainIn = mainIn == null ? Layer.shown(new CompactContent(primary))
                    : Layer.shownLike(new CompactContent(primary), mainIn);
            width.animateTo(mainIn.content.desiredWidth(), 0.30f, 0.80f);
        }
        IslandItem want = items.size() > 1 && bubbleFits(primary) ? items.get(1) : null;
        if (want != secondary && mode != Mode.EXPANDED) {
            IslandItem old = secondary;
            secondary = want;
            updateBubble(old, secondary);
            changed = true;
        }
        if (changed) {
            scheduleFrame();
            invalidate();
        }
    }

    public void setItems(List<IslandItem> sorted) {
        setItems(sorted, HINT_NONE);
    }

    private final List<IslandItem> hiddenExtras = new ArrayList<>();

    public void setItems(List<IslandItem> sorted, int hint, List<IslandItem> minimizedItems) {
        hiddenExtras.clear();
        if (minimizedItems != null) hiddenExtras.addAll(minimizedItems);
        setItems(sorted, hint);
        // Shade open on a side notch: keep the cards expanded so media and messages sit in one row.
        if (shadeFold && shadeInIsland && mode != Mode.EXPANDED && !items.isEmpty()) expand(stackItems());
    }

    public void setItems(List<IslandItem> sorted, int hint) {
        IslandItem oldPrimary = primary;
        IslandItem oldSecondary = secondary;
        items.clear();
        items.addAll(sorted);

        Set<String> present = new HashSet<>();
        for (IslandItem it : items) present.add(it.key);
        if (!mediaFxByKey.isEmpty()) {
            Set<String> keep = new HashSet<>(present);
            for (IslandItem it : hiddenExtras) keep.add(it.key);
            mediaFxByKey.keySet().retainAll(keep);
        }

        primary = items.isEmpty() ? null : items.get(0);
        secondary = items.size() > 1 && bubbleFits(primary) ? items.get(1) : null;

        if (primary == null) {
            if (mode == Mode.EXPANDED) collapseCards(false);
            goHidden(hint == HINT_MINIMIZE);
            return;
        }

        if (mode == Mode.EXPANDED) {
            updateExpanded();
        } else if (mode == Mode.HIDDEN) {
            if (shadeHidden) {
                notifyHost();
                return;
            }
            appear(primary, hint == HINT_RESTORE);
        } else {
            boolean same = oldPrimary != null && oldPrimary.key.equals(primary.key);
            if (same && oldPrimary.sameCompactLook(primary)) {
                replaceMain(new CompactContent(primary), false, 0, 0);
            } else {
                int outDir = hint == HINT_MINIMIZE ? -1 : (hint == HINT_RESTORE ? 1 : 0);
                int inDir = hint == HINT_MINIMIZE ? 1 : (hint == HINT_RESTORE ? -1 : 0);
                replaceMain(new CompactContent(primary), true, outDir, inDir);
                if (hint == HINT_MINIMIZE) offsetX.impulse(-dp(220));
                else if (hint == HINT_RESTORE) offsetX.impulse(dp(220));
                else bump();
            }
            width.animateTo(mainIn.content.desiredWidth(), 0.30f, 0.80f);
            height.animateTo(pillH, 0.30f, 0.80f);
        }
        updateBubble(oldSecondary, secondary);
        scheduleFrame();
        scheduleTick();
        notifyHost();
    }

    public void setSuppressed(boolean value) {
        if (suppressed == value) return;
        suppressed = value;
        if (value && mode == Mode.EXPANDED) collapse();
        suppress.animateTo(value ? 0f : 1f, value ? 0.18f : 0.32f, 1f);
        scheduleFrame();
    }

    public void reveal() {
        suppress.snapTo(1f);
        if (shadeHidden) {
            scheduleFrame();
            return;
        }
        if (primary == null || mode == Mode.EXPANDED) {
            scheduleFrame();
            return;
        }
        mode = Mode.HIDDEN;
        appear(primary, false);
        scheduleFrame();
    }

    public void setShadeFraction(float f) {
        f = clamp01(f);
        if (f == shade || (Math.abs(f - shade) < 0.001f && f != 0f && f != 1f)) return;
        shade = f;
        if (f > (headsUpShown() ? HEADS_UP_FOLD : SHADE_FOLD)) setFold(true, statusBarFold);
        else if (f <= 0.001f) setFold(false, statusBarFold);
    }

    private int panelState;
    private boolean headsUpPinned;
    private static final float HEADS_UP_FOLD = 0.5f;
    private static final long HEADS_UP_GRACE_MS = 700;

    public void setPanelState(int state) {
        panelState = state;
    }

    public void setHeadsUpPinned(boolean pinned) {
        headsUpPinned = pinned;
    }

    public static volatile java.util.function.BooleanSupplier headsUpProbe;

    private long headsUpSeenAt;

    private boolean headsUpShown() {
        java.util.function.BooleanSupplier probe = headsUpProbe;
        long t = SystemClock.uptimeMillis();
        if (headsUpPinned || (probe != null && probe.getAsBoolean())) headsUpSeenAt = t;
        return headsUpSeenAt != 0 && t - headsUpSeenAt < HEADS_UP_GRACE_MS;
    }

    public void setStatusBarHidden(boolean hidden) {
        setFold(shadeFold, hidden);
    }

    public void setSleepFold(boolean sleeping) {
        if (sleepFold == sleeping) return;
        sleepFold = sleeping;
        setFold(shadeFold, statusBarFold);
    }

    private boolean sleepFold;

    private static final float SHADE_FOLD = 0.02f;
    private boolean shadeFold;
    private boolean statusBarFold;
    private boolean shadeHidden;

    /** Landscape with a side notch: the open shade keeps the island visible, so media and messages show here. */
    private boolean shadeInIsland;

    public void setShadeInIsland(boolean on) {
        if (shadeInIsland == on) return;
        shadeInIsland = on;
        setFold(shadeFold, statusBarFold);
    }

    private void setFold(boolean shadeOpen, boolean statusBarHidden) {
        boolean was = shadeHidden;
        boolean wasShadeOpen = shadeFold;
        shadeFold = shadeOpen;
        statusBarFold = statusBarHidden;
        shadeHidden = (shadeOpen && !shadeInIsland) || statusBarHidden || sleepFold;
        if (shadeInIsland) {
            if (shadeOpen && !wasShadeOpen && mode != Mode.EXPANDED && !items.isEmpty()) expand(stackItems());
            if (!shadeOpen && wasShadeOpen && mode == Mode.EXPANDED) collapse();
        }
        if (shadeHidden == was) return;
        dev.flexy.dynamicisland.xposed.XLog.i("island fold " + shadeHidden + " (shade " + shadeOpen + " " + shade
                + " panel " + panelState + " heads-up " + headsUpShown() + ", status bar hidden " + statusBarHidden
                + ", sleep " + sleepFold + ")");
        if (shadeHidden) {
            if (mode == Mode.EXPANDED) collapse();
            goHidden(false);
        } else {
            if (mode == Mode.HIDDEN && primary != null) {
                appear(primary, false);
                updateBubble(null, secondary);
            }
            scheduleTick();
        }
        scheduleFrame();
        notifyHost();
    }

    private boolean surfaceFade;

    public boolean shadePulled() {
        return shade > 0.001f;
    }

    private boolean frozenByShade() {
        return surfaceFade && shade > 0.001f && suppress.isAtRest() && visibility.isAtRest();
    }

    public void absorb() {
        if (mode == Mode.HIDDEN) return;
        width.setParams(0.32f, 0.58f);
        height.setParams(0.32f, 0.58f);
        float omega = (float) (2 * Math.PI / 0.32f);
        width.impulse(width.target() * 0.10f * omega / 0.52f);
        height.impulse(dp(4) * omega / 0.52f);
        scheduleFrame();
    }

    public boolean isExpanded() {
        return mode == Mode.EXPANDED;
    }

    public boolean isFullyHidden() {
        boolean noShape = mode == Mode.HIDDEN && visibility.isAtRest() && visibility.value() <= 0.001f && cards.isEmpty();
        boolean holdTouchZone = mode == Mode.HIDDEN && host.hasMinimized() && !suppressed && !shadeHidden;
        if (holdTouchZone) return false;
        return noShape
                || (suppressed && suppress.isAtRest() && suppress.value() <= 0.001f)
                || shadeAlpha() <= 0.001f;
    }

    public float visibleAlpha() {
        return visibility.value() * suppress.value() * shadeAlpha();
    }

    public boolean needsAudio() {
        if (mode != Mode.COMPACT || mainIn == null || visibleAlpha() < 0.2f) return false;
        IslandItem it = mainIn.content.item;
        if (it.trailing == IslandItem.Trailing.WAVEFORM && it.playing) return true;
        IslandItem b = detailedBubbleItem();
        return b != null && b.trailing == IslandItem.Trailing.WAVEFORM && b.playing;
    }

    public void getCoverageBounds(RectF out) {
        computeMain(out);
        if (split.value() > 0.2f) {
            computeBubble(out, tmpRect);
            out.union(tmpRect);
        }
        float rowTop = cy - pillH / 2f;
        float rowBottom = cy + pillH / 2f;
        for (Card c : cards) {
            computeCard(c, tmpRect);
            if (tmpRect.top < rowBottom && tmpRect.bottom > rowTop) out.union(tmpRect);
        }
    }

    public void getRestingPillBounds(RectF out) {
        float w = mainIn != null && mode == Mode.COMPACT ? width.target() : Math.max(minPillW, dp(92));
        float mid = centerFor(w);
        out.set(mid - w / 2f, cy - pillH / 2f, mid + w / 2f, cy + pillH / 2f);
        if (rotatedNow()) {
            Matrix m = new Matrix();
            m.setRotate(sideDeg, sideCx, sideCy);
            m.mapRect(out);
        }
    }

    public void getTouchRegion(Region out) {
        if (!rotatedNow()) {
            buildTouchRegion(out);
            return;
        }
        Region flat = new Region();
        buildTouchRegion(flat);
        RectF box = new RectF(flat.getBounds());
        Matrix m = new Matrix();
        m.setRotate(sideDeg, sideCx, sideCy);
        m.mapRect(box);
        out.set(Math.round(box.left), Math.round(box.top), Math.round(box.right), Math.round(box.bottom));
    }

    private void buildTouchRegion(Region out) {
        out.setEmpty();
        float m = dp(4);
        if (mode == Mode.HIDDEN) {
            if (host.hasMinimized() && !suppressed && !shadeHidden) {
                float half = Math.max(dp(46), holeD);
                float mid = centerFor(half * 2f);
                out.op(Math.round(mid - half), Math.round(cy - pillH / 2f - m), Math.round(mid + half),
                        Math.round(cy + pillH / 2f + m), Region.Op.UNION);
            }
            return;
        }
        if (visibleAlpha() < 0.4f) return;
        if (mode == Mode.EXPANDED) {
            out.set(0, 0, screenW, Math.max(getHeight(), screenW * 3));
            return;
        }
        computeMain(tmpRect);
        if (mode == Mode.COMPACT || tmpRect.width() > dp(40)) union(out, tmpRect, m);
        if (split.value() > 0.6f && mode == Mode.COMPACT) {
            computeBubble(mainRect, tmpRect);
            union(out, tmpRect, m);
        }
        for (Card c : cards) {
            if (c.leaving) continue;
            computeCard(c, tmpRect);
            union(out, tmpRect, m);
        }
    }

    private static void union(Region r, RectF f, float m) {
        r.op(Math.round(f.left - m), Math.round(f.top - m), Math.round(f.right + m), Math.round(f.bottom + m), Region.Op.UNION);
    }

    public void collapse() {
        if (mode != Mode.EXPANDED) return;
        removeCallbacks(fadePillBlob);
        pillBlob.animateTo(1f, 0.1f, 1f);
        scheduleFrame();
        if (primary == null) {
            collapseCards(true);
            mode = Mode.COMPACT;
            goHidden(false);
            host.onExpandedChanged(false);
            return;
        }
        mode = Mode.COMPACT;
        bubbleHoldUntil = now() + 420 + POP_DELAY_MS;
        collapseCards(true);
        replaceMain(new CompactContent(primary), true, 0, 0);
        mainIn = Layer.incoming(mainIn.content, now(), 330);
        removeCallbacks(regrowPill);
        postDelayed(regrowPill, 420);
        scheduleFrame();
        host.onExpandedChanged(false);
        notifyHost();
    }


    private final Runnable regrowPill = this::regrowPillNow;

    private void regrowPillNow() {
        if (mode != Mode.COMPACT || mainIn == null) return;
        width.animateTo(mainIn.content.desiredWidth(), 0.38f, 0.7f);
        height.animateTo(pillH, 0.34f, 0.75f);
        removeCallbacks(popBubble);
        if (secondary != null) postDelayed(popBubble, POP_DELAY_MS);
        else split.animateTo(0f, 0.46f, 0.6f);
        scheduleFrame();
    }

    private static final double BEAM_ANGLE = Math.toRadians(28);
    private static final long POP_DELAY_MS = 60;
    private static final long MERGE_MS = 80;
    private long bubbleHoldUntil;
    private final Runnable popBubble = this::popBubbleNow;

    private void popBubbleNow() {
        if (mode != Mode.COMPACT || secondary == null) return;
        split.animateTo(1f, 0.34f, 0.6f);
        scheduleFrame();
    }

    private final Spring pillBlob = new Spring(1f, 0.2f, 1f).setValueThreshold(0.002f);
    private final Runnable fadePillBlob = this::fadePillBlobNow;

    private void fadePillBlobNow() {
        if (mode != Mode.EXPANDED) return;
        pillBlob.animateTo(0f, 0.22f, 1f);
        scheduleFrame();
    }

    private float restWidth() {
        return holeD > 0 ? holeD * 0.92f : 0f;
    }

    private void appear(IslandItem p, boolean fromLeft) {
        mode = Mode.COMPACT;
        removeCallbacks(fadePillBlob);
        pillBlob.snapTo(1f);
        float start = restWidth();
        width.snapTo(start);
        height.snapTo(Math.min(start, pillH));
        if (holeD > 0) {
            visibility.snapTo(1f);
        } else {
            visibility.animateTo(1f, 0.22f, 1f);
        }
        offsetX.snapTo(0f);
        if (fromLeft) offsetX.impulse(dp(260));
        mainOut = null;
        mainIn = Layer.incoming(new CompactContent(p), now(), 150).slide(fromLeft ? -1 : 0);
        width.animateTo(mainIn.content.desiredWidth(), 0.45f, 0.65f);
        height.animateTo(pillH, 0.35f, 0.8f);
        host.onWindowHeightNeeded(compactWindowHeight());
    }

    private void goHidden(boolean toLeft) {
        removeCallbacks(fadePillBlob);
        pillBlob.animateTo(1f, 0.1f, 1f);
        boolean wasExpanded = mode == Mode.EXPANDED;
        if (mode == Mode.HIDDEN) return;
        mode = Mode.HIDDEN;
        long t = now();
        if (mainIn != null) mainOut = mainIn.toOutgoing(t, toLeft ? -1 : 0);
        mainIn = null;
        if (bubbleIn != null) bubbleOut = bubbleIn.toOutgoing(t, 0);
        bubbleIn = null;
        float end = restWidth();
        width.animateTo(end, 0.3f, 0.9f);
        height.animateTo(Math.min(end, pillH), 0.3f, 0.9f);
        split.animateTo(0f, 0.3f, 0.9f);
        if (toLeft) offsetX.impulse(-dp(200));
        if (holeD <= 0) visibility.animateTo(0f, 0.22f, 1f);
        scheduleFrame();
        if (wasExpanded) host.onExpandedChanged(false);
        notifyHost();
    }

    private List<IslandItem> stableStackItems() {
        List<IslandItem> all = new ArrayList<>(items);
        for (int i = hiddenExtras.size() - 1; i >= 0; i--) all.add(hiddenExtras.get(i));
        List<IslandItem> out = new ArrayList<>();
        List<Card> shown = new ArrayList<>();
        for (Card c : cards) if (!c.leaving) shown.add(c);
        java.util.Collections.sort(shown, (a, b) -> Integer.compare(a.order, b.order));
        for (Card c : shown) {
            for (IslandItem it : all) {
                if (it.key.equals(c.item.key) && out.size() < MAX_CARDS && !out.contains(it)) {
                    out.add(it);
                    break;
                }
            }
        }
        for (IslandItem it : all) {
            if (out.size() >= MAX_CARDS) break;
            if (!out.contains(it)) out.add(it);
        }
        return out;
    }

    private List<IslandItem> stackItems() {
        List<IslandItem> out = new ArrayList<>();
        for (int i = 0; i < items.size() && out.size() < MAX_CARDS; i++) out.add(items.get(i));
        for (int i = hiddenExtras.size() - 1; i >= 0 && out.size() < MAX_CARDS; i--) out.add(hiddenExtras.get(i));
        return out;
    }

    private float cardWidth() {
        return Math.min(screenW - cardMargin * 2f, cardMaxW);
    }

    private float stackTop() {
        return cy + pillH / 2f + dp(8);
    }

    private void expand(List<IslandItem> show) {
        if (show.isEmpty()) return;
        boolean wasExpanded = mode == Mode.EXPANDED;
        long merge = !wasExpanded && split.value() > 0.05f ? MERGE_MS : 0;
        mode = Mode.EXPANDED;
        removeCallbacks(popBubble);
        layoutCards(show, true);
        if (merge > 0) {
            for (Card c : cards) {
                if (c.leaving) continue;
                c.startAt += merge;
                c.contentStart += merge;
            }
        }
        width.animateTo(restWidth(), 0.34f, 0.86f);
        height.animateTo(Math.min(restWidth(), pillH), 0.34f, 0.86f);
        if (mainIn != null) mainOut = mainIn.toOutgoing(now(), 0);
        split.animateTo(0f, merge > 0 ? 0.18f : 0.3f, 0.92f);
        offsetX.animateTo(0f);
        removeCallbacks(fadePillBlob);
        postDelayed(fadePillBlob, 260);
        host.onWindowHeightNeeded(expandedWindowHeight());
        scheduleFrame();
        scheduleTick();
        if (!wasExpanded) {
            host.onExpandedChanged(true);
            performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
        }
        notifyHost();
    }

    private void layoutCards(List<IslandItem> show, boolean animateFromPill) {
        float w = cardWidth();
        float y = stackTop();
        long t = now();
        List<Card> next = new ArrayList<>();
        computeMain(mainRect);
        int newIndex = 0;
        for (IslandItem it : show) {
            Card c = findCard(it.key);
            if (c == null) {
                c = sameActivity(it, show);
                if (c != null) dev.flexy.dynamicisland.xposed.XLog.i("card kept for re-keyed activity " + c.item.key + " -> " + it.key);
            }
            CardBody body = makeBody(it, w);
            if (c == null || c.leaving) {
                c = new Card(it, body);
                c.cy.snapTo(cy + pillH * 0.15f);
                c.w.snapTo(pillH * 1.8f);
                c.h.snapTo(pillH * 0.8f);
                c.startAt = t + (animateFromPill ? newIndex * CARD_STAGGER_MS : 0);
                c.contentStart = c.startAt + 230;
                newIndex++;
            } else {
                c.item = it;
                c.body = body;
            }
            c.targetW = w;
            c.targetH = body.h;
            c.targetCy = y + body.h / 2f;
            c.order = next.size();
            c.launched = false;
            y += body.h + cardGap;
            next.add(c);
        }
        for (Card old : cards) {
            if (!next.contains(old) && !old.leaving) {
                dev.flexy.dynamicisland.xposed.XLog.i("card leaves " + old.item.key);
                startLeaving(old, t);
            }
        }
        List<Card> leaving = new ArrayList<>();
        for (Card old : cards) if (old.leaving) leaving.add(old);
        cards.clear();
        cards.addAll(next);
        cards.addAll(leaving);
    }

    private void updateExpanded() {
        List<IslandItem> show = stableStackItems();
        if (show.isEmpty()) {
            collapse();
            return;
        }
        layoutCards(show, true);
        host.onWindowHeightNeeded(expandedWindowHeight());
    }

    private void collapseCards(boolean animate) {
        long t = now();
        stackDx.animateTo(0f, 0.3f, 0.9f);
        stackDy.animateTo(0f, 0.3f, 0.9f);
        int n = 0;
        for (Card c : cards) if (!c.leaving) n++;
        for (Card c : cards) {
            if (c.leaving) continue;
            long delay = animate && c.order == 0 && n > 1 ? 70 : 0;
            startLeaving(c, t + delay);
        }
    }

    private void startLeaving(Card c, long t) {
        c.leaving = true;
        c.leaveAt = t;
        c.launched = false;
        c.targetCy = cy;
        c.targetW = pillH * 1.6f;
        c.targetH = pillH * 0.8f;
    }

    private Card findCard(String key) {
        for (Card c : cards) if (c.item.key.equals(key)) return c;
        return null;
    }

    private Card sameActivity(IslandItem it, List<IslandItem> show) {
        if (it.title == null) return null;
        for (Card c : cards) {
            if (c.leaving || c.item.title == null || !TextUtils.equals(c.item.title, it.title)) continue;
            boolean stillShown = false;
            for (IslandItem s : show) if (s.key.equals(c.item.key)) stillShown = true;
            if (stillShown) continue;
            boolean samePkg = TextUtils.equals(c.item.packageName, it.packageName)
                    || "com.locnall.KimGiSa".equals(c.item.packageName) || "com.locnall.KimGiSa".equals(it.packageName);
            if (samePkg) return c;
        }
        return null;
    }

    private int compactWindowHeight() {
        return Math.round(cy + pillH / 2f + dp(40));
    }

    private int expandedWindowHeight() {
        float bottom = stackTop();
        for (Card c : cards) if (!c.leaving) bottom = Math.max(bottom, c.targetCy + c.targetH / 2f);
        return Math.round(bottom + dp(40));
    }

    private void replaceMain(Content content, boolean crossfade, int outDir, int inDir) {
        long t = now();
        if (!crossfade || mainIn == null) {
            mainIn = mainIn == null ? Layer.incoming(content, t, 60) : Layer.shownLike(content, mainIn);
            return;
        }
        mainOut = mainIn.toOutgoing(t, outDir);
        mainIn = Layer.incoming(content, t, IN_DELAY_MS).slide(inDir);
    }

    private void updateBubble(IslandItem oldSecondary, IslandItem s) {
        long t = now();
        if (s == null) {
            if (bubbleIn != null) bubbleOut = bubbleIn.toOutgoing(t, 0);
            bubbleIn = null;
            split.animateTo(0f, 0.34f, 0.84f);
            return;
        }
        boolean same = oldSecondary != null && oldSecondary.key.equals(s.key);
        if (bubbleIn == null) {
            bubbleIn = Layer.incoming(new BubbleContent(s), t, 180);
        } else if (!same) {
            bubbleOut = bubbleIn.toOutgoing(t, 0);
            bubbleIn = Layer.incoming(new BubbleContent(s), t, IN_DELAY_MS);
        } else {
            bubbleIn = Layer.shownLike(new BubbleContent(s), bubbleIn);
        }
        if (mode == Mode.COMPACT && now() >= bubbleHoldUntil) split.animateTo(1f, 0.46f, 0.6f);
    }

    private void bump() {
        pulse.impulse(1.6f);
        scheduleFrame();
    }

    private IslandItem find(String key) {
        for (IslandItem it : items) if (it.key.equals(key)) return it;
        return null;
    }


    private boolean drawnHidden;

    private void scheduleFrame() {
        if (host == null) return;
        if (!frameScheduled) {
            frameScheduled = true;
            choreographer.postFrameCallback(this);
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        frameScheduled = false;
        if (frozenByShade()) {
            lastFrameNanos = 0;
            return;
        }
        float dt = lastFrameNanos == 0 ? 1f / 120f : (frameTimeNanos - lastFrameNanos) / 1e9f;
        lastFrameNanos = frameTimeNanos;
        long t = now();

        boolean active = false;
        active |= width.step(dt);
        active |= height.step(dt);
        active |= visibility.step(dt);
        active |= suppress.step(dt);
        active |= press.step(dt);
        active |= pulse.step(dt);
        active |= offsetX.step(dt);
        active |= split.step(dt);
        active |= stepBubbleExtra(dt);
        active |= pillBlob.step(dt);
        active |= stackDx.step(dt);
        active |= stackDy.step(dt);
        active |= torchPos.step(dt);
        active |= buttonAnimating();

        if (mode == Mode.HIDDEN && holeD > 0 && visibility.target() > 0f) {
            if (width.value() <= restWidth() + dp(5)) {
                visibility.animateTo(0f, 0.12f, 1f);
                active = true;
            }
        }

        active |= stepCards(dt, t);

        if (mainOut != null && mainOut.finished(t)) mainOut = null;
        if (bubbleOut != null && bubbleOut.finished(t)) bubbleOut = null;
        active |= mainOut != null || bubbleOut != null;
        active |= mainIn != null && !mainIn.settled(t);
        active |= bubbleIn != null && !bubbleIn.settled(t);
        active |= stepContinuous(dt);

        updateBlur();
        updateRemote();
        boolean hidden = isFullyHidden();
        if (!hidden || !drawnHidden) invalidate();
        drawnHidden = hidden;
        notifyHost();
        if (active && !hidden && !frozenByShade()) {
            scheduleFrame();
        } else {
            lastFrameNanos = 0;
        }
    }

    private boolean stepCards(float dt, long t) {
        if (cards.isEmpty()) return false;
        boolean active = false;
        for (int i = cards.size() - 1; i >= 0; i--) {
            Card c = cards.get(i);
            if (!c.launched && t >= (c.leaving ? c.leaveAt : c.startAt)) {
                c.launched = true;
                if (c.leaving) {
                    float speed = (1f - Math.min(0.3f, 0.1f * c.order)) * (1f - 0.4f * flingBoost);
                    c.cy.animateTo(c.targetCy, 0.40f * speed, 0.9f);
                    c.w.animateTo(c.targetW, 0.30f * speed, 0.92f);
                    c.h.animateTo(c.targetH, 0.34f * speed, 0.9f);
                    if (flingBoost > 0f) c.cy.impulse(-dp(1400) * flingBoost);
                } else {
                    c.cy.animateTo(c.targetCy, 0.46f, 0.74f);
                    c.w.animateTo(c.targetW, 0.40f, 0.76f);
                    c.h.animateTo(c.targetH, 0.48f, 0.78f);
                }
            }
            active |= !c.launched;
            active |= c.cy.step(dt);
            active |= c.w.step(dt);
            active |= c.h.step(dt);
            active |= c.dx.step(dt);
            if (c.blurOnAt != 0 && t - c.blurOnAt < 320) active = true;
            if (c.leaving && c.launched && Math.abs(c.cy.value() - cy) < dp(4) && c.h.value() < pillH * 0.95f) {
                cards.remove(i);
                if (blur != null) blur.release(c);
                continue;
            }
            active = true;
        }
        if (cards.isEmpty() && mode != Mode.EXPANDED) {
            host.onWindowHeightNeeded(compactWindowHeight());
            if (mode == Mode.COMPACT) {
                removeCallbacks(regrowPill);
                regrowPillNow();
                pulse.impulse(1.4f + 3.2f * flingBoost);
                if (flingBoost > 0f) height.impulse(-dp(60) * flingBoost);
                flingBoost = 0f;
            }
        }
        return active;
    }

    private void updateBlur() {
        if (blur == null) return;
        blur.beginFrame();
        float vis = visibleAlpha();
        if (vis > 0.003f) {
            for (Card c : cards) {
                if (!c.leaving && !c.launched) continue;
                computeCard(c, cardRect);
                if (cardRect.width() < 1f || cardRect.height() < 1f) continue;
                blur.place(c, cardRect, Math.min(cardRect.height() / 2f, cardRadius), vis, frostOn() ? FROST_BLUR : 0);
            }
            if (glassActive()) {
                computeMain(blurRect);
                float a = vis * pillBlob.value();
                if (blurRect.width() > 2f && blurRect.height() > 2f) {
                    scaleAroundPill(blurRect, blurRect);
                    blur.place(PILL_BLUR, blurRect, blurRect.height() / 2f, a, frostOn() ? FROST_BLUR : 0);
                }
                if (split.value() > 0.003f) {
                    computeMain(tmpBlur);
                    computeBubble(tmpBlur, blurRect);
                    scaleAroundPill(tmpBlur, blurRect);
                    blur.place(BUBBLE_BLUR, blurRect, blurRect.height() / 2f, a, frostOn() ? FROST_BLUR : 0);
                }
            }
        }
        blur.endFrame();
    }

    private final RectF blurRect = new RectF();
    private final RectF tmpBlur = new RectF();
    private static final float GLASS_ALPHA = 0.46f;
    private static final float FROST_PILL = 0.12f;
    private static final int GLASS_COLOR = 0xFF0E0E10;

    private boolean glassActive() {
        if (!glassPill || blur == null || !blur.available()) return false;
        return bodyColor(mainIn) == BODY_BLACK && (bubbleIn == null || bodyColor(bubbleIn) == BODY_BLACK);
    }

    private void scaleAroundPill(RectF main, RectF r) {
        float s = press.value() * (1f + pulse.value());
        if (Math.abs(s - 1f) < 0.001f) return;
        float px = main.centerX();
        r.set(px + (r.left - px) * s, cy + (r.top - cy) * s, px + (r.right - px) * s, cy + (r.bottom - cy) * s);
    }

    private boolean stepContinuous(float dt) {
        if (visibleAlpha() <= 0.01f) return false;
        boolean need = false;
        IslandItem waveItem = null;
        if (mainIn != null && mode == Mode.COMPACT) {
            IslandItem it = mainIn.content.item;
            if (it.trailing == IslandItem.Trailing.WAVEFORM) waveItem = it;
            need |= it.pulsingDot;
        }
        IslandItem detailed = detailedBubbleItem();
        if (waveItem == null && detailed != null && detailed.trailing == IslandItem.Trailing.WAVEFORM) waveItem = detailed;
        if (mainIn != null) need |= runningStopwatch(mainIn.content.item);
        need |= claudeAnimating();
        need |= now() < rollUntil;
        need |= toggleAnimating();
        need |= torchAnimating();
        if (bubbleIn != null) need |= bubbleIn.content.item.pulsingDot || runningStopwatch(bubbleIn.content.item);
        if (mainIn != null && recAnimating(mainIn.content.item)) need = true;
        if (bubbleIn != null && recAnimating(bubbleIn.content.item)) need = true;
        for (Card c : cards) {
            need |= c.item.pulsingDot;
            need |= recAnimating(c.item) || runningStopwatch(c.item);
            need |= mode == Mode.EXPANDED && (c.item.isMedia || c.item.iconRight);
        }
        if (mainIn != null && mainIn.content.item.isMedia) {
            MediaFx fx = mediaFxByKey.get(mainIn.content.item.key);
            need |= fx != null && fx.animating(now());
        }
        if (bubbleIn != null && bubbleIn.content.item.isMedia) {
            MediaFx fx = mediaFxByKey.get(bubbleIn.content.item.key);
            need |= fx != null && fx.animating(now());
        }
        if (waveItem != null) {
            AudioLevels audio = host.audio();
            boolean live = waveItem.playing && audio != null && audio.isLive();
            float k = 1f - (float) Math.exp(-dt / 0.09f);
            for (int i = 0; i < waveBars.length; i++) {
                Spring bar = waveBars[i];
                if (live) {
                    float raw = audio.level(BAND_ORDER[i]);
                    if (i == waveBars.length - 1) raw = Math.max(raw, audio.level(4));
                    waveLevel[i] += (raw - waveLevel[i]) * k;
                    float target = 0.10f + 0.90f * waveLevel[i];
                    if (target > bar.value()) bar.animateTo(target, 0.13f, 0.85f);
                    else bar.animateTo(target, 0.30f, 1f);
                } else if (waveItem.playing) {
                    if (bar.isAtRest() || Math.abs(bar.value() - bar.target()) < 0.05f) {
                        bar.animateTo(0.2f + random.nextFloat() * 0.45f, 0.32f + random.nextFloat() * 0.14f, 0.95f);
                    }
                } else {
                    bar.animateTo(0.15f, 0.3f, 0.95f);
                }
                if (!live) waveLevel[i] += (bar.value() - waveLevel[i]) * k;
                need |= bar.step(dt);
            }
            need |= waveItem.playing;
        }
        return need;
    }

    private static final int[] BAND_ORDER = {2, 0, 1, 3};

    private IslandItem detailedBubbleItem() {
        if (mode != Mode.COMPACT || bubbleIn == null || !IslandSettings.detailedBubble()) return null;
        return bubbleIn.content.item;
    }

    private boolean stepBubbleExtra(float dt) {
        float target = 0f;
        if (mode == Mode.COMPACT && bubbleIn != null && bubbleIn.content instanceof BubbleContent) {
            target = ((BubbleContent) bubbleIn.content).extra();
        }
        if (Math.abs(bubbleExtra.target() - target) > 0.5f) bubbleExtra.animateTo(target, 0.36f, 0.82f);
        return bubbleExtra.step(dt);
    }

    private void onSecondTick() {
        tickPosted = false;
        if (frozenByShade()) {
            scheduleTick();
            return;
        }
        updateBlur();
        if (detailedBubbleItem() != null) scheduleFrame();
        if (mode == Mode.COMPACT && mainIn != null && mainIn.content instanceof CompactContent) {
            CompactContent cc = (CompactContent) mainIn.content;
            if (cc.item.trailing == IslandItem.Trailing.CHRONOMETER && cc.remeasure()) {
                width.animateTo(cc.desiredWidth(), 0.30f, 0.80f);
                scheduleFrame();
            }
        }
        invalidate();
        scheduleTick();
    }

    private void scheduleTick() {
        if (tickPosted || !needsTicks()) return;
        tickPosted = true;
        long now = System.currentTimeMillis();
        IslandItem chrono = tickingChronometer();
        long delay = chrono != null ? Math.floorMod(chrono.chronoBase - now, 1000L) + 8
                : 1000 - (now % 1000) + 8;
        postDelayed(secondTick, delay);
    }


    private static final long ROLL_MS = 520;
    private static final int BUTTON_RED = 0xFFEE8E88;
    private static final java.util.regex.Pattern TIME_TEXT =
            java.util.regex.Pattern.compile("-?\\d{1,2}(:\\d{2}){1,2}(\\.\\d{2})?");
    private final java.util.HashMap<String, DigitRoll> rolls = new java.util.HashMap<>();
    private long rollUntil;

    private static final class DigitRoll {
        String text = "";
        char[] from = new char[0];
        long[] start = new long[0];
        long seen;

        private static boolean rolls(String t, int i) {
            return Character.isDigit(t.charAt(i)) && i < t.lastIndexOf(':');
        }

        long update(String t, long now) {
            long until = 0;
            if (t.equals(text)) {
                for (long st : start) until = Math.max(until, st + ROLL_MS);
                return until;
            }
            int n = t.length();
            int shift = n - text.length();
            char[] nf = new char[n];
            long[] ns = new long[n];
            for (int i = 0; i < n; i++) {
                int j = i - shift;
                boolean had = j >= 0 && j < text.length();
                char old = had ? text.charAt(j) : ' ';
                if (old != t.charAt(i) && rolls(t, i)) {
                    nf[i] = old;
                    ns[i] = now;
                } else if (had && now - start[j] < ROLL_MS && text.charAt(j) == t.charAt(i)) {
                    nf[i] = from[j];
                    ns[i] = start[j];
                } else {
                    nf[i] = t.charAt(i);
                    ns[i] = now - ROLL_MS;
                }
                until = Math.max(until, ns[i] + ROLL_MS);
            }
            text = t;
            from = nf;
            start = ns;
            return until;
        }
    }

    private static float easeOutBack(float t) {
        t = clamp01(t);
        float c1 = 1.25f, c3 = c1 + 1f, u = t - 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    private void drawTime(Canvas c, String key, String text, Paint paint, float left, float baseline, boolean countDown) {
        if (text == null) return;
        if (host == null || !TIME_TEXT.matcher(text).matches()) {
            c.drawText(text, left, baseline, paint);
            return;
        }
        long t = now();
        DigitRoll roll = rolls.get(key);
        if (roll == null) {
            if (rolls.size() > 12) rolls.values().removeIf(r -> t - r.seen > 5000);
            roll = new DigitRoll();
            roll.text = text;
            roll.from = text.toCharArray();
            roll.start = new long[text.length()];
            java.util.Arrays.fill(roll.start, t - ROLL_MS);
            rolls.put(key, roll);
        }
        roll.seen = t;
        long until = roll.update(text, t);
        if (until > rollUntil) {
            rollUntil = until;
            scheduleFrame();
        }
        Paint.FontMetrics fm = paint.getFontMetrics();
        float travel = (fm.descent - fm.ascent) * 0.8f;
        float dir = countDown ? -1f : 1f;
        int baseAlpha = paint.getAlpha();
        for (int i = 0; i < text.length(); i++) {
            float x = left + paint.measureText(text, 0, i);
            float p = (t - roll.start[i]) / (float) ROLL_MS;
            if (p >= 1f || roll.from[i] == text.charAt(i)) {
                c.drawText(text, i, i + 1, x, baseline, paint);
                continue;
            }
            float cw = paint.measureText(text, i, i + 1);
            int save = c.save();
            c.clipRect(x - cw * 0.2f, baseline + fm.ascent, x + cw * 1.2f, baseline + fm.descent);
            float out = easeInOut(Math.min(1f, p * 1.25f));
            paint.setAlpha(Math.round(baseAlpha * (1f - out)));
            c.drawText(String.valueOf(roll.from[i]), x, baseline - dir * out * travel, paint);
            float in = easeOutBack(p);
            paint.setAlpha(Math.round(baseAlpha * clamp01(p * 1.8f)));
            c.drawText(text, i, i + 1, x, baseline + dir * (1f - in) * travel, paint);
            paint.setAlpha(baseAlpha);
            c.restoreToCount(save);
        }
    }

    public static boolean hasRunningMetric(IslandItem item) {
        if (item.metrics == null) return false;
        for (IslandItem.Metric m : item.metrics) if (m.running()) return true;
        return false;
    }

    private static boolean runningStopwatch(IslandItem item) {
        return item.chronoMillis && item.trailing == IslandItem.Trailing.CHRONOMETER
                && System.currentTimeMillis() - item.chronoBase < 3_600_000L;
    }

    private IslandItem tickingChronometer() {
        if (mainIn != null && mainIn.content.item.trailing == IslandItem.Trailing.CHRONOMETER) return mainIn.content.item;
        for (Card c : cards) if (c.item.trailing == IslandItem.Trailing.CHRONOMETER) return c.item;
        return null;
    }

    private boolean needsTicks() {
        if (mode == Mode.HIDDEN || !isAttachedToWindow()) return false;
        if (mainIn != null && mainIn.content.item.trailing == IslandItem.Trailing.CHRONOMETER) return true;
        IslandItem detailed = detailedBubbleItem();
        if (detailed != null && (detailed.trailing == IslandItem.Trailing.CHRONOMETER || detailed.progress >= 0)) return true;
        for (Card c : cards) {
            if (c.item.trailing == IslandItem.Trailing.CHRONOMETER || hasRunningMetric(c.item)) return true;
            if (c.item.isMedia && c.item.playing) return true;
        }
        return false;
    }

    private void notifyHost() {
        if (host != null) host.onIslandFrame();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        scheduleTick();
        scheduleFrame();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        choreographer.removeFrameCallback(this);
        frameScheduled = false;
        lastFrameNanos = 0;
        removeCallbacks(secondTick);
        tickPosted = false;
    }


    private float shadeAlpha() {
        return 1f;
    }

    private void computeMain(RectF out) {
        float w = Math.max(0f, width.value());
        float h = Math.max(0f, height.value());
        float top = cy - h / 2f;
        float left = centerFor(w) - w / 2f + offsetX.value();
        out.set(left, top, left + w, top + h);
    }

    private void computeBubble(RectF main, RectF out) {
        float r1 = main.height() / 2f;
        float c1x = main.right - r1;
        float c1y = main.centerY();
        float s = split.value();
        float br = lerp(r1 * 0.72f, bubbleR, clamp01(s));
        float rest = r1 + bubbleGap + bubbleR;
        float bx = c1x + rest * s;
        float extra = Math.max(0f, bubbleExtra.value()) * clamp01(s);
        out.set(bx - br, c1y - br, bx + br + extra, c1y + br);
    }

    private static final float SQUASH = 0.14f;

    private void computeCard(Card c, RectF out) {
        float w = Math.max(0f, c.w.value());
        float h = Math.max(0f, c.h.value());
        float squash = clamp01(Math.abs(c.cy.velocity()) / dp(2600)) * SQUASH;
        h *= 1f + squash;
        w *= 1f - squash * 0.55f;
        float left = cardLeft(c, w);
        if (w < screenW) left = Math.max(Math.min(left, screenW - cardMargin - w), Math.min(cardMargin, left));
        float top = c.cy.value() - h / 2f;
        if (!c.leaving) {
            float k = 1f + 0.16f * c.order;
            float dy = stackDy.value() * k;
            float dx = stackDx.value() * (1f - 0.08f * c.order) + c.dx.value();
            float stretch = Math.min(dp(10), Math.abs(stackDy.value()) * 0.08f);
            top += dy - (stackDy.value() > 0 ? 0f : stretch);
            h += stretch;
            left += dx;
        }
        out.set(left, top, left + w, top + h);
    }

    private float cardLeft(Card c, float w) {
        if (Float.isNaN(anchorLeft)) return cx - w / 2f;
        float bw = c.body.w;
        float finalLeft = anchorLeft;
        if (bw < screenW) finalLeft = Math.max(Math.min(finalLeft, screenW - cardMargin - bw), cardMargin);
        float start = Math.min(bw, pillH * 1.8f);
        float t = clamp01((w - start) / Math.max(1f, bw - start));
        return lerp(anchorLeft + start / 2f, finalLeft + bw / 2f, t) - w / 2f;
    }

    private float expansion(Card c) {
        float full = Math.max(c.body.h, pillH + 1f);
        return clamp01((c.h.value() - pillH) / (full - pillH));
    }


    @Override
    protected void onDraw(Canvas canvas) {
        checkFonts();
        float vis = surfaceFade ? visibility.value() * suppress.value() : visibleAlpha();
        if (screenW == 0 || (vis <= 0.003f && !(mode == Mode.HIDDEN && host.hasMinimized() && !shadeHidden && !suppressed))) return;
        long t = now();
        computeMain(mainRect);
        int save = canvas.save();
        if (rotatedNow()) canvas.rotate(sideDeg, sideCx, sideCy);
        boolean layered = vis < 0.995f;
        if (layered) {
            canvas.saveLayerAlpha(0, 0, screenW, getHeight(), Math.round(255 * vis));
        }

        drawCardGoo(canvas);

        float scale = press.value() * (1f + pulse.value());
        int pillSave = canvas.save();
        canvas.scale(scale, scale, mainRect.centerX(), cy);
        drawPillAndBubble(canvas, t);
        canvas.restoreToCount(pillSave);
        canvas.restoreToCount(save);
    }

    private void drawPillAndBubble(Canvas canvas, long t) {
        float r = mainRect.height() / 2f;
        float s = split.value();
        boolean hasBubble = s > 0.003f;
        float bx = 0, by = 0, br = 0, r1 = 0, c1x = 0, c1y = 0;
        boolean hasNeck = false;
        if (hasBubble) {
            computeBubble(mainRect, bubbleRect);
            br = bubbleRect.height() / 2f;
            bx = bubbleRect.left + br;
            by = bubbleRect.centerY();
            r1 = r;
            c1x = mainRect.right - r1;
            c1y = mainRect.centerY();
            float d = bx - c1x;
            float touching = r1 + br;
            float rest = r1 + bubbleGap + bubbleR;
            float spread = d <= touching ? 0.55f
                    : 0.55f * (1f - clamp01((d - touching) / Math.max(1f, rest - touching - dp(0.5f))));
            neckPath.reset();
            hasNeck = spread > 0.01f && Metaball.neck(c1x, c1y, r1, bx, by, br, spread, neckPath);
        }
        boolean pillVisible = mainRect.width() > 0.5f && mainRect.height() > 0.5f;
        boolean glass = glassActive();
        if (!glass && ((pillVisible && mode == Mode.COMPACT) || hasBubble)) {
            outlinePaint.setStyle(Paint.Style.FILL);
            float o = outlineW;
            if (pillVisible) {
                tmpRect.set(mainRect);
                tmpRect.inset(-o, -o);
                canvas.drawRoundRect(tmpRect, r + o, r + o, outlinePaint);
            }
            if (hasBubble) {
                tmpRect.set(bubbleRect);
                tmpRect.inset(-o, -o);
                canvas.drawRoundRect(tmpRect, br + o, br + o, outlinePaint);
                if (hasNeck) {
                    outlinePaint.setStyle(Paint.Style.FILL_AND_STROKE);
                    outlinePaint.setStrokeWidth(o * 2f);
                    canvas.drawPath(neckPath, outlinePaint);
                    outlinePaint.setStyle(Paint.Style.FILL);
                }
            }
        }
        int mainColor = glass ? GLASS_COLOR : bodyColor(mainIn);
        boolean frost = glass && frostOn();
        int glassSave = glass ? canvas.saveLayerAlpha(0, 0, screenW, getHeight(), Math.round(255 * (frost ? FROST_PILL : GLASS_ALPHA))) : -1;
        shapePaint.setColor(mainColor);
        shapePaint.setAlpha(Math.round(255 * pillBlob.value()));
        if (pillVisible) canvas.drawRoundRect(mainRect, r, r, shapePaint);
        shapePaint.setColor(glass ? GLASS_COLOR : BODY_BLACK);
        shapePaint.setAlpha(255);
        if (hasBubble) {
            shapePaint.setColor(mainColor);
            if (hasNeck) canvas.drawPath(neckPath, shapePaint);
            shapePaint.setColor(glass ? GLASS_COLOR : bodyColor(bubbleIn));
            canvas.drawRoundRect(bubbleRect, br, br, shapePaint);
        }
        shapePaint.setColor(BODY_BLACK);
        if (glass) {
            canvas.restoreToCount(glassSave);
            float rimA = pillBlob.value();
            if (frost && pillVisible && mode == Mode.COMPACT) drawFrost(canvas, mainRect, r, mainRect.height(), rimA);
            if (frost && hasBubble) {
                tmpBlur.set(bubbleRect);
                drawFrost(canvas, tmpBlur, br, tmpBlur.height(), rimA);
            }
            if (pillVisible && mode == Mode.COMPACT) {
                cardStroke.setShader(rimShader(mainRect, rimA));
                tmpRect.set(mainRect);
                tmpRect.inset(outlineW / 2f, outlineW / 2f);
                canvas.drawRoundRect(tmpRect, r, r, cardStroke);
            }
            if (hasBubble) {
                tmpRect.set(bubbleRect);
                cardStroke.setShader(rimShader(tmpRect, rimA));
                tmpRect.inset(outlineW / 2f, outlineW / 2f);
                canvas.drawRoundRect(tmpRect, br - outlineW / 2f, br - outlineW / 2f, cardStroke);
            }
        }

        if (pillVisible) {
            int clipSave = canvas.save();
            clipPath.reset();
            clipPath.addRoundRect(mainRect, r, r, Path.Direction.CW);
            canvas.clipPath(clipPath);
            if (mainOut != null) drawLayer(canvas, mainOut, mainRect, t);
            if (mainIn != null && mode != Mode.EXPANDED) drawLayer(canvas, mainIn, mainRect, t);
            canvas.restoreToCount(clipSave);
        }
        if (hasBubble && s > 0.55f) {
            float a = clamp01((s - 0.55f) / 0.4f);
            if (bubbleOut != null) drawBubbleLayer(canvas, bubbleOut, bubbleRect, t, a);
            if (bubbleIn != null) drawBubbleLayer(canvas, bubbleIn, bubbleRect, t, a);
        }
    }

    private static final int BODY_BLACK = 0xFF000000;

    private int bodyColor(Layer layer) {
        if (layer == null || mode != Mode.COMPACT) return BODY_BLACK;
        IslandItem it = layer.content.item;
        return it.hasTeams() && it.pillColor != 0 ? it.pillColor : BODY_BLACK;
    }

    private android.graphics.LinearGradient rim;
    private float rimH = -1f;
    private float rimTop = Float.NaN;
    private float rimAlpha = -1f;

    private android.graphics.Shader rimShader(RectF r, float alpha) {
        if (rim == null || Math.abs(rimH - r.height()) > 1f || Math.abs(rimTop - r.top) > 1f || Math.abs(rimAlpha - alpha) > 0.02f) {
            rimH = r.height();
            rimTop = r.top;
            rimAlpha = alpha;
            rim = new android.graphics.LinearGradient(0, r.top, 0, r.bottom,
                    Colors.withAlpha(0x38FFFFFF, alpha), Colors.withAlpha(0x0FFFFFFF, alpha), android.graphics.Shader.TileMode.CLAMP);
        }
        return rim;
    }

    private void drawFrost(Canvas canvas, RectF rect, float r, float fullH, float alpha) {
        cardPaint.setColor(0xFF000000);
        cardPaint.setDither(true);
        cardPaint.setShader(frostShade(rect, alpha));
        canvas.drawRoundRect(rect, r, r, cardPaint);
        cardPaint.setShader(null);
        drawTopShadow(canvas, rect, r, fullH, alpha);
        drawBottomGlow(canvas, rect, r, fullH, alpha);
        drawBottomRim(canvas, rect, r, alpha);
    }

    private android.graphics.LinearGradient frostShade;
    private float frostTop = Float.NaN;
    private float frostH = -1f;
    private float frostAlpha = -1f;

    private android.graphics.Shader frostShade(RectF r, float alpha) {
        if (frostShade == null || Math.abs(frostH - r.height()) > 1f || Math.abs(frostTop - r.top) > 1f || Math.abs(frostAlpha - alpha) > 0.02f) {
            frostH = r.height();
            frostTop = r.top;
            frostAlpha = alpha;
            float end = r.top + r.height() * 0.9f;
            int[] colors = new int[FROST_STOPS];
            float[] stops = new float[FROST_STOPS];
            for (int i = 0; i < FROST_STOPS; i++) {
                float f = i / (float) (FROST_STOPS - 1);
                stops[i] = f;
                float k = clamp01((f - 0.2f) / 0.8f);
                float e = k * k * k * (k * (k * 6f - 15f) + 10f);
                colors[i] = Colors.withAlpha(0xFF000000, alpha * 0.73f * (1f - e));
            }
            frostShade = new android.graphics.LinearGradient(0, r.top, 0, end, colors, stops, android.graphics.Shader.TileMode.CLAMP);
        }
        return frostShade;
    }

    private final java.util.HashMap<Long, android.graphics.Bitmap> topShadows = new java.util.HashMap<>();
    private final Paint topShadowPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final android.graphics.Rect shadowSrc = new android.graphics.Rect();
    private final RectF shadowDst = new RectF();

    private android.graphics.Bitmap topShadow(float radius, int depth, boolean wrap) {
        int r = Math.round(radius);
        long key = (long) r << 33 | (long) depth << 1 | (wrap ? 1 : 0);
        android.graphics.Bitmap cached = topShadows.get(key);
        if (cached != null) return cached;
        if (topShadows.size() > 8) topShadows.clear();
        float dy = dp(3);
        float blurR = Math.max(dp(8), (depth - dy) / 2.6f);
        float ex = dp(14);
        float bend = wrap ? 1.7f : 1f;
        float hole = r * bend + ex;
        int half = Math.round(r * bend + dp(6));
        int bw = half * 2 + 1;
        int bh = Math.round(dy + hole + blurR * 4f);
        android.graphics.Bitmap b = android.graphics.Bitmap.createBitmap(bw, bh, android.graphics.Bitmap.Config.ALPHA_8);
        Canvas cv = new Canvas(b);
        Path clip = new Path();
        clip.addRoundRect(0, 0, bw, bh + r * 2f, r, r, Path.Direction.CW);
        cv.clipPath(clip);
        Path shape = new Path();
        shape.setFillType(Path.FillType.EVEN_ODD);
        shape.addRect(-bw, -bh, bw * 2f, bh * 3f, Path.Direction.CW);
        shape.addRoundRect(-ex, dy, bw + ex, bh * 3f, hole, hole, Path.Direction.CW);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(0xFF000000);
        p.setMaskFilter(new BlurMaskFilter(blurR, BlurMaskFilter.Blur.NORMAL));
        cv.drawPath(shape, p);
        topShadows.put(key, b);
        return b;
    }

    private void drawBottomGlow(Canvas canvas, RectF card, float r, float fullH, float alpha) {
        int save = canvas.save();
        canvas.scale(1f, -1f, card.centerX(), card.centerY());
        drawEdgeShade(canvas, card, r, fullH * 0.22f, false, 0xFFFFFFFF, alpha * FROST_GLOW);
        canvas.restoreToCount(save);
    }

    private final Paint bottomRim = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawBottomRim(Canvas canvas, RectF card, float r, float alpha) {
        float w = dp(1.3f);
        bottomRim.setStyle(Paint.Style.STROKE);
        bottomRim.setStrokeWidth(w);
        float top = card.top + card.height() * 0.45f;
        bottomRim.setShader(new LinearGradient(0, top, 0, card.bottom,
                new int[] {0x00FFFFFF, Colors.withAlpha(0xFFFFFFFF, alpha * 0.07f), Colors.withAlpha(0xFFFFFFFF, alpha * 0.24f)},
                new float[] {0f, 0.6f, 1f}, Shader.TileMode.CLAMP));
        tmpRect.set(card);
        tmpRect.inset(w / 2f, w / 2f);
        canvas.drawRoundRect(tmpRect, r - w / 2f, r - w / 2f, bottomRim);
        bottomRim.setShader(null);
    }

    private void drawTopShadow(Canvas canvas, RectF card, float r, float fullH, float alpha) {
        drawEdgeShade(canvas, card, r, fullH * 0.6f, true, 0xFF000000, alpha * FROST_SHADOW);
    }

    private void drawEdgeShade(Canvas canvas, RectF card, float r, float reach, boolean wrap, int color, float alpha) {
        int step = Math.max(1, Math.round(dp(6)));
        int depth = Math.max(step, Math.round(reach / step) * step);
        android.graphics.Bitmap b = topShadow(r, depth, wrap);
        int bw = b.getWidth();
        int bh = b.getHeight();
        int half = bw / 2;
        if (card.width() < bw) return;
        int save = canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(card, r, r, Path.Direction.CW);
        canvas.clipPath(clipPath);
        topShadowPaint.setColor(Colors.withAlpha(color, alpha));
        shadowSrc.set(0, 0, half, bh);
        shadowDst.set(card.left, card.top, card.left + half, card.top + bh);
        canvas.drawBitmap(b, shadowSrc, shadowDst, topShadowPaint);
        shadowSrc.set(half, 0, half + 1, bh);
        shadowDst.set(card.left + half, card.top, card.right - half, card.top + bh);
        canvas.drawBitmap(b, shadowSrc, shadowDst, topShadowPaint);
        shadowSrc.set(bw - half, 0, bw, bh);
        shadowDst.set(card.right - half, card.top, card.right, card.top + bh);
        canvas.drawBitmap(b, shadowSrc, shadowDst, topShadowPaint);
        canvas.restoreToCount(save);
    }

    private final RectF gooA = new RectF();
    private final RectF gooB = new RectF();
    private final Path gooPath = new Path();

    private void drawCardGoo(Canvas canvas) {
        Card upper = null;
        for (Card c : cards) {
            if (!c.leaving) continue;
            if (upper != null) {
                computeCard(upper, gooA);
                computeCard(c, gooB);
                RectF top = gooA.top <= gooB.top ? gooA : gooB;
                RectF bottom = top == gooA ? gooB : gooA;
                float gap = bottom.top - top.bottom;
                float pa = expansion(upper);
                float pb = expansion(c);
                float spread = 0.6f * (1f - clamp01(gap / dp(30))) * (1f - Math.max(pa, pb));
                if (spread > 0.02f) {
                    float ra = Math.min(Math.min(top.height(), top.width()) / 2f, dp(22));
                    float rb = Math.min(Math.min(bottom.height(), bottom.width()) / 2f, dp(22));
                    gooPath.reset();
                    if (Metaball.neck(top.centerX(), top.bottom - ra, ra, bottom.centerX(), bottom.top + rb, rb, spread, gooPath)) {
                        canvas.drawPath(gooPath, shapePaint);
                    }
                }
            }
            upper = c;
        }
    }

    private void drawCard(Canvas canvas, Card c, long t) {
        computeCard(c, cardRect);
        if (cardRect.width() < 1f || cardRect.height() < 1f) return;
        drawCardInner(canvas, c, t, expansion(c));
    }

    private float contentAlpha(Card c, long t, float p) {
        if (c.leaving) return 1f - easeInOut(clamp01((t - c.leaveAt) / 80f));
        return clamp01((p - 0.85f) / 0.15f) * clamp01((t - c.contentStart) / 150f);
    }

    private void drawCardInner(Canvas canvas, Card c, long t, float p) {
        float r = Math.min(cardRect.height() / 2f, cardRadius);
        boolean glass = blur != null && blur.available();
        boolean frost = glass && IslandSettings.frostedCards();
        float g = clamp01((p - 0.82f) / 0.18f);
        float tint = lerp(1f, glass ? (frost ? FROST_TINT : 0.46f) : 0.9f, g);
        cardPaint.setColor(Colors.withAlpha(glass ? 0xFF0E0E10 : 0xFF141416, tint));
        canvas.drawRoundRect(cardRect, r, r, cardPaint);
        if (frost && g > 0.003f) drawFrost(canvas, cardRect, r, c.body.h, g);
        cardStroke.setShader(rimShader(cardRect, 0.4f + 0.6f * p));
        tmpRect.set(cardRect);
        tmpRect.inset(outlineW / 2f, outlineW / 2f);
        canvas.drawRoundRect(tmpRect, r, r, cardStroke);

        float contentAlpha = contentAlpha(c, t, p);
        if (contentAlpha <= 0.003f || c.body instanceof RemoteBody) return;
        int save = canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(cardRect, r, r, Path.Direction.CW);
        canvas.clipPath(clipPath);
        float left = centerFor(c.body.w) - c.body.w / 2f;
        if (c.body.w < screenW) left = Math.max(Math.min(left, screenW - cardMargin - c.body.w), cardMargin);
        if (!c.leaving) left += stackDx.value() * (1f - 0.08f * c.order) + c.dx.value();
        float top = cardRect.top;
        c.body.draw(canvas, left, top, contentAlpha, c.leaving ? Long.MAX_VALUE / 4 : t - c.contentStart);
        canvas.restoreToCount(save);
    }

    private void drawLayer(Canvas c, Layer layer, RectF shape, long t) {
        float a = layer.alpha(t);
        if (a <= 0.003f) return;
        float sc = layer.scale(t);
        int save = c.save();
        c.translate(layer.offset(t) * dp(18), 0f);
        c.scale(sc, sc, shape.centerX(), shape.centerY());
        layer.content.draw(c, shape, a, layer.elapsedIn(t));
        c.restoreToCount(save);
    }

    private void drawBubbleLayer(Canvas c, Layer layer, RectF bubble, long t, float extraAlpha) {
        float a = layer.alpha(t) * extraAlpha;
        if (a <= 0.003f) return;
        layer.content.draw(c, bubble, a, layer.elapsedIn(t));
    }

    private static final android.graphics.ColorMatrixColorFilter GRAYSCALE;

    static {
        android.graphics.ColorMatrix m = new android.graphics.ColorMatrix();
        m.setSaturation(0f);
        GRAYSCALE = new android.graphics.ColorMatrixColorFilter(m);
    }

    private void drawLeadingMono(Canvas c, IslandItem item, float left, float midY, float size, float alpha) {
        if (item.torch) {
            drawTorchCompact(c, item, left, midY, size, alpha);
            return;
        }
        if (item.pulsingDot || item.icon == null) {
            drawLeading(c, item, left, midY, size, alpha);
            return;
        }
        if (item.iconColor) {
            float swell = 1f;
            if (item.ringPercent < 0) {
                long t0 = now();
                if (item.seq > alertIconSeq) {
                    alertIconSeq = item.seq;
                    alertIconAt = t0;
                }
                float t = item.seq == alertIconSeq ? clamp01((t0 - alertIconAt) / (float) TOGGLE_MS) : 1f;
                swell = 1f + 0.16f * (float) Math.sin(Math.PI * Math.min(1f, t * 1.4f)) * (1f - t);
            }
            int save = c.save();
            c.scale(swell, swell, left + size / 2f, midY);
            drawDrawable(c, item.icon, item.iconIsImage, item.iconTint != 0 ? item.iconTint : item.accent, left, midY - size / 2f, size, alpha,
                    item.iconIsImage ? size * 0.2f : 0);
            c.restoreToCount(save);
            return;
        }
        if (!item.iconIsImage) {
            drawDrawable(c, item.icon, false, item.kind == IslandItem.Kind.CALL ? item.accent : 0xFFFFFFFF,
                    left, midY - size / 2f, size, alpha, 0);
            return;
        }
        if (item.isMedia) {
            if (item.cardImage != null) {
                drawArtPaused(c, mediaFx(item), left, midY - size / 2f, size, alpha, size * 0.26f, now(), PAUSED_ART_SHRINK);
            } else {
                drawLeading(c, item, left, midY, size, alpha);
            }
            return;
        }
        drawDrawable(c, item.icon, true, 0, left, midY - size / 2f, size, alpha, size * 0.26f);
    }

    private void drawLeading(Canvas c, IslandItem item, float left, float midY, float size, float alpha) {
        if (item.pulsingDot) {
            float phase = (SystemClock.uptimeMillis() % 1100) / 1100f;
            float pa = 0.55f + 0.45f * (0.5f + 0.5f * (float) Math.cos(phase * Math.PI * 2));
            fillPaint.setColor(Colors.withAlpha(0xFFFF3B30, alpha * pa));
            c.drawCircle(left + size / 2f, midY, size * 0.32f, fillPaint);
            return;
        }
        if (item.icon == null) {
            fillPaint.setColor(Colors.withAlpha(item.accent, alpha));
            c.drawCircle(left + size / 2f, midY, size * 0.3f, fillPaint);
            return;
        }
        drawDrawable(c, item.icon, item.iconIsImage, item.accent, left, midY - size / 2f, size, alpha, size * 0.26f);
    }

    private void drawDrawable(Canvas c, Drawable d, boolean image, int tint, float left, float top, float size,
                              float alpha, float corner) {
        int l = Math.round(left);
        int tp = Math.round(top);
        int sz = Math.round(size);
        d.setBounds(l, tp, l + sz, tp + sz);
        d.setAlpha(Math.round(255 * alpha));
        if (image) {
            int save = c.save();
            tmpRect.set(l, tp, l + sz, tp + sz);
            imageClip.rewind();
            imageClip.addRoundRect(tmpRect, corner, corner, Path.Direction.CW);
            c.clipPath(imageClip);
            d.setTintList(null);
            d.draw(c);
            c.restoreToCount(save);
        } else {
            d.setTint(tint);
            d.draw(c);
        }
    }

    private final Path imageClip = new Path();
    private Shader waveShader;
    private int waveShaderColor;
    private float waveShaderLeft = Float.NaN;
    private float waveShaderRight;
    private final float[] hsv = new float[3];
    private final float[] hsvOut = new float[3];

    private Shader waveShader(int color, float left, float right) {
        if (waveShader != null && color == waveShaderColor && left == waveShaderLeft && right == waveShaderRight) {
            return waveShader;
        }
        android.graphics.Color.colorToHSV(color, hsv);
        hsvOut[0] = (hsv[0] + 30f) % 360f;
        hsvOut[1] = Math.max(0f, hsv[1] - 0.05f);
        hsvOut[2] = Math.min(1f, Math.max(hsv[2], 0.75f) + 0.15f);
        int warm = android.graphics.Color.HSVToColor(hsvOut);
        hsvOut[0] = (hsv[0] + 320f) % 360f;
        hsvOut[1] = Math.min(1f, hsv[1] + 0.05f);
        hsvOut[2] = Math.max(hsv[2], 0.7f);
        int cool = android.graphics.Color.HSVToColor(hsvOut);
        waveShader = new LinearGradient(left, 0, right, 0,
                new int[] {Colors.blend(warm, 0xFFFFFFFF, 0.25f), color, cool}, null, Shader.TileMode.CLAMP);
        waveShaderColor = color;
        waveShaderLeft = left;
        waveShaderRight = right;
        return waveShader;
    }

    private float trailingWidth(IslandItem item, String text) {
        switch (item.trailing) {
            case TEXT:
            case CHRONOMETER:
                return text == null ? 0f : trailPaint.measureText(text);
            case PROGRESS_RING:
                return ringSize;
            case WAVEFORM:
                return waveBars.length * waveBarW + (waveBars.length - 1) * waveGap;
            case CLAUDE:
                return dp(20);
            default:
                return 0f;
        }
    }

    private void drawTrailing(Canvas c, IslandItem item, String text, float right, float midY, float w, float alpha) {
        switch (item.trailing) {
            case TEXT:
            case CHRONOMETER: {
                if (text == null) return;
                int tc = item.trailingColor != 0 ? item.trailingColor
                        : item.kind == IslandItem.Kind.CALL ? item.accent : 0xFFFFFFFF;
                trailPaint.setColor(Colors.withAlpha(tc, alpha));
                Paint.FontMetrics fm = trailPaint.getFontMetrics();
                float baseline = midY - (fm.ascent + fm.descent) / 2f;
                drawTime(c, item.key + "#compact", text, trailPaint, right - w, baseline, item.chronoCountDown);
                break;
            }
            case PROGRESS_RING:
                drawRing(c, right - ringSize / 2f, midY, ringSize / 2f, item.progress,
                        item.accent != 0 ? item.accent : 0xFFFFFFFF, alpha);
                break;
            case CLAUDE:
                drawClaudeRing(c, item, right - w / 2f, midY, w / 2f, alpha);
                break;
            case WAVEFORM: {
                float x = right - w;
                float maxH = dp(18);
                float minH = dp(4f);
                int barColor = item.isMedia ? mediaFx(item).accent(now()) : item.accent;
                fillPaint.setColor(Colors.withAlpha(0xFFFFFFFF, alpha));
                fillPaint.setShader(waveShader(barColor, x, right));
                for (Spring bar : waveBars) {
                    float h = minH + (maxH - minH) * clamp01(bar.value());
                    tmpRect.set(x, midY - h / 2f, x + waveBarW, midY + h / 2f);
                    c.drawRoundRect(tmpRect, waveBarW / 2f, waveBarW / 2f, fillPaint);
                    x += waveBarW + waveGap;
                }
                fillPaint.setShader(null);
                break;
            }
            default:
                break;
        }
    }

    private float recWaveWidth() {
        return dp(56);
    }

    private float checkSize() {
        return dp(19);
    }

    private final java.util.HashMap<String, Long> recFrozen = new java.util.HashMap<>();

    private boolean recAnimating(IslandItem item) {
        return item.recorder && !item.recorderSaved
                && (VoiceLevels.get().live(now()) || item.trailing == IslandItem.Trailing.CHRONOMETER);
    }

    private void drawRecWave(Canvas c, IslandItem item, float left, float midY, float width, float maxH, float alpha, boolean card) {
        VoiceLevels levels = VoiceLevels.get();
        boolean saved = item.recorderSaved;
        long now = now();
        boolean synth = !saved && !levels.live(now) && !levels.recording();
        long t0;
        if (synth) {
            if (item.trailing == IslandItem.Trailing.CHRONOMETER) recFrozen.put(item.key, now);
            Long frozen = recFrozen.get(item.key);
            t0 = frozen != null ? frozen : now;
        } else {
            t0 = levels.renderTime(now, saved);
        }
        float bw = dp(card ? 2.6f : 2.2f);
        float gap = dp(card ? 2.8f : 2.2f);
        float pitch = bw + gap;
        float minH = bw;
        int color = saved ? 0xFFFFFFFF : OaSource.RECORDER_RED;
        float right = left + width;
        long step = VoiceLevels.STEP_MS;
        long base = Math.floorDiv(t0, step) * step;
        float frac = (t0 - base) / (float) step;
        float headX = right - bw;
        if (card) {
            headX = left + width * 0.55f;
            for (int j = 0; ; j++) {
                float dx = headX + (j + 1 - frac) * pitch;
                if (dx > right) break;
                float fade = clamp01((right - dx - bw) / pitch);
                if (fade <= 0f) continue;
                fillPaint.setColor(Colors.withAlpha(0x55FFFFFF, alpha * fade));
                c.drawCircle(dx + bw / 2f, midY, bw / 2f, fillPaint);
            }
        }
        for (int k = 0; ; k++) {
            float bx = headX - (k + frac) * pitch;
            if (bx + bw < left) break;
            float fade = clamp01((bx - left) / pitch);
            if (k == 0 && !card) fade *= clamp01(frac * 2f);
            if (fade <= 0f) continue;
            long t = base - k * step;
            float level = synth ? VoiceLevels.synthetic(t) : levels.levelAt(t);
            if (saved) level *= 0.35f;
            float h = minH + (maxH - minH) * clamp01(level);
            fillPaint.setColor(Colors.withAlpha(color, alpha * fade));
            tmpRect.set(bx, midY - h / 2f, bx + bw, midY + h / 2f);
            c.drawRoundRect(tmpRect, bw / 2f, bw / 2f, fillPaint);
        }
    }

    private final Path checkPath = new Path();

    private void drawCheck(Canvas c, float cx, float cy, float r, float alpha) {
        float stroke = dp(1.6f);
        Paint.Cap cap = strokePaint.getStrokeCap();
        Paint.Join join = strokePaint.getStrokeJoin();
        strokePaint.setStrokeWidth(stroke);
        strokePaint.setColor(Colors.withAlpha(0xFFFFFFFF, alpha));
        c.drawCircle(cx, cy, r - stroke / 2f, strokePaint);
        strokePaint.setStrokeCap(Paint.Cap.ROUND);
        strokePaint.setStrokeJoin(Paint.Join.ROUND);
        checkPath.rewind();
        checkPath.moveTo(cx - r * 0.36f, cy + r * 0.02f);
        checkPath.lineTo(cx - r * 0.1f, cy + r * 0.3f);
        checkPath.lineTo(cx + r * 0.4f, cy - r * 0.3f);
        c.drawPath(checkPath, strokePaint);
        strokePaint.setStrokeCap(cap);
        strokePaint.setStrokeJoin(join);
    }

    private void drawRing(Canvas c, float x, float y, float radius, float progress, int color, float alpha) {
        float stroke = dp(2.6f);
        strokePaint.setStrokeWidth(stroke);
        float rr = radius - stroke / 2f;
        tmpRect.set(x - rr, y - rr, x + rr, y + rr);
        strokePaint.setColor(Colors.withAlpha(0x40FFFFFF, alpha));
        c.drawArc(tmpRect, 0, 360, false, strokePaint);
        strokePaint.setColor(Colors.withAlpha(color, alpha));
        float sweep = progress < 0 ? 90f : 360f * clamp01(progress);
        float start = progress < 0 ? (SystemClock.uptimeMillis() % 1200) / 1200f * 360f : -90f;
        c.drawArc(tmpRect, start, Math.max(sweep, 4f), false, strokePaint);
    }


    private static final java.util.regex.Pattern SCORE =
            java.util.regex.Pattern.compile("(\\S+?)\\s*[-–—:]\\s*(\\S+)");

    private abstract class Content {
        final IslandItem item;

        Content(IslandItem item) {
            this.item = item;
        }

        abstract float desiredWidth();

        abstract void draw(Canvas c, RectF shape, float alpha, long elapsedIn);
    }

    private final class CompactContent extends Content {
        String trailText;
        float trailW;
        final boolean teams;
        float scoreW;
        String scoreA;
        String scoreB;
        final Path idlePath = new Path();
        final Path idleSegment = new Path();
        final PathMeasure idlePathMeasure = new PathMeasure();
        final int[] idleColors = {0xFFFF3B30, 0xFFFF9500, 0xFFFFCC00, 0xFF34C759,
            0xFF00C7BE, 0xFF0A84FF, 0xFFAF52DE};
        float idleGradientWidth = -1f;
        float idleGradientHeight = -1f;

        CompactContent(IslandItem item) {
            super(item);
            teams = item.hasTeams();
            remeasure();
        }

        boolean remeasure() {
            String text = trailingString(item, true);
            float maxTrail = maxTrailingWidth();
            float w;
            if (teams && holeD > 0 && splitScore(text)) {
                float maxHalf = Math.max(0f, maxTrail - leadSize - innerGap);
                scoreA = TextUtils.ellipsize(scoreA, trailPaint, maxHalf, TextUtils.TruncateAt.END).toString();
                scoreB = TextUtils.ellipsize(scoreB, trailPaint, maxHalf, TextUtils.TruncateAt.END).toString();
                scoreW = Math.max(trailPaint.measureText(scoreA), trailPaint.measureText(scoreB));
                w = leadSize + innerGap + scoreW;
            } else if (teams) {
                scoreA = null;
                scoreB = null;
                float maxScore = Math.max(0f, maxTrail - leadSize - innerGap);
                if (text != null && item.trailing == IslandItem.Trailing.TEXT) {
                    text = TextUtils.ellipsize(text, trailPaint, maxScore, TextUtils.TruncateAt.END).toString();
                } else {
                    text = null;
                }
                scoreW = text == null ? 0f : trailPaint.measureText(text);
                w = scoreW + (text == null ? 0f : innerGap) + leadSize;
            } else if (item.recorderSaved) {
                text = null;
                w = checkSize();
            } else {
                if (text != null && item.trailing == IslandItem.Trailing.TEXT) {
                    text = TextUtils.ellipsize(text, trailPaint, maxTrail, TextUtils.TruncateAt.END).toString();
                }
                w = Math.min(trailingWidth(item, text), maxTrail);
            }
            boolean changed = Math.abs(w - trailW) > 0.5f;
            trailText = text;
            trailW = w;
            return changed;
        }

        private boolean splitScore(String text) {
            scoreA = null;
            scoreB = null;
            if (text == null || item.trailing != IslandItem.Trailing.TEXT) return false;
            if (text.trim().matches("[-–—\\s]+")) {
                scoreA = "-";
                scoreB = "-";
                return true;
            }
            java.util.regex.Matcher m = SCORE.matcher(text.trim());
            if (!m.matches()) return false;
            scoreA = m.group(1);
            scoreB = m.group(2);
            return true;
        }

        private float leadWidth() {
            return item.recorder ? recWaveWidth() : leadSize;
        }

        private float maxTrailingWidth() {
            if (holeD > 0) return Math.max(dp(20), (maxPillW - holeD) / 2f - holeGap - padding);
            return Math.max(dp(20), maxPillW - padding * 2 - leadWidth() - innerGap);
        }

        @Override
        float desiredWidth() {
            float w;
            float lead = leadWidth();
            if (holeD > 0) {
                float side = Math.max(lead, trailW);
                w = holeD + 2f * (holeGap + side + padding);
            } else {
                w = padding * 2f + lead + (trailW > 0 ? innerGap + trailW : 0f);
            }
            return Math.max(minPillW, Math.min(w, maxPillW));
        }

        @Override
        void draw(Canvas c, RectF s, float alpha, long elapsedIn) {
            if (item.blank) return;
            if (item.trailing == IslandItem.Trailing.CHRONOMETER) trailText = trailingString(item, true);
            float midY = s.centerY();
            float lead = Math.min(leadSize, s.height() * 0.8f);
            if (teams) {
                drawTeams(c, s, midY, lead, alpha);
                return;
            }
            if (item.recorder) {
                float waveW = Math.min(recWaveWidth(), Math.max(dp(16), s.width() - padding * 2 - (trailW > 0 ? innerGap + trailW : 0f)));
                drawRecWave(c, item, s.left + padding, midY, waveW, s.height() * 0.86f, alpha, false);
                if (item.recorderSaved) {
                    float r = checkSize() / 2f;
                    drawCheck(c, s.right - padding - r, midY, r, alpha);
                } else if (trailW > 0) {
                    drawTrailing(c, item, trailText, s.right - padding, midY, trailW, alpha);
                }
                return;
            }
            if (item.idle) {
                drawIdlePill(c, s, alpha);
                if (IslandSettings.rainbowIdle()) postInvalidateDelayed(66L);
                return;
            }
            drawLeadingMono(c, item, s.left + padding, midY, lead, alpha);
            if (trailW > 0) drawTrailing(c, item, trailText, s.right - padding, midY, trailW, alpha);
        }

        private void drawIdlePill(Canvas c, RectF s, float alpha) {
            if (!IslandSettings.rainbowIdle()) {
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeWidth(dp(1.5f));
                strokePaint.setColor(Colors.withAlpha(item.accent, alpha * 0.22f));
                tmpRect.set(s.left + dp(1.5f), s.top + dp(1.5f), s.right - dp(1.5f), s.bottom - dp(1.5f));
                c.drawRoundRect(tmpRect, s.height() / 2f, s.height() / 2f, strokePaint);
                strokePaint.setStyle(Paint.Style.FILL);
                return;
            }
            float phase = (SystemClock.uptimeMillis() % 1800L) / 1800f;
            if (idleGradientWidth != s.width() || idleGradientHeight != s.height()) {
                tmpRect.set(s.left + dp(1.5f), s.top + dp(1.5f), s.right - dp(1.5f), s.bottom - dp(1.5f));
                idlePath.reset();
                idlePath.addRoundRect(tmpRect, s.height() / 2f, s.height() / 2f, Path.Direction.CW);
                idlePathMeasure.setPath(idlePath, false);
                idleGradientWidth = s.width();
                idleGradientHeight = s.height();
            }
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(dp(1.5f));
            strokePaint.setColor(Colors.withAlpha(item.accent, alpha * 0.22f));
            c.drawPath(idlePath, strokePaint);
            float length = idlePathMeasure.getLength();
            float head = phase * length;
            for (int i = 0; i < idleColors.length; i++) {
                float start = head - (idleColors.length - 1 - i) * length * 0.035f;
                float end = start + length * 0.04f;
                while (start < 0f) {
                    start += length;
                    end += length;
                }
                idleSegment.reset();
                if (end <= length) {
                    idlePathMeasure.getSegment(start, end, idleSegment, true);
                } else {
                    idlePathMeasure.getSegment(start, length, idleSegment, true);
                    idlePathMeasure.getSegment(0f, end - length, idleSegment, true);
                }
                strokePaint.setColor(Colors.withAlpha(idleColors[i], alpha * 0.95f));
                c.drawPath(idleSegment, strokePaint);
            }
            strokePaint.setStyle(Paint.Style.FILL);
        }

        private void drawTeams(Canvas c, RectF s, float midY, float lead, float alpha) {
            float corner = lead * 0.14f;
            if (scoreA != null) {
                float lx = s.left + padding;
                float rx = s.right - padding - lead;
                drawDrawable(c, item.leftImage, true, 0, lx, midY - lead / 2f, lead, alpha, corner);
                drawDrawable(c, item.rightImage, true, 0, rx, midY - lead / 2f, lead, alpha, corner);
                trailPaint.setColor(Colors.withAlpha(0xFFFFFFFF, alpha));
                Paint.FontMetrics fm = trailPaint.getFontMetrics();
                float baseline = midY - (fm.ascent + fm.descent) / 2f;
                c.drawText(scoreA, lx + lead + innerGap, baseline, trailPaint);
                c.drawText(scoreB, rx - innerGap - trailPaint.measureText(scoreB), baseline, trailPaint);
                return;
            }
            drawDrawable(c, item.leftImage, true, 0, s.left + padding, midY - lead / 2f, lead, alpha, corner);
            float x = s.right - padding - lead;
            drawDrawable(c, item.rightImage, true, 0, x, midY - lead / 2f, lead, alpha, corner);
            if (trailText != null) {
                trailPaint.setColor(Colors.withAlpha(0xFFFFFFFF, alpha));
                Paint.FontMetrics fm = trailPaint.getFontMetrics();
                float baseline = midY - (fm.ascent + fm.descent) / 2f;
                c.drawText(trailText, x - innerGap - scoreW, baseline, trailPaint);
            }
        }
    }

    private final java.util.HashMap<String, int[]> scorers = new java.util.HashMap<>();

    private Drawable scorerLogo(IslandItem item) {
        int[] score = parseScore(item.trailingText);
        int[] seen = scorers.get(item.key);
        if (score != null) {
            if (seen == null) {
                seen = new int[] {score[0], score[1], score[1] > score[0] ? 1 : 0};
                if (scorers.size() > 16) scorers.clear();
                scorers.put(item.key, seen);
            } else if (score[0] != seen[0] || score[1] != seen[1]) {
                if (score[1] > seen[1]) seen[2] = 1;
                else if (score[0] > seen[0]) seen[2] = 0;
                seen[0] = score[0];
                seen[1] = score[1];
            }
        }
        return seen != null && seen[2] == 1 ? item.rightImage : item.leftImage;
    }

    private static int[] parseScore(CharSequence text) {
        if (text == null) return null;
        java.util.regex.Matcher m = SCORE.matcher(text.toString().trim());
        if (!m.find()) return null;
        String a = m.group(1).replaceAll("[^0-9]", ""), b = m.group(2).replaceAll("[^0-9]", "");
        if (a.isEmpty() || b.isEmpty() || a.length() > 3 || b.length() > 3) return null;
        return new int[] {Integer.parseInt(a), Integer.parseInt(b)};
    }

    private final class BubbleContent extends Content {
        BubbleContent(IslandItem item) {
            super(item);
        }

        @Override
        float desiredWidth() {
            return bubbleR * 2;
        }

        float extra() {
            if (!IslandSettings.detailedBubble()) return 0f;
            if (item.isMedia) return item.trailing == IslandItem.Trailing.WAVEFORM ? miniWaveWidth() + dp(5) : 0f;
            String s = detailText();
            return s == null ? 0f : bubbleTextPaint().measureText(s) + dp(6);
        }

        String detailText() {
            if (item.isMedia || item.recorder || item.hasTeams()) return null;
            if (item.progress >= 0) return String.valueOf(Math.round(clamp01(item.progress) * 100f));
            long ms;
            if (item.pausedMs >= 0) {
                ms = item.pausedMs;
            } else if (item.trailing == IslandItem.Trailing.CHRONOMETER) {
                long now = System.currentTimeMillis();
                ms = item.chronoCountDown ? item.chronoBase - now : now - item.chronoBase;
            } else {
                return null;
            }
            return shortDuration(Math.max(0, ms));
        }

        @Override
        void draw(Canvas c, RectF b, float alpha, long elapsedIn) {
            float extra = b.width() - b.height();
            if (extra < dp(4) || !IslandSettings.detailedBubble()) {
                drawCore(c, b, alpha);
                return;
            }
            bubbleCore.set(b.left, b.top, b.left + b.height(), b.bottom);
            drawCore(c, bubbleCore, alpha);
            float x = bubbleCore.right - dp(2);
            float midY = b.centerY();
            if (item.isMedia) {
                float a = alpha * clamp01((extra - dp(6)) / dp(14));
                drawMiniWave(c, item, x, midY, b.height() * 0.46f, a);
                return;
            }
            String s = detailText();
            if (s == null) return;
            TextPaint p = bubbleTextPaint();
            float tw = p.measureText(s);
            int tc = item.trailingColor != 0 ? item.trailingColor : 0xFFFFFFFF;
            p.setColor(Colors.withAlpha(tc, alpha * clamp01((extra - dp(4)) / Math.max(1f, tw))));
            Paint.FontMetrics fm = p.getFontMetrics();
            c.drawText(s, x, midY - (fm.ascent + fm.descent) / 2f, p);
        }

        private void drawCore(Canvas c, RectF b, float alpha) {
            float size = b.width() * 0.56f;
            if (item.progress >= 0 && !item.isMedia) {
                drawRing(c, b.centerX(), b.centerY(), b.width() / 2f - dp(2.5f), item.progress,
                        item.accent != 0 ? item.accent : 0xFFFFFFFF, alpha);
                size = b.width() * 0.44f;
            }
            if (item.hasTeams()) {
                float logo = b.width() * 0.6f;
                drawDrawable(c, scorerLogo(item), true, 0, b.centerX() - logo / 2f, b.centerY() - logo / 2f, logo, alpha, logo / 2f);
                return;
            }
            if (item.recorder) {
                float ww = b.width() * 0.62f;
                drawRecWave(c, item, b.centerX() - ww / 2f, b.centerY(), ww, b.height() * 0.5f, alpha, false);
                return;
            }
            drawLeadingMono(c, item, b.centerX() - size / 2f, b.centerY(), size, alpha);
        }
    }


    private final RectF bubbleCore = new RectF();
    private TextPaint bubbleText;

    private TextPaint bubbleTextPaint() {
        if (bubbleText == null) {
            bubbleText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            bubbleText.setTypeface(Fonts.of(600));
            bubbleText.setTextSize(sp(13.5f));
            bubbleText.setFontFeatureSettings("tnum");
        }
        return bubbleText;
    }

    private static String shortDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) return s + "s";
        long m = s / 60;
        if (m < 60) return m + "m";
        return Math.min(99, m / 60) + "h";
    }

    private float miniWaveWidth() {
        return waveBars.length * dp(2.4f) + (waveBars.length - 1) * dp(1.8f);
    }

    private void drawMiniWave(Canvas c, IslandItem item, float left, float midY, float maxH, float alpha) {
        if (alpha <= 0.003f) return;
        float bw = dp(2.4f);
        float gap = dp(1.8f);
        float minH = dp(3f);
        float right = left + miniWaveWidth();
        int barColor = mediaFx(item).accent(now());
        fillPaint.setColor(Colors.withAlpha(0xFFFFFFFF, alpha));
        fillPaint.setShader(waveShader(barColor, left, right));
        float x = left;
        for (Spring bar : waveBars) {
            float h = minH + (maxH - minH) * clamp01(bar.value());
            tmpRect.set(x, midY - h / 2f, x + bw, midY + h / 2f);
            c.drawRoundRect(tmpRect, bw / 2f, bw / 2f, fillPaint);
            x += bw + gap;
        }
        fillPaint.setShader(null);
    }

    private final class Card {
        IslandItem item;
        CardBody body;
        final Spring cy = new Spring(0f, 0.45f, 0.75f).setValueThreshold(0.4f);
        final Spring w = new Spring(0f, 0.45f, 0.75f).setValueThreshold(0.4f);
        final Spring h = new Spring(0f, 0.45f, 0.75f).setValueThreshold(0.4f);
        final Spring dx = new Spring(0f, 0.42f, 0.5f).setValueThreshold(0.3f);
        float targetCy;
        float targetW;
        float targetH;
        long startAt;
        long contentStart;
        long leaveAt;
        long blurOnAt;
        int order;
        boolean launched;
        boolean leaving;

        Card(IslandItem item, CardBody body) {
            this.item = item;
            this.body = body;
        }
    }

    private CardBody makeBody(IslandItem it, float w) {
        return makeBody(it, w, false);
    }

    private CardBody makeBody(IslandItem it, float w, boolean shadeCard) {
        if (it.isMedia) return new MediaBody(it, w, shadeCard && shadeLandscape);
        if (it.claude) return new ClaudeBody(it, w);
        if (it.torch) return new TorchBody(it, w);
        if (it.alert) return new AlertBody(it, w);
        if (!shadeCard && it.remoteView != null && remoteLayer != null) {
            try {
                return new RemoteBody(it, w);
            } catch (Throwable t) {
                dev.flexy.dynamicisland.xposed.XLog.e("RemoteViews card failed for " + it.packageName, t);
            }
        }
        if (it.recorder && !it.recorderSaved) return new RecorderBody(it, w);
        if (it.metrics != null) return new MetricBody(it, w);
        boolean timerLike = it.kind == IslandItem.Kind.TIMER || it.kind == IslandItem.Kind.RECORDING
                || it.kind == IslandItem.Kind.CALL || it.pulsingDot
                || (it.trailing == IslandItem.Trailing.CHRONOMETER && it.progress < 0);
        if (timerLike) return new TimerBody(it, w);
        return new LiveBody(it, w);
    }

    private boolean shadeLandscape;

    /** Shade cards: lay the media player out as one compact row when the screen is landscape. */
    public void setShadeLandscape(boolean on) {
        if (shadeLandscape == on) return;
        shadeLandscape = on;
        shadeBody = null;
    }

    private CardBody shadeBody(IslandItem item, float width) {
        checkFonts();
        if (shadeBody == null || shadeItem != item || shadeWidth != width) {
            CardBody body = makeBody(item, width, true);
            shadeItem = item;
            shadeWidth = width;
            shadeBody = body;
        }
        return shadeBody;
    }

    public float shadeCardHeight(IslandItem item, float width) {
        return shadeBody(item, width).h;
    }

    public void drawShadeCard(Canvas canvas, IslandItem item, float width) {
        if (item.torch) stepShadeTorch();
        shadeBody(item, width).draw(canvas, 0f, 0f, 1f, IN_MS + 3 * ROW_STAGGER_MS);
    }

    public boolean shadeCardHasButton(IslandItem item, float width, float x, float y) {
        return shadeBody(item, width).hit(x, y) != null;
    }

    public boolean shadeMediaSeekStart(IslandItem item, float width, float x, float y) {
        CardBody body = shadeBody(item, width);
        if (!(body instanceof MediaBody) || !((MediaBody) body).onSeekBar(x, y)) return false;
        seekBody = (MediaBody) body;
        seekFraction = seekBody.fractionAt(x);
        return true;
    }

    public void shadeMediaSeekMove(float x) {
        if (seekBody != null) seekFraction = seekBody.fractionAt(x);
    }

    public void shadeMediaSeekEnd(boolean commit) {
        MediaBody body = seekBody;
        seekBody = null;
        if (commit && body != null && body.item.mediaSeek != null) {
            body.item.mediaSeek.accept((long) (seekFraction * body.item.mediaDuration));
        }
    }

    public boolean shadeCardTap(IslandItem item, float width, float x, float y) {
        Btn b = shadeBody(item, width).hit(x, y);
        if (b == null) return false;
        if (b.run != null) b.run.run();
        else if (b.action != null) {
            IslandController c = IslandController.get();
            if (c == null) return false;
            c.onItemAction(item, b.action);
        }
        return true;
    }

    private static final long BTN_DOWN_MS = 110;
    private static final long BTN_UP_MS = 380;
    private static final long SPLASH_MS = 750;
    private static final long SPLASH_WIDE_MS = 1000;
    private final Paint splashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF splashRect = new RectF();
    private BlurMaskFilter splashGlow;
    private final RectF pressedBtn = new RectF();
    private boolean btnHeld;
    private long btnDownAt;
    private long btnUpAt;
    private float btnUpFrom;

    private String pressedKey;

    private void pressButton(Btn b, IslandItem owner) {
        pressedKey = owner == null ? null : owner.key;
        pressedBtn.set(b.r);
        btnHeld = true;
        btnDownAt = now();
        btnUpAt = 0;
    }

    private void releaseButton() {
        if (!btnHeld) return;
        btnHeld = false;
        btnUpFrom = btnPressAmount(now());
        btnUpAt = now();
    }

    private float btnPressAmount(long t) {
        if (btnHeld) return easeOutCubic((t - btnDownAt) / (float) BTN_DOWN_MS);
        if (btnUpAt == 0) return 0f;
        float u = (t - btnUpAt) / (float) BTN_UP_MS;
        if (u >= 1f) return 0f;
        return btnUpFrom * (float) (Math.cos(u * Math.PI * 1.6f) * (1f - u) * (1f - u));
    }

    private static float easeOutCubic(float t) {
        t = clamp01(t);
        float i = 1f - t;
        return 1f - i * i * i;
    }

    boolean buttonAnimating() {
        return btnHeld || (btnUpAt != 0 && now() - btnUpAt < BTN_UP_MS) || (btnDownAt != 0 && now() - btnDownAt < SPLASH_WIDE_MS);
    }

    private float pressOf(Btn b, IslandItem owner) {
        if (!btnHeld && btnUpAt == 0) return 0f;
        if (owner == null || !owner.key.equals(pressedKey)) return 0f;
        if (Math.abs(b.r.centerX() - pressedBtn.centerX()) > 1f || Math.abs(b.r.centerY() - pressedBtn.centerY()) > 1f) return 0f;
        return btnPressAmount(now());
    }

    public boolean shadeButtonDown(IslandItem item, float width, float x, float y) {
        Btn b = shadeBody(item, width).hit(x, y);
        if (b != null) pressButton(b, item);
        return b != null;
    }

    public void shadeButtonUp() {
        releaseButton();
    }

    public boolean shadeButtonAnimating() {
        return buttonAnimating() || torchAnimating();
    }

    private long shadeTorchFrame;

    private void stepShadeTorch() {
        long t = SystemClock.uptimeMillis();
        float dt = shadeTorchFrame == 0 ? 1f / 120f : Math.min(0.05f, (t - shadeTorchFrame) / 1000f);
        shadeTorchFrame = t;
        torchPos.step(dt);
    }

    public boolean shadeTorchStart(IslandItem item, float width, float x, float y) {
        CardBody body = shadeBody(item, width);
        if (!(body instanceof TorchBody) || !((TorchBody) body).onTrack(x, y)) return false;
        torchDrag = (TorchBody) body;
        torchDragStep = torchStep(item, torchPos.value());
        moveTorch(torchDrag, x);
        return true;
    }

    public boolean shadeTorchMove(float x) {
        return torchDrag != null && moveTorch(torchDrag, x);
    }

    public void shadeTorchEnd() {
        endTorchDrag();
    }

    private final class Btn {
        final RectF r = new RectF();
        int glyph;
        Drawable icon;
        int iconTint;
        String label;
        int bg;
        boolean circle;
        boolean tile;
        Runnable run;
        IslandItem.Action action;
    }

    private static final int FROST_BUTTON = 0x38FFFFFF;
    private static final float FROST_TINT = 0.03f;
    private static final int FROST_STOPS = 33;
    private static final int FROST_BLUR = 34;
    private static final float FROST_SHADOW = 0.85f;
    private static final float FROST_GLOW = 0.2f;

    private boolean frostOn() {
        return blur != null && blur.available() && IslandSettings.frostedCards();
    }

    private int frostBg(Btn b) {
        return b.bg == 0xFF3A3A3C && frostOn() ? FROST_BUTTON : b.bg;
    }

    private abstract class CardBody {
        final IslandItem item;
        final float w;
        float h;
        final List<Btn> buttons = new ArrayList<>();

        CardBody(IslandItem item, float w) {
            this.item = item;
            this.w = w;
        }

        abstract void draw(Canvas c, float x, float y, float alpha, long elapsed);

        Btn hit(float lx, float ly) {
            float slop = dp(6);
            for (Btn b : buttons) {
                if (lx >= b.r.left - slop && lx <= b.r.right + slop && ly >= b.r.top - slop && ly <= b.r.bottom + slop) {
                    return b;
                }
            }
            return null;
        }

        float rowAlpha(long elapsed, int row) {
            return clamp01((elapsed - row * ROW_STAGGER_MS) / (float) IN_MS);
        }

        void drawButtons(Canvas c, float x, float y, float alpha) {
            for (Btn b : buttons) {
                tmpRect.set(b.r);
                tmpRect.offset(x, y);
                float pr = pressOf(b, item);
                int save = c.save();
                if (pr != 0f) {
                    if (pr > 0f && !b.tile) {
                        boolean filled = b.circle && (b.bg >>> 24) > 0x20;
                        float rad = Math.max(tmpRect.width(), tmpRect.height()) / 2f + (filled ? dp(5) : b.circle ? 0f : dp(10));
                        fillPaint.setColor(Colors.withAlpha(0xFFFFFFFF, alpha * (filled ? 0.22f : 0.16f) * Math.min(1f, pr)));
                        if (b.circle || b.glyph != 0) c.drawCircle(tmpRect.centerX(), tmpRect.centerY(), rad, fillPaint);
                        else c.drawRoundRect(tmpRect, tmpRect.height() / 2f, tmpRect.height() / 2f, fillPaint);
                    }
                    float sc = 1f - 0.14f * pr;
                    c.scale(sc, sc, tmpRect.centerX(), tmpRect.centerY());
                }
                drawButton(c, b, alpha);
                c.restoreToCount(save);
                drawSplash(c, b, tmpRect, alpha);
            }
        }

        private void drawSplash(Canvas c, Btn b, RectF r, float alpha) {
            if (b.tile || pressedKey == null || !pressedKey.equals(item.key)) return;
            if (Math.abs(b.r.centerX() - pressedBtn.centerX()) > 1f || Math.abs(b.r.centerY() - pressedBtn.centerY()) > 1f) return;
            boolean wide = !b.circle && b.glyph == 0;
            float t = (now() - btnDownAt) / (float) (wide ? SPLASH_WIDE_MS : SPLASH_MS);
            if (t < 0f || t >= 1f) return;
            float e = 1f - (1f - t) * (1f - t) * (1f - t);
            float fade = (1f - t) * (1f - t);
            int tone = b.bg != 0 && (b.bg >>> 24) > 0x80 && b.bg != 0xFF3A3A3C ? Colors.blend(b.bg, 0xFFFFFFFF, 0.5f) : 0xFFFFFFFF;
            if (wide) {
                float grow = dp(2) + dp(9) * e;
                splashRect.set(r.left - grow, r.top - grow, r.right + grow, r.bottom + grow);
                splashPaint.setStyle(Paint.Style.STROKE);
                splashPaint.setStrokeWidth(dp(2.5f) + dp(4) * (1f - e));
                if (splashGlow == null) splashGlow = new BlurMaskFilter(dp(3), BlurMaskFilter.Blur.NORMAL);
                splashPaint.setMaskFilter(splashGlow);
                splashPaint.setColor(Colors.withAlpha(tone, alpha * 0.28f * fade));
                float rad = splashRect.height() / 2f;
                c.drawRoundRect(splashRect, rad, rad, splashPaint);
                splashPaint.setMaskFilter(null);
                splashPaint.setStyle(Paint.Style.FILL);
                return;
            }
            float base = Math.max(r.width(), r.height()) / 2f;
            float ring = base * (0.95f + 1.15f * e);
            float band = dp(9) + dp(11) * e;
            float outer = ring + band;
            splashPaint.setColor(0xFFFFFFFF);
            splashPaint.setShader(new RadialGradient(r.centerX(), r.centerY(), outer,
                    new int[] {Colors.withAlpha(tone, 0f), Colors.withAlpha(tone, 0f), Colors.withAlpha(tone, alpha * 0.4f * fade),
                            Colors.withAlpha(tone, 0f)},
                    new float[] {0f, clamp01((ring - band) / outer), clamp01(ring / outer), 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(r.centerX(), r.centerY(), outer, splashPaint);
            splashPaint.setShader(null);
        }

        private void drawButton(Canvas c, Btn b, float alpha) {
            {
                if (b.tile) {
                    float corner = dp(12);
                    if (b.icon != null) {
                        drawDrawable(c, b.icon, true, 0, tmpRect.left, tmpRect.top, tmpRect.width(), alpha, corner);
                    } else {
                        fillPaint.setColor(Colors.withAlpha(0x2EFFFFFF, alpha));
                        c.drawRoundRect(tmpRect, corner, corner, fillPaint);
                    }
                } else if (b.circle) {
                    fillPaint.setColor(Colors.withAlpha(frostBg(b), alpha));
                    c.drawOval(tmpRect, fillPaint);
                    float gs = tmpRect.height() * (b.bg == 0x00FFFFFF ? 0.6f : 0.44f);
                    if (b.glyph != 0) {
                        fillPaint.setColor(Colors.withAlpha(b.bg == 0xFFFFFFFF ? 0xFF000000 : 0xFFFFFFFF, alpha));
                        if (b.glyph == Glyphs.PLAY || b.glyph == Glyphs.PAUSE) gs = tmpRect.height() * 0.4f;
                        Glyphs.draw(c, b.glyph, tmpRect.centerX(), tmpRect.centerY(), gs, fillPaint);
                    } else if (b.icon != null) {
                        drawDrawable(c, b.icon, false, b.iconTint != 0 ? b.iconTint : 0xFFFFFFFF,
                                tmpRect.centerX() - gs / 2f, tmpRect.centerY() - gs / 2f, gs, alpha, 0);
                    } else if (b.label != null) {
                        drawLabel(c, b.label, tmpRect, alpha);
                    }
                } else if (b.glyph != 0) {
                    fillPaint.setColor(Colors.withAlpha(0xFFFFFFFF, alpha));
                    Glyphs.draw(c, b.glyph, tmpRect.centerX(), tmpRect.centerY(), tmpRect.height() * 0.5f, fillPaint);
                } else {
                    fillPaint.setColor(Colors.withAlpha(frostBg(b), alpha));
                    c.drawRoundRect(tmpRect, tmpRect.height() / 2f, tmpRect.height() / 2f, fillPaint);
                    if (b.label != null) drawLabel(c, b.label, tmpRect, alpha);
                    else if (b.icon != null) {
                        float side = Math.min(tmpRect.width(), tmpRect.height());
                        b.icon.setBounds(Math.round(tmpRect.centerX() - side / 2f), Math.round(tmpRect.centerY() - side / 2f),
                                Math.round(tmpRect.centerX() + side / 2f), Math.round(tmpRect.centerY() + side / 2f));
                        b.icon.setAlpha(Math.round(255 * alpha));
                        b.icon.draw(c);
                    }
                }
            }
        }

        private void drawLabel(Canvas c, String label, RectF r, float alpha) {
            buttonPaint.setColor(Colors.withAlpha(0xFFFFFFFF, alpha));
            String text = TextUtils.ellipsize(label, buttonPaint, r.width() - dp(14), TextUtils.TruncateAt.END).toString();
            Paint.FontMetrics fm = buttonPaint.getFontMetrics();
            c.drawText(text, r.centerX(), r.centerY() - (fm.ascent + fm.descent) / 2f, buttonPaint);
        }

        float layoutTextButtons(float top, float pad) {
            int n = Math.min(3, item.actions.size());
            if (n == 0) return 0f;
            float gap = dp(8);
            float bw = (w - pad * 2 - gap * (n - 1)) / n;
            float bh = dp(40);
            for (int i = 0; i < n; i++) {
                IslandItem.Action a = item.actions.get(i);
                Btn b = new Btn();
                b.r.set(pad + i * (bw + gap), top, pad + i * (bw + gap) + bw, top + bh);
                b.label = a.title == null ? null : a.title.toString();
                b.bg = a.destructive ? BUTTON_RED : 0x2EFFFFFF;
                b.action = a;
                buttons.add(b);
            }
            return bh;
        }
    }

    private final class RemoteBody extends CardBody {
        final float pad = dp(14);
        final View view;

        RemoteBody(IslandItem item, float w) {
            super(item, w);
            int cw = Math.max(1, Math.round(w - pad * 2));
            view = remoteViewFor(item);
            view.measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.round(dp(300)), MeasureSpec.AT_MOST));
            int ch = Math.max(Math.round(dp(40)), view.getMeasuredHeight());
            android.view.ViewGroup.LayoutParams lp = view.getLayoutParams();
            if (lp == null || lp.width != cw || lp.height != ch) {
                view.setLayoutParams(new android.widget.FrameLayout.LayoutParams(cw, ch));
            }
            h = ch + pad * 2;
        }

        @Override
        void draw(Canvas c, float x, float y, float alpha, long elapsed) {
        }
    }

    private final class LiveBody extends CardBody {
        final float pad = dp(18);
        final float iconSize = dp(22);
        final StaticLayout title;
        final StaticLayout subtitle;
        final StaticLayout detail;
        String trail;
        float progressTop = -1;
        float actionsTop = -1;
        final boolean teams;
        final float logoSize = dp(52);
        String score;

        LiveBody(IslandItem item, float w) {
            super(item, w);
            teams = item.hasTeams();
            if (teams && item.trailing == IslandItem.Trailing.TEXT && item.trailingText != null) {
                score = item.trailingText.toString();
            }
            int textW = Math.max(1, Math.round(w - pad * 2 - (teams ? 0f : iconSize + dp(12))));
            CharSequence t = item.title != null ? item.title : item.appName;
            title = t == null ? null : layout(t, titlePaint, textW, 4);
            CharSequence sub = item.subtitle;
            if (item.trailing == IslandItem.Trailing.TEXT && item.trailingText != null && sub == null) sub = item.trailingText;
            subtitle = sub == null ? null : layout(sub, bodyPaint, textW, 4);
            detail = item.detail == null ? null : layout(item.detail, bodyPaint, textW, 2);
            float y = pad;
            if (teams) y += logoSize + dp(10);
            if (title != null) y += title.getHeight();
            if (subtitle != null) y += dp(3) + subtitle.getHeight();
            if (detail != null) y += dp(2) + detail.getHeight();
            y = Math.max(y, pad + (teams ? 0f : iconSize));
            if (item.progress >= 0) {
                progressTop = y + dp(16);
                y = progressTop + dp(7);
            }
            if (!item.actions.isEmpty()) {
                actionsTop = y + dp(14);
                y = actionsTop + layoutTextButtons(actionsTop, pad);
            }
            h = y + pad;
        }

        @Override
        void draw(Canvas c, float x, float y, float alpha, long el) {
            float a0 = alpha * rowAlpha(el, 0);
            float dy0 = (1f - rowAlpha(el, 0)) * dp(8);
            float ty = y + pad + dy0;
            if (teams) {
                float midY = ty + logoSize / 2f;
                drawDrawable(c, item.leftImage, true, 0, x + pad, ty, logoSize, a0, logoSize * 0.14f);
                drawDrawable(c, item.rightImage, true, 0, x + w - pad - logoSize, ty, logoSize, a0, logoSize * 0.14f);
                if (score != null) {
                    bigTimePaint.setColor(Colors.withAlpha(0xFFFFFFFF, a0));
                    float room = w - pad * 2 - logoSize * 2 - dp(16);
                    String text = TextUtils.ellipsize(score, bigTimePaint, room, TextUtils.TruncateAt.END).toString();
                    Paint.FontMetrics fm = bigTimePaint.getFontMetrics();
                    float tw = bigTimePaint.measureText(text);
                    c.drawText(text, x + w / 2f - tw / 2f, midY - (fm.ascent + fm.descent) / 2f, bigTimePaint);
                }
                ty += logoSize + dp(10);
            }
            if (title != null) {
                titlePaint.setColor(Colors.withAlpha(0xFFFFFFFF, a0));
                drawLayout(c, title, x + pad, ty);
                ty += title.getHeight() + dp(3);
            }
            if (subtitle != null) {
                bodyPaint.setColor(Colors.withAlpha(0xD9FFFFFF, a0));
                drawLayout(c, subtitle, x + pad, ty);
                ty += subtitle.getHeight() + dp(2);
            }
            if (detail != null) {
                bodyPaint.setColor(Colors.withAlpha(0x99FFFFFF, a0));
                drawLayout(c, detail, x + pad, ty);
            }
            if (item.cardIcon != null && !teams) {
                drawDrawable(c, item.cardIcon, true, 0, x + w - pad - iconSize, y + pad + dy0, iconSize, a0,
                        iconSize * 0.26f);
            } else if (item.icon != null && !teams) {
                drawDrawable(c, item.icon, item.iconIsImage, 0xFFFFFFFF, x + w - pad - iconSize, y + pad + dy0, iconSize, a0,
                        iconSize * 0.26f);
            }
            if (progressTop >= 0) {
                float ra = rowAlpha(el, 1);
                float a1 = alpha * ra;
                float top = y + progressTop + (1f - ra) * dp(8);
                float bh = dp(7);
                tmpRect.set(x + pad, top, x + w - pad, top + bh);
                fillPaint.setColor(Colors.withAlpha(0x2EFFFFFF, a1));
                c.drawRoundRect(tmpRect, bh / 2f, bh / 2f, fillPaint);
                float fill = (w - pad * 2) * clamp01(item.progress);
                tmpRect.set(x + pad, top, x + pad + Math.max(bh, fill), top + bh);
                fillPaint.setColor(Colors.withAlpha(item.accent, a1));
                c.drawRoundRect(tmpRect, bh / 2f, bh / 2f, fillPaint);
            }
            if (actionsTop >= 0) {
                float ra = rowAlpha(el, 2);
                drawButtons(c, x, y + (1f - ra) * dp(8), alpha * ra);
            }
        }
    }

    private long toggleSeq;
    private long toggleAt;
    private int toggleFrom;
    private static final long TOGGLE_MS = 450;
    private static final long RIPPLE_MS = 1600;
    private static final int TOGGLE_OFF_BG = 0x40FFFFFF;
    private static final int TOGGLE_ON_FALLBACK = 0xFF5B8DF9;

    private long alertIconSeq;
    private long alertIconAt;

    boolean toggleAnimating() {
        return (toggleSeq != 0 && now() - toggleAt < TOGGLE_MS)
                || (alertIconSeq != 0 && now() - alertIconAt < TOGGLE_MS);
    }

    private final class AlertBody extends CardBody {
        final float pad;
        final float lead;
        final float ring = dp(52);
        final String overline;
        final StaticLayout title;
        final String trail;
        final StaticLayout subtitle;
        final float rowH;
        float actionsTop = -1;
        final float pillW = dp(76);
        final float pillH = dp(46);

        AlertBody(IslandItem item, float w) {
            super(item, w);
            boolean toggle = item.iconTap != null;
            pad = toggle || item.iconRight ? dp(14) : dp(18);
            lead = item.iconRight ? -dp(16) : toggle ? dp(44) : dp(64);
            float right = item.iconRight ? pillW : item.ringPercent >= 0 ? ring
                    : item.trailingText == null ? 0f : titlePaint.measureText(item.trailingText.toString());
            float textW = Math.max(dp(40), w - pad * 2 - lead - dp(16) - right - dp(12));
            subtitle = item.iconRight && item.subtitle != null ? layout(item.subtitle, bodyPaint, Math.round(textW), 2) : null;
            overline = item.overline == null ? null
                    : TextUtils.ellipsize(item.overline, bodyPaint, textW, TextUtils.TruncateAt.END).toString();
                boolean message = item.key.startsWith("msg:");
                title = item.title == null ? null
                    : layout(item.title, message ? bodyPaint : titlePaint, Math.round(textW), message ? 4 : 2);
            trail = item.trailingText == null ? null : item.trailingText.toString();
            Paint.FontMetrics bf = bodyPaint.getFontMetrics();
            float overH = overline == null ? 0f : bf.descent - bf.ascent + dp(1);
            float textH = overH + (title == null ? 0f : title.getHeight())
                    + (subtitle == null ? 0f : dp(2) + subtitle.getHeight());
            rowH = Math.max(item.iconRight ? pillH : lead, textH);
            h = pad * 2 + rowH;
            if (!item.actions.isEmpty()) {
                actionsTop = h + dp(14);
                h = actionsTop + layoutTextButtons(actionsTop, pad) + pad;
            }
            if (item.iconRight) {
                Btn b = new Btn();
                b.r.set(w - pad - pillW, pad + (rowH - pillH) / 2f, w - pad, pad + (rowH + pillH) / 2f);
                b.bg = Colors.withAlpha(item.accent, 0.22f);
                b.icon = item.cardImage != null ? item.cardImage : item.icon;
                b.run = item.trailingTap;
                buttons.add(b);
            }
            if (item.iconTap != null) {
                Btn b = new Btn();
                b.r.set(pad, pad + (rowH - lead) / 2f, pad + lead, pad + (rowH + lead) / 2f);
                b.circle = true;
                b.bg = 0xFFFFFFFF;
                b.icon = item.icon;
                b.iconTint = item.accent;
                b.run = item.iconTap;
                buttons.add(b);
            }
        }

        private void drawPillRipple(Canvas c, RectF pill, float x, float y, float alpha) {
            float t = (now() % RIPPLE_MS) / (float) RIPPLE_MS;
            float e = 1f - (1f - t) * (1f - t) * (1f - t);
            float grow = dp(2) + dp(14) * e;
            splashRect.set(x + pill.left - grow, y + pill.top - grow, x + pill.right + grow, y + pill.bottom + grow);
            splashPaint.setShader(null);
            splashPaint.setStyle(Paint.Style.STROKE);
            splashPaint.setStrokeWidth(dp(3) + dp(4) * (1f - e));
            if (splashGlow == null) splashGlow = new BlurMaskFilter(dp(3), BlurMaskFilter.Blur.NORMAL);
            splashPaint.setMaskFilter(splashGlow);
            splashPaint.setColor(Colors.withAlpha(item.accent, alpha * 0.45f * (1f - t) * (1f - t)));
            float rad = splashRect.height() / 2f;
            c.drawRoundRect(splashRect, rad, rad, splashPaint);
            splashPaint.setMaskFilter(null);
            splashPaint.setStyle(Paint.Style.FILL);
        }

        @Override
        void draw(Canvas c, float x, float y, float alpha, long el) {
            float a0 = alpha * rowAlpha(el, 0);
            float dy = (1f - rowAlpha(el, 0)) * dp(8);
            float midY = y + pad + rowH / 2f + dy;
            if (item.iconRight) {
                if (!buttons.isEmpty()) drawPillRipple(c, buttons.get(0).r, x, y + dy, a0);
                drawButtons(c, x, y + dy, a0);
            } else if (item.iconTap != null) {
                int save = c.save();
                if (item.toggle >= 0 && !buttons.isEmpty()) {
                    Btn b = buttons.get(0);
                    long t0 = now();
                    if (item.seq > toggleSeq) {
                        toggleSeq = item.seq;
                        toggleAt = t0;
                        toggleFrom = item.toggle == 1 ? TOGGLE_OFF_BG : TOGGLE_ON_FALLBACK;
                    }
                    int to = item.toggle == 1 ? item.accent : TOGGLE_OFF_BG;
                    float t = item.seq == toggleSeq ? clamp01((t0 - toggleAt) / (float) TOGGLE_MS) : 1f;
                    b.bg = Colors.blend(toggleFrom, to, easeOutCubic(t));
                    b.iconTint = 0xFFFFFFFF;
                    float swell = 1f + 0.10f * (float) Math.sin(Math.PI * Math.min(1f, t * 1.4f)) * (1f - t);
                    c.scale(swell, swell, x + b.r.centerX(), y + dy + b.r.centerY());
                }
                drawButtons(c, x, y + dy, a0);
                c.restoreToCount(save);
            } else {
                Drawable d = item.cardImage != null ? item.cardImage : item.icon;
                if (d != null) drawDrawable(c, d, item.iconIsImage, item.iconTint != 0 ? item.iconTint : item.accent, x + pad, midY - lead / 2f, lead, a0, lead * 0.22f);
                if (actionsTop >= 0) drawButtons(c, x, y + actionsTop + dy, a0 * rowAlpha(el, 2));
            }
            float tx = x + pad + lead + dp(16);
            Paint.FontMetrics tf = titlePaint.getFontMetrics();
            Paint.FontMetrics bf = bodyPaint.getFontMetrics();
            float titleH = title == null ? 0f : title.getHeight();
            float overH = overline == null ? 0f : bf.descent - bf.ascent + dp(1);
            float subH = subtitle == null ? 0f : dp(2) + subtitle.getHeight();
            float top = midY - (titleH + overH + subH) / 2f;
            if (overline != null) {
                bodyPaint.setColor(Colors.withAlpha(item.overlineColor != 0 ? item.overlineColor : 0x99FFFFFF, a0));
                c.drawText(overline, tx, top - bf.ascent, bodyPaint);
            }
            if (title != null) {
                titlePaint.setColor(Colors.withAlpha(0xFFFFFFFF, a0));
                drawLayout(c, title, tx, top + overH);
            }
            if (subtitle != null) {
                bodyPaint.setColor(Colors.withAlpha(0x99FFFFFF, a0));
                drawLayout(c, subtitle, tx, top + overH + titleH + dp(2));
            }
            float right = x + w - pad;
            if (item.iconRight) {
            } else if (item.ringPercent >= 0) {
                float r = ring / 2f;
                float cx = right - r;
                float stroke = dp(4.5f);
                strokePaint.setStrokeWidth(stroke);
                tmpRect.set(cx - r + stroke / 2f, midY - r + stroke / 2f, cx + r - stroke / 2f, midY + r - stroke / 2f);
                strokePaint.setColor(Colors.withAlpha(Colors.blend(item.accent, 0xFF000000, 0.6f), a0));
                c.drawArc(tmpRect, 0, 360, false, strokePaint);
                strokePaint.setColor(Colors.withAlpha(item.accent, a0));
                c.drawArc(tmpRect, -90, Math.max(4f, 3.6f * Math.min(100, item.ringPercent)), false, strokePaint);
                String n = String.valueOf(item.ringPercent);
                titlePaint.setColor(Colors.withAlpha(item.accent, a0));
                c.drawText(n, cx - titlePaint.measureText(n) / 2f, midY - (tf.ascent + tf.descent) / 2f, titlePaint);
            } else if (trail != null) {
                titlePaint.setColor(Colors.withAlpha(item.trailingColor != 0 ? item.trailingColor : item.accent, a0));
                c.drawText(trail, right - titlePaint.measureText(trail), midY - (tf.ascent + tf.descent) / 2f, titlePaint);
            }
        }
    }

    private static final int TORCH_OFF = 0xFF2C2C31;
    private static final long TORCH_IGNITE_MS = 620;
    private static final long TORCH_FADE_MS = 460;
    private final Spring torchPos = new Spring(1f, 0.32f, 0.62f).setValueThreshold(0.001f);
    private boolean torchSeen;
    private boolean torchTarget;
    private long torchSwitchAt;
    private float torchFrom;
    private long torchLocalAt;
    private long torchSetAt;
    private TorchBody torchDrag;
    private int torchDragStep;
    private final Path torchPath = new Path();
    private final Path torchNeck = new Path();
    private final Path torchCone = new Path();
    private final android.graphics.Matrix torchMatrix = new android.graphics.Matrix();
    private final Paint torchPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private BlurMaskFilter torchBlur;
    private float torchBlurSize;

    private static float torchFrac(IslandItem it, boolean on) {
        return on ? it.torchLevel / (float) Math.max(1, it.torchMax) : 0f;
    }

    private static int torchStep(IslandItem it, float f) {
        return Math.round(clamp01(f) * Math.max(1, it.torchMax));
    }

    private void syncTorch(IslandItem it) {
        long t = now();
        if (!torchSeen) {
            torchSeen = true;
            torchTarget = it.torchOn;
            torchSwitchAt = t - TORCH_IGNITE_MS;
            torchFrom = it.torchOn ? 1f : 0f;
            torchPos.snapTo(torchFrac(it, it.torchOn));
        }
        if (it.torchOn != torchTarget && t - torchLocalAt > 1500) setTorchTarget(it.torchOn);
        float want = torchFrac(it, torchTarget);
        if (torchDrag == null && t - torchSetAt > 900 && Math.abs(torchPos.target() - want) > 0.001f) {
            torchPos.animateTo(want, 0.32f, 0.62f);
            scheduleFrame();
        }
    }

    private void setTorchTarget(boolean on) {
        if (on == torchTarget) return;
        long t = now();
        torchFrom = torchExtent(t);
        torchTarget = on;
        torchSwitchAt = t;
        scheduleFrame();
    }

    private float torchU(long t) {
        return clamp01((t - torchSwitchAt) / (float) (torchTarget ? TORCH_IGNITE_MS : TORCH_FADE_MS));
    }

    private float torchGlow(long t) {
        float u = torchU(t);
        if (torchTarget) return torchFrom + (1f - torchFrom) * easeOutCubic(Math.min(1f, u * 2.4f));
        return torchFrom * (1f - easeInOut(clamp01((u - 0.55f) / 0.45f)));
    }

    private float torchExtent(long t) {
        float u = torchU(t);
        if (torchTarget) return torchFrom + (1f - torchFrom) * easeOutCubic(u);
        return torchFrom * (1f - easeInOut(Math.min(1f, u * 1.15f)));
    }

    private float torchFlare(long t) {
        if (!torchTarget) return 0f;
        float u = torchU(t);
        return (float) Math.sin(Math.PI * clamp01((u - 0.35f) / 0.65f)) * 0.22f;
    }

    private boolean torchAnimating() {
        if (!torchSeen) return false;
        return torchDrag != null || !torchPos.isAtRest() || now() - torchSwitchAt < Math.max(TORCH_IGNITE_MS, TORCH_FADE_MS) + 40;
    }

    private boolean moveTorch(TorchBody tb, float lx) {
        float f = tb.fractionAt(lx);
        torchPos.snapTo(f);
        int step = torchStep(tb.item, f);
        boolean changed = step != torchDragStep;
        if (changed) {
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            torchDragStep = step;
            if (torchTarget != (step > 0)) {
                torchLocalAt = now();
                setTorchTarget(step > 0);
            }
            torchSetAt = now();
            if (tb.item.torchSet != null) tb.item.torchSet.accept(step);
        }
        scheduleFrame();
        invalidate();
        return changed;
    }

    private void endTorchDrag() {
        TorchBody tb = torchDrag;
        torchDrag = null;
        if (tb == null) return;
        int step = torchStep(tb.item, torchPos.value());
        float target = step / (float) Math.max(1, tb.item.torchMax);
        torchPos.animateTo(target, 0.34f, 0.58f);
        torchSetAt = now();
        if (step != torchDragStep && tb.item.torchSet != null) tb.item.torchSet.accept(step);
        torchDragStep = step;
        scheduleFrame();
    }

    private void torchTap(IslandItem it) {
        torchLocalAt = now();
        torchSetAt = now();
        setTorchTarget(!torchTarget);
        torchPos.animateTo(torchFrac(it, torchTarget), 0.42f, 0.9f);
        if (it.torchToggle != null) it.torchToggle.run();
    }

    private void torchGlyph(Canvas c, float cx, float cy, float len, float deg, int color, float alpha, float glow) {
        int save = c.save();
        c.translate(cx, cy);
        c.rotate(deg);
        float half = len / 2f;
        float capT = 0.11f * len;
        float gap = 0.04f * len;
        float taper = 0.20f * len;
        float capH = 0.46f * len;
        float bodyH = 0.29f * len;
        float capL = half - capT;
        float neckR = capL - gap;
        float neckL = neckR - taper;
        torchPath.rewind();
        float rr = bodyH * 0.28f;
        tmpRect.set(-half, -bodyH / 2f, neckL + rr, bodyH / 2f);
        torchPath.addRoundRect(tmpRect, rr, rr, Path.Direction.CW);
        torchNeck.rewind();
        torchNeck.moveTo(neckL, -bodyH / 2f);
        torchNeck.lineTo(neckR, -capH / 2f);
        torchNeck.lineTo(neckR, capH / 2f);
        torchNeck.lineTo(neckL, bodyH / 2f);
        torchNeck.close();
        torchPath.op(torchNeck, Path.Op.UNION);
        float cr = capT * 0.42f;
        tmpRect.set(capL, -capH / 2f, half, capH / 2f);
        torchPath.addRoundRect(tmpRect, cr, cr, Path.Direction.CW);
        if (glow > 0.01f) {
            float blur = len * 0.16f;
            if (torchBlur == null || Math.abs(torchBlurSize - blur) > 0.5f) {
                torchBlur = new BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL);
                torchBlurSize = blur;
            }
            torchPaint.setShader(null);
            torchPaint.setMaskFilter(torchBlur);
            torchPaint.setColor(Colors.withAlpha(FlashlightSource.ACCENT, alpha * 0.75f * glow));
            c.drawPath(torchPath, torchPaint);
            torchPaint.setMaskFilter(null);
        }
        torchPaint.setColor(Colors.withAlpha(color, alpha));
        c.drawPath(torchPath, torchPaint);
        c.restoreToCount(save);
    }

    private static float torchLit(float f) {
        return 0.2f + 0.8f * clamp01(f);
    }

    private static int torchColor(float lit, float g) {
        return Colors.blend(TORCH_OFF, Colors.blend(0xFF9C99CF, 0xFFF2F0FF, lit), g);
    }

    private void beamColumn(Canvas c, float hx, float cy, float reach, float mouth, float far, float blur, Shader shader) {
        float tip = hx + reach;
        torchCone.rewind();
        torchCone.moveTo(hx - dp(4), cy - mouth);
        torchCone.cubicTo(hx + reach * 0.3f, cy - far * 1.1f, tip - reach * 0.2f, cy - far, tip, cy - far * 0.72f);
        torchCone.quadTo(tip + far, cy, tip, cy + far * 0.72f);
        torchCone.cubicTo(tip - reach * 0.2f, cy + far, hx + reach * 0.3f, cy + far * 1.1f, hx - dp(4), cy + mouth);
        torchCone.close();
        drawBlurred(c, torchCone, blur, shader);
    }

    private final java.util.HashMap<Integer, BlurMaskFilter> torchBlurs = new java.util.HashMap<>();

    private void drawBlurred(Canvas c, Path path, float radius, Shader shader) {
        int key = Math.round(radius);
        BlurMaskFilter blur = torchBlurs.get(key);
        if (blur == null) {
            blur = new BlurMaskFilter(Math.max(1f, radius), BlurMaskFilter.Blur.NORMAL);
            torchBlurs.put(key, blur);
        }
        torchPaint.setMaskFilter(blur);
        torchPaint.setShader(shader);
        torchPaint.setColor(0xFFFFFFFF);
        c.drawPath(path, torchPaint);
        torchPaint.setShader(null);
        torchPaint.setMaskFilter(null);
    }

    private void radialGlow(Canvas c, float cx, float cy, float rx, float ry, int[] colors, float[] stops) {
        if (rx < 1f || ry < 1f) return;
        RadialGradient g = new RadialGradient(0f, 0f, 1f, colors, stops, Shader.TileMode.CLAMP);
        torchMatrix.setScale(rx, ry);
        torchMatrix.postTranslate(cx, cy);
        g.setLocalMatrix(torchMatrix);
        torchPaint.setShader(g);
        tmpRect.set(cx - rx, cy - ry, cx + rx, cy + ry);
        c.drawOval(tmpRect, torchPaint);
        torchPaint.setShader(null);
    }

    private void drawTorchCompact(Canvas c, IslandItem item, float left, float midY, float size, float alpha) {
        syncTorch(item);
        float g = torchGlow(now());
        float lit = torchLit(torchPos.value());
        float cx = left + size / 2f;
        if (g > 0.01f) {
            int a = FlashlightSource.ACCENT;
            float k = g * (0.35f + 0.65f * lit);
            radialGlow(c, cx, midY, size * 0.8f, size * 0.8f,
                    new int[] {Colors.withAlpha(a, alpha * 0.55f * k), Colors.withAlpha(a, alpha * 0.22f * k), Colors.withAlpha(a, 0f)},
                    new float[] {0f, 0.45f, 1f});
        }
        torchGlyph(c, cx, midY, size * 0.86f, -90f, Colors.blend(0xFF8E8E93,Colors.blend(0xFF9C99CF, 0xFFF2F0FF, lit), g), alpha,
                g * (0.3f + 0.4f * lit));
    }

    private final class TorchBody extends CardBody {
        final float pad = dp(20);
        final float len = dp(66);
        final float iconCx;
        final float headX;
        final float x0;
        final float x1;

        TorchBody(IslandItem item, float w) {
            super(item, w);
            h = dp(132);
            iconCx = pad + dp(6) + len / 2f;
            headX = iconCx + len / 2f;
            x0 = headX + dp(30);
            x1 = w - pad - dp(24);
            Btn b = new Btn();
            b.r.set(iconCx - len / 2f - dp(8), h / 2f - dp(30), headX + dp(8), h / 2f + dp(30));
            b.bg = 0;
            b.run = () -> torchTap(item);
            buttons.add(b);
        }

        boolean onTrack(float lx, float ly) {
            return lx >= headX + dp(6) && ly >= 0 && ly <= h;
        }

        float fractionAt(float lx) {
            return clamp01((lx - x0) / Math.max(1f, x1 - x0));
        }

        @Override
        void draw(Canvas c, float x, float y, float alpha, long el) {
            syncTorch(item);
            float a0 = alpha * rowAlpha(el, 0);
            long t = now();
            float g = torchGlow(t);
            float e = torchExtent(t);
            float flare = torchFlare(t);
            float f = clamp01(torchPos.value());
            float cy = y + h / 2f;
            float hx = x + headX;
            float mx = x + x0 + f * (x1 - x0);
            int accent = FlashlightSource.ACCENT;
            float lit = torchLit(f);
            float full = x + x1 + dp(14) - hx;
            float reach = Math.max(0f, full * e);
            if (g > 0.003f && reach > dp(2)) {
                float k = g * lit * (1f + flare);
                float tip = hx + reach;
                float mouth = len * 0.23f;
                float hazeFar = dp(84) * e;
                torchCone.rewind();
                torchCone.moveTo(hx - dp(6), cy - mouth * 1.1f);
                torchCone.lineTo(tip, cy - hazeFar);
                torchCone.lineTo(tip, cy + hazeFar);
                torchCone.lineTo(hx - dp(6), cy + mouth * 1.1f);
                torchCone.close();
                drawBlurred(c, torchCone, dp(32), new LinearGradient(hx, 0, tip, 0,
                        new int[] {Colors.withAlpha(0xFFCFCBEA, a0 * 0.52f * k), Colors.withAlpha(0xFFB9B2CF, a0 * 0.30f * k),
                                Colors.withAlpha(0xFF9F98B5, a0 * 0.14f * k)},
                        new float[] {0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
                beamColumn(c, hx, cy, reach, mouth * 1.05f, dp(36) * (0.5f + 0.5f * e), dp(16), new LinearGradient(hx, 0, tip + dp(30), 0,
                        new int[] {Colors.withAlpha(0xFFE4DEFF, a0 * 0.62f * k), Colors.withAlpha(0xFFD3C3E6, a0 * 0.42f * k),
                                Colors.withAlpha(0xFFC9B2CF, a0 * 0.26f * k), Colors.withAlpha(0xFFB2A8D6, a0 * 0.12f * k)},
                        new float[] {0f, 0.3f, 0.62f, 1f}, Shader.TileMode.CLAMP));
                beamColumn(c, hx, cy, reach, mouth * 0.7f, dp(18) * (0.5f + 0.5f * e), dp(8), new LinearGradient(hx, 0, tip + dp(18), 0,
                        new int[] {Colors.withAlpha(0xFFF7F5FF, a0 * 0.70f * k), Colors.withAlpha(0xFFE7DEF5, a0 * 0.42f * k),
                                Colors.withAlpha(0xFFD8C6DE, a0 * 0.22f * k), Colors.withAlpha(0xFFC4BBE6, a0 * 0.08f * k)},
                        new float[] {0f, 0.28f, 0.6f, 1f}, Shader.TileMode.CLAMP));
                float spotL = full * 0.3f * e;
                int spot = Colors.blend(0xFFC4C0F4, 0xFFFFFFFF, lit);
                radialGlow(c, hx + spotL * 0.8f, cy, spotL * 1.1f, mouth * 1.25f,
                        new int[] {Colors.withAlpha(0xFFDCD7FF, a0 * 0.40f * k), Colors.withAlpha(0xFFDCD7FF, a0 * 0.16f * k), Colors.withAlpha(accent, 0f)},
                        new float[] {0f, 0.5f, 1f});
                radialGlow(c, hx + spotL * 0.82f, cy, spotL, mouth * 0.95f,
                        new int[] {Colors.withAlpha(spot, a0 * Math.min(1f, 1.05f * k + 0.1f * g)), Colors.withAlpha(spot, a0 * 0.75f * k),
                                Colors.withAlpha(0xFFDCD7FF, a0 * 0.28f * k), Colors.withAlpha(accent, 0f)},
                        new float[] {0f, 0.35f, 0.7f, 1f});
                radialGlow(c, x + iconCx + len * 0.25f, cy, len * 0.85f, len * 0.6f,
                        new int[] {Colors.withAlpha(accent, a0 * 0.22f * g * (0.4f + 0.6f * lit)), Colors.withAlpha(accent, 0f)},
                        new float[] {0f, 1f});
            }
            if (item.torchMax >= 1) {
                float step = (x1 - x0) / item.torchMax;
                for (int i = 1; i <= item.torchMax; i++) {
                    float dx = x + x0 + i * step;
                    if (dx < mx + dp(9)) continue;
                    fillPaint.setColor(Colors.withAlpha(0xFFFFFFFF, a0 * 0.32f));
                    c.drawCircle(dx, cy, dp(1.7f), fillPaint);
                }
                float lineH = dp(12) + dp(14) * f;
                strokePaint.setStrokeWidth(dp(3));
                strokePaint.setColor(Colors.withAlpha(Colors.blend(0xFF5A5A60, 0xFFFFFFFF, g), a0 * (0.55f + 0.4f * g)));
                c.drawLine(mx, cy - lineH / 2f, mx, cy + lineH / 2f, strokePaint);
                if (g > 0.01f) {
                    float sy = cy + lineH / 2f + dp(16);
                    int sun = Colors.withAlpha(0xFFFFFFFF, a0 * 0.6f * g);
                    strokePaint.setStrokeWidth(dp(1.5f));
                    strokePaint.setColor(sun);
                    c.drawCircle(mx, sy, dp(4.2f), strokePaint);
                    fillPaint.setColor(sun);
                    for (int i = 0; i < 8; i++) {
                        double ang = Math.PI * 2 * i / 8;
                        c.drawCircle(mx + (float) Math.cos(ang) * dp(8.2f), sy + (float) Math.sin(ang) * dp(8.2f), dp(1.05f), fillPaint);
                    }
                }
            }
            Btn b = buttons.get(0);
            float pr = pressOf(b, item);
            int save = c.save();
            float sc = 1f - 0.08f * pr;
            c.scale(sc, sc, x + iconCx, cy);
            torchGlyph(c, x + iconCx, cy, len, 0f, torchColor(lit, g), a0, g * (0.35f + 0.65f * lit));
            c.restoreToCount(save);
        }
    }

    private final class MetricBody extends CardBody {
        final float pad = dp(18);
        final float iconSize = dp(22);
        final StaticLayout title;
        final StaticLayout subtitle;
        final float metricsTop;
        final int columns;
        final float cellW;
        final float rowH;
        float actionsTop = -1;
        final TextPaint value = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        final TextPaint critical = new TextPaint(Paint.ANTI_ALIAS_FLAG);

        MetricBody(IslandItem item, float w) {
            super(item, w);
            value.setTypeface(Fonts.of(600));
            value.setTextSize(sp(20f));
            value.setFontFeatureSettings("tnum");
            critical.set(value);
            critical.setTextSize(sp(26f));
            int textW = Math.max(1, Math.round(w - pad * 2 - iconSize - dp(12)));
            CharSequence t = item.title != null ? item.title : item.appName;
            title = t == null ? null : layout(t, titlePaint, textW, 4);
            subtitle = item.subtitle == null ? null : layout(item.subtitle, bodyPaint, textW, 4);
            float y = pad;
            if (title != null) y += title.getHeight();
            if (subtitle != null) y += dp(3) + subtitle.getHeight();
            y = Math.max(y, pad + iconSize);
            metricsTop = y + dp(14);
            int n = item.metrics.size();
            columns = Math.min(3, n);
            cellW = (w - pad * 2) / columns;
            Paint.FontMetrics vf = (item.criticalMetric >= 0 ? critical : value).getFontMetrics();
            Paint.FontMetrics lf = smallPaint.getFontMetrics();
            rowH = (vf.descent - vf.ascent) + dp(2) + (lf.descent - lf.ascent);
            int rows = (n + columns - 1) / columns;
            y = metricsTop + rows * rowH + (rows - 1) * dp(12);
            if (!item.actions.isEmpty()) {
                actionsTop = y + dp(14);
                y = actionsTop + layoutTextButtons(actionsTop, pad);
            }
            h = y + pad;
        }

        @Override
        void draw(Canvas c, float x, float y, float alpha, long el) {
            float a0 = alpha * rowAlpha(el, 0);
            float dy0 = (1f - rowAlpha(el, 0)) * dp(8);
            float ty = y + pad + dy0;
            if (title != null) {
                titlePaint.setColor(Colors.withAlpha(0xFFFFFFFF, a0));
                drawLayout(c, title, x + pad, ty);
                ty += title.getHeight() + dp(3);
            }
            if (subtitle != null) {
                bodyPaint.setColor(Colors.withAlpha(0xD9FFFFFF, a0));
                drawLayout(c, subtitle, x + pad, ty);
            }
            Drawable icon = item.cardIcon != null ? item.cardIcon : item.icon;
            if (icon != null) {
                drawDrawable(c, icon, item.cardIcon != null || item.iconIsImage, 0xFFFFFFFF, x + w - pad - iconSize,
                        y + pad + dy0, iconSize, a0, iconSize * 0.26f);
            }
            float ra = rowAlpha(el, 1);
            float a1 = alpha * ra;
            float top = y + metricsTop + (1f - ra) * dp(8);
            long now = System.currentTimeMillis();
            Paint.FontMetrics lf = smallPaint.getFontMetrics();
            Paint.FontMetrics rf = (item.criticalMetric >= 0 ? critical : value).getFontMetrics();
            for (int i = 0; i < item.metrics.size(); i++) {
                IslandItem.Metric m = item.metrics.get(i);
                float cx = x + pad + (i % columns) * cellW;
                float cy = top + (i / columns) * (rowH + dp(12));
                TextPaint p = i == item.criticalMetric ? critical : value;
                p.setColor(Colors.withAlpha(Metrics.color(m.semantic), a1));
                String v = TextUtils.ellipsize(Metrics.text(m, now), p, cellW - dp(8), TextUtils.TruncateAt.END).toString();
                c.drawText(v, cx, cy - rf.ascent, p);
                if (m.label != null) {
                    smallPaint.setColor(Colors.withAlpha(0x99FFFFFF, a1));
                    String l = TextUtils.ellipsize(m.label, smallPaint, cellW - dp(8), TextUtils.TruncateAt.END).toString();
                    c.drawText(l, cx, cy + (rf.descent - rf.ascent) + dp(2) - lf.ascent, smallPaint);
                }
            }
            if (actionsTop >= 0) {
                float rb = rowAlpha(el, 2);
                drawButtons(c, x, y + (1f - rb) * dp(8), alpha * rb);
            }
        }
    }

    private final class RecorderBody extends CardBody {
        final float pad = dp(18);
        final float bs = dp(54);
        float buttonsLeft;

        RecorderBody(IslandItem item, float w) {
            super(item, w);
            h = dp(88);
            float right = w - pad;
            for (int i = item.actions.size() - 1; i >= 0; i--) {
                IslandItem.Action a = item.actions.get(i);
                String t = a.title == null ? "" : a.title.toString().toLowerCase(java.util.Locale.ROOT);
                Btn b = new Btn();
                b.action = a;
                b.circle = true;
                if (t.contains("stop") || t.contains("стоп") || t.contains("останов") || t.contains("save") || t.contains("сохран")) {
                    b.bg = BUTTON_RED;
                    b.icon = a.icon;
                    if (a.icon == null) b.glyph = Glyphs.STOP;
                } else {
                    b.bg = 0xFF3A3A3C;
                    b.glyph = t.contains("resum") || t.contains("продолж") || t.contains("возоб") || t.contains("record") ? Glyphs.PLAY : Glyphs.PAUSE;
                }
                b.r.set(right - bs, (h - bs) / 2f, right, (h + bs) / 2f);
                right -= bs + dp(12);
                buttons.add(b);
            }
            buttonsLeft = right + dp(12);
        }

        @Override
        void draw(Canvas c, float x, float y, float alpha, long el) {
            float ra = rowAlpha(el, 0);
            float a = alpha * ra;
            float dy = (1f - ra) * dp(6);
            float midY = y + h / 2f + dy;
            String time = trailingString(item, false);
            if (time == null && item.trailingText != null) time = item.trailingText.toString();
            float timeRight = x + (buttons.isEmpty() ? w - pad : buttonsLeft - dp(14));
            float timeW = 0f;
            if (time != null) {
                bigTimePaint.setColor(Colors.withAlpha(OaSource.RECORDER_RED, a));
                timeW = bigTimePaint.measureText(time);
                Paint.FontMetrics fm = bigTimePaint.getFontMetrics();
                drawTime(c, item.key + "#rec", time, bigTimePaint, timeRight - timeW, midY - (fm.ascent + fm.descent) / 2f, false);
            }
            float waveLeft = x + pad;
            float waveW = timeRight - timeW - dp(16) - waveLeft;
            if (waveW > dp(24)) drawRecWave(c, item, waveLeft, midY, waveW, dp(54), a, true);
            drawButtons(c, x, y + dy, a);
        }
    }

    private final class TimerBody extends CardBody {
        final float pad = dp(16);
        final float iconSize;
        final String label;
        final boolean call;

        TimerBody(IslandItem item, float w) {
            super(item, w);
            call = item.kind == IslandItem.Kind.CALL;
            iconSize = call ? dp(40) : dp(30);
            h = dp(84);
            CharSequence l = item.title != null ? item.title : item.appName;
            label = l == null ? null : l.toString();
            float bs = dp(46);
            float gap = dp(10);
            float right = w - pad;
            int n = Math.min(item.actions.size(), 3);
            boolean usedAccent = false;
            for (int i = n - 1; i >= 0; i--) {
                IslandItem.Action a = item.actions.get(i);
                Btn b = new Btn();
                b.action = a;
                b.icon = a.icon;
                b.label = a.title == null ? null : a.title.toString();
                b.circle = a.icon != null || a.runnable != null || a.glyph != 0;
                b.glyph = a.glyph;
                if (a.glyph != 0) b.icon = null;
                if (a.destructive) {
                    b.bg = BUTTON_RED;
                } else if (a.glyph != 0) {
                    b.bg = a.active ? 0xFFFFFFFF : 0xFF3A3A3C;
                } else if (false) {
                    b.bg = BUTTON_RED;
                } else if (!usedAccent && i == 0) {
                    b.bg = call ? 0xFF34C759 : Colors.blend(item.accent, 0xFF000000, 0.15f);
                    usedAccent = true;
                } else {
                    b.bg = 0xFF3A3A3C;
                }
                if (a.runnable != null && a.icon == null && b.glyph == 0) b.glyph = Glyphs.STOP;
                float bw = b.circle ? bs : Math.min(dp(110), buttonPaint.measureText(b.label == null ? "" : b.label) + dp(28));
                b.r.set(right - bw, (h - bs) / 2f, right, (h + bs) / 2f);
                right -= bw + gap;
                buttons.add(b);
            }
        }

        @Override
        void draw(Canvas c, float x, float y, float alpha, long el) {
            float ra = rowAlpha(el, 0);
            float a = alpha * ra;
            float dy = (1f - ra) * dp(6);
            float midY = y + h / 2f + dy;
            float ix = x + pad;
            if (call && item.cardImage != null) {
                drawDrawable(c, item.cardImage, true, 0, ix, midY - iconSize / 2f, iconSize, a, iconSize / 2f);
            } else {
                drawLeading(c, item, ix, midY, iconSize, a);
            }
            float tx = ix + iconSize + dp(14);
            float textRight = buttons.isEmpty() ? x + w - pad : x + buttons.get(buttons.size() - 1).r.left - dp(8);
            String time = trailingString(item, false);
            if (time == null && item.trailingText != null) time = item.trailingText.toString();
            Paint.FontMetrics fm = bigTimePaint.getFontMetrics();
            float timeH = fm.descent - fm.ascent;
            float labelH = label != null ? smallPaint.getTextSize() + dp(4) : 0f;
            float blockTop = midY - (timeH + labelH) / 2f;
            if (call && label != null) {
                titlePaint.setColor(Colors.withAlpha(0xFFFFFFFF, a));
                String name = TextUtils.ellipsize(label, titlePaint, textRight - tx, TextUtils.TruncateAt.END).toString();
                Paint.FontMetrics tf = titlePaint.getFontMetrics();
                c.drawText(name, tx, midY - dp(2) - tf.descent, titlePaint);
                if (time != null) {
                    trailPaint.setColor(Colors.withAlpha(0xFF34C759, a));
                    drawTime(c, item.key + "#call", time, trailPaint, tx, midY + dp(4) - trailPaint.getFontMetrics().ascent, false);
                }
            } else {
                if (time != null) {
                    bigTimePaint.setColor(Colors.withAlpha(0xFFFFFFFF, a));
                    String t = TextUtils.ellipsize(time, bigTimePaint, textRight - tx, TextUtils.TruncateAt.END).toString();
                    drawTime(c, item.key + "#card", t, bigTimePaint, tx, blockTop - fm.ascent, item.chronoCountDown);
                }
                if (label != null) {
                    smallPaint.setColor(Colors.withAlpha(0x99FFFFFF, a));
                    String l = TextUtils.ellipsize(label, smallPaint, textRight - tx, TextUtils.TruncateAt.END).toString();
                    float base = time != null ? blockTop + timeH + dp(2) - smallPaint.getFontMetrics().ascent
                            : midY - (smallPaint.getFontMetrics().ascent + smallPaint.getFontMetrics().descent) / 2f;
                    c.drawText(l, tx, base, smallPaint);
                }
            }
            drawButtons(c, x, y + dy, a);
        }
    }

    private static final float TWO_PI = (float) (Math.PI * 2.0);


    private static final long FX_EASE_MS = 600;
    private static final long FX_COLOR_MS = 500;
    private static final long FLIP_MS = 340;
    private static final long WAVE_MS = 1150;
    private static final float PAUSED_SPEED = 0.15f;
    private static final float PAUSED_GLOW = 0.72f;
    private static final long PAUSE_DIM_DELAY_MS = 700;
    private static final float PAUSED_ART_SHRINK = 0.16f;
    private static final float PAUSED_ART_DIM = 0.42f;
    private static final long PAUSE_ART_MS = 260;
    private static final long PAUSE_ART_DELAY_MS = 80;
    private static final long STALE_MS = 400;

    private final java.util.HashMap<String, MediaFx> mediaFxByKey = new java.util.HashMap<>();
    private final android.graphics.Camera flipCamera = new android.graphics.Camera();
    private final android.graphics.Matrix flipMatrix = new android.graphics.Matrix();

    private void drawArtPaused(Canvas c, MediaFx fx, float left, float top, float size, float alpha, float corner,
                               long t, float shrink) {
        float p = fx.pauseAmount(t);
        int save = c.save();
        if (p > 0.001f) {
            float s = 1f - shrink * p;
            c.scale(s, s, left + size / 2f, top + size / 2f);
        }
        fx.drawArt(c, left, top, size, alpha, corner, t);
        if (p > 0.001f) {
            fillPaint.setColor(Colors.withAlpha(0xFF000000, alpha * PAUSED_ART_DIM * p));
            tmpRect.set(left, top, left + size, top + size);
            c.drawRoundRect(tmpRect, corner, corner, fillPaint);
        }
        c.restoreToCount(save);
    }

    private MediaFx mediaFx(IslandItem item) {
        MediaFx fx = mediaFxByKey.get(item.key);
        if (fx == null) {
            fx = new MediaFx();
            mediaFxByKey.put(item.key, fx);
        }
        fx.sync(item, now());
        return fx;
    }

    private static float easeInOut(float t) {
        t = clamp01(t);
        return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
    }

    private static final class Tween {
        private float from;
        private float to;
        private long start;
        private final long dur;

        Tween(float v, long dur) {
            from = to = v;
            this.dur = dur;
        }

        float value(long now) {
            return from + (to - from) * easeInOut((now - start) / (float) dur);
        }

        void set(float target, long now) {
            if (target == to) return;
            from = value(now);
            to = target;
            start = now;
        }

        boolean running(long now) {
            return now - start < dur;
        }
    }

    private static final class ToneSet {
        Shader base;
        final Shader[] blobs = new Shader[3];
        int accent;
        float h = -1f;

        void build(int accent, float h) {
            this.accent = accent;
            this.h = h;
            float[] hsv = new float[3];
            android.graphics.Color.colorToHSV(accent, hsv);
            float hue = hsv[0];
            int warm = android.graphics.Color.HSVToColor(new float[] {(hue + 28f) % 360f, Math.min(1f, hsv[1] + 0.05f), 1f});
            int cool = android.graphics.Color.HSVToColor(new float[] {(hue + 332f) % 360f, hsv[1], Math.max(0.7f, hsv[2])});
            int[] tones = {Colors.blend(accent, 0xFFFFFFFF, 0.12f), warm, cool};
            base = new LinearGradient(0, h * 0.40f, 0, h,
                    Colors.withAlpha(Colors.blend(accent, 0xFF000000, 0.45f), 0f),
                    Colors.withAlpha(Colors.blend(accent, 0xFF000000, 0.25f), 0.50f), Shader.TileMode.CLAMP);
            for (int i = 0; i < 3; i++) {
                blobs[i] = new RadialGradient(0, 0, 1f,
                        new int[] {Colors.withAlpha(tones[i], 0.85f), Colors.withAlpha(tones[i], 0.35f), Colors.withAlpha(tones[i], 0f)},
                        new float[] {0f, 0.55f, 1f}, Shader.TileMode.CLAMP);
            }
        }
    }

    private static final float[] GLOW_LANE = {0.16f, 0.52f, 0.86f};
    private static final float[] GLOW_RX = {0.50f, 0.42f, 0.50f};
    private static final float[] GLOW_RY = {0.46f, 0.34f, 0.44f};
    private static final float[] GLOW_PEAK = {0.95f, 0.55f, 0.90f};
    private static final float[] GLOW_RATE = {0.55f, 0.80f, 0.65f};

    private final class MediaFx {
        final float[] phase = new float[3];
        float seekPhase;
        long lastAdvance;
        float lastDt;
        final Tween speed = new Tween(1f, FX_EASE_MS);
        final Tween glow = new Tween(1f, FX_EASE_MS);
        final Tween pause = new Tween(0f, PAUSE_ART_MS);
        boolean primed;

        float pauseAmount(long now) {
            pause.set(pausedSince != 0 && now - pausedSince >= PAUSE_ART_DELAY_MS ? 1f : 0f, now);
            return pause.value(now);
        }

        int accentFrom;
        int accentTo;
        long accentStart;
        final ToneSet setTo = new ToneSet();
        final Paint basePaint = new Paint();
        final Paint blobPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Object artKey;
        final Object iconKey = new Object();
        Drawable artCur;
        Drawable artPrev;
        Drawable blurCur;
        Drawable blurPrev;
        long flipStart = -FLIP_MS;
        long waveStart = -WAVE_MS;
        final Paint wavePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        long lastSeq;

        IslandItem posItem;
        float posOffset;
        long lastShown = -1;

        long lastSync;

        void sync(IslandItem item, long now) {
            boolean stale = host == null && now - lastSync > STALE_MS;
            lastSync = now;
            if (item.seq < lastSeq) return;
            lastSeq = item.seq;
            if (stale) primed = false;
            if (!primed) {
                primed = true;
                pausedSince = 0;
                artPrev = null;
                blurPrev = null;
                flipStart = now - FLIP_MS;
                waveStart = now - WAVE_MS;
                speed.set(item.playing ? 1f : PAUSED_SPEED, now - FX_EASE_MS);
                glow.set(item.playing ? 1f : PAUSED_GLOW, now - FX_EASE_MS);
                pause.set(item.playing ? 0f : 1f, now - PAUSE_ART_MS);
                if (!item.playing) pausedSince = now - PAUSE_ART_DELAY_MS;
                accentFrom = accentTo = item.accent;
                accentStart = now - FX_COLOR_MS;
                artKey = artKeyOf(item);
                artCur = item.cardImage;
                blurCur = makeBlur(item.art);
                return;
            }
            if (item.playing) {
                pausedSince = 0;
                speed.set(1f, now);
                glow.set(1f, now);
            } else if (pausedSince == 0) {
                pausedSince = now;
            }
            if (item.accent != accentTo) {
                accentFrom = accent(now);
                accentTo = item.accent;
                accentStart = now;
            }
            Object key = artKeyOf(item);
            if (key != artKey) {
                if (artCur != null && key != null) {
                    artPrev = artCur;
                    blurPrev = blurCur;
                    flipStart = now;
                    waveStart = now;
                }
                artKey = key;
                blurCur = makeBlur(item.art);
            }
            artCur = item.cardImage;
        }

        private Object artKeyOf(IslandItem item) {
            if (item.art != null) return item.art;
            return item.cardImage == null ? null : iconKey;
        }

        private Drawable makeBlur(android.graphics.Bitmap art) {
            if (art == null) return null;
            try {
                android.graphics.Bitmap src = art;
                if (src.getConfig() == android.graphics.Bitmap.Config.HARDWARE) {
                    src = src.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                }
                final int n = 24;
                android.graphics.Bitmap tiny = android.graphics.Bitmap.createScaledBitmap(src, n, n, true);
                int[] px = new int[n * n];
                tiny.getPixels(px, 0, n, 0, 0, n, n);
                int[] tmp = new int[n * n];
                for (int pass = 0; pass < 2; pass++) {
                    boxBlur(px, tmp, n, 3, true);
                    boxBlur(tmp, px, n, 3, false);
                }
                android.graphics.Bitmap soft = android.graphics.Bitmap.createBitmap(px, n, n, android.graphics.Bitmap.Config.ARGB_8888);
                android.graphics.drawable.BitmapDrawable d = new android.graphics.drawable.BitmapDrawable(getResources(), soft);
                d.setFilterBitmap(true);
                return d;
            } catch (Throwable t) {
                return null;
            }
        }

        private void boxBlur(int[] in, int[] out, int n, int r, boolean horizontal) {
            for (int a = 0; a < n; a++) {
                for (int b = 0; b < n; b++) {
                    int sa = 0, sr = 0, sg = 0, sb = 0, cnt = 0;
                    for (int k = -r; k <= r; k++) {
                        int q = Math.max(0, Math.min(n - 1, b + k));
                        int c = horizontal ? in[a * n + q] : in[q * n + a];
                        sa += c >>> 24;
                        sr += (c >> 16) & 0xFF;
                        sg += (c >> 8) & 0xFF;
                        sb += c & 0xFF;
                        cnt++;
                    }
                    int v = ((sa / cnt) << 24) | ((sr / cnt) << 16) | ((sg / cnt) << 8) | (sb / cnt);
                    if (horizontal) out[a * n + b] = v;
                    else out[b * n + a] = v;
                }
            }
        }

        int accent(long now) {
            return Colors.blend(accentFrom, accentTo, easeInOut((now - accentStart) / (float) FX_COLOR_MS));
        }

        boolean animating(long now) {
            return speed.running(now) || glow.running(now) || pause.running(now) || now - accentStart < FX_COLOR_MS
                    || now - flipStart < FLIP_MS || now - waveStart < WAVE_MS
                    || (pausedSince != 0 && now - pausedSince < PAUSE_DIM_DELAY_MS + 50);
        }

        long pausedSince;

        void advance(long now) {
            if (pausedSince != 0 && now - pausedSince >= PAUSE_DIM_DELAY_MS) {
                speed.set(PAUSED_SPEED, now);
                glow.set(PAUSED_GLOW, now);
            }
            float dt = lastAdvance == 0 ? 0f : Math.min(0.05f, (now - lastAdvance) / 1000f);
            lastAdvance = now;
            lastDt = dt;
            float sp = speed.value(now);
            for (int i = 0; i < 3; i++) {
                phase[i] += dt * GLOW_RATE[i] * sp;
                if (phase[i] > TWO_PI) phase[i] -= TWO_PI;
            }
            seekPhase += dt * (1000f / 420f) * sp;
            if (seekPhase > TWO_PI) seekPhase -= TWO_PI;
        }

        void drawGlow(Canvas c, float x, float y, float w, float h, float glowH, float alpha, long now) {
            int tone = accent(now);
            if (setTo.h != glowH || setTo.accent != tone) setTo.build(tone, glowH);
            float a = alpha * glow.value(now);
            c.save();
            c.translate(x, y);
            drawLayer(c, setTo, w, glowH, h, a);
            c.restore();
        }

        private void drawLayer(Canvas c, ToneSet set, float w, float h, float bottom, float alpha) {
            if (alpha <= 0.002f) return;
            basePaint.setShader(set.base);
            basePaint.setAlpha(Math.round(255f * alpha));
            c.drawRect(0, h * 0.40f, w, Math.max(h, bottom), basePaint);
            for (int i = 0; i < 3; i++) {
                float ph = phase[i];
                float cx = w * (GLOW_LANE[i] + 0.12f * (float) Math.sin(ph));
                float cy = h * (0.95f - 0.07f * (float) Math.sin(ph * 2f + i));
                float breathe = 0.82f + 0.18f * (float) Math.sin(ph * 3f + i * 2f);
                c.save();
                c.translate(cx, cy);
                c.scale(w * GLOW_RX[i] * breathe, h * GLOW_RY[i] * breathe);
                blobPaint.setShader(set.blobs[i]);
                blobPaint.setAlpha(Math.round(255f * alpha * GLOW_PEAK[i] * (0.8f + 0.2f * breathe)));
                c.drawCircle(0, 0, 1f, blobPaint);
                c.restore();
            }
            blobPaint.setShader(null);
            basePaint.setShader(null);
        }

        float liveness(long now) {
            return clamp01((glow.value(now) - PAUSED_GLOW) / (1f - PAUSED_GLOW));
        }

        long shownPosition(IslandItem item, long nowElapsed) {
            long pos = item.currentMediaPosition(nowElapsed);
            if (item != posItem) {
                boolean sameTrack = posItem != null && TextUtils.equals(posItem.title, item.title)
                        && posItem.mediaDuration == item.mediaDuration;
                posOffset = sameTrack && lastShown >= 0 && pos >= 0 && Math.abs(lastShown - pos) < 2000
                        ? lastShown - pos : 0f;
                posItem = item;
            }
            posOffset *= (float) Math.exp(-lastDt / 0.18f);
            if (Math.abs(posOffset) < 1f) posOffset = 0f;
            long shown = pos < 0 ? pos : Math.max(0, pos + Math.round(posOffset));
            lastShown = shown;
            return shown;
        }

        void drawWave(Canvas c, float x, float y, float w, float h, float cx, float cy, float artSize,
                      float alpha, long now) {
            float t = (now - waveStart) / (float) WAVE_MS;
            if (t < 0f || t >= 1f || alpha <= 0.01f) return;
            int tone = Colors.blend(accent(now), 0xFFFFFFFF, 0.35f);
            float reach = (float) Math.hypot(Math.max(cx - x, x + w - cx), Math.max(cy - y, y + h - cy));
            float e = 1f - (1f - t) * (1f - t) * (1f - t);
            float ring = artSize * 0.5f + reach * e;
            float band = dp(28) + dp(56) * e;
            float outer = ring + band;
            float fade = (1f - t) * (1f - t);
            int peak = Colors.withAlpha(tone, alpha * 0.55f * fade);
            float inner = clamp01((ring - band) / outer);
            float mid = clamp01(ring / outer);
            float bloom = clamp01(1f - t * 3f);
            int core = Colors.withAlpha(tone, alpha * 0.45f * bloom * bloom);
            wavePaint.setShader(new RadialGradient(cx, cy, outer,
                    new int[] {core, Colors.withAlpha(tone, 0f), Colors.withAlpha(tone, 0f), peak, Colors.withAlpha(tone, 0f)},
                    new float[] {0f, Math.min(inner, Math.max(0.001f, artSize / outer)), inner, mid, 1f},
                    Shader.TileMode.CLAMP));
            wavePaint.setBlendMode(android.graphics.BlendMode.PLUS);
            c.drawRect(x, y, x + w, y + h, wavePaint);
            wavePaint.setShader(null);
        }

        void drawArt(Canvas c, float left, float top, float size, float alpha, float corner, long now) {
            float t = (now - flipStart) / (float) FLIP_MS;
            if (artPrev == null || t >= 1f) {
                artPrev = null;
                blurPrev = null;
                if (artCur != null) drawDrawable(c, artCur, true, 0, left, top, size, alpha, corner);
                return;
            }
            float e = easeInOut(t);
            float angle = 180f * e;
            boolean first = angle < 90f;
            Drawable d = first ? artPrev : artCur;
            Drawable b = first ? blurPrev : blurCur;
            float rot = first ? angle : angle - 180f;
            float blur = Math.min(1f, 1.3f * (float) Math.sin(Math.PI * clamp01(t)));
            float cxp = left + size / 2f;
            float cyp = top + size / 2f;
            flipCamera.save();
            flipCamera.rotateY(-rot);
            flipCamera.getMatrix(flipMatrix);
            flipCamera.restore();
            flipMatrix.preTranslate(-cxp, -cyp);
            flipMatrix.postTranslate(cxp, cyp);
            int save = c.save();
            c.concat(flipMatrix);
            if (d != null) drawDrawable(c, d, true, 0, left, top, size, alpha, corner);
            if (b != null && blur > 0.01f) drawDrawable(c, b, true, 0, left, top, size, alpha * blur, corner);
            c.restoreToCount(save);
        }
    }

    private final class MediaBody extends CardBody {
        final float pad = dp(18);
        final float art = dp(56);
        final float tile = dp(32);
        final StaticLayout title;
        final StaticLayout artist;
        final float seekTop;
        final float controlsTop;
        final float controlH = dp(52);
        float barLeft;
        float barRight;
        float playerH;

        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF tmpRect2 = new RectF();

        private final Paint beamPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path beamPath = new Path();

        private void drawBeam(Canvas c, float hx, float hy, float bh, float barL, float alpha, int color) {
            if (alpha <= 0.01f) return;
            if (beamPaint.getMaskFilter() == null) beamPaint.setMaskFilter(new BlurMaskFilter(dp(6f), BlurMaskFilter.Blur.NORMAL));
            float len = Math.min(dp(130), Math.max(dp(30), hx - barL + dp(6)));
            float drop = Math.min(dp(30), len * 0.34f);
            beamPath.rewind();
            beamPath.moveTo(hx, hy - bh * 0.5f);
            beamPath.lineTo(hx - len, hy - drop * 0.75f);
            beamPath.lineTo(hx - len, hy + drop);
            beamPath.lineTo(hx, hy + bh * 0.6f);
            beamPath.close();
            beamPaint.setShader(new LinearGradient(hx, hy, hx - len, hy + drop * 0.1f,
                    new int[] {Colors.withAlpha(color, alpha * 0.55f), Colors.withAlpha(color, alpha * 0.2f), Colors.withAlpha(color, 0f)},
                    new float[] {0f, 0.4f, 1f}, Shader.TileMode.CLAMP));
            c.drawPath(beamPath, beamPaint);
            beamPaint.setShader(null);
        }

        boolean onSeekBar(float lx, float ly) {
            float sy = seekTop + dp(7);
            return item.mediaSeek != null && item.mediaDuration > 0 && ly > sy - dp(18) && ly < sy + dp(18)
                    && lx > barLeft - dp(12) && lx < barRight + dp(12);
        }

        float fractionAt(float lx) {
            return clamp01((lx - barLeft) / Math.max(1f, barRight - barLeft));
        }

        MediaBody(IslandItem item, float w, boolean compact) {
            super(item, w);
            glowPaint.setMaskFilter(new BlurMaskFilter(dp(5), BlurMaskFilter.Blur.NORMAL));
            // Compact: art and text on the left, controls on the right, seek bar underneath.
            int textW = Math.max(1, Math.round(compact
                    ? w * 0.55f - pad - art - dp(24)
                    : w - pad * 2 - art - dp(14) - tile - dp(10)));
            title = item.title == null ? null : layout(item.title, mediaTitlePaint(), textW, 1);
            artist = item.subtitle == null ? null : layout(item.subtitle, mediaArtistPaint(), textW, 1);
            seekTop = compact ? pad + art + dp(10) : pad + art + dp(16);
            controlsTop = compact ? pad + (art - controlH) / 2f : seekTop + dp(14) + dp(10);
            String totalText = item.mediaDuration > 0 ? formatTimePadded(item.mediaDuration) : "--:--";
            barLeft = pad + smallPaint.measureText("00:00") + dp(10);
            barRight = w - pad - smallPaint.measureText(totalText) - dp(10);
            float span = w - pad * 2;
            if (compact) {
                float left = w * 0.55f;
                float cs = w - pad - tile - dp(16) - left;
                addControl(Glyphs.PREV, left + cs * 0.2f, item.mediaPrev, false);
                addControl(item.playing ? Glyphs.PAUSE : Glyphs.PLAY, left + cs * 0.5f, item.mediaPlayPause, true);
                addControl(Glyphs.NEXT, left + cs * 0.8f, item.mediaNext, false);
                h = seekTop + dp(26);
            } else {
                if (item.mediaCustom.size() > 0) addCustom(item.mediaCustom.get(0), pad + span * 0.08f);
                addControl(Glyphs.PREV, pad + span * 0.30f, item.mediaPrev, false);
                addControl(item.playing ? Glyphs.PAUSE : Glyphs.PLAY, pad + span * 0.5f, item.mediaPlayPause, true);
                addControl(Glyphs.NEXT, pad + span * 0.70f, item.mediaNext, false);
                if (item.mediaCustom.size() > 1) addCustom(item.mediaCustom.get(1), pad + span * 0.92f);
                h = controlsTop + controlH + dp(14);
            }
            playerH = h;
            int n = compact ? 0 : Math.min(4, item.mediaShortcuts.size());
            if (n > 0) {
                float gap = dp(10);
                float side = (span - gap * 3) / 4f;
                float top = controlsTop + controlH + dp(10);
                for (int i = 0; i < n; i++) {
                    IslandItem.Shortcut sc = item.mediaShortcuts.get(i);
                    Btn b = new Btn();
                    b.tile = true;
                    b.icon = sc.art == null ? null : new android.graphics.drawable.BitmapDrawable(getResources(), sc.art);
                    b.run = sc.run;
                    float l = pad + i * (side + gap);
                    b.r.set(l, top, l + side, top + side);
                    buttons.add(b);
                }
                h = top + side + pad;
            }
        }

        private void addControl(int glyph, float centerX, Runnable r, boolean circle) {
            Btn b = new Btn();
            b.glyph = glyph;
            b.circle = circle;
            b.bg = 0x24FFFFFF;
            b.run = r;
            float s = circle ? controlH : controlH * 0.84f;
            b.r.set(centerX - s / 2f, controlsTop + (controlH - s) / 2f, centerX + s / 2f, controlsTop + (controlH + s) / 2f);
            buttons.add(b);
        }

        private void addCustom(IslandItem.Action a, float centerX) {
            Btn b = new Btn();
            b.icon = a.icon;
            b.label = a.icon == null && a.title != null ? a.title.toString() : null;
            b.circle = true;
            b.bg = 0x00FFFFFF;
            b.run = a.runnable;
            float s = controlH * 0.84f;
            b.r.set(centerX - s / 2f, controlsTop + (controlH - s) / 2f, centerX + s / 2f, controlsTop + (controlH + s) / 2f);
            buttons.add(b);
        }

        @Override
        void draw(Canvas c, float x, float y, float alpha, long el) {
            long nowT = now();
            MediaFx fx = mediaFx(item);
            fx.advance(nowT);
            fx.drawGlow(c, x, y, w, h, playerH, alpha, nowT);
            float r0 = rowAlpha(el, 0);
            float a0 = alpha * r0;
            float dy0 = (1f - r0) * dp(8);
            fx.drawWave(c, x, y, w, h, x + pad + art / 2f, y + pad + dy0 + art / 2f, art, alpha, nowT);
            fx.drawArt(c, x + pad, y + pad + dy0, art, a0, dp(12), nowT);
            float tx = x + pad + art + dp(14);
            float textH = (title != null ? title.getHeight() : 0) + (artist != null ? dp(3) + artist.getHeight() : 0);
            float ty = y + pad + dy0 + (art - textH) / 2f;
            if (title != null) {
                mediaTitlePaint().setColor(Colors.withAlpha(0xFFFFFFFF, a0));
                drawLayout(c, title, tx, ty);
                ty += title.getHeight() + dp(3);
            }
            if (artist != null) {
                mediaArtistPaint().setColor(Colors.withAlpha(0x8CFFFFFF, a0));
                drawLayout(c, artist, tx, ty);
            }
            if (item.appGlyph != null || item.appIcon != null) {
                float tl = x + w - pad - tile;
                float tt = y + pad + dy0;
                if (item.appGlyph != null) {
                    tmpRect.set(tl, tt, tl + tile, tt + tile);
                    fillPaint.setColor(Colors.withAlpha(0x2EFFFFFF, a0));
                    c.drawRoundRect(tmpRect, dp(9), dp(9), fillPaint);
                    float is = tile * 1.25f;
                    drawDrawable(c, item.appGlyph, false, 0xFFFFFFFF, tl + (tile - is) / 2f, tt + (tile - is) / 2f, is, a0, 0);
                } else {
                    drawDrawable(c, item.appIcon, true, 0, tl, tt, tile, a0, dp(9));
                }
            }

            float r1 = rowAlpha(el, 1);
            float a1 = alpha * r1;
            float sy = y + seekTop + (1f - r1) * dp(8) + dp(7);
            long pos = fx.shownPosition(item, SystemClock.elapsedRealtime());
            String cur = formatTimePadded(Math.max(0, pos));
            String total = item.mediaDuration > 0 ? formatTimePadded(item.mediaDuration) : "--:--";
            smallPaint.setColor(Colors.withAlpha(0x99FFFFFF, a1));
            Paint.FontMetrics fm = smallPaint.getFontMetrics();
            float base = sy - (fm.ascent + fm.descent) / 2f;
            if (seekBody != this) c.drawText(cur, x + pad, base, smallPaint);
            float totalW = smallPaint.measureText(total);
            c.drawText(total, x + w - pad - totalW, base, smallPaint);
            float barL = x + barLeft;
            float barR = x + barRight;
            boolean dragged = seekBody == this;
            if (dragged) {
                cur = formatTimePadded((long) (seekFraction * Math.max(0, item.mediaDuration)));
                c.drawText(cur, x + pad, base, smallPaint);
            }
            float bh = dragged ? dp(7f) : dp(3.5f);
            tmpRect.set(barL, sy - bh / 2f, barR, sy + bh / 2f);
            fillPaint.setColor(Colors.withAlpha(0x33FFFFFF, a1));
            c.drawRoundRect(tmpRect, bh / 2f, bh / 2f, fillPaint);
            float p = dragged ? seekFraction : (item.mediaDuration > 0 ? clamp01(pos / (float) item.mediaDuration) : 0f);
            tmpRect.right = barL + Math.max(bh * 2.4f, (barR - barL) * p);
            float head = tmpRect.right;
            float glowA = a1 * (0.8f + fx.liveness(nowT) * (0.05f + 0.15f * (float) Math.sin(fx.seekPhase)));
            int tint = Colors.blend(fx.accent(nowT), 0xFFFFFFFF, 0.45f);
            drawBeam(c, head - bh * 0.5f, sy, bh, barL, glowA, Colors.blend(tint, 0xFFFFFFFF, 0.4f));
            glowPaint.setColor(Colors.withAlpha(0xFFFFFFFF, glowA * 0.5f));
            c.drawCircle(head - bh * 0.6f, sy, bh * 1.1f, glowPaint);
            fillPaint.setColor(0xFFFFFFFF);
            fillPaint.setShader(new LinearGradient(barL, 0, head, 0, Colors.withAlpha(tint, a1),
                    Colors.withAlpha(0xFFFFFFFF, a1), Shader.TileMode.CLAMP));
            c.drawRoundRect(tmpRect, bh / 2f, bh / 2f, fillPaint);
            fillPaint.setShader(null);

            float r2 = rowAlpha(el, 2);
            drawButtons(c, x, y + (1f - r2) * dp(8), alpha * r2);
        }
    }

    private Object fontKey;

    private void applyFonts() {
        fontKey = Fonts.key();
        Typeface regular = Fonts.of(400);
        Typeface medium = Fonts.of(500);
        Typeface semibold = Fonts.of(600);
        trailPaint.setTypeface(semibold);
        smallPaint.setTypeface(medium);
        titlePaint.setTypeface(Fonts.of(700));
        bodyPaint.setTypeface(regular);
        bigTimePaint.setTypeface(semibold);
        buttonPaint.setTypeface(medium);
        if (mediaTitle != null) mediaTitle.setTypeface(semibold);
        if (mediaArtist != null) mediaArtist.setTypeface(regular);
    }

    private void checkFonts() {
        if (java.util.Objects.equals(fontKey, Fonts.key())) return;
        applyFonts();
        shadeBody = null;
    }

    private TextPaint mediaTitle;
    private TextPaint mediaArtist;

    private TextPaint mediaTitlePaint() {
        if (mediaTitle == null) {
            mediaTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            mediaTitle.setTypeface(Fonts.of(600));
            mediaTitle.setTextSize(sp(17f));
        }
        return mediaTitle;
    }

    private TextPaint mediaArtistPaint() {
        if (mediaArtist == null) {
            mediaArtist = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            mediaArtist.setTypeface(Fonts.of(400));
            mediaArtist.setTextSize(sp(14.5f));
        }
        return mediaArtist;
    }

    private static StaticLayout layout(CharSequence text, TextPaint paint, int width, int maxLines) {
        return StaticLayout.Builder.obtain(text, 0, text.length(), paint, width)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setMaxLines(maxLines)
                .setEllipsize(TextUtils.TruncateAt.END)
                .setIncludePad(false)
                .build();
    }

    private static void drawLayout(Canvas c, StaticLayout l, float x, float y) {
        int sv = c.save();
        c.translate(x, y);
        l.draw(c);
        c.restoreToCount(sv);
    }


    private boolean claudeLive(IslandItem it) {
        return it != null && it.claude && !"done".equals(it.claudeState);
    }

    private boolean claudeAnimating() {
        if (now() - claudeShiftAt < CLAUDE_SHIFT_MS + 40) return true;
        if (mode == Mode.COMPACT) {
            return (mainIn != null && claudeLive(mainIn.content.item)) || (bubbleIn != null && claudeLive(bubbleIn.content.item));
        }
        if (mode == Mode.EXPANDED) {
            for (Card c : cards) if (claudeLive(c.item)) return true;
        }
        return false;
    }

    private final RectF claudeArc = new RectF();

    private void drawClaudeRing(Canvas c, IslandItem item, float cx, float cy, float r, float alpha) {
        long t = now();
        String st = item.claudeState == null ? "working" : item.claudeState;
        if (AssistantApp.waiting(st)) {
            float p = (float) ((t % 1400) / 1400.0);
            fillPaint.setColor(Colors.withAlpha(item.accent, alpha * 0.45f * (1f - p)));
            c.drawCircle(cx, cy, r * (0.8f + 0.55f * p), fillPaint);
            fillPaint.setColor(Colors.withAlpha(item.accent, alpha));
            c.drawCircle(cx, cy, r * 0.82f, fillPaint);
            Paint.FontMetrics fm = claudeBadge().getFontMetrics();
            claudeBadge().setColor(Colors.withAlpha(0xFF1A0E09, alpha));
            c.drawText("question".equals(st) ? "?" : "!", cx, cy - (fm.ascent + fm.descent) / 2f, claudeBadge());
            return;
        }
        if ("done".equals(st)) {
            fillPaint.setColor(Colors.withAlpha(0xFF30D158, alpha * 0.2f));
            c.drawCircle(cx, cy, r * 0.9f, fillPaint);
            strokePaint.setStrokeWidth(dp(2f));
            strokePaint.setColor(Colors.withAlpha(0xFF30D158, alpha));
            checkPath.rewind();
            checkPath.moveTo(cx - r * 0.38f, cy + r * 0.02f);
            checkPath.lineTo(cx - r * 0.1f, cy + r * 0.3f);
            checkPath.lineTo(cx + r * 0.42f, cy - r * 0.3f);
            Paint.Cap cap = strokePaint.getStrokeCap();
            strokePaint.setStrokeCap(Paint.Cap.ROUND);
            c.drawPath(checkPath, strokePaint);
            strokePaint.setStrokeCap(cap);
            return;
        }
        drawAssistantActivity(c, item, cx, cy, r, alpha);
    }

    private void drawAssistantActivity(Canvas c, IslandItem item, float cx, float cy, float r, float alpha) {
        if (item.assistantStyle == AssistantApp.STYLE_PULSE) {
            double s = now() / 1000.0;
            float k = (float) (0.5 + 0.5 * Math.sin(s * Math.PI * 2 / 1.1));
            fillPaint.setColor(Colors.withAlpha(item.accent, alpha * (0.18f + 0.18f * k)));
            c.drawCircle(cx, cy, r * (0.62f + 0.28f * k), fillPaint);
            fillPaint.setColor(Colors.withAlpha(item.accent, alpha));
            c.drawCircle(cx, cy, r * (0.36f + 0.12f * k), fillPaint);
            return;
        }
        drawClaudeDots(c, item.accent, cx, cy, r, alpha);
    }

    private void drawClaudeDots(Canvas c, int color, float cx, float cy, float r, float alpha) {
        double s = now() / 1000.0;
        double turn = s * Math.PI * 2 / 1.3;
        float orbit = r * (0.5f + 0.06f * (float) Math.sin(s * Math.PI * 2 / 0.65));
        float dot = r * 0.25f;
        for (int i = 0; i < 4; i++) {
            double a = turn + i * Math.PI / 2;
            float k = 0.75f + 0.25f * (float) (0.5 + 0.5 * Math.cos(a - turn * 2));
            fillPaint.setColor(Colors.withAlpha(color, alpha * k));
            c.drawCircle(cx + orbit * (float) Math.cos(a), cy + orbit * (float) Math.sin(a), dot, fillPaint);
        }
    }

    private TextPaint claudeBadgePaint;

    private TextPaint claudeBadge() {
        if (claudeBadgePaint == null) {
            claudeBadgePaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            claudeBadgePaint.setTypeface(Fonts.of(800));
            claudeBadgePaint.setTextSize(sp(12.5f));
            claudeBadgePaint.setTextAlign(Paint.Align.CENTER);
        }
        return claudeBadgePaint;
    }

    private static final long CLAUDE_SHIFT_MS = 460;
    private String[] claudeShown = new String[0];
    private String claudeDropped;
    private Drawable claudeDroppedIcon;
    private Drawable[] claudeShownIcons = new Drawable[0];
    private long claudeShiftAt;

    private void trackClaudeStages(String[] now) {
        if (java.util.Arrays.equals(now, claudeShown)) return;
        String[] old = claudeShown;
        claudeShown = now;
        boolean appended = now.length > 0 && old.length > 0
                && !now[now.length - 1].equals(old[old.length - 1])
                && (now.length < 2 || now[now.length - 2].equals(old[old.length - 1]));
        if (appended) {
            claudeDropped = old.length == now.length ? old[0] : null;
            claudeDroppedIcon = claudeShownIcons.length > 0 ? claudeShownIcons[0] : null;
            claudeShiftAt = now();
        } else {
            claudeDropped = null;
            claudeShiftAt = 0;
        }
        scheduleFrame();
    }

    private static Drawable claudeFrame(Drawable d, long time, boolean animate) {
        if (!(d instanceof android.graphics.drawable.AnimationDrawable)) return d;
        android.graphics.drawable.AnimationDrawable a = (android.graphics.drawable.AnimationDrawable) d;
        int n = a.getNumberOfFrames();
        if (n == 0) return d;
        if (!animate) return a.getFrame(0);
        int total = 0;
        for (int i = 0; i < n; i++) total += Math.max(1, a.getDuration(i));
        long pos = time % total;
        for (int i = 0; i < n; i++) {
            pos -= Math.max(1, a.getDuration(i));
            if (pos < 0) return a.getFrame(i);
        }
        return a.getFrame(0);
    }

    private final java.util.HashMap<Integer, BlurMaskFilter> claudeBlurs = new java.util.HashMap<>();

    private BlurMaskFilter claudeBlur(float radius) {
        int key = Math.round(radius * 2f);
        if (key <= 0) return null;
        BlurMaskFilter f = claudeBlurs.get(key);
        if (f == null) {
            f = new BlurMaskFilter(key / 2f, BlurMaskFilter.Blur.NORMAL);
            claudeBlurs.put(key, f);
        }
        return f;
    }

    private final class ClaudeBody extends CardBody {
        static final int ROWS = 3;
        final float pad = dp(18);
        final float iconSize = dp(22);
        final float rowH = dp(26);
        final float ROW_ICON = dp(16);
        final StaticLayout question;
        final float rowsTop;
        final float questionTop;
        final float actionsTop;
        final float footerTop;

        ClaudeBody(IslandItem item, float w) {
            super(item, w);
            int textW = Math.max(1, Math.round(w - pad * 2 - iconSize - dp(12)));
            Paint.FontMetrics tf = titlePaint.getFontMetrics();
            Paint.FontMetrics bf = bodyPaint.getFontMetrics();
            float y = pad + (tf.descent - tf.ascent) + dp(3) + (bf.descent - bf.ascent);
            y = Math.max(y, pad + iconSize);
            boolean waiting = AssistantApp.waiting(item.claudeState) && item.claudeQuestion != null;
            if (waiting) {
                question = layout(item.claudeQuestion, bodyPaint, Math.max(1, Math.round(w - pad * 2)), 5);
                questionTop = y + dp(10);
                y = questionTop + question.getHeight();
                rowsTop = -1;
                actionsTop = y + dp(14);
                Btn b = new Btn();
                b.label = "permission".equals(item.claudeState) ? AssistantApp.text("Open in ", "Открыть в ") + item.title
                        : AssistantApp.text("Answer in ", "Ответить в ") + item.title;
                b.bg = 0x2EFFFFFF;
                b.run = () -> {
                    IslandController c = IslandController.get();
                    if (c != null) c.onOpenItem(item, null);
                    try {
                        Object sbm = getContext().getSystemService("statusbar");
                        sbm.getClass().getMethod("collapsePanels").invoke(sbm);
                    } catch (Throwable ignored) {
                    }
                };
                b.r.set(pad, actionsTop, w - pad, actionsTop + dp(40));
                buttons.add(b);
                y = actionsTop + dp(40);
                footerTop = item.claudeTasks > 0 ? y + dp(14) : -1;
                if (footerTop >= 0) y = footerTop + rowH;
            } else {
                question = null;
                questionTop = -1;
                actionsTop = -1;
                rowsTop = y + dp(12);
                y = rowsTop + rowH * ROWS;
                footerTop = item.claudeTasks > 0 ? y + dp(8) : -1;
                if (footerTop >= 0) y = footerTop + rowH;
            }
            h = y + pad;
        }

        @Override
        void draw(Canvas c, float x, float y, float alpha, long el) {
            String st = item.claudeState == null ? "working" : item.claudeState;
            boolean live = AssistantApp.working(st);
            float a0 = alpha * rowAlpha(el, 0);
            float dy0 = (1f - rowAlpha(el, 0)) * dp(8);
            float ty = y + pad + dy0;
            Paint.FontMetrics tf = titlePaint.getFontMetrics();
            titlePaint.setColor(Colors.withAlpha(0xFFFFFFFF, a0));
            c.drawText(String.valueOf(item.title), x + pad, ty - tf.ascent, titlePaint);
            ty += tf.descent - tf.ascent + dp(3);
            Paint.FontMetrics bf = bodyPaint.getFontMetrics();
            int stateColor = AssistantApp.waiting(st) && item.accent != 0xFFFFFFFF ? item.accent : 0xD9FFFFFF;
            bodyPaint.setColor(Colors.withAlpha(stateColor, a0));
            c.drawText(AssistantApp.stateLabel(st), x + pad, ty - bf.ascent, bodyPaint);
            if (item.icon != null) drawDrawable(c, item.icon, item.iconIsImage, 0xFFFFFFFF, x + w - pad - iconSize, y + pad + dy0, iconSize, a0, 0);
            if (question != null) {
                float ra = rowAlpha(el, 1);
                bodyPaint.setColor(Colors.withAlpha(0xFFFFFFFF, alpha * ra));
                drawLayout(c, question, x + pad, y + questionTop + (1f - ra) * dp(8));
                float rb = rowAlpha(el, 2);
                drawButtons(c, x, y + (1f - rb) * dp(8), alpha * rb);
                drawFooter(c, x, y, alpha * rowAlpha(el, 3));
                return;
            }
            trackClaudeStages(item.claudeStages);
            claudeShownIcons = item.claudeIcons;
            float ra = rowAlpha(el, 1);
            drawRows(c, x, y + rowsTop + (1f - ra) * dp(8), alpha * ra, live);
            drawFooter(c, x, y, alpha * rowAlpha(el, 2));
        }

        private void drawFooter(Canvas c, float x, float y, float alpha) {
            if (footerTop < 0 || alpha <= 0.003f) return;
            float top = y + footerTop;
            fillPaint.setColor(Colors.withAlpha(0x14FFFFFF, alpha));
            c.drawRect(x + pad, top - dp(4), x + w - pad, top - dp(4) + Math.max(1f, dp(0.7f)), fillPaint);
            float cy = top + rowH / 2f;
            if (item.claudeTaskIcon != null) {
                drawDrawable(c, item.claudeTaskIcon, false, 0x99FFFFFF, x + pad, cy - ROW_ICON / 2f, ROW_ICON, alpha, 0);
            } else {
                drawAssistantActivity(c, item, x + pad + ROW_ICON / 2f, cy, ROW_ICON * 0.42f, alpha * 0.8f);
            }
            Paint.FontMetrics fm = bodyPaint.getFontMetrics();
            bodyPaint.setColor(Colors.withAlpha(0x99FFFFFF, alpha));
            c.drawText(AssistantApp.tasksLabel(item.claudeTasks), x + pad + ROW_ICON + dp(10), cy - (fm.ascent + fm.descent) / 2f, bodyPaint);
        }

        private void drawRows(Canvas c, float x, float top, float alpha, boolean live) {
            String[] rows = item.claudeStages;
            float e = claudeShiftAt == 0 ? 1f : easeInOut(clamp01((now() - claudeShiftAt) / (float) CLAUDE_SHIFT_MS));
            boolean moving = e < 1f;
            float maxW = w - pad * 2 - ROW_ICON - dp(10);
            float textX = x + pad + ROW_ICON + dp(10);
            Paint.FontMetrics fm = bodyPaint.getFontMetrics();
            float base = -(fm.ascent + fm.descent) / 2f;
            int first = ROWS - rows.length;
            if (moving && claudeDropped != null) {
                float cy = top + rowH / 2f - e * rowH;
                drawRowIcon(c, x + pad, cy, false, claudeDroppedIcon, alpha * (1f - e) * 0.25f);
                drawRow(c, claudeDropped, textX, cy + base, maxW, 0x40FFFFFF, alpha * (1f - e), dp(6) * e);
            }
            for (int i = 0; i < rows.length; i++) {
                int slot = first + i;
                boolean newest = i == rows.length - 1;
                float cy = top + slot * rowH + rowH / 2f + (moving ? (1f - e) * rowH : 0f);
                int color = newest ? 0xFFFFFFFF : slot == ROWS - 2 ? 0x8CFFFFFF : 0x4DFFFFFF;
                if (moving && !newest) {
                    int from = slot + 1 == ROWS - 1 ? 0xFFFFFFFF : 0x8CFFFFFF;
                    color = Colors.blend(from, color, e);
                }
                float blur = moving ? (newest ? dp(6) * (1f - e) : dp(2.5f) * (float) Math.sin(Math.PI * e)) : 0f;
                float ra = alpha * (moving && newest ? e : 1f);
                Drawable icon = i < item.claudeIcons.length ? item.claudeIcons[i] : null;
                drawRowIcon(c, x + pad, cy, newest && live, icon, ra * ((color >>> 24) / 255f));
                drawRow(c, rows[i], textX, cy + base, maxW, color, ra, blur);
            }
        }

        private void drawRowIcon(Canvas c, float x, float cy, boolean animated, Drawable icon, float alpha) {
            if (alpha <= 0.003f) return;
            if (animated) {
                drawAssistantActivity(c, item, x + ROW_ICON / 2f, cy, ROW_ICON / 2f, alpha);
                return;
            }
            if (icon == null) {
                fillPaint.setColor(Colors.withAlpha(item.accent, alpha));
                c.drawCircle(x + ROW_ICON / 2f, cy, dp(3.2f), fillPaint);
                return;
            }
            boolean spark = icon == item.icon && item.iconIsImage;
            drawDrawable(c, icon, spark, 0xFFFFFFFF, x, cy - ROW_ICON / 2f, ROW_ICON, alpha, 0);
        }

        private void drawRow(Canvas c, String text, float x, float baseline, float maxW, int color, float alpha, float blur) {
            if (alpha <= 0.003f || text == null) return;
            bodyPaint.setColor(Colors.withAlpha(color, alpha));
            bodyPaint.setMaskFilter(claudeBlur(blur));
            c.drawText(TextUtils.ellipsize(text, bodyPaint, maxW, TextUtils.TruncateAt.END).toString(), x, baseline, bodyPaint);
            bodyPaint.setMaskFilter(null);
        }
    }

    private TextPaint claudeStageText;
    private TextPaint claudeQuestionText;

    private TextPaint claudeStagePaint() {
        if (claudeStageText == null) {
            claudeStageText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            claudeStageText.setTypeface(Fonts.of(500));
            claudeStageText.setTextSize(sp(14f));
        }
        return claudeStageText;
    }

    private TextPaint claudeQuestionPaint() {
        if (claudeQuestionText == null) {
            claudeQuestionText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
            claudeQuestionText.setTypeface(Fonts.of(500));
            claudeQuestionText.setTextSize(sp(16f));
        }
        return claudeQuestionText;
    }

    private static final class Layer {
        final Content content;
        final long start;
        final long delay;
        final boolean outgoing;
        final float fromAlpha;
        int slideDir;

        private Layer(Content content, long start, long delay, boolean outgoing, float fromAlpha) {
            this.content = content;
            this.start = start;
            this.delay = delay;
            this.outgoing = outgoing;
            this.fromAlpha = fromAlpha;
        }

        static Layer incoming(Content content, long now, long delay) {
            return new Layer(content, now, delay, false, 1f);
        }

        static Layer shown(Content content) {
            return new Layer(content, 0L, 0L, false, 1f);
        }

        static Layer shownLike(Content content, Layer previous) {
            Layer l = new Layer(content, previous.start, previous.delay, false, 1f);
            l.slideDir = previous.slideDir;
            return l;
        }

        Layer slide(int dir) {
            slideDir = dir;
            return this;
        }

        Layer toOutgoing(long now, int dir) {
            Layer l = new Layer(content, now, 0L, true, alpha(now));
            l.slideDir = dir;
            return l;
        }

        float progress(long now) {
            if (outgoing) return clamp01((now - start) / (float) OUT_MS);
            return clamp01((now - start - delay) / (float) IN_MS);
        }

        long elapsedIn(long now) {
            return outgoing ? Long.MAX_VALUE / 4 : now - start - delay;
        }

        float alpha(long now) {
            float p = progress(now);
            if (outgoing) return fromAlpha * (1f - p);
            return 1f - (1f - p) * (1f - p);
        }

        float scale(long now) {
            float p = progress(now);
            if (outgoing) return 1f - 0.1f * p;
            float e = 1f - (1f - p) * (1f - p) * (1f - p);
            return 0.9f + 0.1f * e;
        }

        float offset(long now) {
            if (slideDir == 0) return 0f;
            float p = progress(now);
            if (outgoing) return slideDir * p;
            float e = 1f - (1f - p) * (1f - p);
            return slideDir * (1f - e);
        }

        boolean finished(long now) {
            return outgoing && progress(now) >= 1f;
        }

        boolean settled(long now) {
            return !outgoing && now - start - delay > IN_MS + ROW_STAGGER_MS * 4;
        }
    }


    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!rotatedNow()) return handleTouch(e);
        Matrix fwd = new Matrix();
        fwd.setRotate(sideDeg, sideCx, sideCy);
        Matrix inv = new Matrix();
        fwd.invert(inv);
        MotionEvent local = MotionEvent.obtain(e);
        try {
            local.transform(inv);
            return handleTouch(local);
        } finally {
            local.recycle();
        }
    }

    private boolean handleTouch(MotionEvent e) {
        if (tracker == null) tracker = android.view.VelocityTracker.obtain();
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) tracker.clear();
        tracker.addMovement(e);
        if (e.getActionMasked() != MotionEvent.ACTION_DOWN && ignoreGesture) {
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                ignoreGesture = false;
            }
            return true;
        }
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_OUTSIDE:
                if (mode == Mode.EXPANDED) collapse();
                return false;
            case MotionEvent.ACTION_DOWN: {
                ignoreGesture = false;
                dragAxis = 0;
                downX = e.getX();
                downY = e.getY();
                dragging = false;
                downOnMain = false;
                downOnBubble = false;
                downCard = null;
                downOnHole = false;
                if (mode == Mode.HIDDEN) {
                    downOnHole = host.hasMinimized();
                    return downOnHole;
                }
                if (visibleAlpha() < 0.4f) return false;
                float m = dp(6);
                if (mode == Mode.EXPANDED) {
                    for (Card c : cards) {
                        if (c.leaving) continue;
                        computeCard(c, tmpRect);
                        tmpRect.inset(-m, -m);
                        if (tmpRect.contains(downX, downY)) {
                            downCard = c;
                            break;
                        }
                    }
                    if (downCard == null) {
                        collapse();
                        ignoreGesture = true;
                        return true;
                    }
                    if (downCard.body instanceof MediaBody) {
                        computeCard(downCard, cardRect);
                        float left = cardContentLeft(downCard);
                        MediaBody mb = (MediaBody) downCard.body;
                        if (mb.onSeekBar(downX - left, downY - cardRect.top)) {
                            seekBody = mb;
                            seekFraction = mb.fractionAt(downX - left);
                            getParent().requestDisallowInterceptTouchEvent(true);
                            invalidate();
                        }
                    }
                    if (downCard.body instanceof TorchBody) {
                        computeCard(downCard, cardRect);
                        TorchBody tb = (TorchBody) downCard.body;
                        float lx = downX - cardContentLeft(downCard);
                        if (tb.onTrack(lx, downY - cardRect.top)) {
                            torchDrag = tb;
                            torchDragStep = torchStep(tb.item, torchPos.value());
                            moveTorch(tb, lx);
                            getParent().requestDisallowInterceptTouchEvent(true);
                            return true;
                        }
                    }
                    if (seekBody == null) {
                        computeCard(downCard, cardRect);
                        Btn pb = downCard.body.hit(downX - cardContentLeft(downCard), downY - cardRect.top);
                        if (pb != null) {
                            pressButton(pb, downCard.item);
                            scheduleFrame();
                            invalidate();
                        }
                    }
                    return true;
                }
                computeMain(mainRect);
                downOnMain = downX >= mainRect.left - m && downX <= mainRect.right + m
                        && downY >= mainRect.top - m && downY <= mainRect.bottom + m;
                if (!downOnMain && split.value() > 0.6f) {
                    computeBubble(mainRect, bubbleRect);
                    bubbleRect.inset(-m, -m);
                    downOnBubble = bubbleRect.contains(downX, downY);
                }
                if (!downOnMain && !downOnBubble) return false;
                press.animateTo(0.94f, 0.2f, 0.9f);
                scheduleFrame();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (forwardingShade) {
                    host.onShadeDragEvent(e);
                    return true;
                }
                if (seekBody != null && downCard != null) {
                    seekFraction = seekBody.fractionAt(e.getX() - cardContentLeft(downCard));
                    scheduleFrame();
                    invalidate();
                    return true;
                }
                if (torchDrag != null && downCard != null) {
                    moveTorch(torchDrag, e.getX() - cardContentLeft(downCard));
                    return true;
                }
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                if (!dragging && (Math.abs(dx) > touchSlop || Math.abs(dy) > touchSlop)) {
                    dragging = true;
                    releaseButton();
                    press.animateTo(1f, 0.35f, 0.6f);
                }
                if (dragging && mode == Mode.EXPANDED && downCard != null) {
                    if (dragAxis == 0) dragAxis = Math.abs(dx) > Math.abs(dy) ? 1 : 2;
                    stackDragging = true;
                    if (dragAxis == 1) {
                        float pull = rubber(dx, downCard.body.w * 0.55f);
                        downCard.dx.snapTo(pull);
                        for (Card o : cards) {
                            if (o != downCard && !o.leaving) {
                                o.dx.animateTo(pull * 0.12f / (1 + Math.abs(o.order - downCard.order)), 0.25f, 0.8f);
                            }
                        }
                    } else {
                        stackDy.snapTo(rubber(dy, dp(dy > 0 ? 90 : 60)));
                        stackDx.snapTo(rubber(dx, dp(24)));
                    }
                    scheduleFrame();
                    invalidate();
                    notifyHost();
                    return true;
                }
                if (dragging && mode == Mode.COMPACT && (downOnMain || downOnBubble)
                        && dy > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                    forwardingShade = true;
                    press.animateTo(1f, 0.35f, 0.6f);
                    offsetX.animateTo(0f, 0.4f, 0.55f);
                    scheduleFrame();
                    host.onShadeDragStart(downX, downY, e.getDownTime());
                    host.onShadeDragEvent(e);
                    return true;
                }
                if (dragging && mode == Mode.COMPACT && (downOnMain || downOnBubble)) {
                    float rubber = Math.signum(dx) * Math.min(dp(16), Math.abs(dx) * 0.18f);
                    offsetX.snapTo(rubber);
                    invalidate();
                    notifyHost();
                }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                if (forwardingShade) {
                    forwardingShade = false;
                    host.onShadeDragEvent(e);
                    return true;
                }
                if (seekBody != null) {
                    MediaBody mb = seekBody;
                    seekBody = null;
                    long target = (long) (seekFraction * mb.item.mediaDuration);
                    mb.item.mediaPosition = target;
                    mb.item.mediaPositionUpdateTime = SystemClock.elapsedRealtime();
                    if (mb.item.mediaSeek != null) mb.item.mediaSeek.accept(target);
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    invalidate();
                    return true;
                }
                if (torchDrag != null) {
                    endTorchDrag();
                    return true;
                }
                releaseButton();
                press.animateTo(1f, 0.35f, 0.6f);
                if (offsetX.value() != 0f) offsetX.animateTo(0f, 0.4f, 0.55f);
                scheduleFrame();
                float dx = e.getX() - downX;
                float dy = e.getY() - downY;
                boolean horizontal = Math.abs(dx) > dp(26) && Math.abs(dx) > Math.abs(dy) * 1.2f;
                if (downOnHole) {
                    if (horizontal && dx > 0 || !dragging) host.onRestore();
                    return true;
                }
                if (dragging) {
                    if (mode == Mode.COMPACT && horizontal) {
                        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                        if (dx < 0) {
                            IslandItem target = downOnBubble && secondary != null ? secondary : primary;
                            host.onMinimize(target);
                        } else if (host.hasMinimized()) {
                            host.onRestore();
                        } else {
                            host.onCycle(-1);
                        }
                    } else if (mode == Mode.EXPANDED && dragAxis == 2 && (dy < -dp(48) || (dy < -dp(16) && flingVelocity() < -dp(1100)))) {
                        flingBoost = clamp01(-flingVelocity() / dp(3200));
                        releaseStack();
                        collapse();
                    } else if (mode == Mode.EXPANDED) {
                        releaseStack();
                    }
                    return true;
                }
                onTap(e.getX(), e.getY());
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                if (forwardingShade) {
                    forwardingShade = false;
                    host.onShadeDragEvent(e);
                }
                seekBody = null;
                if (torchDrag != null) endTorchDrag();
                releaseStack();
                releaseButton();
                press.animateTo(1f, 0.35f, 0.6f);
                offsetX.animateTo(0f, 0.4f, 0.55f);
                scheduleFrame();
                return true;
            default:
                return true;
        }
    }

    private void onTap(float x, float y) {
        if (mode == Mode.COMPACT) {
            if (mainIn != null && mainIn.content.item.noExpand) return;
            expand(stackItems());
            return;
        }
        if (mode == Mode.EXPANDED && downCard != null) {
            Card c = downCard;
            computeCard(c, cardRect);
            float left = centerFor(c.body.w) - c.body.w / 2f;
            if (c.body.w < screenW) left = Math.max(Math.min(left, screenW - cardMargin - c.body.w), cardMargin);
            Btn b = c.body.hit(x - left, y - cardRect.top);
            if (b != null) {
                performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK);
                if (b.run != null) b.run.run();
                else if (b.action != null) host.onItemAction(c.item, b.action);
                return;
            }
            if (c.body instanceof TorchBody) return;
            host.onOpenItem(c.item, new RectF(cardRect));
            collapse();
        }
    }


    private String trailingString(IslandItem item, boolean compact) {
        switch (item.trailing) {
            case TEXT:
                if (item.pausedMs >= 0) return formatTimeCentis(item.pausedMs);
                if (item.trailingText == null) return null;
                String s = item.trailingText.toString();
                return compact ? limitChars(s, IslandSettings.maxChars()) : s;
            case CHRONOMETER: {
                long now = System.currentTimeMillis();
                long ms = item.chronoCountDown ? item.chronoBase - now : now - item.chronoBase;
                return item.chronoMillis ? formatTimeCentis(ms) : formatTime(Math.max(0, ms));
            }
            default:
                return null;
        }
    }

    static String limitChars(String s, int max) {
        if (max >= dev.flexy.dynamicisland.Settings.NO_MAX_CHARS || s.codePointCount(0, s.length()) <= max) return s;
        return s.substring(0, s.offsetByCodePoints(0, Math.max(1, max - 1))).trim() + "…";
    }

    public void onSettingsChanged() {
        if (mainIn != null && mainIn.content instanceof CompactContent) {
            CompactContent cc = (CompactContent) mainIn.content;
            if (cc.remeasure() && mode == Mode.COMPACT) {
                width.animateTo(cc.desiredWidth(), 0.30f, 0.80f);
                scheduleFrame();
            }
        }
        invalidate();
    }

    static String formatTimeCentis(long ms) {
        ms = Math.max(0, ms);
        if (ms >= 3_600_000L) return formatTime(ms);
        return formatTime(ms) + String.format(Locale.US, ".%02d", (ms % 1000) / 10);
    }

    static String formatTimePadded(long ms) {
        long total = Math.max(0, ms) / 1000;
        if (total >= 3600) return formatTime(ms);
        return String.format(Locale.US, "%02d:%02d", (total / 60) % 60, total % 60);
    }

    static String formatTime(long ms) {
        if (ms < 0) return "0:00";
        long total = ms / 1000;
        long h = total / 3600;
        long m = (total / 60) % 60;
        long s = total % 60;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%d:%02d", m, s);
    }

    private float dp(float v) {
        return v * dp;
    }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }

    private static long now() {
        return SystemClock.uptimeMillis();
    }

    static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
