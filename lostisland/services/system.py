"""CPU / RAM readout for the expanded card.

The sampler runs on a 3 s timer that exists only between start() and stop() —
the card starts it on map and kills it on unmap, so a closed island costs
nothing.
"""

from __future__ import annotations

from gi.repository import GLib, GObject


class SystemService(GObject.Object):
    __gsignals__ = {
        # cpu percent, ram percent, ram used GiB
        "changed": (GObject.SignalFlags.RUN_FIRST, None, (int, int, float)),
    }

    def __init__(self):
        super().__init__()
        self._timer = 0
        self._prev_total = 0
        self._prev_idle = 0

    def start(self):
        if not self._timer:
            self._sample()
            self._timer = GLib.timeout_add_seconds(3, self._sample)

    def stop(self):
        if self._timer:
            GLib.source_remove(self._timer)
            self._timer = 0
        self._prev_total = self._prev_idle = 0

    def _sample(self):
        try:
            with open("/proc/stat") as f:
                parts = f.readline().split()[1:]
            nums = list(map(int, parts))
            idle = nums[3] + nums[4]
            total = sum(nums)
            cpu = 0
            if self._prev_total:
                dt = total - self._prev_total
                di = idle - self._prev_idle
                cpu = round(100 * (dt - di) / dt) if dt else 0
            self._prev_total, self._prev_idle = total, idle

            info = {}
            with open("/proc/meminfo") as f:
                for line in f:
                    k, v = line.split(":", 1)
                    info[k] = int(v.split()[0])
                    if len(info) > 4:
                        break
            avail = info.get("MemAvailable", 0)
            mem_total = info.get("MemTotal", 1)
            used_kb = mem_total - avail
            ram = round(100 * used_kb / mem_total)
            self.emit("changed", cpu, ram, used_kb / 1024 / 1024)
        except OSError:
            pass
        return True
