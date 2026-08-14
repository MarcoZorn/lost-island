#!/usr/bin/env python3
"""Deterministic screenshot generator for the README.

Drives the real island widgets (same code, same CSS) with demo data under a
headless X server, captures each state, then masks the rounded corners and
composites onto a gradient backdrop.

Usage:  Xvfb :99 -screen 0 1600x1000x24 &
        DISPLAY=:99 GDK_BACKEND=x11 python3 scripts/render-shots.py docs/
"""

from __future__ import annotations

import math
import os
import subprocess
import sys

import cairo
import gi

gi.require_version("Gtk", "4.0")
gi.require_version("GdkX11", "4.0")
from gi.repository import Gdk, GdkX11, GLib, Gtk  # noqa: E402

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from lostisland import config  # noqa: E402
from lostisland.services.weather import WeatherService  # noqa: E402
from lostisland.ui.expanded import Expanded  # noqa: E402
from lostisland.ui.peek import Peek  # noqa: E402
from lostisland.ui.pill import Pill  # noqa: E402

OUT = sys.argv[1] if len(sys.argv) > 1 else "docs"
ART = "/tmp/lost-island-shot-art.png"
CFG = dict(config.DEFAULTS)

TRACK = dict(title="Halfway to Nowhere", artist="Night Cartography",
             album="Isole Perdute", length_us=214_000_000)


class FakePlayer:
    def __init__(self):
        self.__dict__.update(TRACK)
        self.status = "Playing"
        self.art_path = ART
        self.track_id = "/demo/1"
        self.can_next = True
        self.can_prev = True


class FakeMedia:
    active = FakePlayer()

    def get_position(self, cb):
        cb(47_000_000)

    def play_pause(self): ...
    def next(self): ...
    def previous(self): ...
    def seek_to(self, *_): ...


class FakePower:
    available = True
    percentage = 84.0
    charging = False


def paint_art():
    size = 300
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, size, size)
    cr = cairo.Context(surf)
    g = cairo.LinearGradient(0, 0, size, size)
    g.add_color_stop_rgb(0, 0.10, 0.09, 0.22)
    g.add_color_stop_rgb(0.55, 0.55, 0.16, 0.35)
    g.add_color_stop_rgb(1, 0.98, 0.55, 0.15)
    cr.set_source(g)
    cr.paint()
    cr.set_source_rgba(1, 1, 1, 0.85)
    for i in range(40):
        a = i / 40 * 2 * math.pi
        r = 40 + 60 * math.sin(i * 0.9)
        cr.arc(size / 2 + r * math.cos(a), size / 2 + r * math.sin(a),
               1.6, 0, 2 * math.pi)
        cr.fill()
    cr.set_source_rgba(0, 0, 0, 0.35)
    cr.arc(size / 2, size / 2, 52, 0, 2 * math.pi)
    cr.fill()
    surf.write_to_png(ART)


def load_css():
    provider = Gtk.CssProvider()
    css_path = os.path.join(os.path.dirname(__file__), "..",
                            "lostisland", "ui", "style.css")
    with open(css_path) as f:
        provider.load_from_string(
            f"@define-color accent {CFG['accent']};\n" + f.read())
    Gtk.StyleContext.add_provider_for_display(
        Gdk.Display.get_default(), provider,
        Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)


def rounded_mask(src: str, radius_hint: int):
    """Clip the raw capture to the island's rounded rect, in place."""
    img = cairo.ImageSurface.create_from_png(src)
    w, h = img.get_width(), img.get_height()
    r = min(radius_hint, h / 2, w / 2)
    out = cairo.ImageSurface(cairo.FORMAT_ARGB32, w, h)
    cr = cairo.Context(out)
    _rrect(cr, 0, 0, w, h, r)
    cr.clip()
    cr.set_source_surface(img, 0, 0)
    cr.paint()
    out.write_to_png(src)
    return w, h, r


