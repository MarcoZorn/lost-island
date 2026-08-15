# Lost Island for Android

Companion to the Lost Island desktop overlay: a floating pill that sits at
the very top of the screen — over the punch-hole camera, like a real Dynamic
Island — showing the clock, the current track, notifications and more.
Feature parity with desktop v1.3 where Android allows it.

## Features

- **Pill faces**, cycled by tapping or swiping the pill:
  - `auto` — song title + animated EQ bars while music plays, clock otherwise
  - `status` — clock + battery %
  - `title` — just the song title
  - `lyrics` — the current synced lyric line (from [lrclib.net](https://lrclib.net))
  - `clock` — only the time
  - `battery` — only the battery
  - `notifs` — up to 4 app icons of active notifications, plus a `+N` badge
    when there are more; falls back to the clock when there is nothing to show
- **Long-press** expands a card with title, artist, progress, battery and
  transport controls; tap outside (or on the card's empty space) to collapse.
- **Notifications in the island**: when a notification arrives while the pill
  is collapsed, the app's icon and the notification title peek into the pill
  for ~2.5 s, then the previous face returns. The expanded card lists the
  latest notifications (up to 6): tap a row to open it, tap ✕ to dismiss it.
- Media detection via the system's media sessions — nothing is read beyond
  track metadata and playback state, and nothing leaves the device except the
  lyrics lookup (artist / title / album / duration to lrclib.net).

## Camera-safe layout

Punch-hole cameras sit at the screen center, exactly where the pill lives.
The collapsed pill is therefore split into two content slots with a
configurable **camera gap** between them: the capsule background still spans
the whole pill, but content never crosses the center, so nothing disappears
behind the lens. The gap stays screen-centered because the pill has a fixed
width and equal slot widths.

- **Pill width** (140–340 dp, default 210) — total capsule width.
- **Camera gap** (0–80 dp, default 28) — content-free center zone.
  Set it to 0 for the legacy single centered pill (no punch-hole, or one in
  a corner).
- **Content side** — where face content goes:
  - `left` / `right` — everything in that slot
  - `split` — sensible pairs per face: title left + EQ right (auto), clock
    left + battery right (status), notification icons left + count right, …

## Notifications and the status bar

Dismissing a notification from the island (the ✕ on a card row) removes it
from the status bar too — that is `cancelNotification()`, the same thing the
notification shade does. Android offers no way for a non-system app to *hide*
a status-bar icon while keeping the notification alive, so "moving" an icon
into the island without dismissing it is not possible; dismissal is the
closest the platform allows.

Ongoing/foreground-service notifications, group summaries and media
notifications (already shown as the music face) are ignored. If notification
access is off, the notifs face falls back to the clock and the card section
is hidden.

## Settings

All settings apply live (gear on the card, or from the onboarding screen):

- **Layout** — pill width, camera gap, content side, top offset, opacity
- **Look** — accent color (8 choices; drives the idle dot, charging battery
  text and the card progress bar), 24-hour clock
- **Faces** — which faces are in the tap / swipe cycle, including `notifs`
- **Behavior** — what a tap does (cycle faces / open the card), haptic tick
  on face change, auto-collapse for the card (0 = never, up to 15 s; any
  touch on the card resets the timer)
- **About** — version and project link

## Permissions

- **Display over other apps** — the island is a system overlay window
  (`TYPE_APPLICATION_OVERLAY`), so it needs `SYSTEM_ALERT_WINDOW`.
- **Notification access** — Android only exposes active media sessions
  (metadata, playback state, transport controls) and status-bar
  notifications to enabled notification listeners.

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
