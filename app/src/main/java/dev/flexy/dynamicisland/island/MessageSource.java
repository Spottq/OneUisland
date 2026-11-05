package dev.flexy.dynamicisland.island;

import android.app.Notification;
import android.content.Context;
import android.os.Bundle;
import android.os.Parcelable;
import android.service.notification.StatusBarNotification;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chat messages from WhatsApp and Telegram. A new message becomes a short alert in the
 * island. Regular message notifications are not ongoing, so the Now Bar code never shows them.
 */
final class MessageSource {
    static final Set<String> PACKAGES = new HashSet<>(Arrays.asList(
            "com.whatsapp",
            "com.whatsapp.w4b",
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.telegram.messenger.beta",
            "org.thunderdog.challegram"));

    /** A notification only counts as a new message if it was posted this recently. */
    private static final long FRESH_MS = 5000;
    private static final int MAX_TEXT = 160;
    private static final int MAX_CHAT = 18;
    private static final int WHATSAPP_GREEN = 0xFF25D366;
    private static final int TELEGRAM_BLUE = 0xFF2AABEE;

    private final Context ctx;
    private final Map<String, Long> posted = new HashMap<>();
    private boolean primed;

    MessageSource(Context ctx) {
        this.ctx = ctx;
    }

    /**
     * Returns an alert for the newest message that arrived since the last call, or null.
     * The first call only records what is already in the shade, so nothing old pops up at boot.
     */
    IslandItem scan(List<StatusBarNotification> all) {
        long now = System.currentTimeMillis();
        StatusBarNotification best = null;
        CharSequence[] bestMessage = null;
        Set<String> live = new HashSet<>();
        for (StatusBarNotification sbn : all) {
            if (!PACKAGES.contains(sbn.getPackageName())) continue;
            String key = sbn.getKey();
            live.add(key);
            long post = sbn.getPostTime();
            Long before = posted.put(key, post);
            if (!primed || (before != null && before == post)) continue;
            Notification n = sbn.getNotification();
            if (!isNewMessage(n, post, now)) continue;
            CharSequence[] message = lastMessage(n);
            if (message == null) continue;
            if (best == null || post > best.getPostTime()) {
                best = sbn;
                bestMessage = message;
            }
        }
        posted.keySet().retainAll(live);
        primed = true;
        return best == null ? null : build(best, bestMessage);
    }

    private static boolean isNewMessage(Notification n, long post, long now) {
        if ((n.flags & (Notification.FLAG_GROUP_SUMMARY | Notification.FLAG_ONGOING_EVENT)) != 0) return false;
        if (Notification.CATEGORY_CALL.equals(n.category)) return false;
        return now - post < FRESH_MS;
    }

    /** Returns {sender, text} of the newest message, or null when the notification has no text. */
    private static CharSequence[] lastMessage(Notification n) {
        Parcelable[] messages = n.extras.getParcelableArray(Notification.EXTRA_MESSAGES);
        if (messages != null && messages.length > 0 && messages[messages.length - 1] instanceof Bundle) {
            Bundle m = (Bundle) messages[messages.length - 1];
            CharSequence text = m.getCharSequence("text");
            if (text != null && text.length() > 0) return new CharSequence[]{m.getCharSequence("sender"), text};
        }
        CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT);
        if (text == null || text.length() == 0) return null;
        return new CharSequence[]{null, text};
    }

    private IslandItem build(StatusBarNotification sbn, CharSequence[] message) {
        Notification n = sbn.getNotification();
        String pkg = sbn.getPackageName();
        CharSequence chat = n.extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE);
        if (chat == null || chat.length() == 0) chat = n.extras.getCharSequence(Notification.EXTRA_TITLE);
        String chatName = AppInfo.clip(chat, MAX_CHAT);
        String body = AppInfo.clip(message[1], MAX_TEXT);
        String sender = message[0] == null ? "" : message[0].toString().trim();
        // In a group the sender goes in front of the text. In a one-to-one chat the sender is the chat name.
        boolean named = !sender.isEmpty() && !sender.equals(chatName);

        IslandItem it = new IslandItem("msg:" + sbn.getKey());
        it.alert = true;
        it.kind = IslandItem.Kind.LIVE;
        it.packageName = pkg;
        it.userId = sbn.getUserId();
        it.postTime = Long.MAX_VALUE;
        it.icon = AppInfo.icon(ctx, pkg);
        it.iconIsImage = it.icon != null;
        it.overline = AppInfo.label(ctx, pkg);
        it.title = named ? sender + ": " + body : body;
        if (!chatName.isEmpty()) {
            it.trailing = IslandItem.Trailing.TEXT;
            it.trailingText = chatName;
        }
        it.accent = n.color != 0 ? Colors.readableOnBlack(n.color) : defaultAccent(pkg);
        it.contentIntent = n.contentIntent;
        return it;
    }

    private static int defaultAccent(String pkg) {
        return pkg.startsWith("org.telegram") || pkg.equals("org.thunderdog.challegram")
                ? TELEGRAM_BLUE : WHATSAPP_GREEN;
    }
}
