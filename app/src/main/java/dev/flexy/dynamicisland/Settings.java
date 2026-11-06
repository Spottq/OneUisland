package dev.flexy.dynamicisland;

public final class Settings {
    public static final String FILE = "settings";
    public static final android.net.Uri URI = android.net.Uri.parse("content://dev.flexy.dynamicisland.settings");
    public static final String METHOD_GET = "get";

    public static final String MAX_CHARS = "max_chars";
    public static final int MIN_MAX_CHARS = 4;
    public static final int MAX_MAX_CHARS = 20;
    public static final int NO_MAX_CHARS = MAX_MAX_CHARS + 1;
    public static final int DEF_MAX_CHARS = NO_MAX_CHARS;

    public static final String PREVIEW_LENGTH = "preview_length";
    public static final String SHOW_FULLSCREEN = "show_fullscreen";
    public static final boolean DEF_SHOW_FULLSCREEN = false;
    public static final String RAINBOW_IDLE = "rainbow_idle";
    public static final boolean DEF_RAINBOW_IDLE = false;
    public static final String LANDSCAPE_NOTCH = "landscape_notch";
    public static final boolean DEF_LANDSCAPE_NOTCH = false;

    public static final String ALERT_PODS = "alert_pods";
    public static final String PODS_EVERY_UNLOCK = "pods_every_unlock";
    public static final String ALERT_DND = "alert_dnd";
    public static final String ALERT_RINGER = "alert_ringer";

    public static final String SHOW_MESSAGES = "show_messages";
    public static final boolean DEF_SHOW_MESSAGES = true;
    public static final String SHOW_DOWNLOADS = "show_downloads";
    public static final boolean DEF_SHOW_DOWNLOADS = true;

    public static final String ALERT_BATTERY = "alert_battery";
    public static final boolean DEF_ALERT_BATTERY = true;
    public static final String ALERT_AIRPLANE = "alert_airplane";
    public static final boolean DEF_ALERT_AIRPLANE = true;
    public static final String ALERT_WIFI = "alert_wifi";
    public static final boolean DEF_ALERT_WIFI = true;
    /** Under investigation: expanding the island in landscape. Off by default. */
    public static final String EXPAND_LANDSCAPE = "expand_landscape";
    public static final boolean DEF_EXPAND_LANDSCAPE = false;

    public static final boolean DEF_ALERT_PODS = true;
    public static final boolean DEF_PODS_EVERY_UNLOCK = true;
    public static final boolean DEF_ALERT_DND = true;
    public static final boolean DEF_ALERT_RINGER = true;

    public static final String POSITION = "island_position";
    public static final int POS_CENTER = 0;
    public static final int POS_LEFT = 1;
    public static final int POS_CAMERA = 2;
    public static final int DEF_POSITION = POS_CENTER;

    public static final String POSITION_PHONE = "island_position_phone";
    public static final String GLASS_PILL = "glass_pill";
    public static final String GLASS_PILL_PHONE = "glass_pill_phone";
    public static final boolean DEF_GLASS_PILL = false;

    public static final String DETAILED_BUBBLE = "detailed_bubble";
    public static final boolean DEF_DETAILED_BUBBLE = false;
    public static final String FROSTED_CARDS = "frosted_cards";
    public static final boolean DEF_FROSTED_CARDS = false;
    public static final String SPOTIFY_RECENTS = "spotify_recents";
    public static final boolean DEF_SPOTIFY_RECENTS = false;

    private Settings() {
    }
}
