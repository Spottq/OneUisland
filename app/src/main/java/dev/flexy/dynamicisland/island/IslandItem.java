package dev.flexy.dynamicisland.island;

import android.app.PendingIntent;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class IslandItem {

    public enum Kind {
        CALL(100),
        NAVIGATION(90),
        RECORDING(80),
        TIMER(70),
        LIVE(60),
        MEDIA(50),
        CHARGING(40);

        public final int priority;

        Kind(int priority) {
            this.priority = priority;
        }
    }

    public enum Trailing {
        NONE,
        TEXT,
        CHRONOMETER,
        PROGRESS_RING,
        WAVEFORM,
        CLAUDE
    }

    public static final class Shortcut {
        public final CharSequence title;
        public final android.graphics.Bitmap art;
        public final Runnable run;

        public Shortcut(CharSequence title, android.graphics.Bitmap art, Runnable run) {
            this.title = title;
            this.art = art;
            this.run = run;
        }
    }

    public static final class Action {
        public final CharSequence title;
        public final Drawable icon;
        public final PendingIntent intent;
        public final Runnable runnable;
        public final boolean destructive;
        public int glyph;
        public boolean active;

        public Action(CharSequence title, Drawable icon, PendingIntent intent, Runnable runnable, boolean destructive) {
            this.title = title;
            this.icon = icon;
            this.intent = intent;
            this.runnable = runnable;
            this.destructive = destructive;
        }
    }

    public final String key;
    public Kind kind = Kind.LIVE;
    public String packageName;
    public int userId;
    public long postTime;

    public Drawable icon;
    public boolean iconIsImage;
    public int accent = 0xFFFFFFFF;
    public Trailing trailing = Trailing.NONE;
    public CharSequence trailingText;
    public long chronoBase;
    public boolean chronoCountDown;
    public boolean chronoMillis;
    public long pausedMs = -1;
    public float progress = -1f;
    public boolean progressStyle;
    public boolean pulsingDot;
    public Drawable leftImage;
    public Drawable rightImage;
    public int pillColor;

    public CharSequence appName;
    public CharSequence title;
    public CharSequence subtitle;
    public CharSequence detail;
    public java.util.List<Metric> metrics;
    public int criticalMetric = -1;

    public static final class Metric {
        public static final int TIME = 1, DATE = 2, TIME_OF_DAY = 3, INT = 4, FLOAT = 5, TEXT = 6;
        public CharSequence label;
        public int type;
        public CharSequence fixed;
        public long zeroTime;
        public long pausedMs = -1;
        public boolean countDown;
        public int format;
        public int semantic;

        public boolean running() {
            return type == TIME && pausedMs < 0;
        }
    }
    public Drawable cardImage;
    public Drawable cardIcon;
    public android.graphics.Bitmap art;
    public boolean cardImageIsImage;
    public PendingIntent contentIntent;
    public final List<Action> actions = new ArrayList<>();

    public boolean claude;
    public String claudeState;
    public String[] claudeStages = new String[0];
    public String claudeQuestion;
    public int claudeTasks;
    public int assistantStyle;
    public Drawable[] claudeIcons = new Drawable[0];
    public Drawable claudeTaskIcon;
    public Drawable claudeSpark;

    public boolean isMedia;
    public boolean playing;
    public long mediaPosition = -1;
    public long mediaDuration = -1;
    public float mediaSpeed = 1f;
    public long mediaPositionUpdateTime;
    public Runnable mediaPrev;
    public Runnable mediaPlayPause;
    public Runnable mediaNext;
    public Runnable mediaOpen;
    public final List<Action> mediaCustom = new ArrayList<>();
    public final List<Shortcut> mediaShortcuts = new ArrayList<>();
    public Drawable appIcon;
    public android.widget.RemoteViews remoteView;
    public Drawable appGlyph;
    public java.util.function.LongConsumer mediaSeek;

    public boolean alert;
    public boolean iconColor;
    public int iconTint;
    public int trailingColor;
    public CharSequence overline;
    public int overlineColor;
    public Runnable iconTap;
    public int toggle = -1;
    public boolean iconRight;
    public Runnable trailingTap;
    public boolean noExpand;
    public boolean blank;
    public int ringPercent = -1;

    public boolean torch;
    public boolean recorder;
    public boolean recorderSaved;
    public boolean torchOn;
    public int torchLevel = 1;
    public int torchMax = 1;
    public Runnable torchToggle;
    public java.util.function.IntConsumer torchSet;

    public long expiresAtUptime;
    public long contentVersion;

    private static final java.util.concurrent.atomic.AtomicLong SEQ = new java.util.concurrent.atomic.AtomicLong();
    public final long seq = SEQ.incrementAndGet();

    public IslandItem(String key) {
        this.key = key;
    }

    public boolean hasTeams() {
        return leftImage != null && rightImage != null;
    }

    public int priority() {
        return kind.priority;
    }

    public long currentMediaPosition(long nowElapsed) {
        if (mediaPosition < 0) return -1;
        if (!playing) return mediaPosition;
        long pos = mediaPosition + (long) ((nowElapsed - mediaPositionUpdateTime) * mediaSpeed);
        if (mediaDuration > 0) pos = Math.min(pos, mediaDuration);
        return Math.max(0, pos);
    }

    public boolean sameCompactLook(IslandItem o) {
        return o != null
                && Objects.equals(key, o.key)
                && trailing == o.trailing
                && (accent == o.accent || (isMedia && o.isMedia))
                && iconIsImage == o.iconIsImage
                && pulsingDot == o.pulsingDot
                && pillColor == o.pillColor
                && torch == o.torch
                && Objects.equals(claudeState, o.claudeState)
                && recorder == o.recorder
                && recorderSaved == o.recorderSaved
                && trailingColor == o.trailingColor
                && hasTeams() == o.hasTeams();
    }

    @Override
    public String toString() {
        return "IslandItem{" + key + " " + kind + " " + packageName + "}";
    }
}
