"""Weather tile backed by wttr.in.

Deliberately not a poller: a fetch happens only when the expanded card asks
for one and the cached value is older than 30 minutes. Closed island = zero
network traffic.
"""

from __future__ import annotations

import json
import threading
import time
import urllib.parse
import urllib.request

from gi.repository import GLib, GObject

TTL = 30 * 60

# wttr.in weather codes → (emoji-free label, symbolic icon)
_ICONS = {
    "113": "weather-clear-symbolic",
    "116": "weather-few-clouds-symbolic",
    "119": "weather-overcast-symbolic",
    "122": "weather-overcast-symbolic",
    "143": "weather-fog-symbolic",
    "248": "weather-fog-symbolic",
    "260": "weather-fog-symbolic",
}


class WeatherService(GObject.Object):
    __gsignals__ = {
        # temp string ("21°"), description, icon name
        "changed": (GObject.SignalFlags.RUN_FIRST, None, (str, str, str)),
    }

    def __init__(self, city: str = ""):
        super().__init__()
        self.city = city
        self.temp = ""
        self.desc = ""
        self.icon = "weather-clear-symbolic"
        self._stamp = 0.0
        self._busy = False

    def request(self):
        """Refresh if stale; emits `changed` when data lands."""
        if self._busy or time.time() - self._stamp < TTL:
            if self.temp:
                self.emit("changed", self.temp, self.desc, self.icon)
            return
        self._busy = True
        threading.Thread(target=self._fetch, daemon=True).start()

    def _fetch(self):
        url = f"https://wttr.in/{urllib.parse.quote(self.city)}?format=j1"
        try:
            with urllib.request.urlopen(url, timeout=8) as r:
                data = json.load(r)
            now = data["current_condition"][0]
            temp = f"{now['temp_C']}°"
            desc = now["weatherDesc"][0]["value"]
            code = now["weatherCode"]
        except Exception:
            self._busy = False
            return
        icon = _ICONS.get(code)
        if icon is None:
            # coarse buckets: thunder, snow, rain, default clouds
            n = int(code)
            icon = ("weather-storm-symbolic" if n in (200, 386, 389, 392, 395)
                    else "weather-snow-symbolic" if n >= 320
                    else "weather-showers-symbolic" if n >= 263
                    else "weather-few-clouds-symbolic")

        def done():
            self.temp, self.desc, self.icon = temp, desc, icon
            self._stamp = time.time()
            self._busy = False
            self.emit("changed", temp, desc, icon)
            return False
        GLib.idle_add(done)
