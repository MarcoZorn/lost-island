# Lost Island for Windows

Windows companion to Lost Island, the Dynamic Island for desktop OSes.
A small always-on-top capsule at the top of the screen: a clock when idle,
"Artist — Title" with an accent dot while music plays. Clicking it morphs
the pill into a card with album art and previous / play-pause / next
controls; with no media session it shows a large clock and date instead.

Media comes from the Global System Media Transport Controls — the same
source as the Windows volume flyout — so Spotify, browsers and most players
work out of the box. Scope is deliberately smaller than the Linux flagship:
no peek notifications, tiles or pomodoro here.

## Build

    dotnet publish windows/LostIsland.csproj -c Release -r win-x64 --self-contained false -p:PublishSingleFile=true

Needs the .NET 8 SDK to build and the .NET 8 Desktop Runtime to run.

## Run at startup

Press Win+R, type `shell:startup`, and drop a shortcut to `LostIsland.exe`
(found under `windows/bin/Release/net8.0-windows10.0.19041.0/win-x64/publish/`)
into the folder that opens.
