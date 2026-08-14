"""The expanded card — media player up top, quick tiles below.

The 1 Hz seek-bar tick runs only while this card is mapped and something is
playing; unmapping tears every timer down.
"""

from __future__ import annotations

import time

from gi.repository import Gio, GLib, Gtk, Pango

from lostisland.ui.draw import BatteryRing

POMODORO = 25 * 60


class Expanded(Gtk.Box):
    def __init__(self, cfg: dict, media, power, on_timer_change=None):
        super().__init__(orientation=Gtk.Orientation.VERTICAL, spacing=16)
        self.add_css_class("expanded")
        self.cfg = cfg
        self.media = media
        self.power = power
        self.on_timer_change = on_timer_change

        self._pos_timer = 0
        self._seek_dragging = False
        self._position_us = 0

        self._build_player()
        self._build_tiles()

        self.connect("map", lambda *_: self._on_map())
        self.connect("unmap", lambda *_: self._on_unmap())

    # -- media player section ---------------------------------------------

    def _build_player(self):
        self.player_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=12)

        head = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=14)
        art_frame = Gtk.Box()
        art_frame.add_css_class("art-frame")
        art_frame.set_overflow(Gtk.Overflow.HIDDEN)
        self.art = Gtk.Picture()
        self.art.set_size_request(92, 92)
        self.art.set_content_fit(Gtk.ContentFit.COVER)
        art_frame.append(self.art)

        meta = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=3)
        meta.set_valign(Gtk.Align.CENTER)
        meta.set_hexpand(True)
        self.title = _label("track-title", 24)
        self.artist = _label("track-artist", 30)
        self.album = _label("track-album", 34)
        for w in (self.title, self.artist, self.album):
            meta.append(w)

        head.append(art_frame)
        head.append(meta)

        seek_box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=8)
        self.time_now = Gtk.Label(label="0:00")
        self.time_now.add_css_class("seek-time")
        self.seek = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 1, 1)
        self.seek.add_css_class("seek")
        self.seek.set_hexpand(True)
        self.seek.set_draw_value(False)
        self.time_total = Gtk.Label(label="0:00")
        self.time_total.add_css_class("seek-time")
        seek_box.append(self.time_now)
        seek_box.append(self.seek)
        seek_box.append(self.time_total)

        drag = Gtk.GestureClick()
        drag.connect("pressed", lambda *_: setattr(self, "_seek_dragging", True))
        drag.connect("released", self._on_seek_released)
        self.seek.add_controller(drag)

        controls = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=18)
        controls.set_halign(Gtk.Align.CENTER)
        self.btn_prev = _media_btn("media-skip-backward-symbolic")
        self.btn_play = _media_btn("media-playback-start-symbolic", main=True)
        self.btn_next = _media_btn("media-skip-forward-symbolic")
        self.btn_prev.connect("clicked", lambda *_: self.media.previous())
        self.btn_play.connect("clicked", lambda *_: self.media.play_pause())
        self.btn_next.connect("clicked", lambda *_: self.media.next())
        for b in (self.btn_prev, self.btn_play, self.btn_next):
            controls.append(b)

        self.player_box.append(head)
        self.player_box.append(seek_box)
        self.player_box.append(controls)
        self.append(self.player_box)

        # idle header shown when nothing plays
        self.idle_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=2)
        self.idle_box.set_halign(Gtk.Align.CENTER)
        self.big_clock = Gtk.Label()
        self.big_clock.add_css_class("track-title")
        self.big_date = Gtk.Label()
        self.big_date.add_css_class("track-artist")
        self.idle_box.append(self.big_clock)
        self.idle_box.append(self.big_date)
        self.append(self.idle_box)

    # -- tiles -------------------------------------------------------------

    def _build_tiles(self):
        row = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        row.set_homogeneous(True)

        # battery
        self.battery_tile = _tile()
        self.ring = BatteryRing(accent=_hex_rgb(self.cfg.get("accent", "#ff9f0a")))
        self.ring.set_halign(Gtk.Align.CENTER)
        self.batt_label = Gtk.Label()
        self.batt_label.add_css_class("tile-sub")
        self.battery_tile.append(self.ring)
        self.battery_tile.append(self.batt_label)

        # volume
        vol_tile = _tile()
        vol_icon = Gtk.Image.new_from_icon_name("audio-volume-high-symbolic")
        vol_icon.set_halign(Gtk.Align.CENTER)
        self.vol = Gtk.Scale.new_with_range(Gtk.Orientation.HORIZONTAL, 0, 100, 1)
        self.vol.add_css_class("vol")
        self.vol.set_draw_value(False)
        self.vol.connect("change-value", self._on_vol_set)
        vol_label = Gtk.Label(label="Volume")
        vol_label.add_css_class("tile-sub")
        vol_tile.append(vol_icon)
        vol_tile.append(self.vol)
        vol_tile.append(vol_label)

        # pomodoro timer
        self.timer_tile = _tile()
        self.timer_label = Gtk.Label(label="25:00")
        self.timer_label.add_css_class("tile-big")
        btns = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=6)
        btns.set_halign(Gtk.Align.CENTER)
        self.timer_toggle = Gtk.Button()
        self.timer_toggle.add_css_class("tile-btn")
        self.timer_toggle.set_icon_name("media-playback-start-symbolic")
        self.timer_toggle.connect("clicked", self._on_timer_toggle)
        timer_reset = Gtk.Button()
        timer_reset.add_css_class("tile-btn")
        timer_reset.set_icon_name("view-refresh-symbolic")
        timer_reset.connect("clicked", self._on_timer_reset)
        btns.append(self.timer_toggle)
        btns.append(timer_reset)
        self.timer_tile.append(self.timer_label)
        self.timer_tile.append(btns)

        # network chip
        net_tile = _tile()
        self.net_icon = Gtk.Image.new_from_icon_name("network-wireless-symbolic")
        self.net_icon.set_halign(Gtk.Align.CENTER)
        self.net_label = Gtk.Label(label="—")
        self.net_label.add_css_class("net-chip")
        self.net_label.set_ellipsize(Pango.EllipsizeMode.END)
        self.net_label.set_max_width_chars(10)
        net_tile.append(self.net_icon)
        net_tile.append(self.net_label)

        for t in (self.battery_tile, vol_tile, self.timer_tile, net_tile):
            row.append(t)
        self.append(row)

        # timer state
        self.timer_left = POMODORO
        self.timer_running = False
        self._timer_src = 0

    # -- refresh from services --------------------------------------------

    def refresh_media(self):
        p = self.media.active
        has = p is not None
        self.player_box.set_visible(has)
        self.idle_box.set_visible(not has)
        if not has:
            self._refresh_big_clock()
            self._sync_pos_timer()
            return
        self.title.set_label(p.title or "Unknown track")
        self.artist.set_label(p.artist or "")
        self.album.set_label(p.album or "")
        self.artist.set_visible(bool(p.artist))
        self.album.set_visible(bool(p.album))
        if p.art_path:
            self.art.set_filename(p.art_path)
        else:
            self.art.set_paintable(None)
        playing = p.status == "Playing"
        self.btn_play.set_icon_name(
            "media-playback-pause-symbolic" if playing
            else "media-playback-start-symbolic")
        self.btn_prev.set_sensitive(p.can_prev)
        self.btn_next.set_sensitive(p.can_next)
        total = p.length_us
        self.seek.set_range(0, max(1, total))
        self.time_total.set_label(_fmt_us(total))
        self._sync_pos_timer()
        self._poll_position()

    def refresh_battery(self):
        if not self.power.available:
            self.battery_tile.set_visible(False)
            return
        self.ring.update(self.power.percentage, self.power.charging)
        suffix = " ⚡" if self.power.charging else ""
        self.batt_label.set_label(f"{self.power.percentage:.0f}%{suffix}")

    def refresh_volume(self, percent: int):
        self.vol.set_value(percent)

    def refresh_network(self, name: str, kind: str, connected: bool):
        if not connected:
            self.net_icon.set_from_icon_name("network-offline-symbolic")
            self.net_label.set_label("Offline")
        else:
            icon = ("network-wireless-symbolic" if "wireless" in kind
                    else "network-wired-symbolic")
            self.net_icon.set_from_icon_name(icon)
            self.net_label.set_label(name or "Connected")

    # -- seek bar ----------------------------------------------------------

    def _on_map(self):
        self.refresh_media()
        self.refresh_battery()
        self._refresh_big_clock()

    def _on_unmap(self):
        if self._pos_timer:
            GLib.source_remove(self._pos_timer)
            self._pos_timer = 0

    def _sync_pos_timer(self):
        playing = self.media.active and self.media.active.status == "Playing"
        want = bool(playing) and self.get_mapped()
        if want and not self._pos_timer:
            self._pos_timer = GLib.timeout_add_seconds(1, self._poll_position)
        elif not want and self._pos_timer:
            GLib.source_remove(self._pos_timer)
            self._pos_timer = 0

    def _poll_position(self):
        self.media.get_position(self._on_position)
        return True

    def _on_position(self, pos_us: int):
        self._position_us = pos_us
        if not self._seek_dragging:
            self.seek.set_value(pos_us)
        self.time_now.set_label(_fmt_us(pos_us))

    def _on_seek_released(self, gesture, n, x, y):
        self._seek_dragging = False
        self.media.seek_to(int(self.seek.get_value()))

    def _on_vol_set(self, scale, scroll, value):
        Gio.Subprocess.new(
            ["pactl", "set-sink-volume", "@DEFAULT_SINK@",
             f"{int(max(0, min(100, value)))}%"],
            Gio.SubprocessFlags.NONE)
        return False

    # -- pomodoro ----------------------------------------------------------

    def _on_timer_toggle(self, *_):
        self.timer_running = not self.timer_running
        if self.timer_running and not self._timer_src:
            self._timer_src = GLib.timeout_add_seconds(1, self._timer_tick)
        self._sync_timer_ui()

    def _on_timer_reset(self, *_):
        self.timer_running = False
        self.timer_left = POMODORO
        if self._timer_src:
            GLib.source_remove(self._timer_src)
            self._timer_src = 0
        self._sync_timer_ui()

    def _timer_tick(self):
        if not self.timer_running:
            self._timer_src = 0
            return False
        self.timer_left -= 1
        if self.timer_left <= 0:
            self.timer_left = 0
            self.timer_running = False
            self._timer_src = 0
            self._notify_done()
            self._sync_timer_ui()
            return False
        self._sync_timer_ui()
        return True

    def _sync_timer_ui(self):
        m, s = divmod(self.timer_left, 60)
        text = f"{m:02d}:{s:02d}"
        self.timer_label.set_label(text)
        self.timer_toggle.set_icon_name(
            "media-playback-pause-symbolic" if self.timer_running
            else "media-playback-start-symbolic")
        if self.timer_running:
            self.timer_tile.add_css_class("tile--timer-running")
        else:
            self.timer_tile.remove_css_class("tile--timer-running")
        if self.on_timer_change:
            self.on_timer_change(text if self.timer_running else None)

    def _notify_done(self):
        app = Gio.Application.get_default()
        if app:
            note = Gio.Notification.new("Focus round complete")
            note.set_body("25 minutes are up — take a break.")
            app.send_notification("pomodoro", note)

    def _refresh_big_clock(self):
        fmt = "%H:%M" if self.cfg.get("clock_24h", True) else "%I:%M %p"
        self.big_clock.set_label(time.strftime(fmt))
        self.big_date.set_label(time.strftime("%A %d %B"))


def _label(css: str, width: int) -> Gtk.Label:
    lbl = Gtk.Label(xalign=0)
    lbl.add_css_class(css)
    lbl.set_ellipsize(Pango.EllipsizeMode.END)
    lbl.set_max_width_chars(width)
    return lbl


def _media_btn(icon: str, main: bool = False) -> Gtk.Button:
    btn = Gtk.Button()
    btn.set_icon_name(icon)
    btn.add_css_class("media-btn")
    if main:
        btn.add_css_class("media-btn--main")
    return btn


def _tile() -> Gtk.Box:
    tile = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=6)
    tile.add_css_class("tile")
    tile.set_valign(Gtk.Align.CENTER)
    return tile


def _fmt_us(us: int) -> str:
    s = max(0, us // 1_000_000)
    return f"{s // 60}:{s % 60:02d}"


def _hex_rgb(hex_color: str):
    hex_color = hex_color.lstrip("#")
    return tuple(int(hex_color[i:i + 2], 16) / 255 for i in (0, 2, 4))
