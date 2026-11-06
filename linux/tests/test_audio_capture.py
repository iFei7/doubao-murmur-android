"""Tests for audio capture dependency handling."""

import builtins

import pytest

from doubao_murmur.audio_capture import AudioCapture, _load_sounddevice


def test_audio_capture_import_does_not_load_sounddevice():
    capture = AudioCapture()
    assert not capture.is_capturing


def test_load_sounddevice_error_is_actionable(monkeypatch):
    real_import = builtins.__import__

    def fake_import(name, *args, **kwargs):
        if name == "sounddevice":
            raise ImportError("missing")
        return real_import(name, *args, **kwargs)

    monkeypatch.setattr(builtins, "__import__", fake_import)

    with pytest.raises(RuntimeError, match="sounddevice/PortAudio"):
        _load_sounddevice()
