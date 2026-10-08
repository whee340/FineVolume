package tw.finevolume

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build

/** 目前播放媒體的輸出裝置 */
data class OutputDevice(val key: String, val name: String, val kind: String)

object DeviceResolver {

    fun current(am: AudioManager): OutputDevice {
        val info = routedDevice(am)
        return info?.let { describe(it) } ?: OutputDevice("speaker", "手機喇叭", "內建")
    }

    private fun routedDevice(am: AudioManager): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT >= 33) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            runCatching { am.getAudioDevicesForAttributes(attrs).firstOrNull() }
                .getOrNull()?.let { routed ->
                    // getAudioDevicesForAttributes 回傳的物件有時沒有產品名稱，從已連線清單找回完整資訊
                    val full = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                        .firstOrNull { it.type == routed.type && (routed.address.isEmpty() || it.address == routed.address) }
                    return full ?: routed
                }
        }
        // 舊版 Android：依常見的路由優先順序猜測
        val outs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return outs.minByOrNull { priority(it.type) }?.takeIf { priority(it.type) < 100 }
    }

    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> 0
        26 /* TYPE_BLE_HEADSET */, 27 /* TYPE_BLE_SPEAKER */, 30 /* TYPE_BLE_BROADCAST */ -> 1
        AudioDeviceInfo.TYPE_HEARING_AID -> 2
        AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY -> 3
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 4
        AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL, AudioDeviceInfo.TYPE_AUX_LINE -> 5
        AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_DOCK -> 6
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> 50
        else -> 100
    }

    private fun describe(d: AudioDeviceInfo): OutputDevice {
        val product = d.productName?.toString()?.trim().orEmpty()
        return when (d.type) {
            AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE ->
                OutputDevice("speaker", "手機喇叭", "內建")
            AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES ->
                OutputDevice("wired", "有線耳機", "3.5mm")
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 26, 27, 30 ->
                OutputDevice("bt:${product.ifEmpty { "unknown" }}", product.ifEmpty { "藍牙裝置" }, "藍牙")
            AudioDeviceInfo.TYPE_HEARING_AID ->
                OutputDevice("hearing:$product", product.ifEmpty { "助聽器" }, "助聽器")
            AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_ACCESSORY ->
                OutputDevice("usb:$product", product.ifEmpty { "USB 音訊" }, "USB")
            AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC ->
                OutputDevice("hdmi", "HDMI", "HDMI")
            else ->
                OutputDevice("type${d.type}:$product", product.ifEmpty { "其他輸出" }, "其他")
        }
    }
}
