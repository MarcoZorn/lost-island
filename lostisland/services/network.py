"""Connectivity chip via NetworkManager — reacts to PrimaryConnection changes."""

from __future__ import annotations

from gi.repository import Gio, GLib, GObject

NM = "org.freedesktop.NetworkManager"
NM_PATH = "/org/freedesktop/NetworkManager"


class NetworkService(GObject.Object):
    __gsignals__ = {
        # connection id ("HomeWifi"), type ("802-11-wireless"), connected
        "changed": (GObject.SignalFlags.RUN_FIRST, None, (str, str, bool)),
    }

    def __init__(self):
        super().__init__()
        self.name = ""
        self.kind = ""
        self.connected = False
        self._initial = True
        try:
            self._bus = Gio.bus_get_sync(Gio.BusType.SYSTEM, None)
        except GLib.Error:
            return
        self._bus.signal_subscribe(
            NM, "org.freedesktop.DBus.Properties", "PropertiesChanged",
            NM_PATH, None, Gio.DBusSignalFlags.NONE, self._on_props,
        )
        self._fetch_primary()

    def _on_props(self, bus, sender, path, iface, signal, params):
        _i, changed, _inv = params.unpack()
        if "PrimaryConnection" in changed:
            self._fetch_primary()

    def _fetch_primary(self):
        self._bus.call(
            NM, NM_PATH, "org.freedesktop.DBus.Properties", "Get",
            GLib.Variant("(ss)", (NM, "PrimaryConnection")),
            GLib.VariantType("(v)"), Gio.DBusCallFlags.NONE, -1, None,
            self._on_primary,
        )

    def _on_primary(self, bus, res):
        try:
            path = bus.call_finish(res).unpack()[0]
        except GLib.Error:
            return
        if not path or path == "/":
            self._update("", "", False)
            return
        self._bus.call(
            NM, path, "org.freedesktop.DBus.Properties", "GetAll",
            GLib.Variant("(s)", (NM + ".Connection.Active",)),
            GLib.VariantType("(a{sv})"), Gio.DBusCallFlags.NONE, -1, None,
            self._on_active,
        )

    def _on_active(self, bus, res):
        try:
            props = bus.call_finish(res).unpack()[0]
        except GLib.Error:
            return
        self._update(props.get("Id", ""), props.get("Type", ""), True)

    def _update(self, name, kind, connected):
        changed = (name, kind, connected) != (self.name, self.kind, self.connected)
        self.name, self.kind, self.connected = name, kind, connected
        if changed and not self._initial:
            self.emit("changed", name, kind, connected)
        self._initial = False
