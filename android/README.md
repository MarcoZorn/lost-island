# Lost Island for Android

Companion to the Lost Island desktop overlay: a floating pill pinned to the
top of the screen showing the clock and the currently playing track. Tapping
it expands a card with title, artist and transport controls, styled after the
Linux app.

## Permissions

- **Display over other apps** — the island is a system overlay window
  (`TYPE_APPLICATION_OVERLAY`), so it needs `SYSTEM_ALERT_WINDOW`.
- **Notification access** — Android only exposes active media sessions
  (metadata, playback state, transport controls) to enabled notification
  listeners. Nothing else is read and nothing leaves the device.

Both are granted from the onboarding screen, which deep-links to the right
settings pages and shows current status.

## Build

    cd android
    gradle assembleDebug

Needs Gradle 8.7+ and JDK 17. The debug APK ends up at
`app/build/outputs/apk/debug/app-debug.apk`.

## Install

    adb install app/build/outputs/apk/debug/app-debug.apk

The APK is debug-signed. For anything Play-shaped, wire up your own keystore
and release signing config.
