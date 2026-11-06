"""Keyboard modifier and layer state.

Modifiers are *sticky* with a three-state cycle, which is far friendlier on
a touchscreen than press-and-hold:

    OFF -> ARMED (applies to the next key only) -> LOCKED (stays on) -> OFF

After a character key fires, ARMED modifiers clear; LOCKED ones persist.
"""

from __future__ import annotations

from enum import Enum
from typing import Callable

# Order here is the order modifiers are emitted in an xdotool combo.
MODIFIERS = ("shift", "ctrl", "alt", "super")


class ModState(Enum):
    OFF = 0
    ARMED = 1
    LOCKED = 2


class KeyboardState:
    """Mutable modifier + layer state with a change callback for the UI."""

    def __init__(self, on_change: Callable[[], None] | None = None) -> None:
        self._mods: dict[str, ModState] = {m: ModState.OFF for m in MODIFIERS}
        self.layer: str = "letters"
        self.on_change = on_change

    # -- modifiers ----------------------------------------------------------

    def mod_state(self, name: str) -> ModState:
        return self._mods.get(name, ModState.OFF)

    def cycle_mod(self, name: str) -> None:
        """OFF -> ARMED -> LOCKED -> OFF."""
        if name not in self._mods:
            return
        nxt = {
            ModState.OFF: ModState.ARMED,
            ModState.ARMED: ModState.LOCKED,
            ModState.LOCKED: ModState.OFF,
        }[self._mods[name]]
        self._mods[name] = nxt
        self._notify()

    def active_mods(self) -> tuple[str, ...]:
        return tuple(
            m for m in MODIFIERS if self._mods[m] != ModState.OFF
        )

    def is_shifted(self) -> bool:
        return self._mods["shift"] != ModState.OFF

    def consume_after_char(self) -> None:
        """Clear ARMED modifiers after a character key fires."""
        changed = False
        for m, st in self._mods.items():
            if st == ModState.ARMED:
                self._mods[m] = ModState.OFF
                changed = True
        if changed:
            self._notify()

    # -- layer --------------------------------------------------------------

    def set_layer(self, layer: str) -> None:
        if layer != self.layer:
            self.layer = layer
            self._notify()

    # -- internal -----------------------------------------------------------

    def _notify(self) -> None:
        if self.on_change:
            self.on_change()
