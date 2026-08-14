"""Volume watcher for the OSD peek.

Listens on `pactl subscribe` (a blocking pipe — the process sleeps until
PulseAudio/PipeWire actually emits an event) and reads the sink volume only
when something changed. No timers, no polling.
"""

from __future__ import annotations

import re

from gi.repository import Gio, GLib, GObject

_VOL_RE = re.compile(r"(\d+)%")


class AudioService(GObject.Object):
    __gsignals__ = {
        # percent (0-150), muted
        "volume": (GObject.SignalFlags.RUN_FIRST, None, (int, bool)),
    }

    def __init__(self):
        super().__init__()
        self.percent = -1
        self.muted = False
        self._primed = False  # swallow the initial baseline read
        self._pending = False
        try:
            self._proc = Gio.Subprocess.new(
                ["pactl", "subscribe"], Gio.SubprocessFlags.STDOUT_PIPE
            )
        except GLib.Error:
            return
        self._stream = Gio.DataInputStream.new(self._proc.get_stdout_pipe())
        self._read_next()
        self._query()  # establish baseline silently

    def _read_next(self):
        self._stream.read_line_async(GLib.PRIORITY_DEFAULT, None, self._on_line)

    def _on_line(self, stream, res):
        try:
            line, _len = stream.read_line_finish_utf8(res)
        except GLib.Error:
            return
        if line is None:
            return
        if "'change' on sink" in line or "'change' on server" in line:
            # coalesce bursts: pactl fires several events per keypress
            if not self._pending:
                self._pending = True
                GLib.timeout_add(40, self._query_once)
        self._read_next()

    def _query_once(self):
        self._pending = False
        self._query()
        return False

    def _query(self):
        proc = Gio.Subprocess.new(
            ["pactl", "get-sink-volume", "@DEFAULT_SINK@"],
            Gio.SubprocessFlags.STDOUT_PIPE,
        )
        proc.communicate_utf8_async(None, None, self._on_volume_out)

    def _on_volume_out(self, proc, res):
        try:
            _ok, out, _err = proc.communicate_utf8_finish(res)
        except GLib.Error:
            return
        m = _VOL_RE.search(out or "")
        if not m:
            return
        percent = int(m.group(1))
        mproc = Gio.Subprocess.new(
            ["pactl", "get-sink-mute", "@DEFAULT_SINK@"],
            Gio.SubprocessFlags.STDOUT_PIPE,
        )
        mproc.communicate_utf8_async(
            None, None, lambda p, r: self._on_mute_out(p, r, percent))

    def _on_mute_out(self, proc, res, percent):
        try:
            _ok, out, _err = proc.communicate_utf8_finish(res)
        except GLib.Error:
            return
        muted = "yes" in (out or "")
        changed = percent != self.percent or muted != self.muted
        self.percent, self.muted = percent, muted
        if self._primed and changed:
            self.emit("volume", percent, muted)
        self._primed = True
