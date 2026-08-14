"""The collapsed pill — what the island looks like 99% of the time.

Faces, cycled with a click or a horizontal swipe (config `pill_faces`):
  auto       clock when idle, art + EQ + song title when music plays
  status     clock + battery, always
  lyrics     the current synced lyric line while music plays
  title      just the song title (never the artist)
  compact    just art + EQ
  clock      only the time
  battery    only the battery
  weather    temperature + condition icon
  bluetooth  connected device + its battery

A running timer earns a small accent chip on every face. With cava
installed the EQ bars follow the actual audio; cava only runs while music
plays and the bars are on screen, and the bars fall back to their own
animation until real data flows.
"""

from __future__ import annotations

import time

from gi.repository import GLib, Gtk, Pango

from lostisland import config
from lostisland.ui.draw import EqBars, pick_icon

FACES = ["auto", "status", "lyrics", "title", "compact", "clock",
         "battery", "weather", "bluetooth"]


class Pill(Gtk.Box):
    def __init__(self, cfg: dict, media=None, cava=None, weather=None,
                 lyrics=None):
        super().__init__(orientation=Gtk.Orientation.HORIZONTAL, spacing=10)
        self.add_css_class("pill")
        self.cfg = cfg
        self.media = media
        self.cava = cava
        self.weather = weather
        self.lyrics = lyrics
        self._minute_timer = 0
        self._lyric_timer = 0
        self._music = None  # (title, art_path, playing) while music is active
        self._batt = (-1.0, False)
        self._bt = ("", False, -1)

        # music side
        self.art = Gtk.Image()
        self.art.set_pixel_size(20)
        self.art.add_css_class("pill-art")
        self.title = Gtk.Label()
        self.title.add_css_class("pill-title")
        self.title.set_ellipsize(Pango.EllipsizeMode.END)
        self.title.set_max_width_chars(32)
        self.eq = EqBars()
        self.eq.connect("map", lambda *_: self._sync_cava())
        self.eq.connect("unmap", lambda *_: self._sync_cava())
        if self.cava is not None:
            self.cava.connect("levels", self._on_levels)
            self.cava.connect("stopped",
                              lambda *_: self.eq.set_external(False))

        # lyric line
        self.lyric = Gtk.Label()
        self.lyric.add_css_class("pill-lyric")
        self.lyric.set_ellipsize(Pango.EllipsizeMode.END)
        self.lyric.set_max_width_chars(40)
        if self.lyrics is not None:
            self.lyrics.connect("ready", lambda *_: self._lyric_tick())

        # idle side
        self.dot = Gtk.Label(label="●")
        self.dot.add_css_class("pill-dot")
        self.clock = Gtk.Label()
        self.clock.add_css_class("pill-clock")

        # generic icon face (weather / bluetooth)
        self.face_icon = Gtk.Image()
        self.face_icon.set_pixel_size(16)
        self.face_label = Gtk.Label()
        self.face_label.add_css_class("pill-title")
        self.face_label.set_ellipsize(Pango.EllipsizeMode.END)
        self.face_label.set_max_width_chars(24)

        # timer chip (shared)
        self.timer_chip = Gtk.Label()
        self.timer_chip.add_css_class("timer-chip")
        self.timer_chip.set_visible(False)

        # small battery readout
        self.batt_icon = Gtk.Image()
        self.batt_icon.set_pixel_size(13)
        self.batt_icon.add_css_class("pill-batt")
        self.batt_label = Gtk.Label()
        self.batt_label.add_css_class("pill-batt")

        for widget in (self.art, self.title, self.eq, self.lyric,
                       self.face_icon, self.face_label, self.dot, self.clock,
                       self.timer_chip, self.batt_icon, self.batt_label):
            self.append(widget)

        self.connect("map", lambda *_: (self._start_clock(), self.render()))
        self.connect("unmap", lambda *_: (self._stop_clock(),
                                          self._sync_cava(),
                                          self._sync_lyric_timer()))
        self.render()

    # -- face state ---------------------------------------------------------

    @property
    def face(self) -> str:
        return self.cfg.get("pill_face", "auto")

    def enabled_faces(self) -> list[str]:
        faces = [f for f in self.cfg.get("pill_faces", FACES) if f in FACES]
        return faces or ["auto"]

    def cycle(self, step: int = 1):
        faces = self.enabled_faces()
        try:
            i = faces.index(self.face)
        except ValueError:
            i = 0
        self.cfg["pill_face"] = faces[(i + step) % len(faces)]
        config.save(self.cfg)
        self.render()

    # -- rendering ----------------------------------------------------------

    def _hide_all(self):
        for w in (self.art, self.title, self.eq, self.lyric, self.face_icon,
                  self.face_label, self.dot, self.clock, self.batt_icon,
                  self.batt_label):
            w.set_visible(False)

    def render(self):
        face = self.face
        if face not in self.enabled_faces():
            face = self.cfg["pill_face"] = self.enabled_faces()[0]
        self._hide_all()
        music = self._music
        playing = bool(music and music[2])

        if face == "auto" and music:
            self._show_music_row(title=True)
        elif face == "auto":
            self._show_clock(dot=True)
            self._render_battery()
        elif face == "status":
            self._show_clock(dot=False)
            self._render_battery(force=self._batt[0] >= 0)
        elif face == "lyrics" and music:
            self.lyric.set_visible(True)
            self._lyric_tick()
        elif face == "title" and music:
            self.title.set_label(music[0] or "…")
            self.title.set_visible(True)
        elif face == "compact" and music:
            self._show_music_row(title=False)
        elif face == "battery" and self._batt[0] >= 0:
            self._render_battery(force=True)
        elif face == "weather" and self.weather is not None:
            self.weather.request()
            if self.weather.temp:
                self.face_icon.set_from_icon_name(pick_icon(
                    self, self.weather.icon.replace("-symbolic", ""),
                    self.weather.icon, "weather-few-clouds"))
                self.face_label.set_label(self.weather.temp)
                self.face_icon.set_visible(True)
                self.face_label.set_visible(True)
            else:
                self._show_clock(dot=True)
        elif face == "bluetooth" and self._bt[1]:
            dev, _up, batt = self._bt
            self.face_icon.set_from_icon_name(pick_icon(
                self, "bluetooth-symbolic", "network-bluetooth-symbolic",
                "bluetooth-active-symbolic"))
            self.face_label.set_label(
                dev if batt < 0 else f"{dev} · {batt}%")
            self.face_icon.set_visible(True)
            self.face_label.set_visible(True)
        else:
            # face has nothing to show right now: fall back to the clock
            self._show_clock(dot=True)
            self._render_battery()

        self.eq.set_playing(playing and self.eq.get_visible())
        self._sync_cava()
        self._sync_lyric_timer()

    def _show_music_row(self, title: bool):
        music = self._music
        if music and music[1]:
            self.art.set_from_file(music[1])
            self.art.set_visible(True)
        if title:
            self.title.set_label(music[0] or "…")
            self.title.set_visible(True)
        self.eq.set_visible(True)

    def _show_clock(self, dot: bool):
        if not self.cfg.get("idle_clock", True) and self.face == "auto":
            return
        self.dot.set_visible(dot)
        self.clock.set_visible(True)
        self._refresh_clock()

    # -- music --------------------------------------------------------------

    def show_music(self, title: str, art_path: str, playing: bool):
        self._music = (title, art_path, playing)
        self.render()

    def show_idle(self):
        self._music = None
        self.render()

    def show_timer_chip(self, text: str | None):
        self.timer_chip.set_visible(text is not None)
        if text is not None:
            self.timer_chip.set_label(text)

    # -- battery / bluetooth ------------------------------------------------

    def show_battery(self, percent: float, charging: bool):
        self._batt = (percent, charging)
        if self.face in ("battery", "status"):
            self.render()
        else:
            self._render_battery()

    def show_bluetooth(self, device: str, connected: bool, battery: int):
        self._bt = (device, connected, battery)
        if self.face == "bluetooth":
            self.render()

    def _render_battery(self, force: bool = False):
        percent, charging = self._batt
        show = force or (self.cfg.get("pill_battery", True) and percent >= 0
                         and (charging or percent <= 30))
        self.batt_icon.set_visible(show)
        self.batt_label.set_visible(show)
        if show:
            if charging:
                icon = pick_icon(self, "battery-charging-symbolic",
                                 "battery-full-charging-symbolic",
                                 "battery-good-charging-symbolic",
                                 "battery-symbolic")
            elif percent <= 30:
                icon = pick_icon(self, "battery-low-symbolic",
                                 "battery-caution-symbolic",
                                 "battery-empty-symbolic",
                                 "battery-symbolic")
            else:
                icon = pick_icon(self, "battery-good-symbolic",
                                 "battery-symbolic")
            self.batt_icon.set_from_icon_name(icon)
            self.batt_label.set_label(f"{percent:.0f}%")

    # -- lyrics -------------------------------------------------------------

    def _sync_lyric_timer(self):
        want = (self.face == "lyrics" and self._music and self._music[2]
                and self.get_mapped() and self.lyrics is not None
                and self.media is not None)
        if want and not self._lyric_timer:
            self._lyric_timer = GLib.timeout_add_seconds(1, self._lyric_tick)
        elif not want and self._lyric_timer:
            GLib.source_remove(self._lyric_timer)
            self._lyric_timer = 0

    def _lyric_tick(self):
        if self.face != "lyrics" or self.lyrics is None or self.media is None:
            return True
        if not self.lyrics.times:
            title = self._music[0] if self._music else ""
            self.lyric.set_label(f"♪ {title}" if title else "♪")
            return True
        self.media.get_position(self._on_lyric_pos)
        return True

    def _on_lyric_pos(self, pos_us: int):
        line = self.lyrics.line_at(pos_us / 1_000_000)
        self.lyric.set_label(line or "♪")

    # -- cava ---------------------------------------------------------------

    def _on_levels(self, _svc, levels):
        # only trust cava once data actually flows
        if not self.eq._external:
            self.eq.set_external(True)
        self.eq.feed(levels)

    def _sync_cava(self):
        if self.cava is None or not self.cava.available:
            return
        playing = bool(self._music and self._music[2])
        want = playing and self.eq.get_visible() and self.eq.get_mapped()
        if want:
            self.cava.start()
        else:
            self.cava.stop()
            self.eq.set_external(False)

    # -- clock, ticking once per minute, aligned to :00 ----------------------

    def _start_clock(self):
        self._refresh_clock()
        self._arm_minute()

    def _stop_clock(self):
        if self._minute_timer:
            GLib.source_remove(self._minute_timer)
            self._minute_timer = 0

    def _arm_minute(self):
        self._stop_clock()
        delay = 60 - time.localtime().tm_sec
        self._minute_timer = GLib.timeout_add_seconds(delay, self._on_minute)

    def _on_minute(self):
        self._refresh_clock()
        self._arm_minute()
        return False

    def _refresh_clock(self):
        fmt = "%H:%M" if self.cfg.get("clock_24h", True) else "%I:%M"
        self.clock.set_label(time.strftime(fmt))
