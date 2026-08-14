"""User configuration, stored as plain JSON in ~/.config/lost-island/config.json.

Missing keys fall back to defaults, so upgrades never break an old config.
"""

from __future__ import annotations

import json
import os

CONFIG_DIR = os.path.join(
    os.environ.get("XDG_CONFIG_HOME", os.path.expanduser("~/.config")), "lost-island"
)
CONFIG_PATH = os.path.join(CONFIG_DIR, "config.json")
CACHE_DIR = os.path.join(
    os.environ.get("XDG_CACHE_HOME", os.path.expanduser("~/.cache")), "lost-island"
)

DEFAULTS = {
    # top margin in px between screen edge and the island
    "margin_top": 0,
    # sit on top of panels/bars instead of being pushed below them
    "overlap_panel": True,
    # monitor connector name ("" = primary / compositor default)
    "monitor": "",
    # "top" stays below fullscreen apps, "overlay" is always on top
    "layer": "top",
    # show the clock in the idle pill
    "idle_clock": True,
    # collapsed face: "auto", "compact" (art+eq only), "clock", "battery"
    "pill_face": "auto",
    # 24h clock
    "clock_24h": True,
    # small battery readout in the pill while charging or below 30%
    "pill_battery": True,
    # seconds a peek (volume / battery / notification) stays visible
    "peek_seconds": 2.2,
    # modules that may take over the island
    "modules": {
        "music": True,
        "volume_osd": True,
        "battery": True,
        "notifications": True,
        "network": True,
        "bluetooth": True,
        "weather": True,
        "system": True,
        "toggles": True,
    },
    # weather location ("" = auto by IP); any wttr.in place name works
    "weather_city": "",
    # accent color used for progress bars and highlights
    "accent": "#ff9f0a",
}


def _merge(base: dict, override: dict) -> dict:
    out = dict(base)
    for k, v in override.items():
        if isinstance(v, dict) and isinstance(base.get(k), dict):
            out[k] = _merge(base[k], v)
        else:
            out[k] = v
    return out


def load() -> dict:
    try:
        with open(CONFIG_PATH) as f:
            return _merge(DEFAULTS, json.load(f))
    except (OSError, ValueError):
        return dict(DEFAULTS)


def write_default() -> str:
    """Write a commented default config if none exists; returns the path."""
    os.makedirs(CONFIG_DIR, exist_ok=True)
    if not os.path.exists(CONFIG_PATH):
        with open(CONFIG_PATH, "w") as f:
            json.dump(DEFAULTS, f, indent=2)
    return CONFIG_PATH


def save(cfg: dict) -> None:
    os.makedirs(CONFIG_DIR, exist_ok=True)
    with open(CONFIG_PATH, "w") as f:
        json.dump(cfg, f, indent=2)
