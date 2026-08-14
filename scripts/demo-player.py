#!/usr/bin/env python3
"""Fake MPRIS player for development: shows up as a real media source so the
island can be exercised without opening Spotify. Ctrl+C to stop."""

import math
import sys

import cairo
import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gio, GLib

TRACK = {
    "title": "Halfway to Nowhere",
    "artist": "Night Cartography",
    "album": "Isole Perdute",
    "length": 214 * 1_000_000,
}

ART = "/tmp/lost-island-demo-art.png"

NODE = """
<node>
  <interface name="org.mpris.MediaPlayer2"/>
  <interface name="org.mpris.MediaPlayer2.Player">
    <method name="PlayPause"/>
    <method name="Next"/>
    <method name="Previous"/>
    <method name="SetPosition">
      <arg type="o" direction="in"/><arg type="x" direction="in"/>
    </method>
    <property name="PlaybackStatus" type="s" access="read"/>
    <property name="Metadata" type="a{sv}" access="read"/>
    <property name="Position" type="x" access="read"/>
    <property name="CanGoNext" type="b" access="read"/>
    <property name="CanGoPrevious" type="b" access="read"/>
  </interface>
</node>
"""


def paint_art():
    size = 300
    surf = cairo.ImageSurface(cairo.FORMAT_ARGB32, size, size)
    cr = cairo.Context(surf)
    grad = cairo.LinearGradient(0, 0, size, size)
    grad.add_color_stop_rgb(0, 0.10, 0.09, 0.22)
    grad.add_color_stop_rgb(0.55, 0.55, 0.16, 0.35)
    grad.add_color_stop_rgb(1, 0.98, 0.55, 0.15)
    cr.set_source(grad)
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


class Demo:
    def __init__(self):
        self.status = "Playing"
        self.position = 47 * 1_000_000
        paint_art()
        self.bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
        Gio.bus_own_name_on_connection(
            self.bus, "org.mpris.MediaPlayer2.lostdemo",
            Gio.BusNameOwnerFlags.NONE, None, None)
        node = Gio.DBusNodeInfo.new_for_xml(NODE)
        for iface in node.interfaces:
            self.bus.register_object(
                "/org/mpris/MediaPlayer2", iface, self.on_call,
                self.on_get, None)
        GLib.timeout_add_seconds(1, self.tick)

    def metadata(self):
        return GLib.Variant("a{sv}", {
            "mpris:trackid": GLib.Variant(
                "o", "/org/lostisland/demo/track/1"),
            "xesam:title": GLib.Variant("s", TRACK["title"]),
            "xesam:artist": GLib.Variant("as", [TRACK["artist"]]),
            "xesam:album": GLib.Variant("s", TRACK["album"]),
            "mpris:length": GLib.Variant("x", TRACK["length"]),
            "mpris:artUrl": GLib.Variant("s", "file://" + ART),
        })

    def on_get(self, conn, sender, path, iface, prop):
        return {
            "PlaybackStatus": GLib.Variant("s", self.status),
            "Metadata": self.metadata(),
            "Position": GLib.Variant("x", self.position),
            "CanGoNext": GLib.Variant("b", True),
            "CanGoPrevious": GLib.Variant("b", True),
        }.get(prop)

    def on_call(self, conn, sender, path, iface, method, params, inv):
        if method == "PlayPause":
            self.status = "Paused" if self.status == "Playing" else "Playing"
            self.emit_props({"PlaybackStatus": GLib.Variant("s", self.status)})
        elif method == "SetPosition":
            self.position = params.unpack()[1]
        inv.return_value(None)

    def emit_props(self, changed):
        self.bus.emit_signal(
            None, "/org/mpris/MediaPlayer2",
            "org.freedesktop.DBus.Properties", "PropertiesChanged",
            GLib.Variant("(sa{sv}as)",
                         ("org.mpris.MediaPlayer2.Player", changed, [])))

    def tick(self):
        if self.status == "Playing":
            self.position += 1_000_000
            if self.position >= TRACK["length"]:
                self.position = 0
        return True


if __name__ == "__main__":
    demo = Demo()
    print("demo player up — Ctrl+C to stop")
    # announce once so already-running islands pick us up immediately
    GLib.timeout_add(300, lambda: (demo.emit_props({
        "PlaybackStatus": GLib.Variant("s", demo.status),
        "Metadata": demo.metadata()}), False)[1])
    try:
        GLib.MainLoop().run()
    except KeyboardInterrupt:
        sys.exit(0)
