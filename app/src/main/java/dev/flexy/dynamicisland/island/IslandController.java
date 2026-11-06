package dev.flexy.dynamicisland.island;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.DisplayCutout;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.flexy.dynamicisland.xposed.XLog;

public final class IslandController implements IslandView.Host, IslandWindow.Listener {
    private static final long FLY_IN_DELAY_MS = 260;
    private static final long UNLOCK_REVEAL_DELAY_MS = 200;

    private static IslandController instance;

    final Context ctx;
    final ClassLoader cl;
    final Handler main = new Handler(Looper.getMainLooper());
    final OaSource oa;
    final MediaSource media;
    final CastSource cast;
    final ExitAnimationBridge exit;
    final AlertSource alerts;
    private final FlashlightSource torch;
    private final StatusBarIconFader fader;
    private final AudioLevels audio = new AudioLevels();
    private final float density;

    private IslandWindow window;
    private IslandView view;
    private View statusBarView;
    private final View.OnLayoutChangeListener statusBarLayoutListener =
            (v, l, t, r, b, ol, ot, or, ob) -> main.post(this::updateGeometry);

    private int barState;
    private boolean dozing;
    private int statusBarWindowState;
    private boolean revealPending;
    private boolean keyguardStuck;
    private long sleepFoldAt = -SLEEP_FOLD_MS;
    private static final long SLEEP_FOLD_MS = 420;
    private final Runnable afterSleepFold = this::updateSuppression;

    public void onStartedWakingUp() {
        main.removeCallbacks(wakeCheck);
        main.postDelayed(wakeCheck, 250);
    }

    private final Runnable wakeCheck = () -> {
        if (view != null && barState == 0 && !dozing && !revealPending) view.setSleepFold(false);
        updateSuppression();
    };

    public void onStartedGoingToSleep() {
        startSleepFold();
        main.postDelayed(this::requestRefresh, 800);
    }

    private void startSleepFold() {
        if (view == null) return;
        sleepFoldAt = SystemClock.uptimeMillis();
        view.setSleepFold(true);
        main.removeCallbacks(afterSleepFold);
        main.postDelayed(afterSleepFold, SLEEP_FOLD_MS + 20);
    }
    private final Runnable revealRunnable = () -> {
        revealPending = false;
        if (view != null) view.setSleepFold(false);
        updateSuppression();
        if (view != null) view.reveal();
        watchKeyguard();
        showUnlockAlerts();
    };

    private void showUnlockAlerts() {
        alerts.onUnlocked();
        requestRefresh();
    }

    private final Runnable keyguardWatch = new Runnable() {
        @Override
        public void run() {
            boolean locked = keyguardLocked();
            boolean stuck = locked && barState == 0;
            if (stuck != keyguardStuck) {
                keyguardStuck = stuck;
                XLog.i("keyguard watch: locked=" + locked + " barState=" + barState + " -> hide=" + stuck);
                updateSuppression();
            }
            if (locked) main.postDelayed(this, 1000);
        }
    };

    private void watchKeyguard() {
        main.removeCallbacks(keyguardWatch);
        main.postDelayed(keyguardWatch, 1000);
    }

    private boolean keyguardLocked() {
        android.app.KeyguardManager km = ctx.getSystemService(android.app.KeyguardManager.class);
        return km != null && km.isKeyguardLocked();
    }

    private Set<String> lastHiddenByForeground = new HashSet<>();
    private Set<String> visibleKeys = new HashSet<>();
    private final Map<String, Long> deferred = new HashMap<>();
    private final Map<String, Long> boost = new HashMap<>();
    private final List<String> minimized = new ArrayList<>();
    private Set<String> lastPackages = new LinkedHashSet<>();
    private String pinnedKey;
    private final List<String> ring = new ArrayList<>();
    private List<String> visibleOrder = new ArrayList<>();
    private int pendingHint = IslandView.HINT_NONE;

