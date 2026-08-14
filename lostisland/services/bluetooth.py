"""Bluetooth chip via BlueZ: tracks the connected device, peeks on
connect/disconnect. Signal driven through the object manager."""

from __future__ import annotations

from gi.repository import Gio, GLib, GObject

BLUEZ = "org.bluez"
DEVICE_IFACE = "org.bluez.Device1"
BATTERY_IFACE = "org.bluez.Battery1"


class BluetoothService(GObject.Object):
    __gsignals__ = {
        # device name, connected, battery percent (-1 unknown)
        "changed": (GObject.SignalFlags.RUN_FIRST, None, (str, bool, int)),
    }

    def __init__(self):
        super().__init__()
        self.device = ""
        self.connected = False
        self.battery = -1
        self._devices: dict[str, dict] = {}  # path -> {name, connected, battery}
        self._initial = True
        try:
            self._bus = Gio.bus_get_sync(Gio.BusType.SYSTEM, None)
        except GLib.Error:
            return
        self._bus.signal_subscribe(
            BLUEZ, "org.freedesktop.DBus.Properties", "PropertiesChanged",
            None, None, Gio.DBusSignalFlags.NONE, self._on_props)
        self._bus.call(
            BLUEZ, "/", "org.freedesktop.DBus.ObjectManager",
            "GetManagedObjects", None,
            GLib.VariantType("(a{oa{sa{sv}}})"),
            Gio.DBusCallFlags.NONE, -1, None, self._on_objects)

    def _on_objects(self, bus, res):
        try:
            objects = bus.call_finish(res).unpack()[0]
        except GLib.Error:
            return
        for path, ifaces in objects.items():
            dev = ifaces.get(DEVICE_IFACE)
            if dev:
                self._devices[path] = {
                    "name": dev.get("Alias", dev.get("Name", "")),
                    "connected": bool(dev.get("Connected", False)),
                    "battery": int(ifaces.get(BATTERY_IFACE, {})
                                   .get("Percentage", -1)),
                }
        self._elect()
        self._initial = False

    def _on_props(self, bus, sender, path, iface, signal, params):
        which, changed, _inv = params.unpack()
        if which == DEVICE_IFACE:
            entry = self._devices.setdefault(
                path, {"name": "", "connected": False, "battery": -1})
            if "Alias" in changed:
                entry["name"] = changed["Alias"]
            if "Connected" in changed:
                entry["connected"] = bool(changed["Connected"])
            self._elect()
        elif which == BATTERY_IFACE and path in self._devices:
            if "Percentage" in changed:
                self._devices[path]["battery"] = int(changed["Percentage"])
                self._elect()

    def _elect(self):
        active = [d for d in self._devices.values() if d["connected"]]
        if active:
            top = active[0]
            state = (top["name"], True, top["battery"])
        else:
            state = ("", False, -1)
        if state != (self.device, self.connected, self.battery):
            self.device, self.connected, self.battery = state
            if not self._initial:
                self.emit("changed", *state)
