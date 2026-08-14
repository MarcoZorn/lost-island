"""Application entry point: layer-shell window, service wiring, CLI."""

from __future__ import annotations

import os
import sys

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gdk, Gio, GLib, Gtk  # noqa: E402

from lostisland import APP_ID, __version__, config  # noqa: E402
from lostisland.services.audio import AudioService  # noqa: E402
from lostisland.services.mpris import MprisService  # noqa: E402
from lostisland.services.network import NetworkService  # noqa: E402
from lostisland.services.notify import NotifyService  # noqa: E402
from lostisland.services.power import PowerService  # noqa: E402
from lostisland.ui.island import Island  # noqa: E402

CSS_PATH = os.path.join(os.path.dirname(__file__), "ui", "style.css")


class LostIsland(Adw.Application):
    def __init__(self):
        super().__init__(
            application_id=APP_ID,
            flags=Gio.ApplicationFlags.HANDLES_COMMAND_LINE,
        )
        self.win: Gtk.Window | None = None
        self.island: Island | None = None
        self.add_main_option("toggle", ord("t"), GLib.OptionFlags.NONE,
                             GLib.OptionArg.NONE, "Expand/collapse the island", None)
        self.add_main_option("quit", ord("q"), GLib.OptionFlags.NONE,
                             GLib.OptionArg.NONE, "Quit a running instance", None)
        self.add_main_option("version", ord("v"), GLib.OptionFlags.NONE,
                             GLib.OptionArg.NONE, "Print version", None)

    def do_command_line(self, cmdline):
        opts = cmdline.get_options_dict()
        if opts.contains("version"):
            cmdline.print_literal(f"lost-island {__version__}\n")
            return 0
        if opts.contains("quit"):
            self.quit()
            return 0
        if opts.contains("toggle"):
            if self.island:
                self.island.toggle()
            return 0
        self.do_activate()
        return 0

    def do_activate(self):
        if self.win:
            return
        self.cfg = config.load()
        config.write_default()
        self._load_css()
        self._build_services()
        self._build_window()

    # -- window ------------------------------------------------------------

    def _build_window(self):
        self.win = Gtk.Window(application=self)
        self.win.set_decorated(False)
        self.win.set_resizable(False)

        try:
            gi.require_version("Gtk4LayerShell", "1.0")
            from gi.repository import Gtk4LayerShell as LayerShell
        except (ValueError, ImportError):
            print("lost-island: gtk4-layer-shell not found — "
                  "falling back to a normal window", file=sys.stderr)
            LayerShell = None

        if LayerShell:
            LayerShell.init_for_window(self.win)
            LayerShell.set_namespace(self.win, "lost-island")
            layer = (LayerShell.Layer.OVERLAY
                     if self.cfg.get("layer") == "overlay"
                     else LayerShell.Layer.TOP)
            LayerShell.set_layer(self.win, layer)
            LayerShell.set_anchor(self.win, LayerShell.Edge.TOP, True)
            LayerShell.set_margin(self.win, LayerShell.Edge.TOP,
                                  int(self.cfg.get("margin_top", 6)))
            self._pick_monitor(LayerShell)

        self.island = Island(self.cfg, self.media, self.power)
        self._wire_services()
        self.win.set_child(self.island)
        self.win.present()

    def _pick_monitor(self, LayerShell):
        want = self.cfg.get("monitor", "")
        if not want:
            return
        display = Gdk.Display.get_default()
        monitors = display.get_monitors()
        for i in range(monitors.get_n_items()):
            mon = monitors.get_item(i)
            if mon.get_connector() == want:
                LayerShell.set_monitor(self.win, mon)
                return

    def _load_css(self):
        provider = Gtk.CssProvider()
        accent = self.cfg.get("accent", "#ff9f0a")
        with open(CSS_PATH) as f:
            css = f"@define-color accent {accent};\n" + f.read()
        provider.load_from_string(css)
        Gtk.StyleContext.add_provider_for_display(
            Gdk.Display.get_default(), provider,
            Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)

    # -- services ----------------------------------------------------------

    def _build_services(self):
        mods = self.cfg.get("modules", {})
        self.media = MprisService() if mods.get("music", True) else _NullMedia()
        self.power = PowerService()
        self.audio = AudioService() if mods.get("volume_osd", True) else None
        self.notifier = NotifyService() if mods.get("notifications", True) else None
        self.network = NetworkService() if mods.get("network", True) else None

    def _wire_services(self):
        isl = self.island
        if isinstance(self.media, MprisService):
            self.media.connect("changed", lambda *_: isl.on_media_changed())
            self.media.connect("art-ready", lambda *_: isl.on_media_changed())
        self.power.connect(
            "changed", lambda *_: isl.expanded.refresh_battery())
        if self.cfg.get("modules", {}).get("battery", True):
            self.power.connect(
                "plug-event",
                lambda _s, plugged: isl.show_peek(
                    isl.peek.show_battery, self.power.percentage, plugged))
        if self.audio:
            self.audio.connect(
                "volume",
                lambda _s, p, m: (isl.show_peek(isl.peek.show_volume, p, m),
                                  isl.expanded.refresh_volume(p)))
        if self.notifier:
            self.notifier.connect(
                "notified",
                lambda _s, app, summary, body, icon: isl.show_peek(
                    isl.peek.show_notification, app, summary, body, icon))
        if self.network:
            self.network.connect(
                "changed",
                lambda _s, name, kind, up: (
                    isl.show_peek(isl.peek.show_network, name, kind, up),
                    isl.expanded.refresh_network(name, kind, up)))


class _NullMedia:
    """Stand-in when the music module is disabled."""
    active = None

    def connect(self, *a): ...
    def play_pause(self): ...
    def next(self): ...
    def previous(self): ...
    def seek_to(self, *a): ...
    def get_position(self, *a): ...


def main() -> int:
    app = LostIsland()
    return app.run(sys.argv)


if __name__ == "__main__":
    raise SystemExit(main())
