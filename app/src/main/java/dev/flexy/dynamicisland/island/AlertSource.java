package dev.flexy.dynamicisland.island;

import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.service.notification.StatusBarNotification;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.flexy.dynamicisland.xposed.XLog;

final class AlertSource {
    static final long SHOW_MS = 3000;
    private static final long BATTERY_SHOW_MS = 5000;
    private static final int PODS_LOW = 10;
    static final String LIBREPODS = "me.kavishdevar.librepods";
    private static final int LIBREPODS_ID = 2;
    private static final int DND_COLOR = 0xFF1B6CF7;
    private static final int OFF_GREY = 0xFFAEAEB2;
    private static final int OFF_TEXT = 0xFFD1D1D6;
    private static final long MESSAGE_SHOW_MS = 4500;
    private static final long DOWNLOAD_SHOW_MS = 3500;

    private final Context ctx;
    private final OaSource oa;
    private final Runnable onChanged;
    private final MessageSource messages;
    private final DownloadSource downloads;
    private IslandItem current;
    private long until;

    private Boolean dndOn;
    private Pods pods;

    AlertSource(Context ctx, OaSource oa, Runnable onChanged) {
        this.ctx = ctx;
        this.oa = oa;
        this.onChanged = onChanged;
        this.messages = new MessageSource(ctx);
        this.downloads = new DownloadSource(ctx, onChanged);
    }

