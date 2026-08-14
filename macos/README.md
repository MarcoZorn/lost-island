# Lost Island for macOS

Companion to the Linux app in the repo root: a Dynamic Island for the Mac.
A small pill floats just under the menu bar showing the time; when Spotify
or Apple Music plays, it shows the track and an accent dot. Click it for a
card with artwork and playback controls; move the mouse away to collapse.

Scope is deliberately tiny — clock plus Spotify/Music control. Everything
else lives in the Linux flagship.

## Build

    swift build -c release
    ./make-app.sh    # produces LostIsland.app and LostIsland-macos.zip

Needs macOS 13+ and the Xcode command line tools. No dependencies.

## First run

- Unsigned build: right-click LostIsland.app, Open, to get past Gatekeeper.
- The first playback poll triggers a "wants to control Spotify/Music"
  automation prompt. Allow it, or the island stays a plain clock.

## Autostart

System Settings → General → Login Items → add LostIsland.app.
