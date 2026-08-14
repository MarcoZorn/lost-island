"""The collapsed pill — what the island looks like 99% of the time.

Idle: a clock. Music: art thumbnail, scrolling-free ellipsized title and the
EQ bars. A running timer earns a small accent chip either way.
"""

from __future__ import annotations

import time

from gi.repository import GLib, Gtk, Pango

from lostisland.ui.draw import EqBars


class Pill(Gtk.Box):
    def __init__(self, cfg: dict):
        super().__init__(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        self.add_css_class("pill")
        self.cfg = cfg
        self._minute_timer = 0

        # music side
        self.art = Gtk.Image()
        self.art.set_pixel_size(20)
        self.art.add_css_class("pill-art")
        self.title = Gtk.Label()
        self.title.add_css_class("pill-title")
        self.title.set_ellipsize(Pango.EllipsizeMode.END)
        self.title.set_max_width_chars(24)
        self.eq = EqBars()

        # idle side
        self.dot = Gtk.Label(label="●")
        self.dot.add_css_class("pill-dot")
        self.clock = Gtk.Label()
        self.clock.add_css_class("pill-clock")

        # timer chip (shared)
        self.timer_chip = Gtk.Label()
        self.timer_chip.add_css_class("timer-chip")
        self.timer_chip.set_visible(False)

        for widget in (self.art, self.title, self.eq, self.dot, self.clock,
                       self.timer_chip):
            self.append(widget)

        self.connect("map", lambda *_: self._start_clock())
        self.connect("unmap", lambda *_: self._stop_clock())
        self.show_idle()

    # -- faces -------------------------------------------------------------

    def show_idle(self):
        show_clock = self.cfg.get("idle_clock", True)
        self.art.set_visible(False)
        self.title.set_visible(False)
        self.eq.set_visible(False)
        self.eq.set_playing(False)
        self.dot.set_visible(show_clock)
        self.clock.set_visible(show_clock)
        self._refresh_clock()

    def show_music(self, title: str, art_path: str, playing: bool):
        self.dot.set_visible(False)
        self.clock.set_visible(False)
        self.title.set_label(title or "…")
        self.title.set_visible(True)
        if art_path:
            self.art.set_from_file(art_path)
            self.art.set_visible(True)
        else:
            self.art.set_visible(False)
        self.eq.set_visible(True)
        self.eq.set_playing(playing)

    def show_timer_chip(self, text: str | None):
        self.timer_chip.set_visible(text is not None)
        if text is not None:
            self.timer_chip.set_label(text)

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
