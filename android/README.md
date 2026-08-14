# Lost Island for Android

Companion to the Lost Island desktop overlay: a floating pill that sits at
the very top of the screen — over the punch-hole camera, like a real Dynamic
Island — showing the clock, the current track and more. Feature parity with
desktop v1.3 where Android allows it.

## Features

- **Pill faces**, cycled by tapping or swiping the pill:
  - `auto` — song title + animated EQ bars while music plays, clock otherwise
  - `status` — clock + battery %
  - `title` — just the song title
  - `lyrics` — the current synced lyric line (from [lrclib.net](https://lrclib.net))
  - `clock` — only the time
  - `battery` — only the battery
- **Long-press** expands a card with title, artist, battery and transport
  controls; tap outside (or on the card's empty space) to collapse.
- **Settings** (gear on the card, or from the onboarding screen): which faces
  are in the cycle, what a tap does (cycle faces / open the card), background
  opacity, and a top-offset slider for fine cutout alignment.
- Media detection via the system's media sessions — nothing is read beyond
  track metadata and playback state, and nothing leaves the device except the
  lyrics lookup (artist / title / album / duration to lrclib.net).

## Permissions

- **Display over other apps** — the island is a system overlay window
  (`TYPE_APPLICATION_OVERLAY`), so it needs `SYSTEM_ALERT_WINDOW`.
- **Notification access** — Android only exposes active media sessions
  (metadata, playback state, transport controls) to enabled notification
  listeners.

Both are granted from the onboarding screen, which deep-links to the right
settings pages and shows current status.

### Android 13+: "restricted settings"

Sideloaded apps get the notification-access toggle locked by default — it
shows greyed out with a "restricted setting" message. Unlock it once:

1. Open **App info** for Lost Island (the onboarding screen has a button).
2. Tap the **⋮ menu** in the top-right corner.
3. Choose **Allow restricted settings** and confirm.
4. Go back to **Notification access** and enable Lost Island.

The onboarding screen detects the situation and walks you through exactly
this.

## Install & Play Protect

The APK is signed with the checked-in sideload key (`sideload.keystore` —
a public key for a free sideloaded app, not a secret). Because the app
doesn't come from Play, Play Protect still shows an "unknown developer" /
"app from an unknown source" prompt on install — that's expected for any
signed non-Play APK; pick **Install anyway**. A consistent signature also
means updates install over the old version instead of being flagged as a
different app.

    adb install -r app/build/outputs/apk/release/app-release.apk

If you had an old debug-signed build installed, uninstall it first — the
signatures differ.

## Build

    cd android
    gradle assembleRelease

Needs Gradle 8.7+ and JDK 17 (CI uses Gradle 8.9, no wrapper). The signed
APK ends up at `app/build/outputs/apk/release/app-release.apk`.
