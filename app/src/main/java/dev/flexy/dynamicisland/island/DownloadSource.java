package dev.flexy.dynamicisland.island;

import android.app.Notification;
import android.content.Context;
import android.service.notification.StatusBarNotification;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Browser downloads. While a file downloads, the island shows a progress ring. When it finishes,
 * a short alert appears. The text comes from the browser's own notification, so it is in the
 * user's language.
 */
final class DownloadSource {
    static final Set<String> PACKAGES = new HashSet<>(Arrays.asList(
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "com.chrome.canary",
            "com.sec.android.app.sbrowser"));

    private static final long FRESH_MS = 5000;
    private static final int NOT_DOWNLOADING = -2;
    private static final int INDETERMINATE = -1;
    private static final int PROGRESS_BLUE = 0xFF8AB4F8;
    private static final int DONE_GREEN = 0xFF30D158;

    private final Context ctx;
    private final Runnable onChanged;
    /** Notifications of downloads in progress, by key. */
    private final Map<String, StatusBarNotification> active = new HashMap<>();
    /** Last progress percent of each active download, or INDETERMINATE. */
    private final Map<String, Integer> percent = new HashMap<>();
    private final Map<String, Long> posted = new HashMap<>();
    private boolean primed;

    DownloadSource(Context ctx, Runnable onChanged) {
        this.ctx = ctx;
        this.onChanged = onChanged;
    }

    /**
     * Updates the live downloads and returns an alert for the newest finished download, or null.
     * The first call only records what is already running, so nothing old pops up at boot.
     */
    IslandItem scan(List<StatusBarNotification> all) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        StatusBarNotification done = null;
        Set<String> live = new HashSet<>();
        for (StatusBarNotification sbn : all) {
            if (!PACKAGES.contains(sbn.getPackageName())) continue;
            String key = sbn.getKey();
            live.add(key);
            Notification n = sbn.getNotification();
            long post = sbn.getPostTime();
            Long before = posted.put(key, post);
            boolean newPost = before == null || before != post;

            int pct = progress(n);
            if (pct != NOT_DOWNLOADING) {
                Integer prev = percent.put(key, pct);
                active.put(key, sbn);
                if (prev == null || prev != pct) changed = true;
                continue;
            }

            boolean wasActive = active.remove(key) != null;
            if (wasActive) {
                percent.remove(key);
                changed = true;
            }
            if (!primed) continue;
            // A download either ended while we watched it, or it is a new download notification
            // posted just now. Plain web-push notifications from Chrome never pass this check.
            boolean finished = wasActive || (newPost && now - post < FRESH_MS && isDownloadChannel(n));
            if (finished && (done == null || post > done.getPostTime())) done = sbn;
        }

        Iterator<String> it = active.keySet().iterator();
        while (it.hasNext()) {
            String key = it.next();
            if (!live.contains(key)) {
                // Removed: the user cancelled it or the browser dropped it. No alert.
                it.remove();
                percent.remove(key);
                changed = true;
            }
        }
        posted.keySet().retainAll(live);

        boolean wasPrimed = primed;
        primed = true;
        if (changed) onChanged.run();
        return wasPrimed && done != null && active.isEmpty() ? completed(done) : null;
    }

    /** Adds one progress item per active download. Skips downloads the system already shows. */
    void collect(List<IslandItem> out, OaSource oa) {
        if (!IslandSettings.showDownloads()) return;
        for (Map.Entry<String, StatusBarNotification> e : active.entrySet()) {
            StatusBarNotification sbn = e.getValue();
            if (oa != null && oa.itemForKey(sbn.getKey()) != null) continue;
            Notification n = sbn.getNotification();
            String pkg = sbn.getPackageName();
            int pct = percent.getOrDefault(e.getKey(), INDETERMINATE);

            IslandItem it = new IslandItem("dl:" + sbn.getKey());
            it.kind = IslandItem.Kind.CHARGING;
            it.packageName = pkg;
            it.userId = sbn.getUserId();
            it.postTime = sbn.getPostTime();
            it.icon = AppInfo.icon(ctx, pkg);
            it.iconIsImage = it.icon != null;
            it.accent = PROGRESS_BLUE;
            CharSequence title = n.extras.getCharSequence(Notification.EXTRA_TITLE);
            it.title = title != null && title.length() > 0 ? title : AppInfo.label(ctx, pkg);
            if (pct >= 0) {
                it.progress = pct / 100f;
                it.trailing = IslandItem.Trailing.PROGRESS_RING;
            }
            it.contentIntent = n.contentIntent;
            out.add(it);
        }
    }

    private IslandItem completed(StatusBarNotification sbn) {
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        IslandItem it = new IslandItem("dl:done:" + sbn.getKey());
        it.alert = true;
        it.kind = IslandItem.Kind.LIVE;
        it.packageName = pkg;
        it.userId = sbn.getUserId();
        it.postTime = Long.MAX_VALUE;
        it.icon = AppInfo.icon(ctx, pkg);
        it.iconIsImage = it.icon != null;
        it.accent = DONE_GREEN;
        CharSequence status = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        it.overline = status != null && status.length() > 0 ? AppInfo.clip(status, 40) : AppInfo.label(ctx, pkg);
        CharSequence name = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        it.title = name != null && name.length() > 0 ? AppInfo.clip(name, 60) : AppInfo.label(ctx, pkg);
        it.ringPercent = 100;
        it.contentIntent = n.contentIntent;
        return it;
    }

    /** Returns the download percent (0 to 99), INDETERMINATE, or NOT_DOWNLOADING when it is idle or done. */
    private static int progress(Notification n) {
        if (n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) return INDETERMINATE;
        int max = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0);
        if (max <= 0) return NOT_DOWNLOADING;
        int cur = n.extras.getInt(Notification.EXTRA_PROGRESS, 0);
        if (cur >= max) return NOT_DOWNLOADING;
        return (int) Math.max(0, Math.min(99, (long) cur * 100 / max));
    }

    private static boolean isDownloadChannel(Notification n) {
        String channel = n.getChannelId();
        return channel != null && channel.toLowerCase(Locale.ROOT).contains("download");
    }
}