    private float cyPortrait = -1f;
    private float cyLandscape = -1f;
    private Rect lastNotchLog;
    /** Last landscape notch that was read, and the rotation it belongs to. */
    private Rect cachedLandscapeNotch;
    private int cachedNotchRotation = -1;
    private int notchRetries;
    private final RectF tmp = new RectF();
    private final int[] loc = new int[2];
    private boolean refreshPosted;
    private final IslandItem lengthPreview = makeLengthPreview();

    private static IslandItem makeLengthPreview() {
        IslandItem it = new IslandItem("preview:length");
        it.blank = true;
        it.noExpand = true;
        it.trailing = IslandItem.Trailing.TEXT;
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < dev.flexy.dynamicisland.Settings.NO_MAX_CHARS + 8; i++) text.append('0');
        it.trailingText = text;
        it.postTime = Long.MAX_VALUE;
        return it;
    }
    private final Runnable refreshRunnable = this::refreshNow;

    private IslandController(Context ctx, ClassLoader cl) {
        this.ctx = ctx;
        this.cl = cl;
        density = ctx.getResources().getDisplayMetrics().density;
        oa = new OaSource(ctx, cl);
        media = new MediaSource(ctx, main, this::requestRefresh);
        cast = new CastSource(ctx);
        exit = new ExitAnimationBridge(cl);
        alerts = new AlertSource(ctx, oa, this::requestRefresh);
        torch = new FlashlightSource(ctx, main, this::requestRefresh);
        fader = new StatusBarIconFader(density);
    }

    public static synchronized IslandController init(Context ctx, ClassLoader cl) {
        if (instance == null) {
            instance = new IslandController(ctx, cl);
            instance.main.post(instance::start);
            XLog.i("controller created");
        }
        return instance;
    }

    public static IslandController get() {
        return instance;
    }

    public void onNotifications(List<android.service.notification.StatusBarNotification> all) {
        if (Looper.myLooper() != main.getLooper()) {
            List<android.service.notification.StatusBarNotification> copy = new ArrayList<>(all);
            main.post(() -> alerts.onNotifications(copy));
            return;
        }
        alerts.onNotifications(all);
    }

    public boolean hasTorch() {
        return torch.available();
    }

    public boolean torchOn() {
        return torch.isOn();
    }

    public IslandItem itemForKey(String notificationKey) {
        IslandItem assistant = AssistantApp.itemForKey(notificationKey);
        if (assistant != null) return assistant;
        if (torch.available() && FlashlightSource.isSystemTorchKey(notificationKey)) return torch.item();
        return oa.itemForKey(notificationKey);
    }

    public IslandItem mediaItemForSession(android.media.session.MediaSession.Token token) {
        return media.itemForSession(token);
    }

    private void addSavedRecording(List<IslandItem> items) {
        VoiceLevels levels = VoiceLevels.get();
        long now = SystemClock.uptimeMillis();
        if (!levels.savedRecently(now)) return;
        for (IslandItem i : items) {
            if (i.recorder && levels.recording()) return;
        }
        items.removeIf(i -> i.recorder);
        IslandItem it = new IslandItem("voice:saved");
        it.kind = IslandItem.Kind.RECORDING;
        it.recorder = true;
        it.recorderSaved = true;
        it.packageName = VoiceLevels.PACKAGE;
        it.accent = 0xFFFFFFFF;
        it.postTime = Long.MAX_VALUE;
        items.add(it);
        main.postDelayed(this::requestRefresh, Math.max(16, levels.savedUntil() - now + 20));
    }

    private void start() {
        media.start();
        alerts.start();
        torch.start();
        VoiceLevels.get().register(ctx, () -> main.post(this::requestRefresh));
        AssistantApp.register(ctx, () -> main.post(this::requestRefresh));
        IslandSettings.watch(ctx, () -> {
            if (view != null) view.onSettingsChanged();
            updateGeometry();
            requestRefresh();
        });
        oa.onChanged = this::requestRefresh;
        try {
            ctx.registerReceiver(new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, android.content.Intent i) {
                    requestRefresh();
                }
            }, new android.content.IntentFilter(android.media.AudioManager.ACTION_MICROPHONE_MUTE_CHANGED),
                    Context.RECEIVER_NOT_EXPORTED);
        } catch (Throwable t) {
            XLog.e("mute receiver failed", t);
        }
        try {
            ctx.getSystemService(android.media.AudioManager.class)
                    .addOnCommunicationDeviceChangedListener(ctx.getMainExecutor(), dev -> requestRefresh());
        } catch (Throwable t) {
            XLog.e("speaker listener failed", t);
        }
        requestRefresh();
    }


    public void requestRefresh() {
        if (Looper.myLooper() != main.getLooper()) {
            main.post(this::requestRefresh);
            return;
        }
        if (refreshPosted) return;
        refreshPosted = true;
        main.post(refreshRunnable);
    }

    public void onStatusBarViewAttached(View v) {
        if (statusBarView == v) return;
        if (statusBarView != null) statusBarView.removeOnLayoutChangeListener(statusBarLayoutListener);
        statusBarView = v;
        v.addOnLayoutChangeListener(statusBarLayoutListener);
        fader.attach(v);
        ensureWindow();
        updateGeometry();
        requestRefresh();
    }

    public void onStatusBarViewDetached(View v) {
        if (statusBarView != v) return;
        v.removeOnLayoutChangeListener(statusBarLayoutListener);
        fader.detach();
        statusBarView = null;
    }

    public void setOngoingActivityController(Object controller) {
        exit.setOngoingActivityController(controller);
        lastPackages = new LinkedHashSet<>();
        requestRefresh();
    }

    public void setCallController(Object controller) {
        oa.callController = controller;
    }

    public void setCastBundle(Object bundle) {
        cast.onBundle(bundle);
        requestRefresh();
    }

    public void setCastController(Object controller) {
        cast.castController = controller;
    }

    public void setBarState(int state) {
        if (barState == state) return;
        boolean unlocking = (barState == 1 || barState == 2) && state == 0;
        if (barState == 0 && state == 1) startSleepFold();
        barState = state;
        if (unlocking) {
            revealPending = true;
            main.removeCallbacks(revealRunnable);
            main.postDelayed(revealRunnable, UNLOCK_REVEAL_DELAY_MS);
        }
        updateSuppression();
        watchKeyguard();
    }

    public void setDozing(boolean value) {
        if (dozing == value) return;
        dozing = value;
        if (value) startSleepFold();
        else if (barState == 0 && !revealPending && view != null) view.setSleepFold(false);
        updateSuppression();
        watchKeyguard();
    }

    public void setStatusBarWindowState(int state) {
        if (statusBarWindowState == state) return;
        statusBarWindowState = state;
        updateSuppression();
    }

    public void setHeadsUpPinned(boolean pinned) {
        if (view != null) view.setHeadsUpPinned(pinned);
    }

    public void setPanelState(int state) {
        if (view != null) view.setPanelState(state);
    }

    public void setShadeExpansion(float fraction) {
        if (view != null) view.setShadeFraction(fraction);
    }

    public void onExitTargetsRequested() {
        pushExitTargets(true);
    }


    @Override
    public boolean onShadeFade(float alpha, float scale, float px, float py) {
        return window != null && window.setSurfaceFade(alpha, scale, px, py);
    }

    private void refreshNow() {
        refreshPosted = false;
        ensureWindow();
        if (view == null) return;
        List<IslandItem> items = new ArrayList<>();
        Set<String> hidden = new HashSet<>();
        Set<String> packages = new LinkedHashSet<>();
        try {
            oa.collect(items, hidden, packages);
            media.collect(items, oa.foregroundPackage(), hidden, packages);
            cast.collect(items);
            alerts.collectDownloads(items);
            AssistantApp.collectAll(items, ctx);
            if (torch.available()) items.removeIf(FlashlightSource::isSystemTorchItem);
            torch.collect(items, view.isExpanded());
            addSavedRecording(items);
        } catch (Throwable t) {
            XLog.e("collect failed", t);
        }
        if (items.isEmpty() && IslandSettings.showFullscreen()) {
            IslandItem idle = new IslandItem("idle:fullscreen");
            idle.kind = IslandItem.Kind.LIVE;
            idle.idle = true;
            idle.noExpand = true;
            idle.accent = 0xFF8E8E93;
            idle.postTime = Long.MAX_VALUE;
            items.add(idle);
        }

        long now = SystemClock.uptimeMillis();
        long nextDeferred = Long.MAX_VALUE;
        boolean flyIn = false;
        Set<String> present = new HashSet<>();
        for (IslandItem i : items) present.add(i.key);
        present.addAll(hidden);
        deferred.keySet().retainAll(present);
        boost.keySet().retainAll(present);
        minimized.retainAll(present);

        Map<String, IslandItem> minimizedItems = new HashMap<>();
        for (Iterator<IslandItem> it = items.iterator(); it.hasNext(); ) {
            IslandItem item = it.next();
            if (lastHiddenByForeground.contains(item.key) && !visibleKeys.contains(item.key)) {
                deferred.put(item.key, now + FLY_IN_DELAY_MS);
                boost.put(item.key, now);
                minimized.remove(item.key);
                pinnedKey = null;
            }
            Long until = deferred.get(item.key);
            if (until != null) {
                if (until > now) {
                    nextDeferred = Math.min(nextDeferred, until);
                    it.remove();
                    continue;
                }
                deferred.remove(item.key);
                flyIn = true;
            }
            if (minimized.contains(item.key)) {
                minimizedItems.put(item.key, item);
                it.remove();
            }
        }
        lastHiddenByForeground = hidden;

        Collections.sort(items, (a, b) -> {
            Long ba = boost.get(a.key);
            Long bb = boost.get(b.key);
            if (ba != null || bb != null) {
                if (ba == null) return 1;
                if (bb == null) return -1;
                if (!ba.equals(bb)) return Long.compare(bb, ba);
            }
            if (a.priority() != b.priority()) return Integer.compare(b.priority(), a.priority());
            return Long.compare(b.postTime, a.postTime);
        });

        applyCycle(items);
        boolean wasVisible = !visibleKeys.isEmpty();
        Set<String> keys = new HashSet<>();
        visibleOrder = new ArrayList<>();
        for (IslandItem i : items) {
            keys.add(i.key);
            visibleOrder.add(i.key);
        }
        visibleKeys = keys;
        int hint = pendingHint;
        pendingHint = IslandView.HINT_NONE;
        List<IslandItem> hiddenList = new ArrayList<>();
        for (String k : minimized) {
            IslandItem m = minimizedItems.get(k);
            if (m != null) hiddenList.add(m);
        }
        IslandItem alert = alerts.current(now, view.isExpanded());
        if (IslandSettings.previewLength()) {
            view.setItems(Collections.singletonList(lengthPreview), IslandView.HINT_NONE, Collections.emptyList());
        } else if (alert != null) {
            boolean idleOnly = items.size() == 1 && items.get(0).idle;
            if (items.isEmpty() || idleOnly) {
                view.setItems(Collections.singletonList(alert), IslandView.HINT_NONE, Collections.emptyList());
            } else {
                items.add(1, alert);
                view.setItems(items, hint, hiddenList);
            }
            main.postDelayed(this::requestRefresh, Math.max(16, alerts.until() - now));
        } else {
            view.setItems(items, hint, hiddenList);
            if (flyIn && wasVisible) view.absorb();
        }

        if (nextDeferred != Long.MAX_VALUE) {
            main.postDelayed(this::requestRefresh, Math.max(16, nextDeferred - now));
        }
        if (!packages.equals(lastPackages)) {
            lastPackages = packages;
            pushExitTargets(false);
        }
        onIslandFrame();
    }

    private void applyCycle(List<IslandItem> items) {
        if (pinnedKey == null) return;
        Map<String, IslandItem> byKey = new HashMap<>();
        for (IslandItem i : items) byKey.put(i.key, i);
        boolean fresh = false;
        for (String k : byKey.keySet()) fresh |= !ring.contains(k);
        if (fresh || !byKey.containsKey(pinnedKey)) {
            pinnedKey = null;
            ring.clear();
            return;
        }
        ring.retainAll(byKey.keySet());
        int start = ring.indexOf(pinnedKey);
        items.clear();
        for (int n = 0; n < ring.size(); n++) items.add(byKey.get(ring.get((start + n) % ring.size())));
    }


    private void ensureWindow() {
        if (window != null) return;
        if (statusBarView == null) return;
        try {
            window = new IslandWindow(ctx, this, this);
            view = window.view;
            window.setShown(false);
            updateGeometry();
            window.attach();
            updateSuppression();
        } catch (Throwable t) {
            XLog.e("island window creation failed", t);
            window = null;
            view = null;
        }
    }

    private void updateGeometry() {
        if (window == null) return;
        Rect b = window.windowBounds();
        int w = b.width();
        int h = b.height();
        if (w <= 0 || h <= 0) return;
        boolean tablet = Math.min(w, h) / density >= 600f;
        int position = IslandSettings.position(tablet);
        boolean landscape = w > h;
        float cx = w / 2f;
        float cy = statusBarCenterY();
        float holeD = 0f;
        Rect landscapeNotchRect = null;
        DisplayCutout c = window.cutout();
        Rect top = c != null ? c.getBoundingRectTop() : null;
        if (top != null && top.isEmpty()) top = null;
        if (position == dev.flexy.dynamicisland.Settings.POS_CAMERA && top == null && w < h) {
            top = hiddenCameraHole(b);
            if (top != null) cy = Math.max(cy, top.exactCenterY() - b.top);
        }
        if (position == dev.flexy.dynamicisland.Settings.POS_CAMERA && top != null) cx = top.exactCenterX() - b.left;
        if (landscape && IslandSettings.landscapeNotch()) {
            // The window insets are sometimes empty during rotation. Reuse the notch read earlier
            // for this same rotation instead of dropping the island to the center of the screen.
            int rot = window.rotation();
            landscapeNotchRect = landscapeNotch(c, b);
            if (landscapeNotchRect != null) {
                cachedLandscapeNotch = new Rect(landscapeNotchRect);
                cachedNotchRotation = rot;
                notchRetries = 0;
            } else if (cachedLandscapeNotch != null && cachedNotchRotation == rot) {
                landscapeNotchRect = new Rect(cachedLandscapeNotch);
            } else if (notchRetries < 10) {
                // Nothing known yet for this rotation: check again shortly.
                notchRetries++;
                main.postDelayed(this::updateGeometry, 200);
            }
            if (landscapeNotchRect != null) {
                cx = landscapeNotchRect.exactCenterX() - b.left;
                cy = landscapeNotchRect.exactCenterY() - b.top;
                top = landscapeNotchRect;
            }
        }
        if (top != null && Math.abs(top.exactCenterX() - b.left - cx) < 10 * density) {
            holeD = Math.min(top.width(), top.height()) * 0.78f;
        }
        float anchor = Float.NaN;
        float limit = Float.NaN;
        if (position == dev.flexy.dynamicisland.Settings.POS_LEFT) {
            anchor = clockEnd(b.left, tablet);
            holeD = 0f;
            limit = w - 12 * density;
            if (top != null && top.left - b.left > anchor) limit = Math.min(limit, top.left - b.left - 7 * density);
        }
        if (landscapeNotchRect != null) {
            // Flush against the notch edge and grow toward the screen center, like iPhone in landscape.
            // The pill may use the whole remaining width, so it never runs off the screen.
            anchor = Math.max(0f, landscapeNotchRect.left - b.left);
            limit = w - 12 * density;
            holeD = 0f;
        }
        if (fader != null) fader.setKeepDate(tablet && position == dev.flexy.dynamicisland.Settings.POS_LEFT);
        boolean glass = (position == dev.flexy.dynamicisland.Settings.POS_LEFT
                || (tablet && position == dev.flexy.dynamicisland.Settings.POS_CENTER)) && IslandSettings.glassPill(tablet);
        if (landscape) cyLandscape = cy;
        else cyPortrait = cy;
        view.setGeometry(w, cx, cy, holeD, tablet, anchor, glass, limit);
        applyWindowHeight();
        updateSuppression();
        pushExitTargets(false);
    }

    private Rect landscapeNotch(DisplayCutout cutout, Rect bounds) {
        if (cutout == null) return null;
        Rect side = null;
        Rect fallback = null;
        int sideArea = -1;
        int fallbackArea = -1;
        for (Rect rect : cutout.getBoundingRects()) {
            if (rect == null || rect.isEmpty()) continue;
            int area = rect.width() * rect.height();
            if (area > fallbackArea) {
                fallback = new Rect(rect);
                fallbackArea = area;
            }
            boolean edge = rect.left <= bounds.left + 2 || rect.right >= bounds.right - 2;
            if (edge && area > sideArea) {
                side = new Rect(rect);
                sideArea = area;
            }
        }
        Rect result = side != null ? side : fallback;
        if (result != null && !result.equals(lastNotchLog)) {
            lastNotchLog = new Rect(result);
            XLog.i("landscape notch " + result.left + "," + result.top + " "
                    + result.width() + "x" + result.height() + (side == null ? " fallback" : ""));
        }
        return result;
    }

    private Rect hiddenCameraHole(Rect bounds) {
        try {
            android.content.res.Resources r = android.content.res.Resources.getSystem();
            int id = r.getIdentifier("config_mainBuiltInDisplayCutoutForUDC", "string", "android");
            String spec = id == 0 ? null : r.getString(id);
            if (spec == null || spec.trim().isEmpty()) return null;
            float scale = spec.contains("@dp") ? density : 1f;
            float origin = spec.contains("@left") ? 0f : bounds.width() / 2f;
            String data = spec.replaceAll("@[a-z_]+", "").trim();
            android.graphics.Path path = (android.graphics.Path) Class.forName("android.util.PathParser")
                    .getMethod("createPathFromPathData", String.class).invoke(null, data);
            android.graphics.RectF box = new android.graphics.RectF();
            path.computeBounds(box, true);
            if (box.width() <= 0f) return null;
            int size = Math.round(box.width() * scale);
            int left = Math.round(bounds.left + origin + box.left * scale);
            int bottom = Math.round(box.bottom * scale);
            return new Rect(left, Math.max(0, bottom - size), left + size, bottom);
        } catch (Throwable t) {
            XLog.e("hidden camera cutout", t);
            return null;
        }
    }

    private View watchedClock;
    private float lastClockEnd = Float.NaN;
    private final Runnable clockMoved = this::updateGeometry;

    private float clockEnd(int windowLeft, boolean large) {
        View sb = statusBarView;
        int id = sb == null ? 0 : sb.getResources().getIdentifier("clock", "id", "com.android.systemui");
        View clock = id == 0 ? null : sb.findViewById(id);
        if (clock != null && clock != watchedClock) {
            watchedClock = clock;
            clock.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
                v.removeCallbacks(clockMoved);
                v.post(clockMoved);
            });
            if (clock instanceof android.widget.TextView) {
                ((android.widget.TextView) clock).addTextChangedListener(new android.text.TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int st, int co, int af) {
                    }

                    @Override
                    public void onTextChanged(CharSequence s, int st, int be, int co) {
                    }

                    @Override
                    public void afterTextChanged(android.text.Editable s) {
                        clock.removeCallbacks(clockMoved);
                        clock.post(clockMoved);
                    }
                });
            }
        }
        if (clock != null && clock.isShown() && clock.getWidth() > 0 && clock.isAttachedToWindow()) {
            clock.getLocationOnScreen(loc);
            float end = large ? -1f : StatusBarIconFader.timeEnd(clock);
            if (end <= 0f) end = clock.getWidth() * clock.getScaleX();
            lastClockEnd = loc[0] - windowLeft + end + 8 * density;
        }
        else if (sb != null && Float.isNaN(lastClockEnd)) {
            sb.removeCallbacks(clockMoved);
            sb.postDelayed(clockMoved, 400);
        }
        if (Float.isNaN(lastClockEnd)) return 96 * density;
        return lastClockEnd;
    }

    private float statusBarCenterY() {
        View sb = statusBarView;
        if (sb != null) {
            int id = sb.getResources().getIdentifier("status_bar_contents", "id", "com.android.systemui");
            View contents = id == 0 ? null : sb.findViewById(id);
            if (contents != null && contents.getHeight() > 0 && contents.isAttachedToWindow()) {
                contents.getLocationOnScreen(loc);
                return loc[1] + contents.getHeight() / 2f;
            }
        }
        int res = ctx.getResources().getIdentifier("status_bar_height", "dimen", "android");
        float sbh = res != 0 ? ctx.getResources().getDimension(res) : 30 * density;
        return sbh * 0.53f;
    }

    private void updateSuppression() {
        if (view == null) return;
        boolean keyguard = barState == 1 || barState == 2;
        view.setStatusBarHidden(statusBarWindowState != 0 && !IslandSettings.showFullscreen());
        boolean folding = SystemClock.uptimeMillis() - sleepFoldAt < SLEEP_FOLD_MS;
        view.setSuppressed(((keyguard || keyguardStuck || dozing) && !folding) || revealPending);
        onIslandFrame();
    }

    private void pushExitTargets(boolean force) {
        if (window == null || view == null) return;
        Rect b = window.windowBounds();
        int shortSide = Math.min(b.width(), b.height());
        int longSide = Math.max(b.width(), b.height());
        view.getRestingPillBounds(tmp);
        float pw = Math.max(tmp.width(), 90 * density);
        float ph = Math.max(tmp.height(), 30 * density);
        float cyP = cyPortrait > 0 ? cyPortrait : 22 * density;
        float cyL = cyLandscape > 0 ? cyLandscape : 15 * density;
        boolean portrait = b.width() <= b.height();
        float cxP = portrait ? tmp.centerX() : shortSide / 2f;
        float cxL = portrait ? longSide / 2f : tmp.centerX();
        Rect port = new Rect(Math.round(cxP - pw / 2f), Math.round(cyP - ph / 2f),
                Math.round(cxP + pw / 2f), Math.round(cyP + ph / 2f));
        Rect land = new Rect(Math.round(cxL - pw / 2f), Math.round(cyL - ph / 2f),
                Math.round(cxL + pw / 2f), Math.round(cyL + ph / 2f));
        exit.push(lastPackages, port, land, force);
    }


    @Override
    public void onIslandFrame() {
        if (window == null || view == null) return;
        window.invalidateTouchRegion();
        boolean hidden = view.isFullyHidden();
        window.setShown(true);
        if (!view.shadePulled()) {
            view.getCoverageBounds(tmp);
            fader.update(tmp, !hidden && view.visibleAlpha() > 0.3f);
        }
        audio.setActive(view.needsAudio());
    }

    @Override
    public void onOpenItem(IslandItem item, RectF from) {
        PendingIntent pi = item.contentIntent;
        try {
            Bundle options = launchOptions(from);
            if (pi != null) {
                pi.send(ctx, 0, null, null, null, null, options);
            } else if (item.packageName != null) {
                Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(item.packageName);
                if (launch != null) {
                    launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    ctx.startActivity(launch, options);
                }
            }
        } catch (Throwable t) {
            XLog.e("open item failed", t);
        }
    }

    @Override
    public void onItemAction(IslandItem item, IslandItem.Action action) {
        try {
            if (action.runnable != null) {
                action.runnable.run();
            } else if (action.intent != null) {
                action.intent.send(ctx, 0, null, null, null, null, balOptions().toBundle());
            }
        } catch (Throwable t) {
            XLog.e("action failed", t);
        }
    }

    @Override
    public void onExpandedChanged(boolean expanded) {
        if (window != null) window.setWatchOutsideTouch(expanded);
        pushExitTargets(false);
        if (!expanded) requestRefresh();
    }

    @Override
    public void onCycle(int direction) {
        if (visibleOrder.size() < 2) return;
        if (pinnedKey == null) {
            ring.clear();
            ring.addAll(visibleOrder);
        }
        int n = ring.size();
        int current = ring.indexOf(visibleOrder.get(0));
        pinnedKey = ring.get(Math.floorMod(current + direction, n));
        pendingHint = direction > 0 ? IslandView.HINT_MINIMIZE : IslandView.HINT_RESTORE;
        requestRefresh();
    }

    @Override
    public void onShadeDragStart(float downX, float downY, long downTime) {
        shadeGestureHandled = false;
        shadeGestureExpanded = false;
        if (statusBarView == null) {
            expandShadeFallback();
            return;
        }
        MotionEvent down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, downX, downY, 0);
        try {
            shadeGestureHandled = statusBarView.dispatchTouchEvent(down);
        } finally {
            down.recycle();
        }
        if (!shadeGestureHandled) expandShadeFallback();
    }

    @Override
    public void onShadeDragEvent(MotionEvent e) {
        if (!shadeGestureHandled || statusBarView == null) return;
        MotionEvent copy = MotionEvent.obtain(e);
        try {
            statusBarView.dispatchTouchEvent(copy);
        } finally {
            copy.recycle();
        }
    }

    private boolean shadeGestureHandled;
    private boolean shadeGestureExpanded;

    private void expandShadeFallback() {
        if (shadeGestureExpanded) return;
        shadeGestureExpanded = true;
        try {
            Object sbm = ctx.getSystemService("statusbar");
            if (sbm != null) sbm.getClass().getMethod("expandNotificationsPanel").invoke(sbm);
        } catch (Throwable t) {
            XLog.e("expand shade failed", t);
        }
    }

    @Override
    public void onMinimize(IslandItem item) {
        if (item == null || minimized.contains(item.key)) return;
        minimized.add(item.key);
        boost.remove(item.key);
        pendingHint = IslandView.HINT_MINIMIZE;
        requestRefresh();
    }

    @Override
    public void onRestore() {
        if (minimized.isEmpty()) return;
        String key = minimized.remove(minimized.size() - 1);
        boost.put(key, SystemClock.uptimeMillis());
        pendingHint = IslandView.HINT_RESTORE;
        requestRefresh();
    }

    @Override
    public boolean hasMinimized() {
        return !minimized.isEmpty();
    }

    @Override
    public void onWindowHeightNeeded(int px) {
        neededHeight = px;
        applyWindowHeight();
    }

    private int neededHeight;

    private void applyWindowHeight() {
        if (window == null) return;
        int h = Math.max(neededHeight, Math.round(statusBarCenterY() + 55 * density));
        if (view != null && view.isExpanded()) h = Math.max(h, window.windowBounds().height());
        window.setWindowHeight(h);
    }

    @Override
    public AudioLevels audio() {
        return audio;
    }

    @Override
    public void onWindowConfigurationChanged() {
        main.post(this::updateGeometry);
    }

    @Override
    public void onWindowInsetsChanged() {
        updateGeometry();
    }

    private Bundle launchOptions(RectF from) {
        ActivityOptions o;
        if (view != null && from != null && from.width() > 0) {
            o = ActivityOptions.makeScaleUpAnimation(view, Math.round(from.left), Math.round(from.top),
                    Math.round(from.width()), Math.round(from.height()));
        } else {
            o = ActivityOptions.makeBasic();
        }
        allowBal(o);
        return o.toBundle();
    }

    private static ActivityOptions balOptions() {
        ActivityOptions o = ActivityOptions.makeBasic();
        allowBal(o);
        return o;
    }

    @SuppressWarnings("deprecation")
    private static void allowBal(ActivityOptions o) {
        try {
            o.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED);
        } catch (Throwable ignored) {
        }
    }
}
