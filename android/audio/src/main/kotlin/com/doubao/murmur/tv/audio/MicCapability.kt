package com.doubao.murmur.tv.audio

/**
 * Outcome of a microphone capability probe, meant to be rendered by the diagnostics screen.
 *
 * The Doubao ASR endpoint only accepts 16 kHz / mono / Int16 LE PCM (see
 * `docs/doubao-wss-asr-spec.md` section 4.1), while a lot of Android TV boxes only open their
 * capture path at 48 kHz. [captureSampleRate] therefore reports what the device will really give
 * us, not what the server wants, and [needsResampling] tells the caller whether every chunk has to
 * go through [LinearResampler].
 *
 * @property hasMicrophone true when either the system feature flag is set or at least one input
 *   device is reported. TV boxes frequently ship without `FEATURE_MICROPHONE` yet accept an
 *   external USB or Bluetooth microphone, so the device list is authoritative, not the flag.
 * @property captureSampleRate sample rate handed to `AudioRecord`, one of 16000 / 48000 / 44100.
 * @property needsResampling true unless [captureSampleRate] already is 16000.
 * @property notes human readable findings, in Chinese, for the diagnostics screen. Never contains
 *   credentials - only audio hardware facts.
 */
data class MicCapability(
    val hasMicrophone: Boolean,
    val captureSampleRate: Int,
    val needsResampling: Boolean,
    val notes: List<String>,
)
