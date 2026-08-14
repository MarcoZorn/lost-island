"""MPRIS media service.

Tracks every org.mpris.MediaPlayer2.* player on the session bus and elects an
"active" one (playing beats paused, most recent wins). Everything is signal
driven — the only polling is a 1 Hz position tick, and the UI only enables it
while the seek bar is actually on screen.
"""

from __future__ import annotations

import hashlib
import os
import threading
import urllib.request

from gi.repository import Gio, GLib, GObject

from lostisland import config

MPRIS_PREFIX = "org.mpris.MediaPlayer2."
PLAYER_IFACE = "org.mpris.MediaPlayer2.Player"
OBJECT_PATH = "/org/mpris/MediaPlayer2"


class Player:
    __slots__ = (
        "bus_name", "status", "title", "artist", "album", "art_url",
        "art_path", "length_us", "track_id", "can_next", "can_prev", "stamp",
    )

    def __init__(self, bus_name: str):
        self.bus_name = bus_name
        self.status = "Stopped"
        self.title = ""
        self.artist = ""
        self.album = ""
        self.art_url = ""
        self.art_path = ""
        self.length_us = 0
        self.track_id = ""
        self.can_next = False
        self.can_prev = False
        self.stamp = 0


class MprisService(GObject.Object):
    __gsignals__ = {
        # emitted whenever the active player or its metadata changes
        "changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        # emitted when album art for the active player finished loading
        "art-ready": (GObject.SignalFlags.RUN_FIRST, None, ()),
    }

    def __init__(self):
        super().__init__()
        self._players: dict[str, Player] = {}
        self._subs: dict[str, int] = {}
        self._counter = 0
        self.active: Player | None = None

        self._bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        self._bus.signal_subscribe(
            "org.freedesktop.DBus", "org.freedesktop.DBus", "NameOwnerChanged",
            "/org/freedesktop/DBus", None, Gio.DBusSignalFlags.NONE,
            self._on_name_owner_changed,
        )
        self._bus.call(
            "org.freedesktop.DBus", "/org/freedesktop/DBus",
            "org.freedesktop.DBus", "ListNames", None,
            GLib.VariantType("(as)"), Gio.DBusCallFlags.NONE, -1, None,
            self._on_list_names,
        )

    # -- discovery ---------------------------------------------------------

    def _on_list_names(self, bus, res):
        try:
            names = bus.call_finish(res).unpack()[0]
        except GLib.Error:
            return
        for name in names:
            if name.startswith(MPRIS_PREFIX):
                self._add_player(name)

    def _on_name_owner_changed(self, bus, sender, path, iface, signal, params):
        name, old, new = params.unpack()
        if not name.startswith(MPRIS_PREFIX):
            return
        if new and not old:
            self._add_player(name)
        elif old and not new:
            self._remove_player(name)

    def _add_player(self, name: str):
        if name in self._players:
            return
        self._players[name] = Player(name)
        self._subs[name] = self._bus.signal_subscribe(
            name, "org.freedesktop.DBus.Properties", "PropertiesChanged",
            OBJECT_PATH, None, Gio.DBusSignalFlags.NONE,
            self._on_props_changed,
        )
        self._bus.call(
            name, OBJECT_PATH, "org.freedesktop.DBus.Properties", "GetAll",
            GLib.Variant("(s)", (PLAYER_IFACE,)),
            GLib.VariantType("(a{sv})"), Gio.DBusCallFlags.NONE, -1, None,
            self._on_get_all, name,
        )

    def _remove_player(self, name: str):
        self._players.pop(name, None)
        sub = self._subs.pop(name, None)
        if sub:
            self._bus.signal_unsubscribe(sub)
        self._elect()

    # -- state -------------------------------------------------------------

    def _on_get_all(self, bus, res, name):
        try:
            props = bus.call_finish(res).unpack()[0]
        except GLib.Error:
            return
        self._apply(name, props)

    def _on_props_changed(self, bus, sender, path, iface, signal, params):
        # PropertiesChanged arrives from the sender's unique name (":1.42"),
        # so resolve which well-known MPRIS name that unique name owns.
        _iface, changed, _invalid = params.unpack()
        self._resolve_sender(sender, changed)

    def _resolve_sender(self, sender: str, changed: dict):
        # ask the bus which well-known MPRIS name the unique sender owns
        for name in list(self._players):
            self._bus.call(
                "org.freedesktop.DBus", "/org/freedesktop/DBus",
                "org.freedesktop.DBus", "GetNameOwner",
                GLib.Variant("(s)", (name,)), GLib.VariantType("(s)"),
                Gio.DBusCallFlags.NONE, -1, None,
                self._on_owner_resolved, (name, sender, changed),
            )

    def _on_owner_resolved(self, bus, res, data):
        name, sender, changed = data
        try:
            owner = bus.call_finish(res).unpack()[0]
        except GLib.Error:
            return
        if owner == sender:
            self._apply(name, changed)

    def _apply(self, name: str, props: dict):
        player = self._players.get(name)
        if player is None:
            return
        if "PlaybackStatus" in props:
            player.status = props["PlaybackStatus"]
            self._counter += 1
            player.stamp = self._counter
        if "CanGoNext" in props:
            player.can_next = bool(props["CanGoNext"])
        if "CanGoPrevious" in props:
            player.can_prev = bool(props["CanGoPrevious"])
        if "Metadata" in props:
            meta = props["Metadata"]
            player.title = meta.get("xesam:title", "") or ""
            artists = meta.get("xesam:artist", [])
            player.artist = ", ".join(artists) if isinstance(artists, list) else str(artists)
            player.album = meta.get("xesam:album", "") or ""
            player.length_us = int(meta.get("mpris:length", 0) or 0)
            player.track_id = str(meta.get("mpris:trackid", ""))
            art = meta.get("mpris:artUrl", "") or ""
            if art != player.art_url:
                player.art_url = art
                player.art_path = ""
                self._fetch_art(player)
        self._elect()

    def _elect(self):
        candidates = list(self._players.values())
        playing = [p for p in candidates if p.status == "Playing"]
        paused = [p for p in candidates if p.status == "Paused"]
        pool = playing or paused
        new = max(pool, key=lambda p: p.stamp) if pool else None
        self.active = new
        self.emit("changed")

    # -- album art ---------------------------------------------------------

    def _fetch_art(self, player: Player):
        url = player.art_url
        if not url:
            return
        if url.startswith("file://"):
            player.art_path = GLib.filename_from_uri(url)[0]
            self.emit("art-ready")
            return
        if not url.startswith(("http://", "https://")):
            return
        os.makedirs(config.CACHE_DIR, exist_ok=True)
        dest = os.path.join(
            config.CACHE_DIR, hashlib.sha1(url.encode()).hexdigest() + ".img"
        )
        if os.path.exists(dest):
            player.art_path = dest
            self.emit("art-ready")
            return

        def worker():
            try:
                with urllib.request.urlopen(url, timeout=10) as r, open(dest, "wb") as f:
                    f.write(r.read())
            except OSError:
                return
            def done():
                if player.art_url == url:
                    player.art_path = dest
                    self.emit("art-ready")
                return False
            GLib.idle_add(done)

        threading.Thread(target=worker, daemon=True).start()

    # -- controls ----------------------------------------------------------

    def _call(self, method: str, params=None):
        if not self.active:
            return
        self._bus.call(
            self.active.bus_name, OBJECT_PATH, PLAYER_IFACE, method, params,
            None, Gio.DBusCallFlags.NONE, -1, None, None,
        )

    def play_pause(self):
        self._call("PlayPause")

    def next(self):
        self._call("Next")

    def previous(self):
        self._call("Previous")

    def seek_to(self, position_us: int):
        if self.active and self.active.track_id:
            self._call("SetPosition", GLib.Variant(
                "(ox)", (self.active.track_id, position_us)))

    def get_position(self, callback):
        """Async position fetch in µs; calls callback(pos_us)."""
        if not self.active:
            return
        self._bus.call(
            self.active.bus_name, OBJECT_PATH,
            "org.freedesktop.DBus.Properties", "Get",
            GLib.Variant("(ss)", (PLAYER_IFACE, "Position")),
            GLib.VariantType("(v)"), Gio.DBusCallFlags.NONE, -1, None,
            lambda bus, res: self._on_position(bus, res, callback),
        )

    def _on_position(self, bus, res, callback):
        try:
            pos = bus.call_finish(res).unpack()[0]
        except GLib.Error:
            return
        callback(int(pos))
