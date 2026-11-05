package dev.flexy.dynamicisland.xposed;

import android.app.Notification;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Looper;
import android.service.notification.StatusBarNotification;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ShadeLiveCardsProbe {
    private static int checks;

    public static void main(String[] args) {
        try {
            runChecks();
        } catch (Throwable failure) {
            failure.printStackTrace(System.out);
            System.exit(1);
        }
    }

    private static void runChecks() throws Exception {
        System.out.println("Starting live shade card checks");
        Looper.prepareMainLooper();
        Class<?> memory = Class.forName("com.android.internal.os.ApplicationSharedMemory");
        memory.getMethod("setInstance", memory).invoke(null, memory.getMethod("create").invoke(null));
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        java.lang.reflect.Constructor<?> constructor = activityThread.getDeclaredConstructor();
        constructor.setAccessible(true);
        Object thread = constructor.newInstance();
        Context system = (Context) activityThread.getMethod("getSystemContext").invoke(thread);
        Context context = new ContextThemeWrapper(system, android.R.style.Theme_Material);
        Field rowClass = ShadeLiveCards.class.getDeclaredField("rowClass");
        rowClass.setAccessible(true);
        rowClass.set(null, TestRow.class);
        TestRow row = new TestRow(context);

        check(!live(row), "regular notification excluded");
        row.mEntryAdapter.notification.flags = Notification.FLAG_ONGOING_EVENT;
        check(!live(row), "ordinary ongoing service excluded");
        row.mEntryAdapter.promoted = true;
        check(live(row), "AOSP promoted notification included");
        row.mOnKeyguard = true;
        check(!live(row), "keyguard excluded");
        row.mOnKeyguard = false;
        row.mEntryAdapter.notification.flags |= Notification.FLAG_GROUP_SUMMARY;
        check(!live(row), "group summary excluded");
        row.mEntryAdapter.notification.flags = 0;
        row.mEntryAdapter.promoted = false;
        row.mEntryAdapter.notification.extras.putInt("android.ongoingActivityNoti.style", 1);
        check(live(row), "Samsung live style included");
        row.mEntryAdapter.notification.extras.putString("android.ongoingActivityNoti.style", " 2 ");
        check(live(row), "string style and shade-only lockscreen activity supported");
        row.mEntryAdapter.notification.extras.putString("android.ongoingActivityNoti.style", "bad");
        check(!live(row), "malformed style ignored");
        row.mEntryAdapter.ongoing = true;
        check(live(row), "Samsung adapter live classification included");

        GradientDrawable original = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0, 0});
        original.setCornerRadius(24);
        row.mBackgroundNormal.mBackground = new LayerDrawable(new Drawable[]{original});
        call("styleFill", row.mBackgroundNormal);
        GradientDrawable glass = (GradientDrawable) row.mBackgroundNormal.mBackground.getDrawable(0);
        check(glass != original && glass.getColors()[0] == 0x760E0E10, "private glass fill installed");
        check(original.getColors()[0] == 0, "original drawable state untouched");
        glass.setCornerRadius(36);
        row.mEntryAdapter.ongoing = false;
        call("styleFill", row.mBackgroundNormal);
        GradientDrawable restored = (GradientDrawable) row.mBackgroundNormal.mBackground.getDrawable(0);
        check(restored.getColors()[0] == 0 && restored.getCornerRadius() == 36, "fill restored with current rounding");
        check(row.mBackgroundNormal.restoredTint == row.mBackgroundNormal.mTintColor, "native tint restored");
        row.mEntryAdapter.ongoing = true;
        row.mBackgroundNormal.mBackground = new LayerDrawable(new Drawable[]{
                new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFEEEEEE, 0xFFEEEEEE})});
        call("styleFill", row.mBackgroundNormal);
        check(((GradientDrawable) row.mBackgroundNormal.mBackground.getDrawable(0)).getColors()[0] == 0xFF141416,
                "solid fallback when native blur is off");

        call("refreshRow", row);
        call("refreshRow", row);
        check(row.rebuilds == 1, "background rebuilt once on entering live state");
        row.mEntryAdapter.ongoing = false;
        call("refreshRow", row);
        call("refreshRow", row);
        check(row.rebuilds == 2, "background rebuilt once on leaving live state");
        System.out.println("PASS: " + checks + " live shade card checks");
        System.exit(0);
    }

    private static boolean live(View row) throws Exception {
        Method method = ShadeLiveCards.class.getDeclaredMethod("shouldStyle", View.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, row);
    }

    private static void call(String name, View view) throws Exception {
        Method method = ShadeLiveCards.class.getDeclaredMethod(name, View.class);
        method.setAccessible(true);
        method.invoke(null, view);
    }

    private static void check(boolean result, String message) {
        if (!result) throw new AssertionError(message);
        checks++;
    }

    public static final class TestRow extends FrameLayout {
        public boolean mOnKeyguard;
        public final TestAdapter mEntryAdapter = new TestAdapter();
        public final TestBackground mBackgroundNormal;
        public final TestContent mPrivateLayout;
        public View mPublicLayout;
        public int rebuilds;

        TestRow(Context context) {
            super(context);
            mBackgroundNormal = new TestBackground(context);
            mPrivateLayout = new TestContent(context);
            addView(mBackgroundNormal);
        }

        public void initBackground() {
            rebuilds++;
        }
    }

    public static final class TestBackground extends View {
        public LayerDrawable mBackground;
        public int mTintColor = 0xFF123456;
        public int restoredTint;

        TestBackground(Context context) {
            super(context);
        }

        public void setTint(int tint) {
            restoredTint = tint;
        }
    }

    public static final class TestContent extends FrameLayout {
        public View mContractedChild;
        public View mExpandedChild;
        public View mHeadsUpChild;

        TestContent(Context context) {
            super(context);
        }
    }

    public static final class TestAdapter {
        public final Notification notification = new Notification();
        public boolean ongoing;
        public boolean promoted;

        public StatusBarNotification getSbn() {
            return new StatusBarNotification("probe", "probe", 1, null, 2000, 0, 0, notification,
                    android.os.Process.myUserHandle(), 0);
        }

        public boolean isOngoingActivity() { return ongoing; }
        public boolean isPromotedOngoing() { return promoted; }
    }
}
