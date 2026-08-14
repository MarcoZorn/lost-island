"""Audio-reactive EQ levels via cava.

cava taps the PipeWire/Pulse monitor and streams five bar amplitudes as
ascii lines. The process only exists while music is playing *and* the bars
are on screen — the pill starts and stops it, so the idle island never
captures audio at all. Without cava installed, `available` is False and the
bars fall back to their internal animation.
"""

from __future__ import annotations

import os

from gi.repository import Gio, GLib, GObject

from lostisland import config

BARS = 5

CONF = f"""[general]
bars = {BARS}
framerate = 30
[output]
method = raw
raw_target = /dev/stdout
data_format = ascii
ascii_max_range = 100
[smoothing]
noise_reduction = 60
"""


class CavaService(GObject.Object):
    __gsignals__ = {
        # list of {BARS} floats in 0..1
        "levels": (GObject.SignalFlags.RUN_FIRST, None, (object,)),
    }

    def __init__(self):
        super().__init__()
        self.available = bool(GLib.find_program_in_path("cava"))
        self._proc: Gio.Subprocess | None = None
        self._stream: Gio.DataInputStream | None = None

    def start(self):
        if not self.available or self._proc is not None:
            return
        os.makedirs(config.CACHE_DIR, exist_ok=True)
        conf = os.path.join(config.CACHE_DIR, "cava.conf")
        with open(conf, "w") as f:
            f.write(CONF)
        try:
            self._proc = Gio.Subprocess.new(
                ["cava", "-p", conf], Gio.SubprocessFlags.STDOUT_PIPE)
        except GLib.Error:
            self.available = False
            return
        self._stream = Gio.DataInputStream.new(self._proc.get_stdout_pipe())
        self._read_next()

    def stop(self):
        if self._proc is not None:
            self._proc.force_exit()
            self._proc = None
            self._stream = None

    def _read_next(self):
        if self._stream is None:
            return
        self._stream.read_line_async(
            GLib.PRIORITY_DEFAULT, None, self._on_line)

    def _on_line(self, stream, res):
        if stream is not self._stream:
            return  # stale callback from a stopped process
        try:
            line, _len = stream.read_line_finish_utf8(res)
        except GLib.Error:
            return
        if line is None:
            return
        parts = line.strip().strip(";").split(";")
        if len(parts) == BARS:
            try:
                self.emit("levels",
                          [min(1.0, int(p) / 100.0) for p in parts])
            except ValueError:
                pass
        self._read_next()