def composite(src: str, dest: str, hue: float):
    img = cairo.ImageSurface.create_from_png(src)
    iw, ih = img.get_width(), img.get_height()
    pad_x, pad_top, pad_bot = 120, 60, 90
    w, h = iw + pad_x * 2, ih + pad_top + pad_bot
    canvas = cairo.ImageSurface(cairo.FORMAT_ARGB32, w, h)
    cr = cairo.Context(canvas)
    # wallpaper-ish gradient
    g = cairo.LinearGradient(0, 0, w, h)
    g.add_color_stop_rgb(0, 0.09 + hue * 0.03, 0.08, 0.16 + hue * 0.05)
    g.add_color_stop_rgb(0.5, 0.13, 0.10 + hue * 0.04, 0.22)
    g.add_color_stop_rgb(1, 0.05, 0.05, 0.10)
    cr.set_source(g)
    cr.paint()
    glow = cairo.RadialGradient(w * 0.7, h * 0.1, 10, w * 0.7, h * 0.1, w * 0.8)
    glow.add_color_stop_rgba(0, 1.0, 0.62 - hue * 0.2, 0.15, 0.10)
    glow.add_color_stop_rgba(1, 0, 0, 0, 0)
    cr.set_source(glow)
    cr.paint()
    # soft shadow
    x, y = pad_x, pad_top
    r = min(26, ih / 2)
    for i in range(14, 0, -1):
        cr.set_source_rgba(0, 0, 0, 0.028)
        _rrect(cr, x - i * 0.6, y + 6 - i * 0.4, iw + i * 1.2, ih + i * 0.8,
               r + i * 0.5)
        cr.fill()
    cr.set_source_surface(img, x, y)
    cr.paint()
    canvas.write_to_png(dest)


def _rrect(cr, x, y, w, h, r):
    cr.new_sub_path()
    cr.arc(x + w - r, y + r, r, -math.pi / 2, 0)
    cr.arc(x + w - r, y + h - r, r, 0, math.pi / 2)
    cr.arc(x + r, y + h - r, r, math.pi / 2, math.pi)
    cr.arc(x + r, y + r, r, math.pi, 3 * math.pi / 2)
    cr.close_path()


def snap_window(win, name, radius, hue):
    win.present()
    loop = GLib.MainLoop()
    GLib.timeout_add(700, loop.quit)
    loop.run()
    xid = win.get_surface().get_xid()
    raw = os.path.join(OUT, f"shot-{name}.png")
    subprocess.run(["import", "-window", str(xid), raw], check=True)
    win.close()
    rounded_mask(raw, radius)
    composite(raw, raw, hue)
    print("rendered", raw)


def snap(widget, name, radius, hue, expanded=False):
    win = Gtk.Window(decorated=False, resizable=False)
    box = Gtk.Box()
    box.add_css_class("island")
    if expanded:
        box.add_css_class("island--expanded")
    box.append(widget)
    win.set_child(box)
    snap_window(win, name, radius, hue)


def main():
    os.makedirs(OUT, exist_ok=True)
    paint_art()
    Gtk.init()
    # match the desktop the app was designed on; Xvfb defaults to a bare theme
    settings = Gtk.Settings.get_default()
    if settings:
        settings.set_property("gtk-icon-theme-name", "breeze")
    load_css()

    # idle pill
    snap(Pill(CFG), "pill", 26, 0.0)

    # music pill — title only, the artist stays in the card
    pill = Pill(CFG)
    pill.show_music("Halfway to Nowhere", ART, True)
    snap(pill, "music-pill", 26, 0.3)

    # volume peek
    peek = Peek()
    peek.show_volume(62, False)
    snap(peek, "volume", 26, 0.6)

    # notification peek
    peek2 = Peek()
    peek2.show_notification("Calendar", "Standup in 10 minutes",
                            "Team sync · Room 2", "view-calendar")
    snap(peek2, "notification", 26, 0.9)

    # expanded player, all modules on
    weather = WeatherService()
    weather.temp, weather.desc, weather.icon = (
        "24°", "Sunny", "weather-clear-symbolic")
    weather._stamp = 10 ** 12  # pretend fresh so no fetch happens
    exp = Expanded(CFG, FakeMedia(), FakePower(), weather=weather)
    exp.set_size_request(400, -1)
    exp.refresh_media()
    exp.refresh_battery()
    exp.refresh_volume(62)
    exp.refresh_network("HomeNet", "802-11-wireless", True)
    exp.refresh_bluetooth("MX Buds", True, 80)
    exp.sys_label.set_visible(True)
    exp._on_system(None, 6, 38, 5.9)
    snap(exp, "expanded", 34, 0.5, expanded=True)

    # settings window
    gi.require_version("Adw", "1")
    from gi.repository import Adw
    from lostisland.ui.settings import SettingsWindow
    Adw.init()
    Adw.StyleManager.get_default().set_color_scheme(
        Adw.ColorScheme.FORCE_DARK)
    win = SettingsWindow(dict(config.DEFAULTS), lambda *_: None)
    snap_window(win, "settings", 14, 0.2)


if __name__ == "__main__":
    main()
