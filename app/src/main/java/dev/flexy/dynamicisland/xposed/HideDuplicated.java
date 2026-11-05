package dev.flexy.dynamicisland.xposed;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.List;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class HideDuplicated {
    private static final String LIVEBRIDGE_PKG = "com.locnall.KimGiSa";
    private static final String LIVEBRIDGE_CHANNEL = "livebridge_";
    private static final String BUILDER = "com.android.systemui.statusbar.notification.collection.ShadeListBuilder";
    private static final String ENTRY = "com.android.systemui.statusbar.notification.collection.NotificationEntry";

    private static final Set<String> COPIES = new HashSet<>();
    private static final Map<String, Notification> ORIGINALS = new HashMap<>();
    private static final Map<String, Long> HIDDEN = new HashMap<>();
    private static final long GRACE_MS = 2500;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static WeakReference<Object> filter;
    private static final Map<String, String> LABELS = new HashMap<>();
    private static Class<?> entryClass;
    private static String lastSummary;

    private HideDuplicated() {
    }

    static void install(ClassLoader cl) {
        entryClass = Refl.findClass(ENTRY, cl);
        if (entryClass == null) return;
        Refl.hookAll(cl, BUILDER, "filterNotifs", SafeHook.before("duplicate copies", p -> {
            if (p.args.length > 2 && p.args[2] == Refl.get(p.thisObject, "mNotifPreGroupFilters")
                    && p.args[0] instanceof Collection) collectCopies((Collection<?>) p.args[0]);
        }));
        Refl.hookAll(cl, BUILDER, "applyFilters", SafeHook.after("duplicate originals", p -> {
            if (p.hasThrowable() || Boolean.TRUE.equals(p.getResult())) return;
            if (filter == null && p.args[2] instanceof List && !((List<?>) p.args[2]).isEmpty()) {
                filter = new WeakReference<>(((List<?>) p.args[2]).get(0));
            }
            if (hide(p.args[0])) p.setResult(true);
        }));
    }

    private static void collectCopies(Collection<?> entries) {
        List<StatusBarNotification> snapshot = new java.util.ArrayList<>();
        for (Object e : entries) {
            if (!entryClass.isInstance(e)) continue;
            StatusBarNotification sbn = sbn(e);
            if (sbn != null) snapshot.add(sbn);
        }
        refresh(snapshot);
    }

    static void refresh(List<StatusBarNotification> snapshot) {
        List<StatusBarNotification> copies = new java.util.ArrayList<>();
        Set<String> active = new HashSet<>();
        for (StatusBarNotification sbn : snapshot) {
            active.add(sbn.getKey());
            if (isLiveCopy(sbn)) copies.add(sbn);
        }
        Map<StatusBarNotification, Set<String>> represented = new HashMap<>();
        for (StatusBarNotification copy : copies) represented.put(copy, representedKeys(copy.getNotification()));
        Map<StatusBarNotification, String> directSources = new HashMap<>();
        for (StatusBarNotification copy : copies) {
            for (StatusBarNotification source : snapshot) {
                if (isCopy(source) || isSummary(source.getNotification()) || source.getUserId() != copy.getUserId()) continue;
                if (copy.getId() == mirrorId(source.getKey())) {
                    directSources.put(copy, source.getKey());
                    break;
                }
            }
        }
        COPIES.clear();
        ORIGINALS.clear();
        HIDDEN.keySet().retainAll(active);
        for (StatusBarNotification source : snapshot) {
            if (isCopy(source) || isSummary(source.getNotification())) continue;
            for (StatusBarNotification copy : copies) {
                boolean explicit = copy.getUserId() == source.getUserId() && represented.get(copy).contains(source.getKey());
                String direct = directSources.get(copy);
                if (!explicit && !(direct != null ? direct.equals(source.getKey()) : matches(source, copy))) continue;
                COPIES.add(source.getKey());
                Notification n = copy.getNotification();
                ORIGINALS.put(n.extras.getCharSequence(Notification.EXTRA_TITLE) + "\n"
                        + n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT), source.getNotification());
                break;
            }
        }
        for (StatusBarNotification summary : snapshot) {
            if (isCopy(summary) || !isSummary(summary.getNotification())) continue;
            boolean any = false, all = true;
            for (StatusBarNotification child : snapshot) {
                if (isSummary(child.getNotification()) || isCopy(child)
                        || child.getUserId() != summary.getUserId()
                        || !child.getPackageName().equals(summary.getPackageName())
                        || !child.getGroupKey().equals(summary.getGroupKey())) continue;
                any = true;
                all &= COPIES.contains(child.getKey());
            }
            if (any && all) COPIES.add(summary.getKey());
        }
        dev.flexy.dynamicisland.island.IslandController island = dev.flexy.dynamicisland.island.IslandController.get();
        if (island != null) island.onNotifications(snapshot);
        String summary = copies.size() + " mirrors, " + COPIES.size() + " represented source rows";
        if (!summary.equals(lastSummary)) {
            lastSummary = summary;
            XLog.i("duplicate matching: " + summary);
        }
    }

    private static boolean isCopy(StatusBarNotification sbn) {
        String channel = sbn.getNotification().getChannelId();
        return LIVEBRIDGE_PKG.equals(sbn.getPackageName()) && channel != null
                && channel.startsWith(LIVEBRIDGE_CHANNEL);
    }

    private static boolean isLiveCopy(StatusBarNotification sbn) {
        if (!isCopy(sbn)) return false;
        Notification n = sbn.getNotification();
        return n.extras.getInt("android.ongoingActivityNoti.style", 0) != 2
                && !n.getChannelId().startsWith("livebridge_notification_capsule");
    }

    private static Set<String> representedKeys(Notification n) {
        Set<String> keys = new HashSet<>();
        collectKeys(n.deleteIntent, keys);
        Object delete = n.extras.get("com.nowbar.ongoing.DELETE_INTENT");
        if (delete instanceof android.app.PendingIntent) collectKeys((android.app.PendingIntent) delete, keys);
        if (n.actions != null) for (Notification.Action action : n.actions) collectKeys(action.actionIntent, keys);
        return keys;
    }

    private static void collectKeys(android.app.PendingIntent pending, Set<String> keys) {
        if (pending == null || !LIVEBRIDGE_PKG.equals(pending.getCreatorPackage())) return;
        Object value = Refl.call(pending, "getIntent");
        if (value instanceof android.content.Intent) collectKeys((android.content.Intent) value, keys);
    }

    static void collectKeys(android.content.Intent intent, Set<String> keys) {
        if (!"com.kakao.taxi.action.CLEAR_NOTIFICATION_CAPSULE_KEYS".equals(intent.getAction())) return;
        java.util.ArrayList<String> sourceKeys = intent.getStringArrayListExtra("notification_keys");
        if (sourceKeys != null) for (String key : sourceKeys)
            if (key != null && !key.trim().isEmpty()) keys.add(key.trim());
    }

    static boolean matches(StatusBarNotification source, StatusBarNotification copy) {
        if (!isLiveCopy(copy) || LIVEBRIDGE_PKG.equals(source.getPackageName())
                || source.getUserId() != copy.getUserId()) return false;
        Notification original = source.getNotification(), mirror = copy.getNotification();
        if (isSummary(original)) return false;
        if (copy.getId() == mirrorId(source.getKey())) return true;
        String title = normalized(original.extras.getCharSequence(Notification.EXTRA_TITLE));
        String copiedTitle = normalized(mirror.extras.getCharSequence(Notification.EXTRA_TITLE));
        boolean sameTitle = !title.isEmpty() && title.equals(copiedTitle);
        boolean sameIntent = original.contentIntent != null && original.contentIntent.equals(mirror.contentIntent);
        if (sameTitle && sameIntent) return true;
        boolean namedSource = normalized(mirror.extras.getCharSequence(Notification.EXTRA_SUB_TEXT))
                .equals(normalized(label(source.getPackageName())));
        if (sameTitle && namedSource) return true;
        if (sameIntent && original.actions != null && mirror.actions != null) {
            for (Notification.Action a : original.actions) {
                if (a.actionIntent == null) continue;
                for (Notification.Action b : mirror.actions)
                    if (a.actionIntent.equals(b.actionIntent)) return true;
            }
        }
        return false;
    }

    private static String normalized(CharSequence text) {
        return text == null ? "" : text.toString().replace('\u00a0', ' ').trim().replaceAll("\\s+", " ");
    }

    private static int mirrorId(String key) {
        int hash = key.hashCode();
        return hash == Integer.MIN_VALUE ? 0 : Math.abs(hash);
    }

    private static boolean isSummary(Notification n) {
        return (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0;
    }

    private static boolean hide(Object entry) {
        StatusBarNotification sbn = sbn(entry);
        if (sbn == null || LIVEBRIDGE_PKG.equals(sbn.getPackageName())) return false;
        if (dev.flexy.dynamicisland.island.MessageSource.isShownInIsland(sbn.getKey())) {
            HIDDEN.put(sbn.getKey(), SystemClock.uptimeMillis());
            return true;
        }
        if (isSummary(sbn.getNotification())
                && sbn.getNotification().extras.containsKey("android.ongoingActivityNoti.style")) return true;
        boolean hide = COPIES.contains(sbn.getKey());
        String key = sbn.getKey();
        long now = SystemClock.uptimeMillis();
        if (hide) {
            if (HIDDEN.put(key, now) == null) XLog.i("duplicated original hidden: " + key);
            return true;
        }
        Long last = HIDDEN.get(key);
        if (last == null) return false;
        if (now - last < GRACE_MS) {
            MAIN.postDelayed(HideDuplicated::rebuild, GRACE_MS - (now - last) + 50);
            return true;
        }
        HIDDEN.remove(key);
        XLog.i("duplicated original shown: " + key);
        return false;
    }

    public static CharSequence originalSubText(CharSequence title, CharSequence source) {
        if (title == null || source == null) return null;
        Notification n = ORIGINALS.get(title + "\n" + source);
        CharSequence sub = n == null ? null : n.extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        if (sub == null || sub.length() == 0 || sub.toString().equals(source.toString())) return null;
        return sub;
    }

    private static void rebuild() {
        Object f = filter == null ? null : filter.get();
        if (f != null) Refl.call(f, "invalidateList", "duplicate copy gone");
    }

    private static String label(String pkg) {
        String label = LABELS.get(pkg);
        if (label != null) return label;
        try {
            Context ctx = (Context) Class.forName("android.app.ActivityThread")
                    .getMethod("currentApplication").invoke(null);
            PackageManager pm = Objects.requireNonNull(ctx).getPackageManager();
            label = String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) {
            label = pkg;
        }
        LABELS.put(pkg, label);
        return label;
    }

    private static StatusBarNotification sbn(Object entry) {
        Object sbn = Refl.get(entry, "mSbn");
        return sbn instanceof StatusBarNotification ? (StatusBarNotification) sbn : null;
    }
}