    void start() {
        startBluetooth();
        startBattery();
        dndOn = dndOn();
        ringerMode = ringerMode();
        try {
            IntentFilter f = new IntentFilter("android.media.INTERNAL_RINGER_MODE_CHANGED_ACTION");
            f.addAction(android.media.AudioManager.RINGER_MODE_CHANGED_ACTION);
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    int mode = ringerMode();
                    if (mode < 0 || mode == ringerMode) return;
                    ringerMode = mode;
                    if (IslandSettings.alertRinger()) show(ringerItem(mode));
                }
            }, f, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            XLog.e("ringer receiver failed", t);
        }
        try {
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    boolean on = dndOn();
                    if (dndOn != null && dndOn == on) return;
                    dndOn = on;
                    if (IslandSettings.alertDnd()) show(dndItem(on));
                }
            }, new IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED), Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            XLog.e("DND receiver failed", t);
        }
    }

    IslandItem current(long now, boolean expanded) {
        if (current == null) return null;
        if (expanded && now >= until - 500) until = now + 500;
        if (now >= until) {
            current = null;
            return null;
        }
        return current;
    }

    long until() {
        return until;
    }

    void onUnlocked() {
        Pods p = shownPods();
        if (p != null && IslandSettings.alertPods() && IslandSettings.podsEveryUnlock()) show(podsItem(p));
    }

    private void show(IslandItem item) {
        show(item, SHOW_MS);
    }

    private void show(IslandItem item, long ms) {
        if (item == null) return;
        current = item;
        until = SystemClock.uptimeMillis() + ms;
        // Only the key goes to the log: alert titles can hold private message text.
        XLog.i("alert " + item.key);
        onChanged.run();
    }


    private boolean dndOn() {
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        int f = nm == null ? NotificationManager.INTERRUPTION_FILTER_ALL : nm.getCurrentInterruptionFilter();
        return f != NotificationManager.INTERRUPTION_FILTER_ALL && f != NotificationManager.INTERRUPTION_FILTER_UNKNOWN;
    }

    private void toggleDnd() {
        try {
            NotificationManager nm = ctx.getSystemService(NotificationManager.class);
            nm.setInterruptionFilter(dndOn() ? NotificationManager.INTERRUPTION_FILTER_ALL
                    : NotificationManager.INTERRUPTION_FILTER_PRIORITY);
        } catch (Throwable t) {
            XLog.e("DND toggle failed", t);
            Intent off = new Intent("com.android.systemui.action.dnd_off").setPackage(ctx.getPackageName());
            off.putExtra("uid", ctx.getApplicationInfo().uid);
            ctx.sendBroadcast(off);
        }
    }

    private IslandItem dndItem(boolean on) {
        IslandItem it = new IslandItem("alert:dnd");
        it.alert = true;
        it.kind = IslandItem.Kind.LIVE;
        it.packageName = ctx.getPackageName();
        it.icon = systemUiDrawable("stat_notify_dormant_mode");
        it.iconColor = true;
        it.accent = on ? Colors.readableOnBlack(DND_COLOR) : OFF_GREY;
        it.title = systemUiString("sec_noti_dnd_text", "Do not disturb");
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = on ? systemUiString("switch_bar_on", "On") : systemUiString("switch_bar_off", "Off");
        it.trailingColor = on ? it.accent : OFF_TEXT;
        it.iconTap = this::toggleDnd;
        it.toggle = on ? 1 : 0;
        it.postTime = Long.MAX_VALUE;
        return it;
    }


    private static final int[] LOW_LEVELS = {20, 10};
    private static final int LOW_RED = 0xFFFF453A;
    private static final int POWER_YELLOW = 0xFFFFD60A;
    private int batteryLevel = -1;

    private void startBattery() {
        try {
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    int level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                    int scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100);
                    boolean plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0;
                    if (level < 0 || scale <= 0) return;
                    onBatteryLevel(Math.round(level * 100f / scale), plugged);
                }
            }, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            XLog.e("battery receiver failed", t);
        }
        try {
            ctx.getContentResolver().registerContentObserver(
                    android.provider.Settings.Global.getUriFor("low_power"), false,
                    new android.database.ContentObserver(btMain) {
                        @Override public void onChange(boolean selfChange) {
                            refreshBatteryAlert();
                        }
                    });
        } catch (Throwable t) {
            XLog.e("power saving observer failed", t);
        }
    }

    private void refreshBatteryAlert() {
        if (current == null || !"alert:battery".equals(current.key) || batteryLevel < 0) return;
        current = batteryItem(batteryLevel);
        onChanged.run();
    }

    private void onBatteryLevel(int level, boolean plugged) {
        int before = batteryLevel;
        batteryLevel = level;
        if (before < 0 || plugged || !IslandSettings.alertBattery()) return;
        for (int threshold : LOW_LEVELS) {
            if (before > threshold && level <= threshold) {
                show(batteryItem(level), BATTERY_SHOW_MS);
                return;
            }
        }
    }

    private boolean powerSaving() {
        int mode = android.provider.Settings.Global.getInt(ctx.getContentResolver(), "low_power", -1);
        if (mode >= 0) return mode != 0;
        android.os.PowerManager pm = ctx.getSystemService(android.os.PowerManager.class);
        return pm != null && pm.isPowerSaveMode();
    }

    private boolean powerSavingBusy;

    private void togglePowerSaving() {
        if (powerSavingBusy) return;
        powerSavingBusy = true;
        boolean enabled = !powerSaving();
        new Thread(() -> {
            try {
                int user = (Integer) android.app.ActivityManager.class.getMethod("getCurrentUser").invoke(null);
                android.net.Uri uri = android.net.Uri.parse("content://" + user + "@com.samsung.android.sm.dcapi");
                String method = enabled ? "from_powerui_turn_on_psm" : "from_routine_turn_off_psm";
                android.os.Bundle result = ctx.getContentResolver().call(uri, method, null, null);
                if (result == null || !result.getBoolean("result", false)) {
                    throw new IllegalStateException("Device Care power saving: " + result);
                }
                XLog.i("power saving " + enabled + " (Device Care)");
            } catch (Throwable t) {
                XLog.e("power saving failed", t);
            } finally {
                btMain.post(() -> {
                    powerSavingBusy = false;
                    if (current != null && "alert:battery".equals(current.key)) {
                        until = SystemClock.uptimeMillis() + BATTERY_SHOW_MS;
                    }
                    refreshBatteryAlert();
                });
            }
        }, "IslandPowerSaving").start();
    }

    IslandItem batteryItem(int level) {
        boolean saving = powerSaving();
        int batteryColor = saving ? POWER_YELLOW : LOW_RED;
        IslandItem it = new IslandItem("alert:battery");
        it.alert = true;
        it.kind = IslandItem.Kind.LIVE;
        it.packageName = ctx.getPackageName();
        Drawable d = statusBarBattery(level, batteryColor);
        if (d != null) {
            it.icon = new Fit(d, 1f);
            Drawable big = statusBarBattery(level, batteryColor);
            it.cardImage = big == null ? null : new Fit(big, 0.72f);
            it.iconIsImage = true;
            it.cardImageIsImage = true;
        }
        it.iconColor = true;
        it.iconRight = true;
        it.accent = batteryColor;
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = level + "%";
        it.trailingColor = LOW_RED;
        String title = systemUiString("battery_low_sec_title", "Battery power %d%%");
        try {
            it.title = String.format(java.util.Locale.getDefault(), title, level);
        } catch (Throwable t) {
            it.title = level + "%";
        }
        if (!saving) it.subtitle = systemUiString("battery_low_turn_on_power_saving", "Turn on Power saving");
        it.trailingTap = this::togglePowerSaving;
        it.postTime = Long.MAX_VALUE;
        return it;
    }

    private Drawable statusBarBattery(int level, int color) {
        try {
            ClassLoader cl = ctx.getClassLoader();
            Class<?> drawableCls = cl.loadClass("com.android.systemui.battery.SamsungBatteryMeterDrawable");
            Class<?> stateCls = cl.loadClass("com.android.systemui.battery.SamsungBatteryState");
            Drawable d = (Drawable) drawableCls.getConstructor(Context.class).newInstance(ctx);
            setField(drawableCls, d, "notShowBatteryBackgroundSetting", false);
            setField(drawableCls, d, "flagDrawIcon", true);
            try {
                java.lang.reflect.Field colors = drawableCls.getDeclaredField("colors");
                colors.setAccessible(true);
                colors.set(d, new int[] {100, color});
            } catch (Throwable ignored) {
            }
            Object state = stateCls.getConstructor().newInstance();
            java.lang.reflect.Field f = stateCls.getDeclaredField("level");
            f.setAccessible(true);
            f.setInt(state, level);
            drawableCls.getMethod("onBatteryLevelChanged", stateCls).invoke(d, state);
            for (String name : new String[] {"batteryLevelColor"}) {
                try {
                    java.lang.reflect.Field c = drawableCls.getDeclaredField(name);
                    c.setAccessible(true);
                    c.setInt(d, color);
                } catch (Throwable ignored) {
                }
            }
            for (String name : new String[] {"batteryLevelColorLight", "batteryLevelColorDark"}) {
                try {
                    java.lang.reflect.Field c = drawableCls.getDeclaredField(name);
                    c.setAccessible(true);
                    c.setInt(d, 0x00000000);
                } catch (Throwable ignored) {
                }
            }
            return d;
        } catch (Throwable t) {
            XLog.e("status bar battery icon unavailable", t);
            return null;
        }
    }

    static final class Fit extends Drawable {
        private final Drawable inner;
        private final float size;
        Fit(Drawable inner, float size) {
            this.inner = inner;
            this.size = size;
        }

        @Override
        public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            int iw = Math.max(1, inner.getIntrinsicWidth()), ih = Math.max(1, inner.getIntrinsicHeight());
            float s = size * Math.min(b.width() / (float) iw, b.height() / (float) ih);
            int save = c.save();
            c.translate(b.exactCenterX() - iw * s / 2f, b.exactCenterY() - ih * s / 2f);
            c.scale(s, s);
            inner.setBounds(0, 0, iw, ih);
            inner.draw(c);
            c.restoreToCount(save);
        }

        @Override
        public void setAlpha(int alpha) {
            inner.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(android.graphics.ColorFilter cf) {
            inner.setColorFilter(cf);
        }

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }
    }

    private static void setField(Class<?> cls, Object o, String name, boolean v) {
        try {
            java.lang.reflect.Field f = cls.getDeclaredField(name);
            f.setAccessible(true);
            f.setBoolean(o, v);
        } catch (Throwable ignored) {
        }
    }


    private int ringerMode;
    private static final int MUTE_RED = 0xFFFF453A;

    private int ringerMode() {
        android.media.AudioManager am = ctx.getSystemService(android.media.AudioManager.class);
        if (am == null) return -1;
        try {
            return (Integer) android.media.AudioManager.class.getMethod("getRingerModeInternal").invoke(am);
        } catch (Throwable t) {
            return am.getRingerMode();
        }
    }

    private IslandItem ringerItem(int mode) {
        IslandItem it = new IslandItem("alert:ringer");
        it.alert = true;
        it.noExpand = true;
        it.kind = IslandItem.Kind.LIVE;
        it.packageName = ctx.getPackageName();
        String icon, label, fallback;
        if (mode == android.media.AudioManager.RINGER_MODE_SILENT) {
            icon = "ic_ringtone_mute";
            label = "quick_settings_sound_mode_mute_label";
            fallback = "Mute";
        } else if (mode == android.media.AudioManager.RINGER_MODE_VIBRATE) {
            icon = "ic_ringtone_vibrate";
            label = "quick_settings_sound_mode_vibrate_label";
            fallback = "Vibrate";
        } else {
            icon = "ic_ringtone_sound";
            label = "quick_settings_sound_mode_sound_label";
            fallback = "Sound";
        }
        it.icon = systemUiDrawable(icon);
        if (it.icon == null) it.icon = systemUiDrawable(icon.replace("ic_ringtone", "ic_volume_ringer").replace("_sound", ""));
        it.iconColor = true;
        it.accent = mode == android.media.AudioManager.RINGER_MODE_SILENT ? MUTE_RED : 0xFFFFFFFF;
        it.title = systemUiString(label, fallback);
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = it.title;
        it.trailingColor = it.accent;
        it.postTime = Long.MAX_VALUE;
        return it;
    }


    static final class Pods {
        String name;
        int left = -1;
        int right = -1;
        int single = -1;
        Drawable picture;
        boolean bluetooth;

        boolean same(Pods o) {
            return o != null && (left >= 0) == (o.left >= 0) && (right >= 0) == (o.right >= 0) && (single >= 0) == (o.single >= 0);
        }

        int percent() {
            if (single >= 0) return single;
            if (left >= 0 && right >= 0) return Math.round((left + right) / 2f);
            return Math.max(left, right);
        }
    }

    private static final Pattern PART = Pattern.compile("(L|R|Case):\\s*⚡?\\s*(\\d{1,3})%");
    private static final Pattern SINGLE = Pattern.compile("^⚡?\\s*(\\d{1,3})%$");

    static Pods parse(CharSequence name, CharSequence text) {
        if (text == null) return null;
        String s = text.toString().trim();
        if (s.isEmpty()) return null;
        Pods p = new Pods();
        p.name = name == null ? null : name.toString();
        Matcher single = SINGLE.matcher(s);
        if (single.matches()) {
            p.single = level(Integer.parseInt(single.group(1)));
            return p.single >= 0 ? p : null;
        }
        Matcher m = PART.matcher(s);
        while (m.find()) {
            int v = level(Integer.parseInt(m.group(2)));
            if ("L".equals(m.group(1))) p.left = v;
            else if ("R".equals(m.group(1))) p.right = v;
        }
        return p.left >= 0 || p.right >= 0 ? p : null;
    }

    static int level(int v) {
        return v >= 0 && v <= 100 ? v : -1;
    }

    void onNotifications(List<StatusBarNotification> all) {
        Pods now = null;
        for (StatusBarNotification sbn : all) {
            if (!LIBREPODS.equals(sbn.getPackageName()) || sbn.getId() != LIBREPODS_ID) continue;
            android.os.Bundle e = sbn.getNotification().extras;
            now = parse(e.getCharSequence(android.app.Notification.EXTRA_TITLE), e.getCharSequence(android.app.Notification.EXTRA_TEXT));
            break;
        }
        Pods before = shownPods();
        pods = now;
        podsChanged(before);
        try {
            IslandItem msg = messages.scan(all);
            IslandItem done = downloads.scan(all);
            IslandItem alert = msg != null ? msg : done;
            if (alert != null && allowed(alert)) {
                show(alert, alert.key.startsWith("dl:") ? DOWNLOAD_SHOW_MS : MESSAGE_SHOW_MS);
            }
        } catch (Throwable t) {
            XLog.e("message and download alerts failed", t);
        }
    }

    /** Live downloads for the island, added on each refresh. */
    void collectDownloads(List<IslandItem> out) {
        downloads.collect(out, oa);
    }

    /**
     * New message and download alerts stay quiet when the screen is locked, Do Not Disturb is on,
     * a call is running, or the app that sent them is already in front.
     */
    private boolean allowed(IslandItem it) {
        boolean download = it.key.startsWith("dl:");
        if (download ? !IslandSettings.showDownloads() : !IslandSettings.showMessages()) return false;
        KeyguardManager km = ctx.getSystemService(KeyguardManager.class);
        if (km != null && km.isKeyguardLocked()) return false;
        if (dndOn() || oa.inCall()) return false;
        String foreground = oa.foregroundPackage();
        return foreground == null || !foreground.equals(it.packageName);
    }

    private Pods shownPods() {
        return pods != null ? pods : btPods;
    }

    private int podsPercent = -1;

    private void podsChanged(Pods before) {
        Pods now = shownPods();
        int was = podsPercent;
        podsPercent = now == null ? -1 : now.percent();
        if (now != null && was > PODS_LOW && podsPercent >= 0 && podsPercent <= PODS_LOW && IslandSettings.alertPods()) {
            show(podsLow(podsItem(now)));
        } else if (now != null && !now.same(before) && IslandSettings.alertPods()) show(podsItem(now));
        else if (now != null && current != null && current.key.equals("alert:pods")) {
            boolean low = current.overlineColor != 0;
            current = podsItem(now);
            if (low) podsLow(current);
            onChanged.run();
        }
    }

    private IslandItem podsLow(IslandItem it) {
        it.overline = systemUiString("battery_low_title", "Low Battery");
        it.overlineColor = 0xFFFF453A;
        return it;
    }


    private static final String[][] GALAXY_BUDS = {
            {"SM-R64", "buds4 pro", "com.samsung.accessory.budsunitemgr", "home_image_bach_white"},
            {"SM-R54", "buds4", "com.samsung.accessory.budsunitemgr", "home_image_handel_while"},
            {"SM-U6", "buds on", "com.samsung.accessory.budsunitemgr", "home_image_able_black"},
            {"SM-U7", "samsung h1", "com.samsung.accessory.budsunitemgr", "home_image_able_black"},
            {"SM-R63", "buds3 pro", "com.samsung.accessory.paranmgr", "home_image_white"},
            {"SM-R53", "buds3", "com.samsung.accessory.jellymgr", "home_image_white"},
            {"SM-R41", "buds core", "com.samsung.accessory.pianomgr", "home_image_white"},
            {"SM-R40", "buds fe", "com.samsung.accessory.pearlmgr", "home_image_white"},
            {"SM-R177", "buds2", "com.samsung.accessory.berrymgr", "home_image_white"},
            {"SM-R19", "buds pro", "com.samsung.accessory.atticmgr", "home_image_silver"},
    };

    static String[] galaxyBudsImage(String model, String name) {
        String m = model == null ? "" : model.toUpperCase(java.util.Locale.ROOT);
        String n = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        for (String[] b : GALAXY_BUDS) {
            if (!m.isEmpty() && m.startsWith(b[0])) return new String[] {b[2], b[3]};
        }
        for (String[] b : GALAXY_BUDS) {
            if (n.contains(b[1])) return new String[] {b[2], b[3]};
        }
        return null;
    }

    private Drawable galaxyBudsPicture(String model, String name) {
        String[] image = galaxyBudsImage(model, name);
        if (image == null) return null;
        try {
            Context pc = ctx.createPackageContext(image[0], 0);
            int id = pc.getResources().getIdentifier(image[1], "drawable", image[0]);
            return id == 0 ? null : pc.getDrawable(id);
        } catch (Throwable t) {
            return null;
        }
    }

    private static final int META_MODEL_NAME = 1;
    private static final int META_COMPANION_APP = 4, META_MAIN_ICON = 5, META_LEFT_ICON = 7, META_RIGHT_ICON = 8,
            META_LEFT_BATTERY = 10, META_RIGHT_BATTERY = 11, META_MAIN_BATTERY = 18;
    private static final long BT_SETTLE_MS = 1500;
    private Pods btPods;
    private String btAddress;

    private static boolean isAudio(android.bluetooth.BluetoothDevice d) {
        try {
            android.bluetooth.BluetoothClass c = d.getBluetoothClass();
            return c == null || c.getMajorDeviceClass() == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO;
        } catch (Throwable t) {
            return true;
        }
    }

    private boolean sameName(android.bluetooth.BluetoothDevice d) {
        try {
            return btPods != null && btPods.name != null && btPods.name.equals(d.getAlias());
        } catch (Throwable t) {
            return false;
        }
    }
    private android.os.Handler btWorker;
    private final android.os.Handler btMain = new android.os.Handler(android.os.Looper.getMainLooper());

    private void startBluetooth() {
        try {
            android.os.HandlerThread t = new android.os.HandlerThread("IslandHeadset");
            t.start();
            btWorker = new android.os.Handler(t.getLooper());
            IntentFilter f = new IntentFilter("android.bluetooth.a2dp.profile.action.CONNECTION_STATE_CHANGED");
            f.addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED");
            ctx.registerReceiver(new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    android.bluetooth.BluetoothDevice d = i.getParcelableExtra(android.bluetooth.BluetoothDevice.EXTRA_DEVICE,
                            android.bluetooth.BluetoothDevice.class);
                    if (d == null || i.getAction() == null || !isAudio(d)) return;
                    if (i.getAction().endsWith("CONNECTION_STATE_CHANGED")) {
                        int state = i.getIntExtra(android.bluetooth.BluetoothProfile.EXTRA_STATE, -1);
                        if (state == android.bluetooth.BluetoothProfile.STATE_DISCONNECTED) {
                            if (!d.getAddress().equals(btAddress) && !sameName(d)) return;
                            btPods = null;
                            btAddress = null;
                            onChanged.run();
                            return;
                        }
                        if (state == android.bluetooth.BluetoothProfile.STATE_CONNECTED) {
                            btWorker.postDelayed(() -> readHeadset(d), BT_SETTLE_MS);
                        }
                    } else if (btAddress == null || d.getAddress().equals(btAddress) || sameName(d)) {
                        btWorker.post(() -> readHeadset(d));
                    }
                }
            }, f, Context.RECEIVER_EXPORTED);
        } catch (Throwable t) {
            XLog.e("headset receiver failed", t);
        }
    }

    private void readHeadset(android.bluetooth.BluetoothDevice d) {
        try {
            if (LIBREPODS.equals(metaString(d, META_COMPANION_APP))) return;
            Pods p = new Pods();
            p.bluetooth = true;
            p.left = level(metaInt(d, META_LEFT_BATTERY));
            p.right = level(metaInt(d, META_RIGHT_BATTERY));
            if (p.left < 0 && p.right < 0) {
                p.single = level(metaInt(d, META_MAIN_BATTERY));
                if (p.single < 0) p.single = level(batteryLevel(d));
            }
            if (p.left < 0 && p.right < 0 && p.single < 0) return;
            try {
                p.name = d.getAlias();
            } catch (Throwable ignored) {
            }
            boolean oneBud = (p.left >= 0) != (p.right >= 0);
            int iconKey = !oneBud ? META_MAIN_ICON : p.left >= 0 ? META_LEFT_ICON : META_RIGHT_ICON;
            p.picture = loadUri(metaString(d, iconKey));
            if (p.picture == null && iconKey != META_MAIN_ICON) p.picture = loadUri(metaString(d, META_MAIN_ICON));
            if (p.picture == null) p.picture = galaxyBudsPicture(metaString(d, META_MODEL_NAME), p.name);
            String address = d.getAddress();
            XLog.i("headset " + p.name + ": L" + p.left + " R" + p.right + " " + p.single + "% picture=" + (p.picture != null));
            btMain.post(() -> {
                Pods before = shownPods();
                btPods = p;
                btAddress = address;
                podsChanged(before);
            });
        } catch (Throwable t) {
            XLog.e("headset read failed", t);
        }
    }

    private static String metaString(android.bluetooth.BluetoothDevice d, int key) {
        try {
            Object v = android.bluetooth.BluetoothDevice.class.getMethod("getMetadata", int.class).invoke(d, key);
            byte[] b = v instanceof byte[] ? (byte[]) v : null;
            return b == null || b.length == 0 ? null : new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            return null;
        }
    }

    private static int metaInt(android.bluetooth.BluetoothDevice d, int key) {
        String s = metaString(d, key);
        try {
            return s == null ? -1 : Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static int batteryLevel(android.bluetooth.BluetoothDevice d) {
        try {
            return (Integer) android.bluetooth.BluetoothDevice.class.getMethod("getBatteryLevel").invoke(d);
        } catch (Throwable t) {
            return -1;
        }
    }

    private Drawable loadUri(String uri) {
        if (uri == null || uri.isEmpty()) return null;
        try (java.io.InputStream in = ctx.getContentResolver().openInputStream(android.net.Uri.parse(uri))) {
            android.graphics.Bitmap bmp = in == null ? null : android.graphics.BitmapFactory.decodeStream(in);
            return bmp == null ? null : new android.graphics.drawable.BitmapDrawable(ctx.getResources(), bmp);
        } catch (Throwable t) {
            XLog.i("headset picture unavailable: " + uri);
            return null;
        }
    }

    private IslandItem podsItem(Pods p) {
        IslandItem it = new IslandItem("alert:pods");
        it.alert = true;
        it.kind = IslandItem.Kind.LIVE;
        it.packageName = p.bluetooth ? null : LIBREPODS;
        boolean oneBud = p.single < 0 && !(p.left >= 0 && p.right >= 0);
        Drawable d;
        if (p.bluetooth) {
            Drawable.ConstantState cs = p.picture == null ? null : p.picture.getConstantState();
            d = cs != null ? cs.newDrawable() : p.picture;
        } else {
            String image = p.single >= 0 ? "airpods_max_buds"
                    : !oneBud ? "airpods_pro_2_buds"
                    : p.left >= 0 ? "airpods_pro_2_left" : "airpods_pro_2_right";
            d = librePodsDrawable(image);
        }
        if (d != null && (oneBud || p.bluetooth)) d = fitted(d, oneBud ? 0.78f : 0.92f);
        if (d != null) {
            it.icon = d;
            it.cardImage = d;
            it.iconIsImage = true;
            it.cardImageIsImage = true;
        } else {
            it.icon = moduleDrawable(dev.flexy.dynamicisland.R.drawable.ic_island_headphones);
            it.iconTint = 0xFFFFFFFF;
        }
        it.iconColor = true;
        int pct = p.percent();
        it.accent = pct <= 10 ? 0xFFFF453A : pct <= 20 ? 0xFFFF9F0A : 0xFF30D158;
        it.overline = systemUiString("quick_settings_bluetooth_device_connected", "Connected");
        it.title = p.name != null ? p.name : p.bluetooth ? "Headphones" : "AirPods";
        it.trailing = IslandItem.Trailing.TEXT;
        it.trailingText = pct + "%";
        it.ringPercent = pct;
        it.postTime = Long.MAX_VALUE;
        return it;
    }


    private final java.util.Map<String, Drawable.ConstantState> drawables = new java.util.HashMap<>();

    private Drawable librePodsDrawable(String name) {
        Drawable.ConstantState cs = drawables.get(name);
        if (cs != null) return cs.newDrawable();
        try {
            Context pc = ctx.createPackageContext(LIBREPODS, 0);
            int id = pc.getResources().getIdentifier(name, "drawable", LIBREPODS);
            if (id == 0) return null;
            Drawable d = pc.getDrawable(id);
            if (d != null && d.getConstantState() != null) drawables.put(name, d.getConstantState());
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Drawable fitted(Drawable d, float size) {
        int w = d.getIntrinsicWidth(), h = d.getIntrinsicHeight();
        if (w <= 0 || h <= 0) return d;
        float fw = size * Math.min(1f, w / (float) h);
        float fh = size * Math.min(1f, h / (float) w);
        return new android.graphics.drawable.InsetDrawable(d, (1f - fw) / 2f, (1f - fh) / 2f, (1f - fw) / 2f, (1f - fh) / 2f);
    }

    private Drawable systemUiDrawable(String name) {
        int id = ctx.getResources().getIdentifier(name, "drawable", ctx.getPackageName());
        try {
            return id == 0 ? null : ctx.getDrawable(id).mutate();
        } catch (Throwable t) {
            return null;
        }
    }

    private Drawable moduleDrawable(int id) {
        try {
            return ctx.createPackageContext("dev.flexy.dynamicisland", Context.CONTEXT_IGNORE_SECURITY).getDrawable(id).mutate();
        } catch (Throwable t) {
            return frameworkDrawable("ic_bt_headphones_a2dp");
        }
    }

    private Drawable frameworkDrawable(String name) {
        int id = ctx.getResources().getIdentifier(name, "drawable", "android");
        try {
            return id == 0 ? null : ctx.getDrawable(id).mutate();
        } catch (Throwable t) {
            return null;
        }
    }

    private String systemUiString(String name, String fallback) {
        int id = ctx.getResources().getIdentifier(name, "string", ctx.getPackageName());
        try {
            return id == 0 ? fallback : ctx.getString(id);
        } catch (Throwable t) {
            return fallback;
        }
    }
}
