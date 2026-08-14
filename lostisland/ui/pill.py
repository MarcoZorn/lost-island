"""The collapsed pill — what the island looks like 99% of the time.

Faces (config `pill_face`):
  auto     clock when idle, art + title + EQ when music plays (default)
  compact  music shows just art + EQ, no title
  clock    always only the time
  battery  always only the battery

A running timer earns a small accent chip on every face. With cava
installed, the EQ bars follow the actual audio; the cava process exists
only while music plays and the bars are on screen.
"""

from __future__ import annotations

import time

from gi.repository import GLib, Gtk, Pango

from lostisland.ui.draw import EqBars, pick_icon


class Pill(Gtk.Box):
    def __init__(self, cfg: dict, cava=None):
        super().__init__(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        self.add_css_class("pill")
        self.cfg = cfg
        self.cava = cava
        self._minute_timer = 0
        self._music = None  # (title, art_path, playing) while music is active
        self._batt = (-1.0, False)

        # music side
        self.art = Gtk.Image()
        self.art.set_pixel_size(20)
        self.art.add_css_class("pill-art")
        self.title = Gtk.Label()
        self.title.add_css_class("pill-title")
        self.title.set_ellipsize(Pango.EllipsizeMode.END)
        self.title.set_max_width_chars(32)
        self.eq = EqBars()
        self.eq.connect("map", lambda *_: self._sync_cava())
        self.eq.connect("unmap", lambda *_: self._sync_cava())
        if self.cava is not None:
            self.cava.connect("levels", lambda _s, lv: self.eq.feed(lv))

        # idle side
        self.dot = Gtk.Label(label="●")
        self.dot.add_css_class("pill-dot")
        self.clock = Gtk.Label()
        self.clock.add_css_class("pill-clock")

        # timer chip (shared)
        self.timer_chip = Gtk.Label()
        self.timer_chip.add_css_class("timer-chip")
        self.timer_chip.set_visible(False)

        # small battery readout
        self.batt_icon = Gtk.Image()
        self.batt_icon.set_pixel_size(13)
        self.batt_icon.add_css_class("pill-batt")
        self.batt_label = Gtk.Label()
        self.batt_label.add_css_class("pill-batt")
        self.batt_icon.set_visible(False)
        self.batt_label.set_visible(False)

        for widget in (self.art, self.title, self.eq, self.dot, self.clock,
                       self.timer_chip, self.batt_icon, self.batt_label):
            self.append(widget)

        self.connect("map", lambda *_: self._start_clock())
        self.connect("unmap", lambda *_: (self._stop_clock(),
                                          self._sync_cava()))
        self.show_idle()

    @property
    def face(self) -> str:
        return self.cfg.get("pill_face", "auto")

    # -- faces -------------------------------------------------------------

    def show_idle(self):
        self._music = None
        self.art.set_visible(False)
        self.title.set_visible(False)
        self.eq.set_visible(False)
        self.eq.set_playing(False)
        self._sync_cava()

        if self.face == "battery" and self._batt[0] >= 0:
            self.dot.set_visible(False)
            self.clock.set_visible(False)
            self._render_battery(force=True)
            return
        show_clock = self.cfg.get("idle_clock", True)
        self.dot.set_visible(show_clock)
        self.clock.set_visible(show_clock)
        self._refresh_clock()
        self._render_battery()

    def show_music(self, title: str, art_path: str, playing: bool):
        self._music = (title, art_path, playing)
        if self.face in ("clock", "battery"):
            # face pinned by the user: ignore the music takeover
            self.show_idle_face_only()
            return
        self.dot.set_visible(False)
        self.clock.set_visible(False)
        self.title.set_label(title or "…")
        self.title.set_visible(self.face != "compact")
        if art_path:
            self.art.set_from_file(art_path)
            self.art.set_visible(True)
        else:
            self.art.set_visible(False)
        self.eq.set_visible(True)
        self.eq.set_playing(playing)
        self._render_battery()
        self._sync_cava()

    def show_idle_face_only(self):
        """Render the pinned clock/battery face while music state exists."""
        music = self._music
        self.show_idle()
        self._music = music

    def show_timer_chip(self, text: str | None):
        self.timer_chip.set_visible(text is not None)
        if text is not None:
            self.timer_chip.set_label(text)

    # -- battery -----------------------------------------------------------

    def show_battery(self, percent: float, charging: bool):
        self._batt = (percent, charging)
        if self.face == "battery" and self._music is None:
            self.show_idle()
        else:
            self._render_battery()

    def _render_battery(self, force: bool = False):
        percent, charging = self._batt
        show = force or (self.cfg.get("pill_battery", True) and percent >= 0
                         and (charging or percent <= 30))
        self.batt_icon.set_visible(show)
        self.batt_label.set_visible(show)
        if show:
            if charging:
                icon = pick_icon(self, "battery-charging-symbolic",
                                 "battery-full-charging-symbolic",
                                 "battery-good-charging-symbolic",
                                 "battery-symbolic")
            elif percent <= 30:
                icon = pick_icon(self, "battery-low-symbolic",
                                 "battery-caution-symbolic",
                                 "battery-empty-symbolic",
                                 "battery-symbolic")
            else:
                icon = pick_icon(self, "battery-good-symbolic",
                                 "battery-symbolic")
            self.batt_icon.set_from_icon_name(icon)
            self.batt_label.set_label(f"{percent:.0f}%")

    # -- cava --------------------------------------------------------------

    def _sync_cava(self):
        if self.cava is None or not self.cava.available:
            return
        playing = bool(self._music and self._music[2])
        want = playing and self.eq.get_mapped()
        self.eq.set_external(want)
        if want:
            self.cava.start()
        else:
            self.cava.stop()

    # -- clock, ticking once per minute, aligned to :00 --------------------

    def _start_clock(self):
        self._refresh_clock()
        self._arm_minute()

    def _stop_clock(self):
        if self._minute_timer:
            GLib.source_remove(self._minute_timer)
            self._minute_timer = 0

    def _arm_minute(self):
        self._stop_clock()
        delay = 60 - time.localtime().tm_sec
        self._minute_timer = GLib.timeout_add_seconds(delay, self._on_minute)

    def _on_minute(self):
        self._refresh_clock()
        self._arm_minute()
        return False

    def _refresh_clock(self):
        fmt = "%H:%M" if self.cfg.get("clock_24h", True) else "%I:%M"
        self.clock.set_label(time.strftime(fmt))
