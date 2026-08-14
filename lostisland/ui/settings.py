"""Preferences window. Every change applies and persists instantly;
structural changes (modules) rebuild the island in place."""

from __future__ import annotations

from gi.repository import Adw, Gdk, Gtk

from lostisland import config

MODULES = [
    ("music", "Music", "MPRIS players: pill face, full controls, album art"),
    ("volume_osd", "Volume OSD", "Peek on volume changes"),
    ("battery", "Battery", "Charge ring and plug/unplug peeks"),
    ("notifications", "Notifications", "Mirror notifications as peeks"),
    ("network", "Network", "Connectivity chip and peeks"),
    ("bluetooth", "Bluetooth", "Connected device chip and peeks"),
    ("weather", "Weather", "Weather tile in the expanded card"),
    ("system", "System stats", "CPU and RAM readout while expanded"),
    ("toggles", "Quick toggles", "Mute, mic, caffeine, screenshot"),
]


class SettingsWindow(Adw.PreferencesWindow):
    def __init__(self, cfg: dict, on_apply):
        super().__init__(title="Lost Island")
        self.set_default_size(420, 620)
        self.set_search_enabled(False)
        self.cfg = cfg
        self.on_apply = on_apply

        self.add(self._island_page())
        self.add(self._modules_page())

    # -- helpers -----------------------------------------------------------

    def _apply(self, structural: bool = False):
        config.save(self.cfg)
        self.on_apply(structural)

    def _switch(self, title: str, subtitle: str, key: str,
                structural: bool = False, mod: bool = False):
        row = Adw.SwitchRow(title=title, subtitle=subtitle)
        row.set_active(self.cfg["modules"][key] if mod else self.cfg[key])
        def on_change(r, _p):
            if mod:
                self.cfg["modules"][key] = r.get_active()
            else:
                self.cfg[key] = r.get_active()
            self._apply(structural)
        row.connect("notify::active", on_change)
        return row

    # -- pages -------------------------------------------------------------

    def _island_page(self):
        page = Adw.PreferencesPage(title="Island", icon_name="go-home-symbolic")

        pos = Adw.PreferencesGroup(title="Position")
        pos.add(self._switch(
            "Sit on top of panels",
            "Overlap the bar at the screen edge instead of sitting below it",
            "overlap_panel"))

        margin = Adw.SpinRow.new_with_range(0, 80, 1)
        margin.set_title("Top margin")
        margin.set_subtitle("Distance from the screen edge, px")
        margin.set_value(self.cfg["margin_top"])
        margin.connect("notify::value", lambda r, _p: (
            self.cfg.__setitem__("margin_top", int(r.get_value())),
            self._apply()))
        pos.add(margin)

        monitors = ["Primary"]
        display = Gdk.Display.get_default()
        if display:
            items = display.get_monitors()
            monitors += [items.get_item(i).get_connector() or f"#{i}"
                         for i in range(items.get_n_items())]
        combo = Adw.ComboRow(title="Monitor")
        combo.set_model(Gtk.StringList.new(monitors))
        try:
            combo.set_selected(monitors.index(self.cfg["monitor"]))
        except ValueError:
            combo.set_selected(0)
        def on_monitor(r, _p):
            i = r.get_selected()
            self.cfg["monitor"] = "" if i == 0 else monitors[i]
            self._apply()
        combo.connect("notify::selected", on_monitor)
        pos.add(combo)

        overlay = Adw.SwitchRow(
            title="Above fullscreen apps",
            subtitle="Keep the island visible over games and videos")
        overlay.set_active(self.cfg["layer"] == "overlay")
        overlay.connect("notify::active", lambda r, _p: (
            self.cfg.__setitem__("layer",
                                 "overlay" if r.get_active() else "top"),
            self._apply()))
        pos.add(overlay)
        page.add(pos)

        look = Adw.PreferencesGroup(title="Look")
        faces = [("auto", "Automatic"), ("compact", "Music icon only"),
                 ("clock", "Clock only"), ("battery", "Battery only")]
        face = Adw.ComboRow(title="Pill face",
                            subtitle="What the collapsed island shows")
        face.set_model(Gtk.StringList.new([f[1] for f in faces]))
        keys = [f[0] for f in faces]
        try:
            face.set_selected(keys.index(self.cfg.get("pill_face", "auto")))
        except ValueError:
            face.set_selected(0)
        face.connect("notify::selected", lambda r, _p: (
            self.cfg.__setitem__("pill_face", keys[r.get_selected()]),
            self._apply()))
        look.add(face)
        color = Adw.ActionRow(title="Accent color")
        btn = Gtk.ColorDialogButton()
        btn.set_dialog(Gtk.ColorDialog(with_alpha=False))
        rgba = Gdk.RGBA()
        rgba.parse(self.cfg["accent"])
        btn.set_rgba(rgba)
        btn.set_valign(Gtk.Align.CENTER)
        def on_color(b, _p):
            c = b.get_rgba()
            self.cfg["accent"] = "#%02x%02x%02x" % (
                int(c.red * 255), int(c.green * 255), int(c.blue * 255))
            self._apply()
        btn.connect("notify::rgba", on_color)
        color.add_suffix(btn)
        look.add(color)
        look.add(self._switch("24-hour clock", "", "clock_24h"))
        look.add(self._switch("Clock in the idle pill", "", "idle_clock"))
        look.add(self._switch("Battery in the pill",
                              "Shown while charging or below 30%",
                              "pill_battery"))
        page.add(look)

        behavior = Adw.PreferencesGroup(title="Behavior")
        peek = Adw.SpinRow.new_with_range(0.8, 6.0, 0.2)
        peek.set_digits(1)
        peek.set_title("Peek duration")
        peek.set_subtitle("Seconds a volume/notification peek stays up")
        peek.set_value(self.cfg["peek_seconds"])
        peek.connect("notify::value", lambda r, _p: (
            self.cfg.__setitem__("peek_seconds", round(r.get_value(), 1)),
            self._apply()))
        behavior.add(peek)
        page.add(behavior)
        return page

    def _modules_page(self):
        page = Adw.PreferencesPage(title="Modules",
                                   icon_name="view-grid-symbolic")
        group = Adw.PreferencesGroup(
            title="Modules",
            description="Turning a module off removes its widgets, peeks "
                        "and background listeners entirely")
        for key, title, subtitle in MODULES:
            group.add(self._switch(title, subtitle, key,
                                   structural=True, mod=True))
        page.add(group)

        weather = Adw.PreferencesGroup(title="Weather")
        city = Adw.EntryRow(title="Location (empty = automatic)")
        city.set_text(self.cfg["weather_city"])
        city.connect("apply", lambda r: (
            self.cfg.__setitem__("weather_city", r.get_text().strip()),
            self._apply(structural=True)))
        city.set_show_apply_button(True)
        weather.add(city)
        page.add(weather)
        return page
