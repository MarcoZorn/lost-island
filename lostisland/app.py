"""Application entry point: layer-shell window, service wiring, CLI."""

from __future__ import annotations

import ctypes
import os
import sys

# gtk4-layer-shell must be resolved before libwayland-client, which GTK pulls
# in on display init — preload it globally or the surface won't anchor.
try:
    ctypes.CDLL("libgtk4-layer-shell.so.0", mode=ctypes.RTLD_GLOBAL)
except OSError:
    pass

import gi

gi.require_version("Gtk", "4.0")
gi.require_version("Adw", "1")

from gi.repository import Adw, Gdk, Gio, GLib, Gtk  # noqa: E402

from lostisland import APP_ID, __version__, config  # noqa: E402
from lostisland.services.audio import AudioService  # noqa: E402
from lostisland.services.bluetooth import BluetoothService  # noqa: E402
from lostisland.services.cava import CavaService  # noqa: E402
from lostisland.services.mpris import MprisService  # noqa: E402
from lostisland.services.network import NetworkService  # noqa: E402
from lostisland.services.notify import NotifyService  # noqa: E402
from lostisland.services.power import PowerService  # noqa: E402
from lostisland.services.system import SystemService  # noqa: E402
from lostisland.services.weather import WeatherService  # noqa: E402
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
        self.settings_win = None
        self._css_provider: Gtk.CssProvider | None = None
        self._layer_shell = None
        for opt, short, desc in (
            ("toggle", "t", "Expand/collapse the island"),
            ("settings", "s", "Open the settings window"),
            ("quit", "q", "Quit a running instance"),
            ("version", "v", "Print version"),
        ):
            self.add_main_option(opt, ord(short), GLib.OptionFlags.NONE,
                                 GLib.OptionArg.NONE, desc, None)

    def do_command_line(self, cmdline):
        opts = cmdline.get_options_dict()
        if opts.contains("version"):
            cmdline.print_literal(f"lost-island {__version__}\n")
            return 0
        if opts.contains("quit"):
            self.quit()
            return 0
        self.do_activate()
        if opts.contains("toggle") and self.island:
            self.island.toggle()
        if opts.contains("settings"):
            self.open_settings()
        return 0

    def do_activate(self):
        if self.win:
            return
        self.hold()  # stay alive across island rebuilds
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
            self._layer_shell = LayerShell
        except (ValueError, ImportError):
            print("lost-island: gtk4-layer-shell not found — "
                  "falling back to a normal window", file=sys.stderr)
            self._layer_shell = None

        if self._layer_shell:
            self._layer_shell.init_for_window(self.win)
            self._layer_shell.set_namespace(self.win, "lost-island")
            self._apply_layer()

        self.island = Island(self.cfg, self.media, self.power,
                             weather=self.weather, system=self.system,
                             on_settings=self.open_settings, cava=self.cava)
        self._wire_services()
        self.win.set_child(self.island)
        self.win.present()

    def _apply_layer(self):
        ls, win = self._layer_shell, self.win
        layer = (ls.Layer.OVERLAY if self.cfg.get("layer") == "overlay"
                 else ls.Layer.TOP)
        ls.set_layer(win, layer)
        ls.set_anchor(win, ls.Edge.TOP, True)
        ls.set_margin(win, ls.Edge.TOP, int(self.cfg.get("margin_top", 0)))
        # -1 ignores other surfaces' exclusive zones, so the island sits on
        # the panel instead of being pushed below it
        ls.set_exclusive_zone(
            win, -1 if self.cfg.get("overlap_panel", True) else 0)
        self._pick_monitor()

    def _pick_monitor(self):
        want = self.cfg.get("monitor", "")
        display = Gdk.Display.get_default()
        monitors = display.get_monitors()
        chosen = None
        if want:
            for i in range(monitors.get_n_items()):
                mon = monitors.get_item(i)
                if mon.get_connector() == want:
                    chosen = mon
                    break
        if chosen is not None:
            self._layer_shell.set_monitor(self.win, chosen)

    def _load_css(self):
        display = Gdk.Display.get_default()
        if self._css_provider:
            Gtk.StyleContext.remove_provider_for_display(
                display, self._css_provider)
        provider = Gtk.CssProvider()
        accent = self.cfg.get("accent", "#ff9f0a")
        with open(CSS_PATH) as f:
            css = f"@define-color accent {accent};\n" + f.read()
        provider.load_from_string(css)
        Gtk.StyleContext.add_provider_for_display(
            display, provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION)
        self._css_provider = provider

    # -- settings ----------------------------------------------------------

    def open_settings(self):
        from lostisland.ui.settings import SettingsWindow
        if self.settings_win:
            self.settings_win.present()
            return
        self.settings_win = SettingsWindow(self.cfg, self.apply_config)
        self.settings_win.connect(
            "close-request",
            lambda *_: (setattr(self, "settings_win", None), False)[1])
        self.settings_win.present()

    def apply_config(self, structural: bool = False):
        """Called by the settings window after each change."""
        if structural:
            self.rebuild()
            return
        self._load_css()
        if self._layer_shell and self.win:
            self._apply_layer()
        if self.island:
            self.island.on_media_changed()
            self.island.expanded.refresh_battery()
            self.island.pill.show_battery(
                self.power.percentage, self.power.charging)

    def rebuild(self):
        if self.cava:
            self.cava.stop()
        if self.win:
            self.win.destroy()
        self.win = self.island = None
        self._build_services()
        self._build_window()

    # -- services ----------------------------------------------------------

    def _build_services(self):
        mods = self.cfg.get("modules", {})
        self.media = MprisService() if mods.get("music", True) else _NullMedia()
        self.cava = CavaService() if mods.get("music", True) else None
        self.power = PowerService()
        self.audio = AudioService() if mods.get("volume_osd", True) else None
        self.notifier = NotifyService() if mods.get("notifications", True) else None
        self.network = NetworkService() if mods.get("network", True) else None
        self.bluetooth = (BluetoothService()
                          if mods.get("bluetooth", True) else None)
        self.weather = (WeatherService(self.cfg.get("weather_city", ""))
                        if mods.get("weather", True) else None)
        self.system = SystemService() if mods.get("system", True) else None

    def _wire_services(self):
        isl = self.island
        if isinstance(self.media, MprisService):
            self.media.connect("changed", lambda *_: isl.on_media_changed())
            self.media.connect("art-ready", lambda *_: isl.on_media_changed())
        self.power.connect("changed", lambda *_: (
            isl.expanded.refresh_battery(),
            isl.pill.show_battery(self.power.percentage, self.power.charging)))
        if self.cfg.get("modules", {}).get("battery", True):
            self.power.connect(
                "plug-event",
                lambda _s, plugged: isl.show_peek(
                    isl.peek.show_battery, self.power.percentage, plugged))
        if self.audio:
            self.audio.connect(
                "volume",
                lambda _s, p, m: (isl.show_peek(isl.peek.show_volume, p, m),
                                  isl.expanded.refresh_volume(p, m)))
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
            isl.expanded.refresh_network(
                self.network.name, self.network.kind, self.network.connected)
        if self.bluetooth:
            self.bluetooth.connect(
                "changed",
                lambda _s, dev, up, batt: (
                    isl.show_peek(isl.peek.show_bluetooth, dev, up),
                    isl.expanded.refresh_bluetooth(dev, up, batt)))
            isl.expanded.refresh_bluetooth(
                self.bluetooth.device, self.bluetooth.connected,
                self.bluetooth.battery)
        else:
            isl.expanded.refresh_bluetooth("", False, -1)


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
