"""Inject keystrokes into the focused window via xdotool (XTEST).

The keyboard window is mapped non-focusing (see ui/windowing.py), so the
application the user is typing into stays the X11 focus window and
``xdotool key`` lands there. This is the same path the auto-paste uses.

Injection runs on a single background worker thread, not the GTK main loop:
each ``xdotool`` call spawns a process (tens of ms, up to the timeout if the
X server is busy), and doing that synchronously on the main thread froze the
UI under a burst of taps. A queue keeps keystrokes ordered while keeping the
main loop responsive.

Modifiers are passed as xdotool's ``+`` combo syntax, e.g. ``ctrl+shift+v``
or ``shift+a`` (which is how we produce uppercase letters and shifted
symbols without a separate keymap).
"""

from __future__ import annotations

import logging
import queue
import subprocess
import threading

from doubao_murmur.host_tools import command_candidates

logger = logging.getLogger(__name__)

# Our internal modifier names -> xdotool keysym modifier names. They match
# today, but keep the indirection so the layout layer stays decoupled.
_MOD_TO_XDOTOOL = {
    "shift": "shift",
    "ctrl": "ctrl",
    "alt": "alt",
    "super": "super",
}


class XdotoolInjector:
    """Send key events to the focused window with xdotool, off-main-thread."""

    def __init__(self) -> None:
        self._commands = command_candidates("xdotool")
        self._queue: queue.Queue[str] = queue.Queue()
        self._worker: threading.Thread | None = None

    def available(self) -> bool:
        return bool(self._commands)

    def send(self, keysym: str, modifiers: tuple[str, ...] = ()) -> None:
        """Queue a keystroke for injection (returns immediately).

        ``keysym`` is an X keysym name (``a``, ``A``, ``BackSpace``,
        ``exclam``, ``Left`` ...).
        """
        if not keysym or not self._commands:
            return
        mods = [_MOD_TO_XDOTOOL[m] for m in modifiers if m in _MOD_TO_XDOTOOL]
        combo = "+".join([*mods, keysym])
        self._ensure_worker()
        self._queue.put(combo)

    def _ensure_worker(self) -> None:
        if self._worker is None or not self._worker.is_alive():
            self._worker = threading.Thread(
                target=self._run, name="kbd-injector", daemon=True
            )
            self._worker.start()

    def _run(self) -> None:
        while True:
            combo = self._queue.get()
            for command in self._commands:
                try:
                    subprocess.run(
                        command + ["key", "--clearmodifiers", combo],
                        check=True,
                        timeout=2,
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                    )
                    break
                except Exception as e:  # noqa: BLE001 - try next candidate
                    logger.warning("xdotool key %s failed: %s", combo, e)
