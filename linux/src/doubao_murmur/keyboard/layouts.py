"""Key layout definitions for the on-screen keyboard (ASCII / Latin only).

Widths are in *units* where a standard alphanumeric key is ``U`` (=4), so a
single shared grid can give the rows the classic typewriter stagger: the
left modifier on each row is progressively wider (Tab < Caps < Shift), which
pushes each row right of the one above and leaves the right edges uneven —
the way a real keyboard looks, not a spreadsheet.

Character keys carry an X keysym name handed straight to xdotool; modifiers
and the layer-switch are handled by the controller. Shift is applied at
injection time (``shift+a`` -> ``A``, ``shift+1`` -> ``!``), so character
keys need no shifted keysym — only an optional ``shift_label`` for display.
"""

from __future__ import annotations

from dataclasses import dataclass

KIND_CHAR = "char"
KIND_MOD = "mod"
KIND_LAYER = "layer"
KIND_SPACER = "spacer"

U = 4.0  # width of a standard key, in grid columns


@dataclass(frozen=True)
class KeyDef:
    label: str
    keysym: str | None = None
    width: float = U
    kind: str = KIND_CHAR
    mod: str | None = None            # for kind == KIND_MOD
    target_layer: str | None = None   # for kind == KIND_LAYER
    shift_label: str | None = None    # display only, when shift is active
    is_letter: bool = False           # uppercase the label when shifted
    repeat: bool = False              # hold-to-repeat (backspace, arrows)


def _c(label, keysym=None, width=U, shift_label=None, repeat=False):
    return KeyDef(label=label, keysym=keysym or label, width=width,
                  shift_label=shift_label, repeat=repeat)


def _ltr(ch):
    return KeyDef(label=ch, keysym=ch, is_letter=True)


def _mod(label, mod, width):
    return KeyDef(label=label, kind=KIND_MOD, mod=mod, width=width)


def _layer(label, target, width=U + 2):
    return KeyDef(label=label, kind=KIND_LAYER, target_layer=target, width=width)


# Shared keys ---------------------------------------------------------------
_ESC = _c("Esc", "Escape", U)
_BACKSPACE = _c("⌫", "BackSpace", U + 2, repeat=True)
_TAB = _c("⇥", "Tab", U + 2)
_CAPS = _c("⇪", "Caps_Lock", U + 3)
_ENTER = _c("⏎", "Return", U + 3)
_SPACE = _c(" ", "space", U * 3 + 2)
_LEFT = _c("←", "Left", U, repeat=True)
_RIGHT = _c("→", "Right", U, repeat=True)
_UP = _c("↑", "Up", U, repeat=True)
_DOWN = _c("↓", "Down", U, repeat=True)


def _num_row(shift_labels):
    keys = [_ESC]
    for digit, sym in zip("1234567890", shift_labels):
        keys.append(_c(digit, digit, shift_label=sym))
    keys.append(_BACKSPACE)
    return keys


def _letters_layer():
    return [
        _num_row("!@#$%^&*()"),
        [_TAB, *[_ltr(c) for c in "qwertyuiop"]],
        [_CAPS, *[_ltr(c) for c in "asdfghjkl"], _ENTER],
        [
            _mod("⇧", "shift", U + 4),
            *[_ltr(c) for c in "zxcvbnm"],
            _c(",", "comma", shift_label="<"),
            _c(".", "period", shift_label=">"),
            _c("/", "slash", shift_label="?"),
        ],
        [
            _mod("Ctrl", "ctrl", U + 2),
            _mod("⊞", "super", U),
            _mod("Alt", "alt", U),
            _layer("?123", "symbols"),
            _SPACE,
            _LEFT, _UP, _DOWN, _RIGHT,
        ],
    ]


def _symbols_layer():
    s = _c
    return [
        [
            _ESC,
            s("!", "exclam"), s("@", "at"), s("#", "numbersign"),
            s("$", "dollar"), s("%", "percent"), s("^", "asciicircum"),
            s("&", "ampersand"), s("*", "asterisk"),
            s("(", "parenleft"), s(")", "parenright"),
            _BACKSPACE,
        ],
        [
            _TAB,
            s("`", "grave"), s("~", "asciitilde"), s("|", "bar"),
            s("\\", "backslash"), s("/", "slash"),
            s("{", "braceleft"), s("}", "braceright"),
            s("[", "bracketleft"), s("]", "bracketright"),
        ],
        [
            _CAPS,
            s("-", "minus"), s("_", "underscore"), s("=", "equal"),
            s("+", "plus"), s(";", "semicolon"), s(":", "colon"),
            s("'", "apostrophe"), s('"', "quotedbl"),
            _ENTER,
        ],
        [
            _mod("⇧", "shift", U + 4),
            s("<", "less"), s(">", "greater"), s(",", "comma"),
            s(".", "period"), s("?", "question"), s("!", "exclam"),
        ],
        [
            _mod("Ctrl", "ctrl", U + 2),
            _mod("⊞", "super", U),
            _mod("Alt", "alt", U),
            _layer("ABC", "letters"),
            _SPACE,
            _LEFT, _UP, _DOWN, _RIGHT,
        ],
    ]


LAYERS: dict[str, list[list[KeyDef]]] = {
    "letters": _letters_layer(),
    "symbols": _symbols_layer(),
}


def split_row(row: list[KeyDef]) -> tuple[list[KeyDef], list[KeyDef]]:
    """Divide one row into left-hand and right-hand groups for split mode.

    Alphanumeric rows split at the halfway point (``qwert | yuiop``). The
    bottom row contains the wide space bar — give each hand its own smaller
    space so neither thumb is left without one.
    """
    space_idx = next(
        (i for i, k in enumerate(row) if k.keysym == "space"), None
    )
    if space_idx is not None:
        half_space = _c(" ", "space", U * 1.5)
        left = row[:space_idx] + [half_space]
        right = [half_space] + row[space_idx + 1:]
        return left, right

    total = sum(k.width for k in row)
    acc = 0.0
    for i, k in enumerate(row):
        acc += k.width
        if acc >= total / 2:
            return row[: i + 1], row[i + 1:]
    return row, []
