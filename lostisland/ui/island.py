"""The island itself: one surface, three states, fluid morphs between them.

The morph is a Gtk.Stack with size interpolation — the window resizes with
it, so the layer surface always hugs the island exactly and clicks anywhere
else on screen go straight through to your apps.
"""

from __future__ import annotations

from gi.repository import GLib, Gtk

from lostisland.ui.expanded import Expanded
from lostisland.ui.peek import Peek
from lostisland.ui.pill import Pill

COLLAPSE_DELAY_MS = 700


class Island(Gtk.Box):
    def __init__(self, cfg: dict, media, power, weather=None, system=None,
                 on_settings=None, cava=None, lyrics=None):
        super().__init__()
        self.add_css_class("island")
        self.set_halign(Gtk.Align.CENTER)
        self.set_valign(Gtk.Align.START)
        self.set_overflow(Gtk.Overflow.HIDDEN)
        self.cfg = cfg
        self.media = media

        self._peek_timeout = 0
        self._collapse_timeout = 0

        self.stack = Gtk.Stack()
        self.stack.set_transition_type(Gtk.StackTransitionType.CROSSFADE)
        self.stack.set_transition_duration(260)
        self.stack.set_interpolate_size(True)
        self.stack.set_hhomogeneous(False)
        self.stack.set_vhomogeneous(False)

        self.lyrics = lyrics
        self.pill = Pill(cfg, media=media, cava=cava, weather=weather,
                         lyrics=lyrics)
        self.peek = Peek()
        self.expanded = Expanded(cfg, media, power,
                                 on_timer_change=self.pill.show_timer_chip,
                                 weather=weather, system=system,
                                 on_settings=on_settings)
        self.expanded.set_size_request(400, -1)

        self.stack.add_named(self.pill, "pill")
        self.stack.add_named(self.peek, "peek")
        self.stack.add_named(self.expanded, "expanded")
        self.append(self.stack)

        click = Gtk.GestureClick()
        click.connect("released", self._on_click)
        self.add_controller(click)

        # right click always opens/closes the card
        right = Gtk.GestureClick(button=3)
        right.connect("released", lambda *_: self.toggle())
        self.add_controller(right)

        # horizontal swipe on the pill cycles faces, tide-style
        drag = Gtk.GestureDrag()
        drag.connect("drag-update", self._on_drag_update)
        drag.connect("drag-end", self._on_drag_end)
        self.add_controller(drag)

        motion = Gtk.EventControllerMotion()
        motion.connect("enter", self._on_enter)
        motion.connect("leave", self._on_leave)
        self.add_controller(motion)

    # -- state -------------------------------------------------------------

    @property
    def state(self) -> str:
        return self.stack.get_visible_child_name() or "pill"

    def collapse(self):
        self._cancel_peek()
        self.remove_css_class("island--expanded")
        self.stack.set_visible_child_name("pill")

    def expand(self):
        self._cancel_peek()
        self.add_css_class("island--expanded")
        self.stack.set_visible_child_name("expanded")

    def toggle(self):
        self.collapse() if self.state == "expanded" else self.expand()

    def show_peek(self, setter, *args):
        """Run `setter(*args)` on the peek widget and surface it briefly."""
        if self.state == "expanded":
            return
        setter(*args)
        self.stack.set_visible_child_name("peek")
        self._cancel_peek()
        ms = int(float(self.cfg.get("peek_seconds", 2.2)) * 1000)
        self._peek_timeout = GLib.timeout_add(ms, self._peek_done)

    def _peek_done(self):
        self._peek_timeout = 0
        if self.state == "peek":
            self.stack.set_visible_child_name("pill")
        return False

    def _cancel_peek(self):
        if self._peek_timeout:
            GLib.source_remove(self._peek_timeout)
            self._peek_timeout = 0

    # -- input -------------------------------------------------------------

    def _on_click(self, gesture, n, x, y):
        # clicks on buttons/sliders inside the expanded card never reach
        # here — GTK claims them first — so this only fires on the surface
        if self.state in ("pill", "peek"):
            if self.cfg.get("click_action", "cycle") == "cycle":
                self.pill.cycle(+1)
            else:
                self.expand()
        else:
            # a click on empty card space closes it again
            self.collapse()

    def _on_drag_update(self, gesture, dx, dy):
        # once it's clearly a horizontal swipe, claim it away from the click
        if self.state == "pill" and abs(dx) > 18 and abs(dx) > abs(dy):
            gesture.set_state(Gtk.EventSequenceState.CLAIMED)

    def _on_drag_end(self, gesture, dx, dy):
        if self.state == "pill" and abs(dx) > 36 and abs(dx) > abs(dy):
            self.pill.cycle(+1 if dx < 0 else -1)

    def _on_enter(self, motion, x, y):
        if self._collapse_timeout:
            GLib.source_remove(self._collapse_timeout)
            self._collapse_timeout = 0

    def _on_leave(self, motion):
        if self.state != "expanded":
            return
        if self._collapse_timeout:
            GLib.source_remove(self._collapse_timeout)
        self._collapse_timeout = GLib.timeout_add(
            COLLAPSE_DELAY_MS, self._leave_collapse)

    def _leave_collapse(self):
        self._collapse_timeout = 0
        if self.state == "expanded":
            self.collapse()
        return False

    # -- service wiring ----------------------------------------------------

    def on_media_changed(self):
        p = self.media.active
        if p is None:
            self.pill.show_idle()
        else:
            # the pill shows the title only — the artist lives in the card
            self.pill.show_music(p.title, p.art_path, p.status == "Playing")
            if self.lyrics is not None:
                self.lyrics.set_track(p.artist, p.title, p.album,
                                      p.length_us // 1_000_000)
        if self.state == "expanded":
            self.expanded.refresh_media()
