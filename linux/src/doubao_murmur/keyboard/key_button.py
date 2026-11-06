"""A single on-screen key widget.

Every key is a plain Gtk.Button that fires once per click via ``clicked``
and dispatches to ``controller.press_key(keydef)``. The widget never takes
focus so the target window keeps it.

No custom gestures, no pointer polling, no hold-to-repeat: those all
depended on the touch *release* event, which this device (Steam Deck /
Legion Go, X11) drops under fast input — a custom GestureClick that loses
its release keeps a pointer grab and deadlocks the whole keyboard. The
button's native click is the most reliable primitive available here. One
tap = one key; tap Backspace repeatedly to delete more.
"""

from __future__ import annotations

import gi

gi.require_version("Gtk", "4.0")
from gi.repository import Gtk

from doubao_murmur.keyboard.layouts import KIND_CHAR, KIND_LAYER, KIND_MOD, KeyDef
from doubao_murmur.keyboard.state import ModState


class KeyButton:
    def __init__(self, keydef: KeyDef, controller) -> None:
        self.keydef = keydef
        self._controller = controller

        button = Gtk.Button(label=self._display_label())
        button.set_focusable(False)
        button.set_can_focus(False)
        button.set_hexpand(True)
        button.set_vexpand(True)
        button.add_css_class("key")
        if keydef.kind == KIND_MOD:
            button.add_css_class("key-mod")
        elif keydef.kind == KIND_LAYER:
            button.add_css_class("key-layer")
        elif keydef.keysym == "space":
            button.add_css_class("key-space")
        button.connect("clicked", self._on_clicked)

        self.widget = button

    # -- layout ------------------------------------------------------------

    @property
    def width(self) -> float:
        return self.keydef.width

    # -- events ------------------------------------------------------------

    def _on_clicked(self, _button) -> None:
        self._controller.press_key(self.keydef)

    # -- display -----------------------------------------------------------

    def refresh(self) -> None:
        """Re-render label + active state from the keyboard state."""
        self.widget.set_label(self._display_label())
        if self.keydef.kind == KIND_MOD:
            st = self._controller.state.mod_state(self.keydef.mod)
            for cls in ("armed", "locked"):
                self.widget.remove_css_class(cls)
            if st == ModState.ARMED:
                self.widget.add_css_class("armed")
            elif st == ModState.LOCKED:
                self.widget.add_css_class("locked")

    def _display_label(self) -> str:
        kd = self.keydef
        if kd.kind != KIND_CHAR:
            return kd.label
        shifted = self._controller.state.is_shifted()
        if kd.is_letter:
            return kd.label.upper() if shifted else kd.label.lower()
        if shifted and kd.shift_label:
            return kd.shift_label
        return kd.label
