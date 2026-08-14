"""Hand-drawn cairo widgets: the equalizer bars and the battery ring.

Both are strictly demand-driven: the EQ animates on a 80 ms timer only while
music is actually playing *and* the widget is mapped; the ring redraws only
when UPower reports a change.
"""

from __future__ import annotations

import math

from gi.repository import GLib, Gtk


class EqBars(Gtk.DrawingArea):
    """Five bouncing bars, the universal 'something is playing' glyph.

    With cava feeding real spectrum levels through `feed()`, the bars follow
    the actual music; without it they fall back to a gentle sine animation.
    """

    BAR_W = 3.0
    GAP = 2.5
    N = 5

    def __init__(self, color=(1.0, 1.0, 1.0)):
        super().__init__()
        self.color = color
        self._phase = 0.0
        self._playing = False
        self._timer = 0
        self._levels: list[float] | None = None
        self._external = False
        width = int(self.N * self.BAR_W + (self.N - 1) * self.GAP)
        self.set_content_width(width)
        self.set_content_height(14)
        self.set_draw_func(self._draw)
        self.connect("map", lambda *_: self._sync_timer())
        self.connect("unmap", lambda *_: self._sync_timer())

    def set_playing(self, playing: bool):
        self._playing = playing
        self._sync_timer()
        self.queue_draw()

    def set_external(self, external: bool):
        """True while a live level source (cava) is driving the bars."""
        self._external = external
        self._levels = None
        self._sync_timer()

    def feed(self, levels: list[float]):
        self._levels = levels
        self.queue_draw()

    def _sync_timer(self):
        want = self._playing and self.get_mapped() and not self._external
        if want and not self._timer:
            self._timer = GLib.timeout_add(80, self._tick)
        elif not want and self._timer:
            GLib.source_remove(self._timer)
            self._timer = 0

    def _tick(self):
        self._phase += 0.55
        self.queue_draw()
        return True

    def _draw(self, area, cr, w, h):
        cr.set_source_rgb(*self.color)
        for i in range(self.N):
            if self._playing and self._external and self._levels:
                frac = 0.15 + 0.85 * self._levels[i]
            elif self._playing:
                frac = 0.35 + 0.65 * abs(math.sin(self._phase + i * 1.1))
            else:
                frac = 0.25
            bh = h * frac
            x = i * (self.BAR_W + self.GAP)
            y = (h - bh) / 2
            _rounded_rect(cr, x, y, self.BAR_W, bh, self.BAR_W / 2)
            cr.fill()


class BatteryRing(Gtk.DrawingArea):
    """Charge level as a ring, accent-colored when charging."""

    def __init__(self, accent=(1.0, 0.62, 0.04)):
        super().__init__()
        self.accent = accent
        self.percent = 0.0
        self.charging = False
        self.set_content_width(40)
        self.set_content_height(40)
        self.set_draw_func(self._draw)

    def update(self, percent: float, charging: bool):
        self.percent = percent
        self.charging = charging
        self.queue_draw()

    def _draw(self, area, cr, w, h):
        cx, cy = w / 2, h / 2
        radius = min(w, h) / 2 - 3
        cr.set_line_width(3.5)
        cr.set_line_cap(1)  # ROUND
        # track
        cr.set_source_rgba(1, 1, 1, 0.13)
        cr.arc(cx, cy, radius, 0, 2 * math.pi)
        cr.stroke()
        # level, from 12 o'clock
        if self.charging:
            cr.set_source_rgb(*self.accent)
        elif self.percent <= 20:
            cr.set_source_rgb(1.0, 0.27, 0.23)
        else:
            cr.set_source_rgb(0.20, 0.84, 0.29)
        start = -math.pi / 2
        cr.arc(cx, cy, radius, start, start + 2 * math.pi * max(0.01, self.percent / 100))
        cr.stroke()


def pick_icon(widget, *names: str) -> str:
    """First icon name the current theme actually ships (breeze and adwaita
    disagree on several battery/bluetooth names)."""
    theme = Gtk.IconTheme.get_for_display(widget.get_display())
    for name in names:
        if theme.has_icon(name):
            return name
    return names[-1]


def _rounded_rect(cr, x, y, w, h, r):
    r = min(r, w / 2, h / 2)
    cr.new_sub_path()
    cr.arc(x + w - r, y + r, r, -math.pi / 2, 0)
    cr.arc(x + w - r, y + h - r, r, 0, math.pi / 2)
    cr.arc(x + r, y + h - r, r, math.pi / 2, math.pi)
    cr.arc(x + r, y + r, r, math.pi, 3 * math.pi / 2)
    cr.close_path()
