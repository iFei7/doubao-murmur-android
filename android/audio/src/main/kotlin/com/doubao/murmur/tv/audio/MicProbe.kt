package com.doubao.murmur.tv.audio

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord

/**
 * One-shot probe of the capture path, run before the first recording and shown on the diagnostics
 * screen.
 *
 * The probe never records and never throws. It only asks the platform what it *would* do, so it is
 * safe to call without `RECORD_AUDIO` (enumerating input devices and querying the minimum buffer
 * size do not need the permission; opening an `AudioRecord` would). Callers must still gate the
 * actual recording on [MicPermission.hasRecordPermission] - [MicCapability.hasMicrophone] reports
 * hardware, not authorisation.
 */
object MicProbe {

    /** Rates tried in order; 16 kHz first because the server wants it natively. */
    private val CANDIDATE_RATES = intArrayOf(16_000, 48_000, 44_100)

    /** `AudioDeviceInfo.TYPE_USB_HEADSET` is API 26, so it is inlined to stay minSdk 23 clean. */
    private const val TYPE_USB_HEADSET = 22

    /**
     * Inspects microphone availability and picks the sample rate for `AudioRecord`.
     *
     * TV boxes commonly report `FEATURE_MICROPHONE == false` while a USB or Bluetooth microphone is
     * perfectly usable, so the input device list takes precedence over the feature flag.
     */
    fun probe(context: Context): MicCapability {
        val notes = mutableListOf<String>()

        val hasFeature = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        if (hasFeature) {
            notes.add("系统声明 FEATURE_MICROPHONE")
        } else {
            notes.add("系统未声明 FEATURE_MICROPHONE，将依赖外接设备探测（TV 盒子常见）")
        }

        val inputDevices = collectInputDevices(context, notes)
        val hasMicrophone = hasFeature || inputDevices > 0
        if (!hasMicrophone) {
            notes.add("未发现任何可用输入设备")
        }

        val captureSampleRate = selectCaptureRate(notes)

        if (!MicPermission.hasRecordPermission(context)) {
            notes.add("未授予 RECORD_AUDIO：硬件探测结果仅供参考，采集会返回 SecurityException")
        }

        return MicCapability(
            hasMicrophone = hasMicrophone,
            captureSampleRate = captureSampleRate,
            needsResampling = captureSampleRate != LinearResampler.OUTPUT_RATE,
            notes = notes,
        )
    }

    /** Counts usable microphone-like inputs and appends a human readable summary to [notes]. */
    private fun collectInputDevices(context: Context, notes: MutableList<String>): Int {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (audioManager == null) {
            notes.add("AudioManager 不可用，无法枚举输入设备")
            return 0
        }

        val devices = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
        } catch (e: RuntimeException) {
            notes.add("枚举输入设备失败：${e.javaClass.simpleName}")
            return 0
        }

        var microphones = 0
        val described = mutableListOf<String>()
        for (device in devices) {
            if (!isMicrophoneLike(device)) continue
            microphones++
            described.add("${typeName(device.type)}(id=${device.id})")
        }

        if (devices.isEmpty()) {
            notes.add("系统报告 0 个输入设备")
        } else {
            notes.add("发现 ${devices.size} 个输入设备，其中 $microphones 个可作麦克风")
        }
        if (described.isNotEmpty()) {
            notes.add("可用麦克风：" + described.joinToString())
        }
        return microphones
    }

    private fun isMicrophoneLike(device: AudioDeviceInfo): Boolean {
        if (!device.isSource) return false
        return when (device.type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            TYPE_USB_HEADSET,
            -> true
            else -> false
        }
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "内置麦"
        AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 设备"
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 配件"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "蓝牙 SCO"
        TYPE_USB_HEADSET -> "USB 耳麦"
        else -> "type=$type"
    }

    /**
     * Picks the first rate whose minimum buffer size is positive.
     *
     * `getMinBufferSize` returns [AudioRecord.ERROR_BAD_VALUE] (-2) for a rate the device cannot
     * open and [AudioRecord.ERROR] (-1) when it cannot even answer, so anything `<= 0` means "try
     * the next candidate".
     */
    private fun selectCaptureRate(notes: MutableList<String>): Int {
        for (rate in CANDIDATE_RATES) {
            val minBuffer = AudioRecord.getMinBufferSize(
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer > 0) {
                notes.add("采集采样率 $rate Hz，最小缓冲区 $minBuffer 字节")
                return rate
            }
            notes.add("采集采样率 $rate Hz 不可用（getMinBufferSize=$minBuffer）")
        }
        notes.add("候选采样率全部探测失败，回退 16000 Hz，采集大概率会失败")
        return LinearResampler.OUTPUT_RATE
    }
}
