package com.zenpeq.audio

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

enum class OutputKind { SPEAKER, WIRED, USB, BLUETOOTH, OTHER }

/** [key] is the stable id profiles are assigned to; Bluetooth/USB keys include the product name. */
data class OutputDevice(val key: String, val label: String, val kind: OutputKind)

object OutputDevices {
    val DEFAULT = OutputDevice("speaker", "Phone speaker", OutputKind.SPEAKER)

    fun classify(d: AudioDeviceInfo): OutputKind = when (d.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_LINE_ANALOG -> OutputKind.WIRED
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_ACCESSORY -> OutputKind.USB
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID -> OutputKind.BLUETOOTH
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> OutputKind.SPEAKER
        else -> OutputKind.OTHER
    }

    private fun priority(k: OutputKind) = when (k) {
        OutputKind.USB -> 0
        OutputKind.WIRED -> 1
        OutputKind.BLUETOOTH -> 2
        OutputKind.SPEAKER -> 3
        OutputKind.OTHER -> 4
    }

    /**
     * Current media output. API 33+ asks the audio policy where media is actually routed; older
     * versions pick the highest-priority connected output (USB > 3.5 mm > Bluetooth > speaker).
     */
    fun resolve(am: AudioManager): OutputDevice {
        val routed: List<AudioDeviceInfo> = if (Build.VERSION.SDK_INT >= 33) {
            runCatching {
                am.getAudioDevicesForAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build(),
                )
            }.getOrNull().orEmpty()
        } else emptyList()
        val pool = routed.ifEmpty { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() }
        val best = pool.minByOrNull { priority(classify(it)) } ?: return DEFAULT
        val kind = classify(best)
        val name = best.productName?.toString()?.trim().orEmpty().ifEmpty { "device" }
        return when (kind) {
            OutputKind.WIRED -> OutputDevice("wired", "3.5 mm headset", kind)
            OutputKind.USB -> OutputDevice("usb:$name", "USB · $name", kind)
            OutputKind.BLUETOOTH -> OutputDevice("bt:$name", "Bluetooth · $name", kind)
            OutputKind.SPEAKER -> DEFAULT
            OutputKind.OTHER -> OutputDevice("other:${best.type}", "Other output", kind)
        }
    }
}
