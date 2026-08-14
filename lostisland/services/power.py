"""Battery state via UPower's DisplayDevice — pure signal driven, zero polling."""

from __future__ import annotations

from gi.repository import Gio, GLib, GObject

UPOWER = "org.freedesktop.UPower"
DEVICE = "/org/freedesktop/UPower/devices/DisplayDevice"
DEVICE_IFACE = "org.freedesktop.UPower.Device"

# UPower state codes
CHARGING = 1
DISCHARGING = 2
FULL = 4


class PowerService(GObject.Object):
    __gsignals__ = {
        "changed": (GObject.SignalFlags.RUN_FIRST, None, ()),
        # True = plugged in, False = unplugged
        "plug-event": (GObject.SignalFlags.RUN_FIRST, None, (bool,)),
    }

    def __init__(self):
        super().__init__()
        self.percentage = -1.0
        self.state = 0
        self.available = False
        try:
            self._bus = Gio.bus_get_sync(Gio.BusType.SYSTEM, None)
        except GLib.Error:
            return
        self._bus.signal_subscribe(
            UPOWER, "org.freedesktop.DBus.Properties", "PropertiesChanged",
            DEVICE, None, Gio.DBusSignalFlags.NONE, self._on_changed,
        )
        self._bus.call(
            UPOWER, DEVICE, "org.freedesktop.DBus.Properties", "GetAll",
            GLib.Variant("(s)", (DEVICE_IFACE,)), GLib.VariantType("(a{sv})"),
            Gio.DBusCallFlags.NONE, -1, None, self._on_get_all,
        )

    def _on_get_all(self, bus, res):
        try:
            props = bus.call_finish(res).unpack()[0]
        except GLib.Error:
            return
        # IsPresent is False on desktops without a battery
        self.available = bool(props.get("IsPresent", False))
        self._apply(props, initial=True)

    def _on_changed(self, bus, sender, path, iface, signal, params):
        _iface, changed, _inv = params.unpack()
        self._apply(changed, initial=False)

    def _apply(self, props: dict, initial: bool):
        old_state = self.state
        if "Percentage" in props:
            self.percentage = float(props["Percentage"])
        if "State" in props:
            self.state = int(props["State"])
        if not initial and old_state != self.state:
            if self.state == CHARGING:
                self.emit("plug-event", True)
            elif self.state == DISCHARGING and old_state in (CHARGING, FULL):
                self.emit("plug-event", False)
        self.emit("changed")

    @property
    def charging(self) -> bool:
        return self.state in (CHARGING, FULL)
