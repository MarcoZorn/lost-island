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
  for a configurable spell (1–8 s, default 3), then the previous face returns.
  The expanded card lists the latest notifications (up to 6): tap a row to open
  it, tap ✕ to dismiss it. Rows carrying a live timer (a running stopwatch or
  count-down notification) show a ticking clock, and rows with a direct-reply
  action grow a **Reply** field so you can answer without leaving the island.
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

## Live in the notch

The island reads the device's **display cutout** and parks itself right on the
punch-hole, morphing out of the hole when there's something to show and melting
back into it when idle.

- **Auto-center on the camera cutout** (default on) — derives the gap and the
  screen position straight from the detected cutout, so the pill wraps the lens
  no matter where the manufacturer put it. With it off, the manual **camera
  gap** / **top offset** from the layout section are used instead.
- **Nudge X / Nudge Y** (−40…40 dp, centered at 0) — fine calibration for the
  auto-centered position. Real cutouts and reported bounds drift a pixel or
  two between devices; nudge until the ring sits dead-on. Shown only while
  auto-center is on.
- **Resting appearance** — what the collapsed island looks like when nothing is
  happening:
  - `hidden` — invisible; it stays alive and morphs out only on activity
  - `outline` — a thin ring traced around the camera hole
  - `pill` — the classic always-on pill with the tap / swipe face cycle
- **Corner radius** (8–40 dp, default 20) — roundness of the pill; the expanded
  card uses this plus a little more.
- **Peek duration** (1–8 s, default 3) — how long a notification peek lingers.
- **Ring pulse** — on a new notification the ring around the hole pulses in your
  accent color (can be turned off).

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

**Allowed apps** — by default every app may peek. Open *Allowed apps* in
settings and flip on specific apps to restrict peeks/notifs to just those;
turn them all back off and the island listens to everything again. The list
is drawn from installed launchable apps, sorted by name.

**Battery alarm** — independent of app notifications, the island can peek a
low-battery warning (with an optional vibration) when the charge drops through
a threshold you pick, plus a quiet "Charging" / "Fully charged" peek when the
cable state changes.

## Settings

All settings apply live (gear on the card, or from the onboarding screen):

- **Layout** — pill width, camera gap, content side, top offset, opacity
- **Look** — accent color (8 choices; drives the idle dot, charging battery
  text and the card progress bar), 24-hour clock
- **Faces** — which faces are in the tap / swipe cycle, including `notifs`
- **Behavior** — what a tap does (cycle faces / open the card), haptic tick
  on face change, auto-collapse for the card (0 = never, up to 15 s; any
  touch on the card resets the timer)
- **Camera notch** — resting appearance (hidden / outline / pill), auto-center
  on the cutout with X / Y nudge calibration, corner radius, peek duration
- **Notifications** — ring pulse on/off, battery alarm with low-battery
  threshold and vibrate toggle, and what a long-press does (open card / open
  app / dismiss)
- **Allowed apps** — the per-app peek allowlist (empty = all apps)
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
