"""Synced lyrics for the pill's lyrics face, from lrclib.net.

One fetch per track change, cached in memory; the current line is looked up
locally against the playback position, so showing lyrics costs one HTTP GET
per song and nothing else.
"""

from __future__ import annotations

import bisect
import json
import re
import threading
import urllib.parse
import urllib.request

from gi.repository import GLib, GObject

_LRC_LINE = re.compile(r"\[(\d+):(\d+(?:\.\d+)?)\](.*)")


class LyricsService(GObject.Object):
    __gsignals__ = {
        # lyrics for the current track are ready (possibly empty)
        "ready": (GObject.SignalFlags.RUN_FIRST, None, ()),
    }

    def __init__(self):
        super().__init__()
        self._key = None
        self._cache: dict[tuple, list] = {}
        self.times: list[float] = []
        self.lines: list[str] = []

    def set_track(self, artist: str, title: str, album: str, duration_s: int):
        key = (artist, title)
        if key == self._key or not title:
            return
        self._key = key
        self.times, self.lines = [], []
        if key in self._cache:
            self.times, self.lines = self._cache[key]
            self.emit("ready")
            return
        threading.Thread(target=self._fetch,
                         args=(key, artist, title, album, duration_s),
                         daemon=True).start()

    def _fetch(self, key, artist, title, album, duration_s):
        params = urllib.parse.urlencode({
            "artist_name": artist, "track_name": title,
            "album_name": album, "duration": max(0, int(duration_s)),
        })
        try:
            req = urllib.request.Request(
                f"https://lrclib.net/api/get?{params}",
                headers={"User-Agent": "lost-island"})
            with urllib.request.urlopen(req, timeout=8) as r:
                data = json.load(r)
            lrc = data.get("syncedLyrics") or ""
        except Exception:
            lrc = ""
        parsed = []
        for raw in lrc.splitlines():
            m = _LRC_LINE.match(raw.strip())
            if m:
                t = int(m.group(1)) * 60 + float(m.group(2))
                text = m.group(3).strip()
                if text:
                    parsed.append((t, text))
        parsed.sort()
        times = [p[0] for p in parsed]
        lines = [p[1] for p in parsed]

        def done():
            self._cache[key] = (times, lines)
            if key == self._key:
                self.times, self.lines = times, lines
                self.emit("ready")
            return False
        GLib.idle_add(done)

    def line_at(self, pos_s: float) -> str:
        if not self.times:
            return ""
        i = bisect.bisect_right(self.times, pos_s) - 1
        return self.lines[i] if i >= 0 else "…"
