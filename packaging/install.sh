#!/bin/bash
# Universal installer for Lost Island (Linux).
#   from a checkout:  sudo ./packaging/install.sh [--autostart]
#   from anywhere:    curl -fsSL https://raw.githubusercontent.com/MarcoZorn/lost-island/main/packaging/install.sh | sudo bash
set -euo pipefail

REPO="https://github.com/MarcoZorn/lost-island"
PREFIX=/usr/local

say() { printf '\033[1;33m[lost-island]\033[0m %s\n' "$*"; }

[ "$(id -u)" -eq 0 ] || { say "run me with sudo"; exit 1; }

# ---- dependencies ----------------------------------------------------------
if command -v pacman >/dev/null; then
  pacman -S --noconfirm --needed python python-gobject python-cairo gtk4 \
    gtk4-layer-shell libadwaita libpulse
elif command -v apt-get >/dev/null; then
  apt-get install -y python3 python3-gi python3-gi-cairo gir1.2-gtk-4.0 \
    gir1.2-adw-1 libgtk4-layer-shell0 pulseaudio-utils
elif command -v dnf >/dev/null; then
  dnf install -y python3 python3-gobject python3-cairo gtk4 gtk4-layer-shell \
    libadwaita pulseaudio-utils
elif command -v zypper >/dev/null; then
  zypper install -y python3 python3-gobject python3-cairo gtk4 \
    gtk4-layer-shell libadwaita pulseaudio-utils
else
  say "unknown distro — install GTK4, gtk4-layer-shell, libadwaita and PyGObject yourself, then re-run"
fi

# ---- source ----------------------------------------------------------------
SRC=$(cd "$(dirname "${BASH_SOURCE[0]:-.}")/.." 2>/dev/null && pwd || true)
if [ ! -f "$SRC/lostisland/app.py" ]; then
  say "fetching sources"
  TMP=$(mktemp -d)
  trap 'rm -rf "$TMP"' EXIT
  git clone --depth 1 "$REPO" "$TMP/lost-island"
  SRC="$TMP/lost-island"
fi

# ---- install ---------------------------------------------------------------
say "installing to $PREFIX"
rm -rf "$PREFIX/lib/lost-island"
mkdir -p "$PREFIX/lib/lost-island" "$PREFIX/bin"
cp -r "$SRC/lostisland" "$PREFIX/lib/lost-island/"
find "$PREFIX/lib/lost-island" -name __pycache__ -type d -exec rm -rf {} + 2>/dev/null || true

cat > "$PREFIX/bin/lost-island" <<EOF
#!/bin/sh
PYTHONPATH=$PREFIX/lib/lost-island exec python3 -m lostisland "\$@"
EOF
chmod 755 "$PREFIX/bin/lost-island"

install -Dm644 "$SRC/data/lost-island.desktop" /usr/share/applications/lost-island.desktop
install -Dm644 "$SRC/data/lost-island.svg" /usr/share/icons/hicolor/scalable/apps/lost-island.svg
sed "s|/usr/bin/lost-island|$PREFIX/bin/lost-island|" \
  "$SRC/data/lost-island.service" > /usr/lib/systemd/user/lost-island.service

say "installed. start with: lost-island"
if [ "${1:-}" = "--autostart" ]; then
  U=${SUDO_USER:-}
  if [ -n "$U" ]; then
    runuser -u "$U" -- systemctl --user enable --now lost-island.service || true
    say "autostart enabled for $U"
  fi
else
  say "autostart: systemctl --user enable --now lost-island.service"
fi
