#!/usr/bin/env python3
"""Records the README demo GIF: the real island widgets running a scripted
timeline (idle → music → volume peek → expanded → collapse) under Xvfb,
captured with ffmpeg.

Usage:  Xvfb :99 -screen 0 760x480x24 &
        DISPLAY=:99 GDK_BACKEND=x11 python3 scripts/record-demo.py docs/demo.gif
"""

from __future__ import annotations

import math
import os
import subprocess
import sys

import cairo
import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gdk, GLib, Gtk  # noqa: E402

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from lostisland import config  # noqa: E402
from lostisland.services.weather import WeatherService  # noqa: E402
from lostisland.ui.island import Island  # noqa: E402

OUT = sys.argv[1] if len(sys.argv) > 1 else "docs/demo.gif"
ART = "/tmp/lost-island-demo-art.png"
SIZE = (760, 480)
RAW = "/tmp/lost-island-demo.mp4"


class FakePlayer:
    title = "Halfway to Nowhere"
    artist = "Night Cartography"
    album = "Isole Perdute"
    length_us = 214_000_000
    status = "Playing"
    art_path = ART
    track_id = "/demo/1"
    can_next = True
    can_prev = True


class FakeMedia:
    active = None

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
    surf.write_to_png(ART)


def load_css():
    provider = Gtk.CssProvider()
    css_path = os.path.join(os.path.dirname(__file__), "..",
                            "lostisland", "ui", "style.css")
    with open(css_path) as f:
        css = "@define-color accent #ff9f0a;\n" + f.read()
    css += """
    .demo-bg {
      background: linear-gradient(135deg, #16142a 0%, #221636 55%, #0d0d16 100%);
    }
    """
    provider.load_from_string(css)
    Gtk.StyleContext.add_provider_for_display(
        Gdk.Display.get_default(), provider,
        Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)


def main():
    paint_art()
    Gtk.init()
    settings = Gtk.Settings.get_default()
    if settings:
        settings.set_property("gtk-icon-theme-name", "breeze")
    load_css()

    cfg = dict(config.DEFAULTS)
    cfg["peek_seconds"] = 1.5

    media = FakeMedia()
    weather = WeatherService()
    weather.temp, weather.desc, weather.icon = (
        "24°", "Sunny", "weather-clear-symbolic")
    weather._stamp = 10 ** 12

    win = Gtk.Window(decorated=False)
    win.set_default_size(*SIZE)
    win.add_css_class("demo-bg")
    island = Island(cfg, media, FakePower(), weather=weather)
    island.set_margin_top(36)
    win.set_child(island)
    win.present()

    island.expanded.refresh_network("HomeNet", "802-11-wireless", True)
    island.expanded.refresh_bluetooth("MX Buds", True, 80)
    island.expanded.refresh_volume(62)
    island.expanded.sys_label.set_visible(True)
    island.expanded._on_system(None, 6, 38, 5.9)

    rec = subprocess.Popen(
        ["ffmpeg", "-y", "-loglevel", "error", "-f", "x11grab",
         "-draw_mouse", "0",
         "-video_size", f"{SIZE[0]}x{SIZE[1]}", "-framerate", "30",
         "-i", os.environ["DISPLAY"], "-t", "12", RAW])

    def music_on():
        media.active = FakePlayer()
        island.on_media_changed()
        return False

    def volume_peek():
        island.show_peek(island.peek.show_volume, 62, False)
        return False

    loop = GLib.MainLoop()
    GLib.timeout_add(1600, music_on)
    GLib.timeout_add(3600, volume_peek)
    GLib.timeout_add(6000, lambda: (island.expand(), False)[1])
    GLib.timeout_add(10200, lambda: (island.collapse(), False)[1])
    GLib.timeout_add(12300, loop.quit)
    loop.run()

    rec.wait(timeout=20)
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", RAW,
         "-vf", "fps=20,scale=700:-1:flags=lanczos,split[s0][s1];"
                "[s0]palettegen=max_colors=128[p];[s1][p]paletteuse=dither=bayer",
         "-loop", "0", OUT], check=True)
    print("recorded", OUT, os.path.getsize(OUT) // 1024, "KB")


if __name__ == "__main__":
    main()
