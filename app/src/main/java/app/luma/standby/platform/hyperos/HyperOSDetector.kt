package app.luma.standby.platform.hyperos

import android.os.Build

data class HyperOsInfo(
    val manufacturer: String,
    val model: String,
    val isXiaomiFamily: Boolean,
    val osVersionName: String,
    val osVersionCode: String,
    val incremental: String,
    val looksLikeHyperOs: Boolean,
)

/**
 * Best-effort detection only. Nothing in Luma may *require* any of this: the
 * clock must work identically on a Pixel or a Samsung device.
 *
 * SystemProperties is a hidden API. Reading it can fail on hardened builds, so
 * every access is wrapped and every caller must tolerate empty strings.
 */
object HyperOSDetector {

    fun detect(): HyperOsInfo {
        val manufacturer = Build.MANUFACTURER.orEmpty()
        val model = Build.MODEL.orEmpty()
        val family = manufacturer.equals("Xiaomi", true) ||
            manufacturer.equals("Redmi", true) ||
            manufacturer.equals("POCO", true)

        val versionName = readProperty("ro.mi.os.version.name")
            .ifEmpty { readProperty("ro.miui.ui.version.name") }
        val versionCode = readProperty("ro.mi.os.version.code")
        val incremental = Build.VERSION.INCREMENTAL.orEmpty()

        val hyperOs = family && (
            versionName.startsWith("OS", true) ||
                versionName.contains("HyperOS", true) ||
                versionCode.isNotEmpty() ||
                incremental.contains("HyperOS", true) ||
                Build.DISPLAY.contains("HyperOS", true)
            )

        return HyperOsInfo(
            manufacturer = manufacturer,
            model = model,
            isXiaomiFamily = family,
            osVersionName = versionName,
            osVersionCode = versionCode,
            incremental = incremental,
            looksLikeHyperOs = hyperOs,
        )
    }

    private fun readProperty(key: String): String = runCatching {
        val clazz = Class.forName("android.os.SystemProperties")
        val get = clazz.getMethod("get", String::class.java)
        (get.invoke(null, key) as? String).orEmpty().trim()
    }.getOrDefault("")
}
