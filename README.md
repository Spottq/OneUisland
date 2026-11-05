# OneUisland — Dynamic Island for One UI 9

**English** | [Русский](README.ru.md)

An LSPosed module (legacy Xposed API) for One UI 9 / Android 17. It replaces the Now Bar capsule in the status bar with a dynamic island around the front camera, in the style of ColorOS 17 and HyperOS.

Get the ready-made APK from [Releases](../../releases).

## Features

- Shows live notifications in the island, like the Now Bar. Lock-screen-only live activities (`android.ongoingActivityNoti.style ≥ 2`) are skipped.
- Shows music, calls, screen recording and casting.
- Splits the island in two with a liquid bridge. A tap opens a stack of glass cards that flow out of the camera.
- ColorOS 17 style player with the app's own buttons and a seekable progress bar.
- The equalizer in the capsule reacts to the real audio.
- Shows apps' own RemoteViews layouts in the card (for example, navigation).
- Swipe left on the capsule to minimize an activity, swipe right to bring it back.
- Closing an app flies it into the island, using the One UI Home animation.
- Status bar icons fade out smoothly when the island covers them.
- Flashlight island: tap the flashlight to switch it on or off, drag along the beam to pick one of the brightness steps or slide to the flashlight to turn it off. The same card replaces the flashlight notification in the shade.
- Samsung Voice Recorder island: a red waveform that scrolls from right to left with the recording timer, pause and stop buttons in the card, and a short "saved" check after stopping. With Voice Recorder added to the module scope in LSPosed the waveform follows the real microphone level.
- Claude island: while Claude works in the Claude app (chats and Claude Code sessions), the island shows the Claude icon and a spinning four-dot indicator. The card shows the last three steps with the app's own icons, sliding in with a blur, questions with an "Answer in Claude" button, and the number of running background tasks. Add Claude to the module scope in LSPosed; a Claude Code session is followed while it is open in the app.
- Stopwatch shows milliseconds in the island, the card and the shade for the first hour. Metric notifications show the chip text the app sends.
- Frosted glass design (optional): see-through blurred cards with a dark fade at the top and a soft light edge at the bottom.
- Recent Spotify playlists (optional): four square tiles under the Spotify player, tap one to play it.
- Detailed second capsule (optional): a mini equalizer for music, short time for timers (`5m`, `1h`) and the number for downloads.
- The player's seek bar is tinted with the album color and casts a cone of light behind its head.
- A fast swipe up closes the cards faster and makes the island bounce harder.
- Short alerts: Do Not Disturb, sound / vibrate / mute, low battery (20% and 10%, shown for 5 s) and headphone battery (AirPods via LibrePods, Galaxy Buds, Fast Pair) with a red Low Battery alert at 10%.
- Live notifications in the shade are styled as dark glass island cards: light text, a thin rim and a solid background when blur is off. App layouts, expansion and actions are kept. Regular notifications and the lock screen keep the system look.
- Messages: a new WhatsApp or Telegram message shows as a card for about 4.5 s (sender, chat name, text, app icon). Tap it to open the chat.
- Chrome downloads: a progress ring while a file downloads, and a short "download complete" card when it finishes. The text comes from Chrome, so it follows your language. Samsung Internet is included.
- Message and download alerts stay quiet when the screen is locked, Do Not Disturb is on, a call is running, or the app is already open in front.

## Settings

Open the module settings from LSPosed:

- Island length (in characters).
- Detailed second capsule.
- Cards: frosted glass design, recent Spotify playlists.
- Alert toggles: headphone battery (every unlock or only on connect), Do Not Disturb, sound mode, low battery.
- Notifications: WhatsApp and Telegram messages, Chrome downloads (each can be turned off). WhatsApp and Telegram island alerts currently work only with Do Not Disturb enabled, due to a duplicate-notification bug under investigation.
- Island position: center or left after the clock (grows only to the right, stops before the camera). Tablets and the inner screen of foldables also get "around the front camera". Foldables have separate settings for the inner and cover screens. The left island, and the centered island on tablets and the inner screen, can use a blurred background instead of black.

## Installation

1. Download and install the APK from [Releases](../../releases).
2. Enable the module in LSPosed. Scope: **System UI**.
3. Restart System UI (no reboot needed), for example:
   `adb shell su -c "pkill -f com.android.systemui"`

## Building

You need JDK 17–21 and the Android SDK (platform 36). Set the SDK path in `local.properties`, then run:

```
gradlew.bat :app:assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`.

To check the shade styling logic on a connected Android 17 device without installing the APK or restarting System UI, run `./tests/device/run.ps1` (PowerShell, JDK in `JAVA_HOME`). Details and the list of visual checks are in `tests/device/README.md`.

## Structure

- `app/src/main/java/dev/flexy/dynamicisland/xposed` — entry point and System UI hooks (`SystemUiHooks`).
  - `ShadeLiveCards` — live notification styling in the shade (Samsung ongoing activity / AOSP promoted ongoing).
- `app/src/main/java/dev/flexy/dynamicisland/island` — the island:
  - `IslandView` — drawing, animations, gestures;
  - `IslandController` — data and signals;
  - data sources: `OaSource`, `MediaSource`, `CastSource`, `AlertSource`;
  - blur, equalizer and app exit animation targets.
- `stubs` — Xposed API stubs, used only at compile time.
