"""Passive notification mirror.

Opens a private monitor connection to the session bus and watches
org.freedesktop.Notifications Notify calls without ever answering them —
KDE's (or any other) notification daemon keeps working untouched; the island
just peeks what flies by.
"""

from __future__ import annotations

from gi.repository import Gio, GLib, GObject


class NotifyService(GObject.Object):
    __gsignals__ = {
        # app_name, summary, body, app_icon
        "notified": (GObject.SignalFlags.RUN_FIRST, None, (str, str, str, str)),
    }

    def __init__(self):
        super().__init__()
        try:
            addr = Gio.dbus_address_get_for_bus_sync(Gio.BusType.SESSION, None)
            self._conn = Gio.DBusConnection.new_for_address_sync(
                addr,
                Gio.DBusConnectionFlags.AUTHENTICATION_CLIENT
                | Gio.DBusConnectionFlags.MESSAGE_BUS_CONNECTION,
                None, None,
            )
            self._conn.call_sync(
                "org.freedesktop.DBus", "/org/freedesktop/DBus",
                "org.freedesktop.DBus.Monitoring", "BecomeMonitor",
                GLib.Variant("(asu)", (
                    ["type='method_call',"
                     "interface='org.freedesktop.Notifications',"
                     "member='Notify'"], 0)),
                None, Gio.DBusCallFlags.NONE, -1, None,
            )
        except GLib.Error:
            self._conn = None
            return
        self._conn.add_filter(self._on_message)

    def _on_message(self, conn, message, incoming):
        if not incoming:
            return message
        try:
            if (message.get_interface() == "org.freedesktop.Notifications"
                    and message.get_member() == "Notify"):
                body = message.get_body()
                if body is not None:
                    app, _rid, icon, summary, text = body.unpack()[:5]
                    GLib.idle_add(self._emit, app, summary, text, icon)
        except (GLib.Error, ValueError):
            pass
        return message

    def _emit(self, app, summary, text, icon):
        self.emit("notified", app or "", summary or "", text or "", icon or "")
        return False
