package dev.flexy.dynamicisland;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

public final class SettingsActivity extends Activity {
    private SharedPreferences prefs;
    private final SharedPreferences.OnSharedPreferenceChangeListener notifier =
            (p, key) -> getContentResolver().notifyChange(Settings.URI, null);
    private final List<Runnable> refreshers = new ArrayList<>();
    private int text;
    private int secondary;
    private int card;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        if (getActionBar() != null) getActionBar().hide();
        prefs = getSharedPreferences(Settings.FILE, Context.MODE_PRIVATE);
        prefs.registerOnSharedPreferenceChangeListener(notifier);
        boolean night = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int bg = night ? 0xFF000000 : 0xFFF2F2F6;
        text = night ? 0xFFFFFFFF : 0xFF111114;
        secondary = night ? 0x99FFFFFF : 0x99111114;
        card = night ? 0xFF1C1C1E : 0xFFFFFFFF;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(24), dp(16), dp(24));

        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(bg);
        scroll.setFitsSystemWindows(true);
        scroll.addView(root);
        setContentView(scroll);

        if (SCREEN_POSITION.equals(getIntent().getStringExtra(EXTRA_SCREEN))) {
            buildPosition(root);
            refresh();
            return;
        }

        TextView title = label("Dynamic Island", 30, text, true);
        title.setPadding(dp(8), dp(28), 0, dp(20));
        root.addView(title);

        LinearLayout island = group(root, "Island");
        slider(island, "Island length", Settings.MAX_CHARS, Settings.MIN_MAX_CHARS, Settings.NO_MAX_CHARS,
                Settings.DEF_MAX_CHARS, v -> v >= Settings.NO_MAX_CHARS ? "No limit" : v + " chars", Settings.PREVIEW_LENGTH);
        toggle(island, "Show in fullscreen", Settings.SHOW_FULLSCREEN, Settings.DEF_SHOW_FULLSCREEN);
        link(island, "Island position", this::positionSummary, () ->
                startActivity(new Intent(this, SettingsActivity.class).putExtra(EXTRA_SCREEN, SCREEN_POSITION)));
        toggle(island, "Detailed second capsule", Settings.DETAILED_BUBBLE, Settings.DEF_DETAILED_BUBBLE);

        gap(root);
        LinearLayout cards = group(root, "Cards");
        toggle(cards, "Frosted glass design", Settings.FROSTED_CARDS, Settings.DEF_FROSTED_CARDS);
        toggle(cards, "Recent Spotify playlists", Settings.SPOTIFY_RECENTS, Settings.DEF_SPOTIFY_RECENTS);

        gap(root);
        LinearLayout alerts = group(root, "Alerts");
        toggle(alerts, "Headphone battery", Settings.ALERT_PODS, Settings.DEF_ALERT_PODS);
        Switch everyUnlock = toggle(alerts, "Show on every unlock", Settings.PODS_EVERY_UNLOCK, Settings.DEF_PODS_EVERY_UNLOCK);
        refreshers.add(() -> everyUnlock.setEnabled(prefs.getBoolean(Settings.ALERT_PODS, Settings.DEF_ALERT_PODS)));
        toggle(alerts, "Do Not Disturb", Settings.ALERT_DND, Settings.DEF_ALERT_DND);
        toggle(alerts, "Sound / Vibrate / Mute", Settings.ALERT_RINGER, Settings.DEF_ALERT_RINGER);
        toggle(alerts, "Low battery (20% and 10%)", Settings.ALERT_BATTERY, Settings.DEF_ALERT_BATTERY);

        gap(root);
        LinearLayout apps = group(root, "Notifications");
        toggle(apps, "WhatsApp and Telegram messages", Settings.SHOW_MESSAGES, Settings.DEF_SHOW_MESSAGES);
        TextView messageNote = label("WhatsApp and Telegram island alerts only work with Do Not Disturb enabled while this bug is investigated.", 12, secondary, false);
        messageNote.setPadding(0, 0, 0, dp(8));
        apps.addView(messageNote);
        toggle(apps, "Chrome downloads", Settings.SHOW_DOWNLOADS, Settings.DEF_SHOW_DOWNLOADS);

        Button reset = new Button(this);
        reset.setText("Reset to defaults");
        reset.setAllCaps(false);
        reset.setTextColor(0xFF0A84FF);
        reset.setBackground(round(card, dp(22)));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        rlp.topMargin = dp(16);
        reset.setOnClickListener(v -> {
            prefs.edit().clear().apply();
            refresh();
        });
        root.addView(reset, rlp);
        refresh();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private static final String EXTRA_SCREEN = "screen";
    private static final String SCREEN_POSITION = "position";
    private static final String[] POSITIONS = {"Center", "Left", "Front camera"};

    private interface Text {
        String get();
    }

    private boolean foldable() {
        return getPackageManager().hasSystemFeature(PackageManager.FEATURE_SENSOR_HINGE_ANGLE);
    }

    private boolean tablet() {
        return !foldable() && getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    private int position(String key, int count) {
        int v = prefs.getInt(key, Settings.DEF_POSITION);
        return v >= 0 && v < count ? v : Settings.DEF_POSITION;
    }

    private String positionSummary() {
        String large = POSITIONS[position(Settings.POSITION, 3)];
        String phone = POSITIONS[position(Settings.POSITION_PHONE, 2)];
        if (foldable()) return large.equals(phone) ? large : large + " / " + phone;
        return tablet() ? large : phone;
    }

    private void link(LinearLayout group, String name, Text value, Runnable open) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, dp(10));
        row.addView(label(name, 16, text, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView v = label("", 16, secondary, false);
        row.addView(v);
        row.addView(label("  ›", 20, secondary, false));
        row.setOnClickListener(x -> open.run());
        refreshers.add(() -> v.setText(value.get()));
        group.addView(row);
    }

    private void buildPosition(LinearLayout root) {
        TextView title = label("Island position", 30, text, true);
        title.setPadding(dp(8), dp(28), 0, dp(20));
        root.addView(title);
        if (foldable()) {
            positionGroup(root, "Inner screen", Settings.POSITION, Settings.GLASS_PILL, 3);
            gap(root);
            positionGroup(root, "Cover screen", Settings.POSITION_PHONE, Settings.GLASS_PILL_PHONE, 2);
        } else if (tablet()) {
            positionGroup(root, "Position", Settings.POSITION, Settings.GLASS_PILL, 3);
        } else {
            positionGroup(root, "Position", Settings.POSITION_PHONE, Settings.GLASS_PILL_PHONE, 2);
        }
    }

    private void positionGroup(LinearLayout root, String name, String key, String glassKey, int count) {
        LinearLayout place = group(root, name);
        RadioGroup radios = new RadioGroup(this);
        for (int i = 0; i < count; i++) {
            RadioButton b = new RadioButton(this);
            b.setId(View.generateViewId());
            b.setText(POSITIONS[i]);
            b.setTextColor(text);
            b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            b.setPadding(dp(8), dp(12), 0, dp(12));
            b.setTag(i);
            radios.addView(b);
        }
        radios.setOnCheckedChangeListener((g, id) -> {
            View b = g.findViewById(id);
            if (b == null || position(key, count) == (int) b.getTag()) return;
            prefs.edit().putInt(key, (int) b.getTag()).apply();
            refresh();
        });
        refreshers.add(() -> {
            View b = radios.getChildAt(position(key, count));
            if (b != null && b.getId() != radios.getCheckedRadioButtonId()) radios.check(b.getId());
        });
        place.addView(radios);

        Switch glass = toggle(place, "Blur instead of black", glassKey, Settings.DEF_GLASS_PILL);
        glass.setOnCheckedChangeListener((b, on) -> {
            if (prefs.getBoolean(glassKey, Settings.DEF_GLASS_PILL) == on) return;
            prefs.edit().putBoolean(glassKey, on).apply();
            preview(b);
            refresh();
        });
        refreshers.add(() -> {
            int pos = position(key, count);
            boolean on = pos == Settings.POS_LEFT || (Settings.POSITION.equals(key) && pos == Settings.POS_CENTER);
            glass.setVisibility(on ? View.VISIBLE : View.GONE);
        });
    }

    private void preview(View anchor) {
        prefs.edit().putBoolean(Settings.PREVIEW_LENGTH, true).apply();
        anchor.removeCallbacks(endPreview);
        anchor.postDelayed(endPreview, PREVIEW_HOLD_MS * 2);
    }

    @Override
    protected void onDestroy() {
        prefs.unregisterOnSharedPreferenceChangeListener(notifier);
        super.onDestroy();
    }

    private void refresh() {
        for (Runnable r : refreshers) r.run();
    }

    private void gap(LinearLayout root) {
        root.addView(new View(this), new LinearLayout.LayoutParams(1, dp(20)));
    }

    private LinearLayout group(LinearLayout root, String name) {
        TextView header = label(name, 14, secondary, true);
        header.setPadding(dp(12), dp(4), 0, dp(8));
        root.addView(header);
        LinearLayout g = new LinearLayout(this);
        g.setOrientation(LinearLayout.VERTICAL);
        g.setPadding(dp(18), dp(10), dp(18), dp(14));
        g.setBackground(round(card, dp(24)));
        root.addView(g);
        return g;
    }

    private Switch toggle(LinearLayout group, String name, String key, boolean def) {
        Switch s = new Switch(this);
        s.setText(name);
        s.setTextColor(text);
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        s.setPadding(0, dp(12), 0, dp(12));
        s.setOnCheckedChangeListener((b, on) -> {
            prefs.edit().putBoolean(key, on).apply();
            refresh();
        });
        refreshers.add(() -> s.setChecked(prefs.getBoolean(key, def)));
        group.addView(s);
        return s;
    }

    private interface Format {
        String of(int v);
    }

    private static final long PREVIEW_HOLD_MS = 1200;
    private final Runnable endPreview = () -> prefs.edit().putBoolean(Settings.PREVIEW_LENGTH, false).apply();

    @Override
    protected void onPause() {
        endPreview.run();
        super.onPause();
    }

    private void slider(LinearLayout group, String name, String key, int min, int max, int def, Format format,
                        String preview) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, 0);
        row.addView(label(name, 16, text, false), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView value = label("", 16, 0xFF0A84FF, true);
        row.addView(value);
        group.addView(row);

        SeekBar bar = new SeekBar(this);
        bar.setMax(max - min);
        bar.setPadding(dp(4), dp(10), dp(4), dp(4));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                int v = min + progress;
                value.setText(format.of(v));
                if (fromUser) prefs.edit().putInt(key, v).apply();
            }

            @Override
            public void onStartTrackingTouch(SeekBar s) {
                if (preview == null) return;
                s.removeCallbacks(endPreview);
                prefs.edit().putBoolean(preview, true).apply();
            }

            @Override
            public void onStopTrackingTouch(SeekBar s) {
                if (preview != null) s.postDelayed(endPreview, PREVIEW_HOLD_MS);
            }
        });
        value.setOnLongClickListener(v -> {
            prefs.edit().remove(key).apply();
            refresh();
            return true;
        });
        refreshers.add(() -> {
            int v = prefs.getInt(key, def);
            bar.setProgress(Math.max(min, Math.min(max, v)) - min);
            value.setText(format.of(v));
        });
        group.addView(bar);
    }

    private TextView label(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        return t;
    }

    private static GradientDrawable round(int color, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radius);
        return d;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
