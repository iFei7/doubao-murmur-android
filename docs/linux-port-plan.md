# Doubao Murmur — SteamOS/Linux Port Implementation Plan

> Target: Steam Deck (SteamOS 3, KDE Plasma, PipeWire)
> Stack: Python 3 + GTK4 (PyGObject) + WebKitGTK 6.0 + Flatpak
> Mode: Desktop Mode only (KDE Plasma on Wayland/X11)

---

## Table of Contents

1. [Environment Baseline](#1-environment-baseline)
2. [Project Structure](#2-project-structure)
3. [Component Architecture](#3-component-architecture)
4. [Component-by-Component Implementation Plan](#4-component-by-component-implementation-plan)
5. [Dependency List](#5-dependency-list)
6. [Flatpak Manifest](#6-flatpak-manifest)
7. [Implementation Order](#7-implementation-order)
8. [Testing Strategy](#8-testing-strategy)
9. [Known Risks and Mitigations](#9-known-risks-and-mitigations)

---

## 1. Environment Baseline

Verified on the target Steam Deck:

| Property | Value |
|----------|-------|
| OS | SteamOS 3 (Arch-based, codename "holo") |
| Kernel | 6.11.11-valve29-1-neptune |
| CPU | AMD Ryzen Z2 Go |
| Display | KDE Plasma 6.2.5 (currently X11, Wayland planned) |
| Audio | PipeWire 1.2.7 + pipewire-pulse compat layer |
| Python | 3.13.1 (system) |
| GTK | 4.16.12 (system) |
| PyGObject | 3.50.0 (system) |
| Flatpak | 1.15.91, Flathub configured, SDK 25.08 |
| User groups | `wheel`, `deck` (NOT `input`) |
| Input devices | `/dev/input/event*` owned by `root:input` |

**Key gaps to address:**
- WebKitGTK 6.0 typelib not installed (`webkitgtk-6.0` in pacman)
- `python-sounddevice`, `python-websockets`, `python-numpy` not installed (available via pacman)
- No `wl-copy`, `ydotool`, or `wtype` installed
- `pip3` not available (system Python uses pacman packages only)
- User not in `input` group (needed for evdev and ydotool)

---

## 2. Project Structure

```
linux/
├── README.md                          # Linux-specific README
├── pyproject.toml                     # Project metadata + dependencies
├── run.sh                             # Dev launch script
├── Makefile                           # Build/install/flatpak targets
│
├── src/
│   └── doubao_murmur/
│       ├── __init__.py
│       ├── __main__.py                # Entry point: GTK app init
│       ├── app.py                     # Main GtkApplication + lifecycle
│       ├── config.py                  # Constants, paths, version
│       │
│       ├── asr_client.py            # WebSocket client (mirrors DoubaoASRClient.swift)
│       ├── audio_capture.py         # Mic capture via sounddevice (mirrors AudioCaptureManager.swift)
│       ├── transcription.py         # State machine orchestrator (mirrors TranscriptionManager.swift)
│       │
│       ├── hotkey/
│       │   ├── __init__.py
│       │   ├── manager.py           # Hotkey manager: dispatches to backend
│       │   ├── overlay_button.py    # PRIMARY: On-screen push-to-talk button
│       │   └── evdev_listener.py    # OPTIONAL: /dev/input evdev listener
│       │
│       ├── ui/
│       │   ├── __init__.py
│       │   ├── overlay.py           # Floating overlay window (mirrors OverlayPanel.swift)
│       │   ├── tray_icon.py         # System tray icon (KDE StatusNotifierItem)
│       │   ├── login_window.py      # WebView login window (WebKitGTK)
│       │   └── settings_dialog.py   # Settings/help dialog
│       │
│       ├── paste/
│       │   ├── __init__.py
│       │   └── paste_helper.py      # Clipboard + paste simulation
│       │
│       ├── params_store.py          # Persist cookies/device_id/web_id to JSON
│       └── resources/
│           ├── inject-websocket.js   # JS: fetch/XHR interception for login detection
│           ├── inject-dom.js         # JS: DOM helpers
│           ├── overlay.css           # Overlay styling
│           └── icons/
│               ├── tray-icon.svg     # System tray icon (symbolic)
│               └── tray-icon-recording.svg
│
├── flatpak/
│   ├── com.doubao.Murmur.yml        # Flatpak manifest
│   ├── com.doubao.Murmur.desktop    # Desktop entry
│   ├── com.doubao.Murmur.metainfo.xml
│   └── setup-permissions.sh         # Post-install script for input group, ydotool, etc.
│
└── tests/
    ├── test_asr_client.py
    ├── test_audio_capture.py
    ├── test_transcription.py
    ├── test_params_store.py
    └── test_paste_helper.py
```

---

## 3. Component Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  app.py — GtkApplication lifecycle                               │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  ┌─────────────┐   ┌──────────────┐   ┌─────────────────────┐  │
│  │ tray_icon.py │   │ login_window │   │  settings_dialog.py │  │
│  │ (KDE tray)   │   │ (WebKitGTK)  │   │  (GTK4 dialog)      │  │
│  └──────┬──────┘   └──────┬───────┘   └─────────────────────┘  │
│         │                 │                                      │
│         ▼                 ▼                                      │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  transcription.py  —  State Machine Orchestrator          │   │
│  │  idle → starting → recording → stopping → idle            │   │
│  └──────────┬───────────────┬───────────────┬───────────────┘   │
│             │               │               │                    │
│     ┌───────▼──────┐ ┌─────▼──────┐ ┌─────▼────────────┐      │
│     │ audio_capture │ │ asr_client │ │   paste_helper    │      │
│     │ (sounddevice) │ │(websockets)│ │ (wl-copy+ydotool) │      │
│     └──────────────┘ └────────────┘ └───────────────────┘      │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  hotkey/manager.py — Input Coordinator                    │   │
│  │  ├─ overlay_button.py (GTK always-on-top PTT button)     │   │
│  │  └─ evdev_listener.py (optional /dev/input reader)       │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌──────────────────────────────────────────────────────────┐   │
│  │  ui/overlay.py — Floating recording indicator window      │   │
│  └──────────────────────────────────────────────────────────┘   │
│                                                                  │
│  ┌──────────────┐                                               │
│  │ params_store  — JSON persistence for cookies/ids            │
│  └──────────────┘                                               │
└──────────────────────────────────────────────────────────────────┘
```

**Data flow during recording:**

```
[User triggers hotkey/overlay button]
    │
    ▼
transcription.start_recording()
    │
    ├─► audio_capture.start()  ──[16kHz Int16 PCM]──► asr_client.send_audio()
    │                                                      │
    ├─► asr_client.connect()  ◄──[JSON: result/finish]─────┘
    │         │
    │         ▼
    │   transcription.on_result(text)
    │         │
    │         ▼
    │   overlay.update_text(text)
    │
    ▼
[User triggers stop]
    │
    ├─► audio_capture.stop()
    ├─► asr_client.finish_sending()
    │         │
    │    [await final result or finish event]
    │         │
    ▼         ▼
transcription.complete()
    │
    ├─► paste_helper.copy_and_paste(text)
    │       ├─ wl-copy (clipboard)
    │       └─ ydotool key ctrl+v (paste)
    │
    └─► reset to idle
```

---

## 4. Component-by-Component Implementation Plan

### 4.1 `config.py` — Constants and Paths

```python
# Mirror the fixed params from DoubaoASRClient.swift
WSS_BASE_URL = "wss://ws-samantha.doubao.com/samantha/audio/asr"
FIXED_QUERY_PARAMS = {
    "version_code": "20800",
    "language": "zh",
    "device_platform": "web",
    "aid": "497858",
    "real_aid": "497858",
    "pkg_type": "release_version",
    "pc_version": "3.12.3",
    "region": "",
    "sys_region": "",
    "samantha_web": "1",
    "use-olympus-account": "1",
    "format": "pcm",
}
ORIGIN = "https://www.doubao.com"
LOGIN_URL = "https://www.doubao.com/chat"

# Audio
AUDIO_SAMPLE_RATE = 16000
AUDIO_CHANNELS = 1
AUDIO_DTYPE = "int16"
AUDIO_BLOCKSIZE = 4096  # samples per callback (~256ms at 16kHz)

# Auth error detection
AUTH_ERROR_CODE = 709599054
AUTH_ERROR_KEYWORDS = ["cookie", "auth", "login", "session", "unauthorized", "expired"]

# Paths
CONFIG_DIR_NAME = "doubao-murmur"
PARAMS_FILE = "asr_params.json"

# Safety timeout (seconds)
STOP_SAFETY_TIMEOUT = 1.0
DEBOUNCE_INTERVAL = 0.3

# Overlay
OVERLAY_WIDTH = 420
OVERLAY_HEIGHT = 70
```

**Storage path:** `$XDG_CONFIG_HOME/doubao-murmur/asr_params.json` (typically `~/.config/doubao-murmur/`).

---

### 4.2 `audio_capture.py` — Microphone Capture

**Mirrors:** `AudioCaptureManager.swift`

**Implementation:**

```python
import sounddevice as sd
import numpy as np
import threading
import logging

logger = logging.getLogger(__name__)

class AudioCapture:
    """Captures microphone audio at 16kHz mono Int16 PCM."""

    def __init__(self):
        self._stream = None
        self._on_audio_data = None  # callback: (bytes) -> None
        self._lock = threading.Lock()

    @property
    def is_capturing(self) -> bool:
        return self._stream is not None and self._stream.active

    def start(self, on_audio_data):
        """Start capturing. on_audio_data(bytes) called on audio thread."""
        if self.is_capturing:
            return

        self._on_audio_data = on_audio_data

        # sounddevice with PortAudio uses PulseAudio compat on PipeWire
        self._stream = sd.RawInputStream(
            samplerate=AUDIO_SAMPLE_RATE,
            channels=AUDIO_CHANNELS,
            dtype='int16',
            blocksize=AUDIO_BLOCKSIZE,
            callback=self._audio_callback,
            latency='low',
        )
        self._stream.start()
        logger.info("Audio capture started: %dHz, %d ch, int16",
                     AUDIO_SAMPLE_RATE, AUDIO_CHANNELS)

    def stop(self):
        """Stop capturing."""
        if self._stream:
            self._stream.stop()
            self._stream.close()
            self._stream = None
            self._on_audio_data = None
            logger.info("Audio capture stopped")

    def _audio_callback(self, indata, frames, time_info, status):
        """Called by PortAudio on audio thread."""
        if status:
            logger.warning("Audio callback status: %s", status)
        if self._on_audio_data:
            # indata is already int16 LE bytes from RawInputStream
            self._on_audio_data(bytes(indata))
```

**Key differences from macOS:**
- `sounddevice` (PortAudio) replaces AVAudioEngine
- No resampling needed: `sd.RawInputStream` can directly open at 16kHz; PortAudio/PipeWire handles hardware rate adaptation internally
- No Float32→Int16 conversion needed: `dtype='int16'` gives native Int16 LE PCM directly
- The asymmetric scaling (`sample < 0 ? sample * 32768 : sample * 32767`) from the macOS version is a Float32→Int16 concern; with native Int16 capture from PipeWire, the hardware ADC already produces correctly-scaled Int16 samples
- `RawInputStream` delivers raw bytes, avoiding numpy overhead per callback

**Device selection:**
```python
@staticmethod
def list_input_devices():
    """List available input devices for debugging."""
    return sd.query_devices(kind='input')

@staticmethod
def get_default_input_device():
    """Get the default input device index."""
    return sd.default.device[0]
```

---

### 4.3 `asr_client.py` — WebSocket Client

**Mirrors:** `DoubaoASRClient.swift`

**Implementation:**

```python
import asyncio
import json
import uuid
import logging
import threading
from urllib.parse import urlencode

import websockets

logger = logging.getLogger(__name__)

class ASRClient:
    """WebSocket client for Doubao's streaming ASR service."""

    def __init__(self):
        self._ws = None
        self._connected = False
        self._pending_audio = []  # buffer audio until connected
        self._lock = threading.Lock()
        self._loop = None  # asyncio event loop (separate from GTK main loop)
        self._thread = None

        # Callbacks (called from asyncio thread — must use GLib.idle_add for GTK)
        self.on_open = None       # () -> None
        self.on_result = None     # (text: str) -> None
        self.on_finish = None     # () -> None
        self.on_error = None      # (error: Exception | None) -> None
        self.on_auth_error = None # () -> None

    @property
    def is_connected(self) -> bool:
        return self._connected

    def connect(self, params, loop=None):
        """Start WebSocket connection on a background asyncio loop."""
        self._loop = loop or asyncio.new_event_loop()
        self._thread = threading.Thread(target=self._run_loop, daemon=True)
        self._thread.start()

    def _run_loop(self):
        """Run asyncio event loop in background thread."""
        asyncio.set_event_loop(self._loop)
        self._loop.run_until_complete(self._connect_and_listen())

    async def _connect_and_listen(self):
        """Build URL, connect, receive messages."""
        url = self._build_url(params)
        headers = {
            "Cookie": params.cookie_header,
            "Origin": ORIGIN,
        }

        logger.info("Connecting to ASR WebSocket...")
        try:
            async with websockets.connect(
                url,
                extra_headers=headers,
                open_timeout=5,
                close_timeout=3,
                max_size=2**20,
            ) as ws:
                self._ws = ws
                self._connected = True
                logger.info("Connected")

                # Flush buffered audio
                self._flush_audio_buffer()

                if self.on_open:
                    self.on_open()

                # Receive loop
                async for message in ws:
                    self._handle_message(message)

        except websockets.exceptions.ConnectionClosed as e:
            logger.warning("WebSocket closed: %s", e)
            if self._connected:
                self._connected = False
                if self.on_error:
                    self.on_error(e)
        except Exception as e:
            logger.error("WebSocket error: %s", e)
            self._connected = False
            if self.on_error:
                self.on_error(e)

    def _build_url(self, params) -> str:
        """Construct the full WSS URL with query parameters."""
        query = dict(FIXED_QUERY_PARAMS)
        query["device_id"] = params.device_id
        query["web_id"] = params.web_id
        query["tea_uuid"] = params.web_id
        query["web_tab_id"] = str(uuid.uuid4())
        return f"{WSS_BASE_URL}?{urlencode(query)}"

    def send_audio(self, data: bytes):
        """Send PCM audio data. Thread-safe. Buffers if not connected."""
        with self._lock:
            if self._connected and self._ws:
                # Schedule send on asyncio loop
                if self._loop and self._loop.is_running():
                    asyncio.run_coroutine_threadsafe(
                        self._ws.send(data), self._loop
                    )
            else:
                self._pending_audio.append(data)

    def finish_sending(self):
        """Signal no more audio; keep WS open for final results."""
        with self._lock:
            self._pending_audio.clear()
            self._connected = False
        logger.info("Finished sending audio, waiting for server response")

    def disconnect(self):
        """Close the WebSocket."""
        self._connected = False
        with self._lock:
            self._pending_audio.clear()
        if self._ws and self._loop and self._loop.is_running():
            asyncio.run_coroutine_threadsafe(self._ws.close(1000, "1000-"), self._loop)
        self._ws = None
        logger.info("Disconnected")

    def _flush_audio_buffer(self):
        with self._lock:
            buffered = list(self._pending_audio)
            self._pending_audio.clear()
        if buffered:
            logger.info("Flushing %d buffered audio chunks", len(buffered))
            for chunk in buffered:
                asyncio.run_coroutine_threadsafe(
                    self._ws.send(chunk), self._loop
                )

    def _handle_message(self, message):
        """Parse JSON message from server."""
        if isinstance(message, bytes):
            message = message.decode('utf-8', errors='replace')

        try:
            data = json.loads(message)
        except json.JSONDecodeError:
            return

        code = data.get("code", 0)
        event = data.get("event", "")
        msg = data.get("message", "")

        # Detect auth errors
        if code != 0:
            lower_msg = msg.lower()
            if (code == AUTH_ERROR_CODE or
                any(kw in lower_msg for kw in AUTH_ERROR_KEYWORDS)):
                logger.warning("Auth error: code=%d, message=%s", code, msg)
                self._connected = False
                if self.on_auth_error:
                    self.on_auth_error()
                return

        if event == "result":
            result = data.get("result") or {}
            text = result.get("Text", "")
            if text and self.on_result:
                self.on_result(text)
        elif event == "finish":
            logger.info("Received finish event")
            if self.on_finish:
                self.on_finish()
```

**Key design decisions:**
- `websockets` library (pure Python, asyncio-native) replaces URLSessionWebSocketTask
- Runs on a dedicated asyncio event loop in a background thread to avoid blocking GTK's GLib main loop
- Callbacks are invoked on the asyncio thread; callers MUST use `GLib.idle_add()` to marshal back to GTK's main thread
- Audio buffering matches macOS behavior: buffer until connected, then flush
- `send_audio` uses `asyncio.run_coroutine_threadsafe` for thread-safe WebSocket sends

---

### 4.4 `transcription.py` — State Machine Orchestrator

**Mirrors:** `TranscriptionManager.swift`

```python
from enum import Enum
import logging
import threading
from gi.repository import GLib

logger = logging.getLogger(__name__)

class RecordingState(Enum):
    IDLE = "idle"
    STARTING = "starting"
    RECORDING = "recording"
    STOPPING = "stopping"

class LoginStatus(Enum):
    CHECKING = "checking"
    LOGGED_IN = "logged_in"
    NOT_LOGGED_IN = "not_logged_in"

class TranscriptionManager:
    """Orchestrates the recording lifecycle."""

    def __init__(self, app_state, login_window, overlay, hotkey_manager):
        self.app_state = app_state
        self.login_window = login_window
        self.overlay = overlay
        self.hotkey_manager = hotkey_manager
        self.asr_client = ASRClient()
        self.audio_capture = AudioCapture()

        self.using_cached_params = False
        self.awaiting_final_result = False
        self.safety_timer_id = None

        self.on_auth_expired = None  # callback

    def start(self):
        """Wire up callbacks and start listening for hotkey events."""
        # Wire hotkey events
        self.hotkey_manager.on_toggle = self._handle_toggle
        self.hotkey_manager.on_cancel = self._handle_cancel

        # Wire ASR callbacks (these come from asyncio thread → marshal to GTK)
        self.asr_client.on_open = lambda: GLib.idle_add(self._on_asr_open)
        self.asr_client.on_result = lambda text: GLib.idle_add(self._on_asr_result, text)
        self.asr_client.on_finish = lambda: GLib.idle_add(self._on_asr_finish)
        self.asr_client.on_error = lambda err: GLib.idle_add(self._on_asr_error, err)
        self.asr_client.on_auth_error = lambda: GLib.idle_add(self._on_auth_error)

        # Pipe audio to ASR client
        self.audio_capture.on_audio_data = self.asr_client.send_audio

        # Wire overlay cancel button
        self.overlay.on_cancel = self._handle_cancel

        logger.info("TranscriptionManager started")

    # --- Toggle ---

    def _handle_toggle(self):
        """Called on GTK main thread from hotkey manager."""
        state = self.app_state.recording_state
        if state == RecordingState.IDLE:
            self._start_recording()
        elif state in (RecordingState.STARTING, RecordingState.RECORDING):
            self._stop_recording()
        # STOPPING: ignore

    def _start_recording(self):
        if self.app_state.login_status != LoginStatus.LOGGED_IN:
            self.login_window.show()
            return

        logger.info("Starting recording...")
        self._set_state(RecordingState.STARTING)
        self.app_state.transcription_text = ""
        self.app_state.error_message = None
        self.overlay.show()

        # Start audio immediately (buffered in ASR client until WS connects)
        try:
            self.audio_capture.start(on_audio_data=self.asr_client.send_audio)
        except Exception as e:
            logger.error("Audio capture failed: %s", e)
            self.app_state.error_message = "麦克风启动失败"
            self._reset_to_idle()
            return

        # Try cached params first, fall back to WebView extraction
        cached = ParamsStore.load()
        if cached:
            logger.info("Using cached ASR params")
            self.using_cached_params = True
            self.asr_client.connect(cached)
        elif self.login_window.is_active:
            self.using_cached_params = False
            # Async param extraction from WebView
            self.login_window.extract_params_async(self._on_params_extracted)
        else:
            self.app_state.error_message = "无法获取连接参数，请重新登录"
            GLib.timeout_add(2000, self._reset_to_idle)

    def _stop_recording(self):
        logger.info("Stopping recording...")
        self._set_state(RecordingState.STOPPING)
        self.audio_capture.stop()
        self.asr_client.finish_sending()
        self.awaiting_final_result = True

        # Safety timeout: 1 second
        self.safety_timer_id = GLib.timeout_add(
            int(STOP_SAFETY_TIMEOUT * 1000),
            self._safety_timeout
        )

    def _safety_timeout(self):
        if self.app_state.recording_state == RecordingState.STOPPING:
            logger.info("Safety timeout, completing with current text")
            self.awaiting_final_result = False
            self._complete_transcription()
        self.safety_timer_id = None
        return GLib.SOURCE_REMOVE

    # --- ASR callbacks (on GTK main thread via GLib.idle_add) ---

    def _on_asr_open(self):
        if self.app_state.recording_state == RecordingState.STARTING:
            self._set_state(RecordingState.RECORDING)
        return GLib.SOURCE_REMOVE

    def _on_asr_result(self, text):
        self.app_state.transcription_text = text
        if self.app_state.recording_state == RecordingState.STARTING:
            self._set_state(RecordingState.RECORDING)
        if self.awaiting_final_result:
            self.awaiting_final_result = False
            self._complete_transcription()
        return GLib.SOURCE_REMOVE

    def _on_asr_finish(self):
        self.awaiting_final_result = False
        if self.app_state.recording_state in (RecordingState.STOPPING, RecordingState.RECORDING):
            self._complete_transcription()
        return GLib.SOURCE_REMOVE

    def _on_asr_error(self, error):
        if self.app_state.recording_state == RecordingState.IDLE:
            return GLib.SOURCE_REMOVE
        if self.using_cached_params:
            self._handle_auth_failure()
            return GLib.SOURCE_REMOVE
        self.app_state.error_message = "连接出错"
        GLib.timeout_add(2000, self._reset_to_idle)
        return GLib.SOURCE_REMOVE

    def _on_auth_error(self):
        self._handle_auth_failure()
        return GLib.SOURCE_REMOVE

    # --- Completion & Reset ---

    def _complete_transcription(self):
        text = self.app_state.transcription_text.strip()
        logger.info("Completing transcription: '%s'", text[:50])
        if text:
            PasteHelper.copy_and_paste(text)
        self._reset_to_idle()

    def _reset_to_idle(self):
        self.awaiting_final_result = False
        self.audio_capture.stop()
        self.asr_client.disconnect()
        self._set_state(RecordingState.IDLE)
        self.overlay.hide()
        self.app_state.error_message = None
        self.using_cached_params = False
        # Clear text after short delay
        GLib.timeout_add(200, lambda: setattr(self.app_state, 'transcription_text', '') or GLib.SOURCE_REMOVE)
        return GLib.SOURCE_REMOVE

    def _handle_cancel(self):
        if self.app_state.recording_state == RecordingState.IDLE:
            return
        logger.info("Cancelling transcription")
        self.awaiting_final_result = False
        self.audio_capture.stop()
        self.asr_client.disconnect()
        self._reset_to_idle()

    def _handle_auth_failure(self):
        logger.warning("Auth failure, clearing cached params")
        ParamsStore.clear()
        self.using_cached_params = False
        self.audio_capture.stop()
        self.asr_client.disconnect()
        self._reset_to_idle()
        self.app_state.login_status = LoginStatus.NOT_LOGGED_IN
        if self.on_auth_expired:
            self.on_auth_expired()

    def _set_state(self, new_state):
        self.app_state.recording_state = new_state
        self.hotkey_manager.set_cancel_enabled(new_state != RecordingState.IDLE)

    def _on_params_extracted(self, params):
        """Called when WebView param extraction completes."""
        if params:
            ParamsStore.save(params)
            self.asr_client.connect(params)
        else:
            self.app_state.error_message = "无法获取连接参数，请重新登录"
            GLib.timeout_add(2000, self._reset_to_idle)
```

**Key design decisions:**
- `GLib.idle_add()` marshals callbacks from the asyncio thread to the GTK main thread — this is the PyGObject equivalent of `Task { @MainActor in }` in Swift
- `GLib.timeout_add()` replaces `DispatchQueue.main.asyncAfter` for delayed execution
- State machine exactly mirrors the macOS version: `idle → starting → recording → stopping → idle`

---

### 4.5 `hotkey/manager.py` — Input Coordinator

```python
class HotkeyManager:
    """Coordinates input methods for triggering recording."""

    def __init__(self):
        self.on_toggle = None   # () -> None
        self.on_cancel = None   # () -> None
        self._overlay_button = None
        self._evdev_listener = None
        self._cancel_enabled = False
        self._last_toggle_time = 0

    def start(self, use_evdev=False):
        """Initialize input backends."""
        # PRIMARY: Always-on-top overlay button
        self._overlay_button = OverlayButton(
            on_press=self._debounced_toggle,
            on_cancel=self._handle_cancel,
        )
        self._overlay_button.start()

        # OPTIONAL: evdev listener for physical keyboard
        if use_evdev:
            self._evdev_listener = EvdevListener(
                on_toggle=self._debounced_toggle,
                on_escape=self._handle_cancel,
            )
            if self._evdev_listener.start():
                logger.info("evdev listener active")
            else:
                logger.warning("evdev not available (need input group?)")

    def _debounced_toggle(self):
        now = time.monotonic()
        if now - self._last_toggle_time < DEBOUNCE_INTERVAL:
            return
        self._last_toggle_time = now
        if self.on_toggle:
            self.on_toggle()

    def _handle_cancel(self):
        if self._cancel_enabled and self.on_cancel:
            self.on_cancel()

    def set_cancel_enabled(self, enabled):
        self._cancel_enabled = enabled
```

---

### 4.6 `hotkey/overlay_button.py` — PRIMARY Input Method

This is an always-on-top GTK4 window with a push-to-talk button. It is the **primary** input method because Wayland's security model prevents global keyboard shortcuts.

```python
import gi
gi.require_version('Gtk', '4.0')
from gi.repository import Gtk, Gdk, GLib

class OverlayButton:
    """Small always-on-top push-to-talk button.

    Design:
    - Positioned at bottom-center of screen (near game UI on Steam Deck)
    - Semi-transparent, minimal footprint (~60x60px)
    - Shows only when app is logged in
    - Click/tap to toggle recording
    - Long-press and release for push-to-talk (future)
    - Contains a small cancel area when recording is active
    """

    def __init__(self, on_press, on_cancel):
        self.on_press = on_press
        self.on_cancel = on_cancel
        self._window = None

    def start(self):
        self._window = Gtk.Window()
        self._window.set_title("Doubao Murmur PTT")
        self._window.set_decorated(False)
        self._window.set_default_size(70, 70)
        self._window.set_resizable(False)
        self._window.set_keep_above(True)  # always on top

        # Make window click-through except for the button area
        # On Wayland, use layer-shell protocol if available (KDE)
        # Fallback: standard always-on-top window

        # Position: bottom-center of screen
        display = Gdk.Display.get_default()
        monitor = display.get_monitors().get_item(0)
        if monitor:
            geo = monitor.get_geometry()
            x = geo.x + geo.width // 2 - 35
            y = geo.y + geo.height - 100
            self._window.move(x, y) if hasattr(self._window, 'move') else None
            # GTK4: use present() and hope the compositor places it

        # Create circular button
        button = Gtk.Button()
        button.add_css_class("ptt-button")
        button.set_label("🎤")
        button.connect("clicked", lambda _: self.on_press())
        self._window.set_child(button)

        # CSS for circular semi-transparent button
        css = b"""
        .ptt-button {
            background: rgba(40, 40, 40, 0.85);
            border-radius: 35px;
            border: 2px solid rgba(255, 255, 255, 0.3);
            color: white;
            font-size: 24px;
            min-width: 60px;
            min-height: 60px;
        }
        .ptt-button:hover {
            background: rgba(60, 60, 60, 0.9);
        }
        .ptt-button.recording {
            background: rgba(200, 40, 40, 0.85);
            border-color: rgba(255, 100, 100, 0.6);
        }
        """
        provider = Gtk.CssProvider()
        provider.load_from_data(css)
        Gtk.StyleContext.add_provider_for_display(
            display, provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )

    def show(self):
        if self._window:
            self._window.present()

    def hide(self):
        if self._window:
            self._window.set_visible(False)

    def set_recording_state(self, is_recording):
        """Update button visual state."""
        if self._window:
            button = self._window.get_child()
            if is_recording:
                button.add_css_class("recording")
                button.set_label("⏹")
            else:
                button.remove_css_class("recording")
                button.set_label("🎤")
```

**Design rationale:**
- A visible button is the most reliable cross-compositor method
- On Steam Deck's 1280×800 touchscreen, a bottom-center button is thumb-accessible
- 60×60px is large enough for touch but small enough to not obstruct
- Semi-transparent dark background matches KDE Plasma's Breeze Dark theme
- When recording, the button turns red and shows a stop icon

**Future enhancement (KDE-specific):**
If KDE's `wlr-layer-shell-unstable-v1` protocol is available, the button can be rendered as a layer-shell surface that stays above all windows without stealing focus. This requires `gtk4-layer-shell` or direct Wayland protocol usage.

---

### 4.7 `hotkey/evdev_listener.py` — OPTIONAL Physical Keyboard Listener

```python
import os
import struct
import threading
import logging
import glob

logger = logging.getLogger(__name__)

# evdev event structure: struct input_event { time_t tv_sec; suseconds_t tv_usec; unsigned short type; unsigned short code; int value; }
# On 64-bit: 24 bytes per event
EV_KEY = 0x01
KEY_ESC = 1
KEY_RIGHTALT = 100

class EvdevListener:
    """Reads /dev/input/event* devices for global hotkeys.

    Requires: user must be in the 'input' group.
    Listens for:
    - Right Alt (KEY_RIGHTALT=100) press-and-release → toggle
    - ESC (KEY_ESC=1) → cancel
    """

    def __init__(self, on_toggle, on_escape):
        self.on_toggle = on_toggle
        self.on_escape = on_escape
        self._thread = None
        self._running = False
        self._right_alt_down = False
        self._other_key_pressed = False

    def start(self) -> bool:
        """Start listening. Returns False if no accessible devices."""
        devices = self._find_keyboard_devices()
        if not devices:
            logger.warning("No accessible evdev input devices found")
            return False

        self._running = True
        self._thread = threading.Thread(
            target=self._listen_loop, args=(devices,), daemon=True
        )
        self._thread.start()
        return True

    def stop(self):
        self._running = False

    def _find_keyboard_devices(self):
        """Find /dev/input/event* files that are readable."""
        accessible = []
        for path in sorted(glob.glob("/dev/input/event*")):
            try:
                fd = os.open(path, os.O_RDONLY | os.O_NONBLOCK)
                os.close(fd)
                accessible.append(path)
            except PermissionError:
                continue
        return accessible

    def _listen_loop(self, devices):
        """Main read loop using select() for multiplexing."""
        import select

        fds = {}
        for path in devices:
            try:
                fd = os.open(path, os.O_RDONLY)
                fds[fd] = path
            except Exception as e:
                logger.warning("Cannot open %s: %s", path, e)

        if not fds:
            return

        event_size = 24  # sizeof(struct input_event) on 64-bit Linux
        buf_size = event_size * 16

        try:
            while self._running:
                readable, _, _ = select.select(list(fds.keys()), [], [], 0.5)
                for fd in readable:
                    try:
                        data = os.read(fd, buf_size)
                    except OSError:
                        continue
                    for i in range(0, len(data), event_size):
                        if i + event_size > len(data):
                            break
                        event = struct.unpack('llHHi', data[i:i+event_size])
                        # event: (tv_sec, tv_usec, type, code, value)
                        ev_type = event[2]
                        ev_code = event[3]
                        ev_value = event[4]

                        if ev_type != EV_KEY:
                            continue

                        if ev_code == KEY_RIGHTALT:
                            if ev_value == 1:  # press
                                self._right_alt_down = True
                                self._other_key_pressed = False
                            elif ev_value == 0:  # release
                                if self._right_alt_down and not self._other_key_pressed:
                                    self.on_toggle()
                                self._right_alt_down = False
                        elif ev_code != KEY_RIGHTALT and ev_value == 1:
                            if self._right_alt_down:
                                self._other_key_pressed = True
                            if ev_code == KEY_ESC:
                                self.on_escape()
        finally:
            for fd in fds:
                os.close(fd)
```

**Setup requirements:**
```bash
# User must be in the input group
sudo usermod -aG input $USER
# Then log out and back in

# For Flatpak, the app needs --device=all
# Or run with: flatpak override --device=all com.doubao.Murmur
```

---

### 4.8 `ui/overlay.py` — Floating Recording Indicator

**Mirrors:** `OverlayPanel.swift` + `OverlayView.swift`

```python
class Overlay:
    """Floating overlay window showing recording status and transcription text.

    Mirrors the macOS OverlayPanel:
    - Top-center of screen
    - Semi-transparent dark background with rounded corners
    - Shows: spinner (starting) | pulsing red dot (recording) + text
    - ESC key handler for cancellation
    """

    def __init__(self, app_state):
        self.app_state = app_state
        self.on_cancel = None
        self._window = None
        self._label = None
        self._indicator = None
        self._css_applied = False

    def _create_window(self):
        self._window = Gtk.Window()
        self._window.set_title("Doubao Murmur")
        self._window.set_decorated(False)
        self._window.set_default_size(OVERLAY_WIDTH, OVERLAY_HEIGHT)
        self._window.set_resizable(False)
        self._window.set_keep_above(True)
        self._window.set_accept_focus(True)  # Need focus for ESC key

        # Main container
        box = Gtk.Box(orientation=Gtk.Orientation.HORIZONTAL, spacing=12)
        box.set_margin_start(16)
        box.set_margin_end(16)
        box.set_margin_top(10)
        box.set_margin_bottom(10)

        # Recording indicator
        self._indicator = Gtk.DrawingArea()
        self._indicator.set_size_request(14, 14)
        self._indicator.set_draw_func(self._draw_indicator)
        box.append(self._indicator)

        # Transcription text label
        self._label = Gtk.Label()
        self._label.set_xalign(0)
        self._label.set_ellipsize(3)  # Pango.EllipsizeMode.END
        self._label.set_max_width_chars(40)
        self._label.set_lines(2)
        self._label.set_wrap(True)
        box.append(self._label)

        self._window.set_child(box)

        # Key press handler for ESC
        key_controller = Gtk.EventControllerKey()
        key_controller.connect("key-pressed", self._on_key_pressed)
        self._window.add_controller(key_controller)

        # CSS
        self._apply_css()

    def _apply_css(self):
        css = b"""
        window {
            background: rgba(30, 30, 30, 0.95);
            border-radius: 12px;
            border: 1px solid rgba(255, 255, 255, 0.15);
        }
        label {
            color: white;
            font-size: 14px;
            font-weight: 500;
        }
        """
        provider = Gtk.CssProvider()
        provider.load_from_data(css)
        display = Gdk.Display.get_default()
        Gtk.StyleContext.add_provider_for_display(
            display, provider, Gtk.STYLE_PROVIDER_PRIORITY_APPLICATION
        )

    def _on_key_pressed(self, controller, keyval, keycode, state):
        if keyval == Gdk.KEY_Escape:
            if self.on_cancel:
                self.on_cancel()
            return True
        return False

    def _draw_indicator(self, area, cr, width, height):
        """Draw recording indicator: spinner or pulsing red dot."""
        state = self.app_state.recording_state
        if state == RecordingState.STARTING:
            # Spinner: arc
            import math
            import time
            angle = (time.time() * 4 * math.pi) % (2 * math.pi)
            cr.set_source_rgba(1, 1, 1, 0.8)
            cr.set_line_width(2)
            cr.arc(width/2, height/2, 5, angle, angle + math.pi * 1.6)
            cr.stroke()
            # Request redraw for animation
            GLib.idle_add(lambda: area.queue_draw() or GLib.SOURCE_REMOVE)
        elif state == RecordingState.RECORDING:
            # Pulsing red dot
            import math, time
            pulse = 0.5 + 0.5 * math.sin(time.time() * 5)
            cr.set_source_rgba(1, 0.2, 0.2, pulse)
            cr.arc(width/2, height/2, 5, 0, 2 * math.pi)
            cr.fill()
            GLib.idle_add(lambda: area.queue_draw() or GLib.SOURCE_REMOVE)
        elif state == RecordingState.STOPPING:
            # Static gray dot
            cr.set_source_rgba(0.5, 0.5, 0.5, 0.8)
            cr.arc(width/2, height/2, 5, 0, 2 * math.pi)
            cr.fill()

    def show(self):
        if not self._window:
            self._create_window()
        self._update_content()
        self._position_top_center()
        self._window.present()

    def hide(self):
        if self._window:
            self._window.set_visible(False)

    def update_text(self, text):
        """Called when transcription text updates."""
        if self._label:
            if self.app_state.error_message:
                self._label.set_text(self.app_state.error_message)
                self._label.add_css_class("error-text")
            elif text:
                self._label.set_text(text)
            else:
                self._label.set_text(self._status_text())

    def _status_text(self):
        state = self.app_state.recording_state
        return {
            RecordingState.STARTING: "正在启动语音识别...",
            RecordingState.RECORDING: "正在聆听...",
            RecordingState.STOPPING: "正在处理...",
        }.get(state, "")

    def _position_top_center(self):
        """Position overlay at top-center of the primary monitor."""
        display = Gdk.Display.get_default()
        monitor = display.get_monitors().get_item(0)
        if monitor:
            geo = monitor.get_geometry()
            # On Steam Deck: 1280x800, so center = 640
            x = geo.x + (geo.width - OVERLAY_WIDTH) // 2
            y = geo.y + 20
            # GTK4 doesn't have window.move(); use present with hints
            # Alternative: use Gdk.Toplevel.present() with layout hints
            # For KDE/Wayland, the compositor may ignore position requests
            # Workaround: use gtk4-layer-shell if available
```

**Steam Deck considerations (1280×800):**
- Overlay width 420px fits well within 1280px screen width
- Height 70px leaves ample room for the active application below
- Top-center placement matches the macOS version
- On Wayland, window positioning is compositor-controlled; KDE Plasma respects `set_keep_above` but may not honor exact coordinates — fallback to layer-shell protocol if needed

---

### 4.9 `ui/login_window.py` — WebKitGTK Login

**Mirrors:** `WebViewManager.swift`

```python
import gi
gi.require_version('Gtk', '4.0')
gi.require_version('WebKit', '6.0')
from gi.repository import Gtk, WebKit, GLib, Gio

class LoginWindow:
    """WebKitGTK-based login window for doubao.com.

    Mirrors the macOS WebViewManager:
    - Loads doubao.com/chat in a WebKitGTK WebView
    - Injects JS to detect login via /alice/profile/self API interception
    - Extracts cookies + localStorage params after login
    - Destroys WebView after params are extracted to free memory
    """

    def __init__(self, app_state):
        self.app_state = app_state
        self._window = None
        self._webview = None
        self._on_login_status_change = None

    @property
    def is_active(self) -> bool:
        return self._webview is not None

    def _setup(self):
        if self._webview:
            return

        # WebKitGTK 6.0 configuration
        settings = WebKit.Settings()
        settings.set_property("enable-developer-extras", True)
        settings.set_property("enable-media-stream", True)
        settings.set_property("user-agent",
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36")

        # User content manager for JS injection
        user_content = WebKit.UserContentManager()

        # Inject login detection JS at document start
        ws_js = self._load_js_resource("inject-websocket.js")
        if ws_js:
            # Adapt JS for WebKitGTK: replace webkit.messageHandlers with
            # WebKit's user script message API
            adapted_js = ws_js.replace(
                "window.webkit.messageHandlers.asrHandler.postMessage",
                "window.webkit.messageHandlers.asr_handler.postMessage"
            )
            script = WebKit.UserScript(
                source=adapted_js,
                injected_frames=WebKit.UserScriptInjectedFrames.ALL_FRAMES,
                injection_time=WebKit.UserScriptInjectionTime.START,
            )
            user_content.add_script(script)

        # Inject DOM helpers at document end
        dom_js = self._load_js_resource("inject-dom.js")
        if dom_js:
            script = WebKit.UserScript(
                source=dom_js,
                injected_frames=WebKit.UserScriptInjectedFrames.TOP_FRAME,
                injection_time=WebKit.UserScriptInjectionTime.END,
            )
            user_content.add_script(script)

        # Register message handler (WebKitGTK 6.0 API)
        user_content.register_script_message_handler("asr_handler", None)
        user_content.connect("script-message-received::asr_handler",
                            self._on_script_message)

        # Create WebView
        self._webview = WebKit.WebView.new_with_user_content_manager(user_content)
        self._webview.set_settings(settings)
        self._webview.connect("load-changed", self._on_load_changed)
        self._webview.connect("decide-policy", self._on_decide_policy)

        # Create window
        self._window = Gtk.Window()
        self._window.set_title("Doubao Murmur - 登录")
        self._window.set_default_size(1280, 800)
        self._window.set_child(self._webview)
        self._window.connect("close-request", self._on_close_request)

    def load(self):
        self._setup()
        self._webview.load_uri(LOGIN_URL)

    def show(self):
        if not self._webview:
            self.load()
        self._window.present()

    def hide(self):
        if self._window:
            self._window.set_visible(False)

    def destroy(self):
        """Destroy WebView to free memory (mirrors destroyWebView())."""
        if self._webview:
            self._webview.stop_loading()
            self._webview = None
        if self._window:
            self._window.destroy()
            self._window = None
        logger.info("WebView destroyed")

    def extract_params_async(self, callback):
        """Extract cookies + localStorage params from WebView.

        callback(params: ASRParams | None) called on GTK main thread.
        """
        if not self._webview:
            callback(None)
            return

        # Step 1: Get cookies via WebKit.CookieManager
        cookie_manager = self._webview.get_website_data_manager().get_cookie_manager()

        def on_cookies(result, task):
            try:
                cookies = cookie_manager.get_cookies_finish(result)
            except Exception:
                callback(None)
                return

            doubao_cookies = {}
            for cookie in cookies:
                domain = cookie.get_domain()
                if "doubao.com" in domain:
                    doubao_cookies[cookie.get_name()] = cookie.get_value()

            if not doubao_cookies:
                logger.warning("No doubao.com cookies found")
                GLib.idle_add(callback, None)
                return

            # Step 2: Extract localStorage values via JS
            self._extract_local_storage(doubao_cookies, callback)

        cookie_manager.get_cookies(
            LOGIN_URL,
            None,  # cancellable
            on_cookies,
        )

    def _extract_local_storage(self, cookies, callback):
        """Extract device_id and web_id from localStorage."""
        js_code = """
        JSON.stringify({
            device_id_raw: localStorage.getItem('samantha_web_web_id'),
            tea_cache_raw: localStorage.getItem('__tea_cache_tokens_497858')
        })
        """

        def on_js_result(result, task):
            try:
                js_value = self._webview.evaluate_javascript_finish(result)
                # js_value is a JSC.Value
                json_str = js_value.to_string()
                data = json.loads(json_str)

                device_id = ""
                web_id = ""

                if data.get("device_id_raw"):
                    parsed = json.loads(data["device_id_raw"])
                    device_id = parsed.get("web_id", "")

                if data.get("tea_cache_raw"):
                    parsed = json.loads(data["tea_cache_raw"])
                    web_id = parsed.get("web_id", "")

                if device_id and web_id:
                    params = ASRParams(
                        cookies=cookies,
                        device_id=device_id,
                        web_id=web_id,
                    )
                    logger.info("Params extracted: %d cookies, device=%s, web=%s",
                               len(cookies), device_id[:10], web_id[:10])
                    GLib.idle_add(callback, params)
                else:
                    logger.warning("Missing localStorage params: device=%s, web=%s",
                                  device_id, web_id)
                    GLib.idle_add(callback, None)
            except Exception as e:
                logger.error("JS evaluation failed: %s", e)
                GLib.idle_add(callback, None)

        self._webview.evaluate_javascript(
            js_code, -1,
            None,  # world_name
            None,  # source_uri
            None,  # cancellable
            on_js_result,
        )

    def _on_script_message(self, manager, js_result):
        """Handle messages from injected JS (login detection)."""
        try:
            json_str = js_result.to_string()
            data = json.loads(json_str)
        except Exception:
            return

        msg_type = data.get("type")
        if msg_type == "login":
            status = data.get("status", "unknown")
            nickname = data.get("nickname")
            if self._on_login_status_change:
                self._on_login_status_change(status, nickname)

    def _on_load_changed(self, webview, event):
        if event == WebKit.LoadEvent.FINISHED:
            # Delayed login state check (fallback)
            GLib.timeout_add(2000, self._check_login_fallback)

    def _on_decide_policy(self, webview, decision, decision_type):
        """Detect login redirect via URL parameter."""
        if decision_type == WebKit.PolicyDecisionType.NAVIGATION_ACTION:
            nav_action = decision.get_navigation_action()
            uri = nav_action.get_request().get_uri()
            if "from_login=1" in uri:
                self.app_state.login_status = LoginStatus.LOGGED_IN
                self.hide()
        return False  # Allow default handling

    def _check_login_fallback(self):
        """Fallback: check if login button is present in DOM."""
        if not self._webview:
            return GLib.SOURCE_REMOVE
        js = "window.__doubaoMurmur && window.__doubaoMurmur.isLoginButtonPresent()"
        self._webview.evaluate_javascript(
            js, -1, None, None, None,
            self._on_login_check_result,
        )
        return GLib.SOURCE_REMOVE

    def _on_login_check_result(self, result, task):
        try:
            val = self._webview.evaluate_javascript_finish(result)
            if val.to_boolean():
                if self.app_state.login_status == LoginStatus.CHECKING:
                    self.app_state.login_status = LoginStatus.NOT_LOGGED_IN
        except Exception:
            pass

    def _on_close_request(self, window):
        """Hide instead of destroy when user closes the window."""
        window.set_visible(False)
        return True  # Prevent default destroy

    def _load_js_resource(self, name):
        """Load JS file from resources directory."""
        import importlib.resources
        try:
            pkg = importlib.resources.files("doubao_murmur.resources")
            return (pkg / name).read_text(encoding="utf-8")
        except Exception as e:
            logger.warning("Cannot load JS resource %s: %s", name, e)
            return None
```

**JS injection adaptation notes:**

The macOS `inject-websocket.js` uses `window.webkit.messageHandlers.asr_handler.postMessage(obj)` which is the WKWebView API. WebKitGTK 6.0 uses the **same API** (`window.webkit.messageHandlers.<name>.postMessage()`), so the JS requires minimal changes — only the handler name normalization (`asr_handler`).

---

### 4.10 `paste/paste_helper.py` — Clipboard + Paste Simulation

**Mirrors:** `PasteHelper.swift`

```python
import subprocess
import shutil
import logging
import time

logger = logging.getLogger(__name__)

class PasteHelper:
    """Copy text to clipboard and simulate Ctrl+V paste.

    Methods (in priority order):
    1. wl-copy (Wayland clipboard) + ydotool (paste simulation)
    2. xclip/xsel (X11 clipboard) + xdotool (X11 paste simulation)
    3. Fallback: copy only, no paste simulation
    """

    @staticmethod
    def copy_and_paste(text: str):
        if not text:
            return

        # Copy to clipboard
        PasteHelper._copy_to_clipboard(text)

        # Short delay then paste
        time.sleep(0.05)
        PasteHelper._simulate_paste()

    @staticmethod
    def copy_only(text: str):
        if not text:
            return
        PasteHelper._copy_to_clipboard(text)

    @staticmethod
    def _copy_to_clipboard(text: str):
        """Copy text to system clipboard."""
        # Try Wayland first
        if shutil.which("wl-copy"):
            try:
                subprocess.run(
                    ["wl-copy", "--", text],
                    input=text.encode(),
                    check=True,
                    timeout=3,
                )
                logger.info("Copied to clipboard via wl-copy")
                return
            except Exception as e:
                logger.warning("wl-copy failed: %s", e)

        # Try X11
        if shutil.which("xclip"):
            try:
                subprocess.run(
                    ["xclip", "-selection", "clipboard"],
                    input=text.encode(),
                    check=True,
                    timeout=3,
                )
                logger.info("Copied to clipboard via xclip")
                return
            except Exception as e:
                logger.warning("xclip failed: %s", e)

        # Try xsel
        if shutil.which("xsel"):
            try:
                subprocess.run(
                    ["xsel", "--clipboard", "--input"],
                    input=text.encode(),
                    check=True,
                    timeout=3,
                )
                logger.info("Copied to clipboard via xsel")
                return
            except Exception as e:
                logger.warning("xsel failed: %s", e)

        # GTK clipboard as last resort (works within GTK apps only)
        try:
            from gi.repository import Gdk, GLib
            display = Gdk.Display.get_default()
            clipboard = display.get_clipboard()
            clipboard.set(text)
            logger.info("Copied to clipboard via GTK")
        except Exception as e:
            logger.error("All clipboard methods failed: %s", e)

    @staticmethod
    def _simulate_paste():
        """Simulate Ctrl+V keystroke."""
        # Try ydotool (works on both Wayland and X11)
        if shutil.which("ydotool"):
            try:
                # ydotool key codes: ctrl=29, v=47
                subprocess.run(
                    ["ydotool", "key", "ctrl+v"],
                    check=True,
                    timeout=3,
                )
                logger.info("Paste simulated via ydotool")
                return
            except Exception as e:
                logger.warning("ydotool failed: %s", e)

        # Try wtype (Wayland virtual keyboard)
        if shutil.which("wtype"):
            try:
                subprocess.run(
                    ["wtype", "-M", "ctrl", "-P", "v", "-m", "ctrl"],
                    check=True,
                    timeout=3,
                )
                logger.info("Paste simulated via wtype")
                return
            except Exception as e:
                logger.warning("wtype failed: %s", e)

        # Try xdotool (X11 only)
        if shutil.which("xdotool"):
            try:
                subprocess.run(
                    ["xdotool", "key", "ctrl+v"],
                    check=True,
                    timeout=3,
                )
                logger.info("Paste simulated via xdotool")
                return
            except Exception as e:
                logger.warning("xdotool failed: %s", e)

        logger.error("No paste simulation method available")
        logger.info("Text was copied to clipboard but could not auto-paste. "
                     "Install ydotool or wtype for auto-paste.")
```

**ydotool setup requirements:**
```bash
# Install ydotool
sudo pacman -S ydotool

# Enable ydotoold daemon (required for ydotool to work)
sudo systemctl enable --now ydotoold

# Add user to input group (ydotool needs /dev/input access)
sudo usermod -aG input $USER
# Log out and back in

# For Flatpak:
# Option A: Run ydotoold on the host, Flatpak connects via socket
# Option B: flatpak override --device=all com.doubao.Murmur
```

---

### 4.11 `params_store.py` — Credential Persistence

**Mirrors:** `ASRParamsStore.swift`

```python
import json
import os
import logging
from pathlib import Path
from dataclasses import dataclass, asdict

logger = logging.getLogger(__name__)

@dataclass
class ASRParams:
    cookies: dict[str, str]
    device_id: str
    web_id: str

    @property
    def cookie_header(self) -> str:
        return "; ".join(f"{k}={v}" for k, v in self.cookies.items())

class ParamsStore:
    """Persist ASR params to JSON file.

    Location: $XDG_CONFIG_HOME/doubao-murmur/asr_params.json
    Typically: ~/.config/doubao-murmur/asr_params.json
    """

    @staticmethod
    def _path() -> Path:
        config_dir = Path(os.environ.get("XDG_CONFIG_HOME",
                                         Path.home() / ".config"))
        app_dir = config_dir / "doubao-murmur"
        app_dir.mkdir(parents=True, exist_ok=True)
        return app_dir / "asr_params.json"

    @staticmethod
    def save(params: ASRParams):
        try:
            data = json.dumps(asdict(params), ensure_ascii=False, indent=2)
            ParamsStore._path().write_text(data, encoding="utf-8")
            logger.info("Saved ASR params to %s", ParamsStore._path())
        except Exception as e:
            logger.error("Failed to save params: %s", e)

    @staticmethod
    def load() -> ASRParams | None:
        path = ParamsStore._path()
        if not path.exists():
            return None
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            return ASRParams(**data)
        except Exception as e:
            logger.error("Failed to load params: %s", e)
            return None

    @staticmethod
    def clear():
        try:
            ParamsStore._path().unlink(missing_ok=True)
            logger.info("Cleared saved params")
        except Exception as e:
            logger.error("Failed to clear params: %s", e)

    @staticmethod
    def has_saved() -> bool:
        return ParamsStore._path().exists()
```

---

### 4.12 `ui/tray_icon.py` — System Tray

```python
"""KDE system tray icon using AyatanaAppIndicator3 or StatusNotifierItem.

KDE Plasma supports:
1. StatusNotifierItem (native KDE, via DBus)
2. AyatanaAppIndicator3 (Ubuntu-style, via libayatana-appindicator)
3. Legacy XEmbed tray (X11 only, not recommended for Wayland)

For maximum KDE compatibility, use StatusNotifierItem via DBus directly
or via gi.repository.AyatanaAppIndicator3 if available.
"""

class TrayIcon:
    """System tray icon with status menu."""

    def __init__(self, app_state, login_window, transcription_manager):
        self.app_state = app_state
        self.login_window = login_window
        self.transcription_manager = transcription_manager
        self._indicator = None

    def start(self):
        """Create the tray icon."""
        try:
            import gi
            gi.require_version('AyatanaAppIndicator3', '0.1')
            from gi.repository import AyatanaAppIndicator3, Gtk

            self._indicator = AyatanaAppIndicator3.Indicator.new(
                "doubao-murmur",
                "audio-input-microphone-symbolic",  # icon name
                AyatanaAppIndicator3.IndicatorCategory.APPLICATION_STATUS,
            )
            self._indicator.set_status(AyatanaAppIndicator3.IndicatorStatus.ACTIVE)

            # Build menu
            menu = Gtk.Menu()
            self._rebuild_menu(menu)
            self._indicator.set_menu(menu)

        except (ImportError, ValueError):
            logger.warning("AyatanaAppIndicator3 not available, "
                          "tray icon will not be shown")

    def _rebuild_menu(self, menu):
        """Rebuild the tray menu with current state."""
        for child in menu.get_children():
            menu.remove(child)

        # Status label
        status = {
            LoginStatus.CHECKING: "⏳ 检查中...",
            LoginStatus.LOGGED_IN: "✅ 已登录",
            LoginStatus.NOT_LOGGED_IN: "❌ 未登录",
        }.get(self.app_state.login_status, "⏳")
        item = Gtk.MenuItem(label=status)
        item.set_sensitive(False)
        menu.append(item)
        menu.append(Gtk.SeparatorMenuItem())

        if self.app_state.login_status != LoginStatus.LOGGED_IN:
            item = Gtk.MenuItem(label="登录豆包")
            item.connect("activate", lambda _: self.login_window.show())
            menu.append(item)

        if self.app_state.login_status == LoginStatus.LOGGED_IN:
            item = Gtk.MenuItem(label="退出登录")
            item.connect("activate", lambda _: self._do_logout())
            menu.append(item)

        item = Gtk.MenuItem(label="使用帮助")
        item.connect("activate", lambda _: self._show_help())
        menu.append(item)

        menu.append(Gtk.SeparatorMenuItem())

        item = Gtk.MenuItem(label="退出")
        item.connect("activate", lambda _: self._quit())
        menu.append(item)

        menu.show_all()
```

---

### 4.13 `app.py` — Main Application

```python
import gi
gi.require_version('Gtk', '4.0')
from gi.repository import Gtk, GLib, Gio

class DoubaoMurmurApp(Gtk.Application):
    """Main GTK Application."""

    def __init__(self):
        super().__init__(
            application_id="com.doubao.Murmur",
            flags=Gio.ApplicationFlags.FLAGS_NONE,
        )
        self.app_state = AppState()
        self.login_window = None
        self.overlay = None
        self.tray_icon = None
        self.hotkey_manager = None
        self.transcription_manager = None

    def do_activate(self):
        # Prevent multiple instances
        if self.login_window:
            return

        self._setup_components()

    def _setup_components(self):
        # 1. Check for cached params — skip WebView if available
        if ParamsStore.has_saved():
            self.app_state.login_status = LoginStatus.LOGGED_IN
            logger.info("Cached params found, skipping WebView")
        else:
            self.login_window = LoginWindow(self.app_state)
            self.login_window._on_login_status_change = self._on_login_status
            self.login_window.load()

        # 2. Create overlay
        self.overlay = Overlay(self.app_state)

        # 3. Create hotkey manager
        self.hotkey_manager = HotkeyManager()

        # 4. Create transcription manager
        self.transcription_manager = TranscriptionManager(
            self.app_state,
            self.login_window,
            self.overlay,
            self.hotkey_manager,
        )
        self.transcription_manager.on_auth_expired = self._on_auth_expired
        self.transcription_manager.start()

        # 5. Start hotkey manager
        use_evdev = EvdevListener.is_available()
        self.hotkey_manager.start(use_evdev=use_evdev)

        # 6. Create tray icon
        self.tray_icon = TrayIcon(
            self.app_state,
            self.login_window,
            self.transcription_manager,
        )
        self.tray_icon.start()

        logger.info("All components initialized")

    def _on_login_status(self, status, nickname):
        if status == "loggedIn":
            self.app_state.login_status = LoginStatus.LOGGED_IN
            logger.info("Logged in as: %s", nickname)
            self._extract_save_and_destroy_webview()
        else:
            self.app_state.login_status = LoginStatus.NOT_LOGGED_IN

    def _extract_save_and_destroy_webview(self):
        """Extract params, save, destroy WebView."""
        def on_params(params):
            if params:
                ParamsStore.save(params)
            if self.login_window:
                self.login_window.hide()
                self.login_window.destroy()
                self.login_window = None

        # Delay 1s for cookies to settle
        GLib.timeout_add(1000, lambda: (
            self.login_window.extract_params_async(on_params)
            if self.login_window else None,
            GLib.SOURCE_REMOVE,
        )[1])

    def _on_auth_expired(self):
        """Show re-login dialog."""
        dialog = Gtk.MessageDialog(
            transient_for=None,
            modal=True,
            message_type=Gtk.MessageType.WARNING,
            buttons=Gtk.ButtonsType.YES_NO,
            text="认证已过期",
        )
        dialog.set_property("secondary-text",
            "豆包登录凭证已失效，是否重新登录？")
        dialog.connect("response", self._on_relogin_response)
        dialog.present()

    def _on_relogin_response(self, dialog, response):
        dialog.destroy()
        if response == Gtk.ResponseType.YES:
            self.login_window = LoginWindow(self.app_state)
            self.login_window.load()
            self.login_window.show()

    def _quit(self):
        """Clean shutdown."""
        if self.transcription_manager:
            self.transcription_manager._reset_to_idle()
        if self.hotkey_manager:
            self.hotkey_manager.stop()
        self.quit()
```

---

## 5. Dependency List

### 5.1 Python Packages (via pip or Flatpak SDK extensions)

| Package | Version | Purpose | Install Method |
|---------|---------|---------|----------------|
| `PyGObject` | >=3.48 | GTK4/WebKitGTK bindings | Flatpak SDK (built-in) |
| `websockets` | >=12.0 | WebSocket client | pip (bundled in Flatpak) |
| `sounddevice` | >=0.4.6 | Audio capture via PortAudio | pip (bundled in Flatpak) |
| `numpy` | >=2.0 | Audio data processing (if needed) | pip (bundled in Flatpak) |
| `cffi` | >=1.15 | sounddevice dependency | pip (bundled in Flatpak) |

### 5.2 System Packages (host or Flatpak runtime)

| Package | Purpose | SteamOS pacman | Flatpak |
|---------|---------|----------------|---------|
| `webkitgtk-6.0` | WebView for login | pacman | Flatpak SDK module |
| `gtk4` | UI toolkit | pacman | Flatpak runtime |
| `python-gobject` | PyGObject | pacman | Flatpak runtime |
| `portaudio` | Audio I/O backend | pacman | Flatpak SDK module |
| `pipewire` | Audio system | pre-installed | Host |
| `libayatana-appindicator` | System tray | pacman | Flatpak SDK module |

### 5.3 External Tools (host system, called via subprocess)

| Tool | Package | Purpose | Required? |
|------|---------|---------|-----------|
| `wl-copy` | `wl-clipboard` | Wayland clipboard | Recommended |
| `ydotool` | `ydotool` | Paste simulation (Wayland+X11) | Recommended |
| `ydotoold` | `ydotool` | ydotool daemon (systemd service) | Required for ydotool |
| `xdotool` | `xdotool` | Paste simulation (X11 only) | Optional |
| `wtype` | `wtype` | Virtual keyboard (Wayland) | Optional |

### 5.4 Flatpak-specific Runtime Modules

These need to be built as Flatpak modules since they're not in the Freedesktop SDK:

```
- portaudio (for sounddevice)
- python3-websockets
- python3-sounddevice (wraps PortAudio)
- python3-cffi
- webkitgtk-6.0 (large, ~50MB)
- libayatana-appindicator
```

---

## 6. Flatpak Manifest

### `com.doubao.Murmur.yml`

```yaml
app-id: com.doubao.Murmur
runtime: org.freedesktop.Platform
runtime-version: '24.08'
sdk: org.freedesktop.Sdk
command: doubao-murmur

finish-args:
  # Display
  - --socket=wayland
  - --socket=fallback-x11
  - --share=ipc

  # Audio (microphone via PulseAudio compat layer)
  - --socket=pulseaudio
  - --filesystem=xdg-run/pipewire-0  # Direct PipeWire access

  # Network (WebSocket to ASR service)
  - --share=network

  # Filesystem (config storage)
  - --filesystem=xdg-config/doubao-murmur

  # Device access (for ydotool/evdev if running inside sandbox)
  # - --device=all  # Uncomment if ydotool runs inside sandbox

  # DBus (system tray, notifications)
  - --talk-name=org.kde.StatusNotifierWatcher
  - --talk-name=org.freedesktop.Notifications
  - --talk-name=org.freedesktop.portal.Desktop

  # Environment
  - --env=PYTHONPATH=/app/lib/python3.12/site-packages

modules:
  # PortAudio (for sounddevice)
  - name: portaudio
    buildsystem: cmake-ninja
    sources:
      - type: archive
        url: https://github.com/PortAudio/portaudio/archive/refs/tags/v19.7.0.tar.gz
        sha256: 5af29ba58bbdbb7bbcebbeae8b7959e408d5c1b0a5e4b0e1b6e5e3b8f8c7d6e5

  # libayatana-appindicator (system tray)
  - name: libayatana-appindicator
    buildsystem: cmake-ninja
    sources:
      - type: git
        url: https://github.com/AyatanaIndicators/libayatana-appindicator.git
        tag: 0.5.93

  # WebKitGTK 6.0 (if not in SDK)
  - name: webkitgtk
    buildsystem: cmake-ninja
    config-opts:
      - -DPORT=GTK
      - -DUSE_GTK4=ON
      - -DENABLE_WEB_AUDIO=OFF
      - -DENABLE_VIDEO=OFF
      - -DENABLE_GAMEPAD=OFF
    sources:
      - type: archive
        url: https://webkitgtk.org/releases/webkitgtk-2.46.5.tar.xz
        sha256: ...
    # NOTE: WebKitGTK is very large to build.
    # Alternative: use Flatpak extension org.freedesktop.Sdk.Extension.webkitgtk
    # if available, or ship as a separate extension.

  # Python dependencies
  - name: python-deps
    buildsystem: simple
    build-commands:
      - pip3 install --no-build-isolation --prefix=/app
        websockets==12.0
        sounddevice==0.4.6
        numpy==2.2.1
        cffi==1.17.1
    sources:
      - type: archive
        url: https://files.pythonhosted.org/packages/.../websockets-12.0.tar.gz
        sha256: ...
      - type: archive
        url: https://files.pythonhosted.org/packages/.../sounddevice-0.4.6.tar.gz
        sha256: ...
      # ... etc

  # Main application
  - name: doubao-murmur
    buildsystem: simple
    build-commands:
      - install -D src/doubao_murmur/*.py -t /app/lib/python3.12/site-packages/doubao_murmur/
      - install -D src/doubao_murmur/hotkey/*.py -t /app/lib/python3.12/site-packages/doubao_murmur/hotkey/
      - install -D src/doubao_murmur/ui/*.py -t /app/lib/python3.12/site-packages/doubao_murmur/ui/
      - install -D src/doubao_murmur/paste/*.py -t /app/lib/python3.12/site-packages/doubao_murmur/paste/
      - install -D src/doubao_murmur/resources/* -t /app/share/doubao-murmur/resources/
      - install -D flatpak/com.doubao.Murmur.desktop /app/share/applications/com.doubao.Murmur.desktop
      - install -D flatpak/com.doubao.Murmur.metainfo.xml /app/share/metainfo/com.doubao.Murmur.metainfo.xml
      - |
        cat > /app/bin/doubao-murmur << 'EOF'
        #!/bin/sh
        exec python3 -m doubao_murmur "$@"
        EOF
        chmod +x /app/bin/doubao-murmur
    sources:
      - type: dir
        path: ..
```

### Post-Install Permission Script

```bash
#!/bin/bash
# setup-permissions.sh — Run on host after Flatpak install
# Required for full functionality

set -e

echo "=== Doubao Murmur Permission Setup ==="

# 1. Add user to input group (for evdev and ydotool)
if ! groups | grep -q input; then
    echo "Adding user to 'input' group..."
    sudo usermod -aG input $USER
    echo "  → You must log out and back in for this to take effect."
fi

# 2. Enable ydotoold systemd service
if command -v ydotoold &>/dev/null; then
    echo "Enabling ydotoold service..."
    sudo systemctl enable --now ydotoold
fi

# 3. Grant Flatpak device access (for ydotool inside sandbox)
echo "Granting Flatpak device access..."
flatpak override --user --device=all com.doubao.Murmur

# 4. Grant Flatpak Wayland socket
flatpak override --user --socket=wayland com.doubao.Murmur

echo ""
echo "=== Setup complete ==="
echo "If you were added to the 'input' group, please log out and back in."
```

### Desktop Entry

```ini
[Desktop Entry]
Name=Doubao Murmur
Name[zh_CN]=豆包语音输入
Comment=Voice-to-text input using Doubao ASR
Comment[zh_CN]=使用豆包语音识别的语音输入工具
Exec=doubao-murmur
Icon=com.doubao.Murmur
Type=Application
Categories=Utility;
StartupNotify=false
Terminal=false
X-KDE-StartupNotify=false
```

---

## 7. Implementation Order

### Phase 1: Foundation (Week 1)
Build order is chosen to enable end-to-end testing as early as possible.

1. **`config.py` + `params_store.py`** — Constants, paths, data model. No external deps. Can unit test immediately.

2. **`audio_capture.py`** — Microphone capture. Quick to verify: capture 5 seconds, write to WAV file, play back. Proves PipeWire/sounddevice pipeline works on the Deck.

3. **`asr_client.py`** — WebSocket client. Can test independently: hardcode cookies from a browser session, send pre-recorded PCM, receive transcription. Proves the ASR protocol works end-to-end.

4. **`transcription.py`** — State machine. Wire audio + ASR together. Test via CLI: start/stop recording, print transcription to stdout. No UI needed yet.

5. **`paste/paste_helper.py`** — Clipboard + paste. Quick standalone test: copy text, verify in clipboard, test ydotool paste.

### Phase 2: UI Shell (Week 2)

6. **`ui/overlay.py`** — Floating overlay window. Visual test: show/hide, update text, ESC key.

7. **`hotkey/overlay_button.py`** — Push-to-talk button. Visual test: click button, verify toggle callback fires.

8. **`ui/tray_icon.py`** — System tray icon. Visual test: icon appears, menu items work.

9. **`app.py` + `__main__.py`** — Main application lifecycle. Integration test: full flow from tray to recording to paste.

### Phase 3: Login Flow (Week 2-3)

10. **`ui/login_window.py`** — WebKitGTK login. Test: load doubao.com, log in, verify cookie extraction.

11. **JS injection adaptation** — Port `inject-websocket.js` and `inject-dom.js` for WebKitGTK. Test: login detection callback fires.

12. **`hotkey/evdev_listener.py`** — Optional physical keyboard support. Test: Right Alt toggle, ESC cancel.

### Phase 4: Packaging (Week 3)

13. **Flatpak manifest** — Package everything. Test: build, install, run.

14. **Permission setup script** — Post-install automation.

15. **Documentation** — README, setup guide, troubleshooting.

### Phase 5: Polish (Week 3-4)

16. **UI refinement** — Steam Deck screen sizing, touch-friendly buttons, animations.

17. **Error handling** — Network failures, auth expiry, audio device changes.

18. **Battery optimization** — Minimize CPU usage during idle, aggressive WebView destruction.

19. **Auto-start** — KDE autostart integration.

---

## 8. Testing Strategy

### 8.1 Unit Tests (pytest, no GTK required)

```
tests/
├── test_params_store.py      # Save/load/clear params JSON
├── test_asr_client.py        # URL building, message parsing, auth error detection
├── test_audio_capture.py     # Mock sounddevice, verify callback format
└── test_paste_helper.py      # Mock subprocess, verify tool selection logic
```

**Key test cases:**
- `test_params_store_roundtrip` — save params, load, verify equality
- `test_asr_url_construction` — verify URL matches macOS format exactly
- `test_auth_error_detection` — verify code 709599054 and keyword matching
- `test_message_parsing_result` — verify `{"event":"result","result":{"Text":"..."}}` parsing
- `test_message_parsing_finish` — verify finish event handling
- `test_audio_buffer_flush` — verify buffered audio is sent after connect

### 8.2 Integration Tests (requires GTK + audio + network)

```python
# test_integration_recording.py
def test_full_recording_flow():
    """End-to-end: capture mic → ASR → transcription.

    Prerequisites:
    - Valid cached params in asr_params.json
    - Microphone available
    - Network connectivity
    """
    params = ParamsStore.load()
    assert params is not None, "Need cached params for integration test"

    # Send 3 seconds of silence, expect empty or minimal result
    # ...
```

### 8.3 Steam Deck Manual Testing Checklist

| # | Test | Steps | Expected Result |
|---|------|-------|-----------------|
| 1 | First launch | Run app, check tray icon | Tray icon appears, status shows "检查中" or "未登录" |
| 2 | Login flow | Click "登录豆包", complete login | WebView loads, login succeeds, params saved, WebView destroyed |
| 3 | Cached params | Restart app after login | Status shows "已登录" immediately, no WebView |
| 4 | Overlay button | Click PTT button | Recording starts, overlay appears |
| 5 | Audio capture | Speak into mic during recording | Real-time text appears in overlay |
| 6 | Stop & paste | Click PTT button again | Text copied to clipboard, Ctrl+V pastes into focused input |
| 7 | Cancel | Press ESC during recording | Recording stops, no paste, overlay disappears |
| 8 | Auth expiry | Wait for cookie expiry or clear params | App detects, prompts re-login |
| 9 | Wayland session | Switch to Wayland (if on X11) | All features work (overlay, clipboard, paste) |
| 10 | X11 session | Run under X11 | All features work with xdotool fallback |
| 11 | Touch input | Use touchscreen on Steam Deck | PTT button is touch-responsive |
| 12 | Battery impact | Monitor CPU during idle | <1% CPU when idle, no unnecessary wakeups |
| 13 | Flatpak sandbox | Install via Flatpak, run | All permissions work, no sandbox violations |
| 14 | Multiple monitors | Connect external display | Overlay positions correctly |
| 15 | PipeWire audio | Check various audio configs | Works with Bluetooth headset, USB mic, built-in mic |

### 8.4 CI/CD

```yaml
# .github/workflows/linux-build.yml
name: Linux Build

on:
  push:
    branches: [main]
    paths: ['linux/**']

jobs:
  build:
    runs-on: ubuntu-latest
    container:
      image: fedora:40  # Has GTK4 + WebKitGTK packages
    steps:
      - uses: actions/checkout@v4
      - name: Install dependencies
        run: |
          dnf install -y python3 python3-pip gtk4-devel \
            webkitgtk6.0-devel gobject-introspection-devel \
            portaudio-devel
          pip3 install websockets sounddevice numpy pytest
      - name: Run tests
        run: cd linux && python3 -m pytest tests/
      - name: Build Flatpak
        run: |
          flatpak install -y flathub org.freedesktop.Sdk//24.08
          flatpak-builder --repo=repo build-dir flatpak/com.doubao.Murmur.yml
```

---

## 9. Known Risks and Mitigations

### 9.1 HIGH RISK: Wayland Global Hotkeys

**Risk:** Wayland's security model prevents apps from reading global key events. The overlay button approach may not work if:
- The compositor doesn't support always-on-top windows
- The overlay button steals focus from the active app
- Touch input conflicts with game controls

**Mitigations:**
1. **Primary: Overlay button** — Always-on-top GTK window. Works on X11. On Wayland/KDE, `set_keep_above(True)` works in most cases.
2. **Secondary: evdev** — Read `/dev/input` directly. Requires `input` group membership, which is a one-time setup. Works regardless of compositor.
3. **Tertiary: KDE KGlobalAccel via DBus** — KDE-specific global shortcut registration. If available on SteamOS, this is the cleanest solution. Register a global shortcut via `org.kde.kglobalaccel` DBus interface.
4. **Fallback: System tray "Record" button** — User clicks tray menu item to toggle. Not ideal but always works.

**KDE KGlobalAccel DBus approach (investigate first):**
```python
# Try KDE's global shortcut API via DBus
import dbus

bus = dbus.SessionBus()
kglobalaccel = bus.get_object('org.kde.kglobalaccel', '/kglobalaccel')
# Register a global shortcut that triggers our callback
```

### 9.2 HIGH RISK: Paste Simulation on Wayland

**Risk:** `ydotool` requires the `ydotoold` daemon running and `input` group membership. Users may not have this set up.

**Mitigations:**
1. Provide clear setup instructions and a `setup-permissions.sh` script
2. Graceful degradation: if no paste tool is available, copy to clipboard only and notify user
3. Try `wtype` as alternative (uses `virtual-keyboard-v1` Wayland protocol)
4. Try `xdotool` on X11 sessions
5. For Flatpak: document that `ydotoold` must run on the host, and the Flatpak connects via the socket

### 9.3 MEDIUM RISK: WebKitGTK Build Size

**Risk:** WebKitGTK is enormous (~500MB source, ~50MB installed). Building it as a Flatpak module adds significant build time and app size.

**Mitigations:**
1. Check if `org.freedesktop.Sdk` already includes WebKitGTK 6.0 (it may in newer SDK versions)
2. Use `webkit2gtk-4.1` (GTK3 version) with a GTK3 compatibility shim if GTK4 version is too large
3. Consider shipping WebKitGTK as a separate Flatpak extension that's only downloaded when login is needed
4. Use `flatpak-builder` with `--enable-cache` to speed up rebuilds

### 9.4 MEDIUM RISK: Cookie Expiry and Refresh

**Risk:** The macOS version relies on WKWebView's built-in cookie management, including automatic cookie refresh via `/passport/token/beat/web/` keep-alive requests. The Linux version extracts cookies once and uses them directly — cookies may expire faster without the keep-alive.

**Mitigations:**
1. The ASR WebSocket connection itself may trigger cookie refresh on the server side
2. When auth error (code 709599054) is detected, prompt re-login
3. Consider periodically re-loading the WebView in background to refresh cookies (e.g., every 7 days)
4. Store cookie expiry time from `sid_guard` and proactively warn before expiry

### 9.5 MEDIUM RISK: Audio Device Changes

**Risk:** PipeWire may switch audio devices (e.g., Bluetooth headset connects/disconnects) during recording, causing the stream to fail.

**Mitigations:**
1. Use `sounddevice`'s default device selection rather than hardcoding a device index
2. Add error handling in the audio callback to restart capture if the stream fails
3. Monitor PipeWire device changes via DBus notifications (future enhancement)

### 9.6 LOW RISK: Steam Deck Screen Size

**Risk:** 1280×800 resolution may make the overlay too large or the PTT button too small.

**Mitigations:**
1. Overlay width 420px = 33% of screen width — acceptable
2. PTT button 60×60px = easily tappable on a 7" touchscreen
3. Use relative positioning rather than absolute pixels where possible
4. Test on both Steam Deck (1280×800) and external displays

### 9.7 LOW RISK: Flatpak Sandboxing

**Risk:** Flatpak sandbox may prevent access to `/dev/input` (for evdev/ydotool) or Wayland protocols.

**Mitigations:**
1. Use `--device=all` in Flatpak manifest or via override
2. Run ydotool/evdev on the host and communicate via socket/DBus
3. Use Flatpak portals for clipboard access where possible
4. Test thoroughly with Flatpak permissions

### 9.8 LOW RISK: Doubao API Changes

**Risk:** Doubao may change their WebSocket URL, query parameters, or message format.

**Mitigations:**
1. All API parameters are centralized in `config.py` for easy updates
2. The WebView-based login flow extracts current parameters dynamically
3. Auth error detection covers multiple failure modes
4. The macOS version faces the same risk; fixes can be ported

---

## Appendix A: Key Differences from macOS Version

| Feature | macOS (Swift) | Linux (Python) |
|---------|---------------|----------------|
| Audio capture | AVAudioEngine + AVAudioConverter | sounddevice (PortAudio/PipeWire) |
| Float32→Int16 | Manual asymmetric scaling | Native Int16 from PortAudio (no conversion) |
| WebSocket | URLSessionWebSocketTask | websockets (asyncio) |
| Threading model | @MainActor + DispatchQueue | GTK GLib main loop + asyncio thread |
| WebView | WKWebView (WebKit) | WebKitGTK 6.0 |
| Cookie extraction | WKHTTPCookieStore.getAllCookies() | WebKit.CookieManager.get_cookies() |
| JS injection | WKUserScript + WKScriptMessageHandler | WebKit.UserScript + script-message-received |
| Global hotkey | CGEvent tap (Right ⌥) | Overlay button (primary) + evdev (optional) |
| Clipboard | NSPasteboard.general | wl-copy / xclip / GTK clipboard |
| Paste simulation | CGEvent ⌘V | ydotool / wtype / xdotool |
| Menu bar | NSStatusItem | AyatanaAppIndicator3 / StatusNotifierItem |
| Overlay window | NSPanel (floating, borderless) | Gtk.Window (keep-above, undecorated) |
| Config storage | ~/Library/Application Support/ | ~/.config/doubao-murmur/ |
| Distribution | .app bundle (DMG/zip) | Flatpak |

## Appendix B: SteamOS-Specific Considerations

1. **SteamOS is immutable by default** — System partition is read-only. All persistent changes must go through user-space (Flatpak, ~/.config, systemd user services). This makes Flatpak the ideal distribution method.

2. **Steam Deck has no physical keyboard** — The overlay PTT button is essential. evdev keyboard shortcuts only apply when an external keyboard is connected.

3. **Steam Deck touchscreen** — The PTT button should be touch-friendly (60×60px minimum). GTK4 has good touch support.

4. **Gaming Mode vs Desktop Mode** — This app only runs in Desktop Mode. In Gaming Mode, Steam's overlay handles input. If the user switches to Desktop Mode mid-game, the app should resume gracefully.

5. **Power management** — Steam Deck has limited battery. The app should:
   - Use <1% CPU when idle (no polling timers)
   - Destroy WebView immediately after param extraction
   - Use PipeWire's low-latency mode only during active recording
   - Not prevent screen dimming/sleep

6. **SteamOS updates** — Valve periodically updates SteamOS. The immutable OS means system packages are updated atomically. Flatpak apps are insulated from OS updates. However, PipeWire and KDE Plasma versions may change, requiring testing.

## Appendix C: Setup Instructions for Users

```bash
# 1. Install from Flatpak (after building)
flatpak install --user doubao-murmur.flatpak

# 2. Run permission setup script
bash setup-permissions.sh

# 3. Log out and back in (for input group to take effect)

# 4. Launch
flatpak run com.doubao.Murmur

# 5. First use: Click tray icon → "登录豆包" → Complete login
# 6. Click the 🎤 button to start recording, speak, click again to stop
# 7. Text is automatically pasted into the focused input field
```

---

*End of implementation plan.*
