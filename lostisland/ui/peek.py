"""The peek capsule — a short-lived widening of the pill for one event:
volume change, charger plug, a notification, a network switch."""

from __future__ import annotations

from gi.repository import Gtk, Pango


class Peek(Gtk.Box):
    def __init__(self):
        super().__init__(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        self.add_css_class("peek")
        self.set_valign(Gtk.Align.CENTER)

        self.icon = Gtk.Image()
        self.icon.set_pixel_size(20)
        self.icon.add_css_class("peek-icon")

        text_box = Gtk.Box(orientation=Gtk.Orientation.VERTICAL, spacing=1)
        text_box.set_valign(Gtk.Align.CENTER)
        self.title = Gtk.Label(xalign=0)
        self.title.add_css_class("peek-title")
        self.title.set_ellipsize(Pango.EllipsizeMode.END)
        self.title.set_max_width_chars(30)
        self.body = Gtk.Label(xalign=0)
        self.body.add_css_class("peek-body")
        self.body.set_ellipsize(Pango.EllipsizeMode.END)
        self.body.set_max_width_chars(38)
        text_box.append(self.title)
        text_box.append(self.body)

        self.level = Gtk.LevelBar()
        self.level.set_min_value(0)
        self.level.set_max_value(100)
        self.level.set_valign(Gtk.Align.CENTER)
        # kill the default warning/error color breakpoints
        self.level.remove_offset_value("low")
        self.level.remove_offset_value("high")
        self.level.remove_offset_value("full")

        self.append(self.icon)
        self.append(text_box)
        self.append(self.level)

    def _set(self, icon: str, title: str, body: str = "", level: float | None = None):
        self.icon.set_from_icon_name(icon)
        self.title.set_label(title)
        self.body.set_label(body)
        self.body.set_visible(bool(body))
        self.level.set_visible(level is not None)
        if level is not None:
            self.level.set_value(min(100, level))

    def show_volume(self, percent: int, muted: bool):
        if muted:
            self._set("audio-volume-muted-symbolic", "Muted", level=0)
        else:
            icon = ("audio-volume-low-symbolic" if percent < 34 else
                    "audio-volume-medium-symbolic" if percent < 67 else
                    "audio-volume-high-symbolic")
            self._set(icon, f"{percent}%", level=percent)

    def show_battery(self, percent: float, plugged: bool):
        if plugged:
            self._set("battery-charging-symbolic", "Charging",
                      f"{percent:.0f}%")
        else:
            self._set("battery-discharging-symbolic", "On battery",
                      f"{percent:.0f}%")

    def show_notification(self, app: str, summary: str, body: str, icon: str):
        theme = Gtk.IconTheme.get_for_display(self.get_display())
        name = "dialog-information-symbolic"
        for candidate in (icon, app.lower().replace(" ", "-")):
            if candidate and theme.has_icon(candidate):
                name = candidate
                break
        self._set(name, summary or app, body)

    def show_network(self, name: str, kind: str, connected: bool):
        if not connected:
            self._set("network-offline-symbolic", "Disconnected")
        elif "wireless" in kind:
            self._set("network-wireless-symbolic", name, "Wi-Fi")
        else:
            self._set("network-wired-symbolic", name, "Ethernet")
