#!/bin/bash
# Builds lost-island_<ver>_all.deb into dist/. Needs dpkg-deb only.
set -euo pipefail

cd "$(dirname "$0")/.."
VER=$(grep -m1 '^version' pyproject.toml | cut -d'"' -f2)
ROOT=dist/deb/lost-island_${VER}_all
rm -rf "$ROOT" && mkdir -p "$ROOT/DEBIAN" dist

mkdir -p "$ROOT/usr/lib/lost-island" "$ROOT/usr/bin" \
  "$ROOT/usr/share/applications" \
  "$ROOT/usr/share/icons/hicolor/scalable/apps" \
  "$ROOT/usr/lib/systemd/user" \
  "$ROOT/usr/share/doc/lost-island"

cp -r lostisland "$ROOT/usr/lib/lost-island/"
find "$ROOT" -name __pycache__ -type d -exec rm -rf {} +

cat > "$ROOT/usr/bin/lost-island" <<'EOF'
#!/bin/sh
PYTHONPATH=/usr/lib/lost-island exec python3 -m lostisland "$@"
EOF
chmod 755 "$ROOT/usr/bin/lost-island"

cp data/lost-island.desktop "$ROOT/usr/share/applications/"
cp data/lost-island.svg "$ROOT/usr/share/icons/hicolor/scalable/apps/"
cp data/lost-island.service "$ROOT/usr/lib/systemd/user/"
cp LICENSE "$ROOT/usr/share/doc/lost-island/copyright"

cat > "$ROOT/DEBIAN/control" <<EOF
Package: lost-island
Version: $VER
Section: utils
Priority: optional
Architecture: all
Depends: python3 (>= 3.11), python3-gi, python3-gi-cairo, gir1.2-gtk-4.0, gir1.2-adw-1, pulseaudio-utils
Recommends: libgtk4-layer-shell0, network-manager, upower, cava
Maintainer: Marco Zorn <m@zorn.it>
Homepage: https://github.com/MarcoZorn/lost-island
Description: Dynamic Island for the Linux desktop
 A fluid, always-on-top island at the top of your screen: music with
 full controls and album art, volume OSD, battery, notifications and a
 pomodoro timer. Native GTK4, event-driven, light on your battery.
EOF

dpkg-deb --build --root-owner-group "$ROOT" "dist/lost-island_${VER}_all.deb"
echo "built dist/lost-island_${VER}_all.deb"
