package app.luma.standby.platform

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.core.content.pm.PackageInfoCompat
import app.luma.standby.dream.LumaDreamService
import app.luma.standby.platform.battery.BatterySnapshot
import app.luma.standby.platform.battery.BatteryController
import app.luma.standby.platform.display.DisplaySnapshot
import app.luma.standby.platform.display.DisplayController
import app.luma.standby.platform.hyperos.HyperOsInfo
import app.luma.standby.platform.hyperos.HyperOSDetector
import app.luma.standby.platform.power.PowerController

data class DeviceReport(
    val appVersion: String,
    val androidRelease: String,
    val apiLevel: Int,
    val securityPatch: String,
    val buildFingerprint: String,
    val hyperOs: HyperOsInfo,
    val display: DisplaySnapshot?,
    val battery: BatterySnapshot,
    val powerSaveMode: Boolean,
    val deviceIdle: Boolean,
    val thermalLabel: String,
    val dreamServiceAvailable: Boolean,
    val dreamEnabledByUser: String,
)

/**
 * Phase 0 deliverable: one place that answers "what can this phone actually do
 * for us?" without any privileged access.
 */
class DeviceDiagnostics(private val context: Context) {

    private val displayController = DisplayController(context)
    private val powerController = PowerController(context)
    private val batteryController = BatteryController(context)

    fun report(): DeviceReport {
        val pm = context.packageManager
        val packageInfo = runCatching {
            pm.getPackageInfo(context.packageName, 0)
        }.getOrNull()
        val version = if (packageInfo == null) {
            "unknown"
        } else {
            packageInfo.versionName.orEmpty() + " (" + PackageInfoCompat.getLongVersionCode(packageInfo) + ")"
        }

        val dreamAvailable = pm.queryIntentServices(
            Intent("android.service.dreams.DreamService"),
            0,
        ).any { it.serviceInfo?.packageName == context.packageName && it.serviceInfo?.name == LumaDreamService::class.java.name }

        val dreamEnabled = runCatching {
            Settings.Secure.getString(context.contentResolver, "screensaver_components")
        }.getOrNull().orEmpty()

        return DeviceReport(
            appVersion = version,
            androidRelease = Build.VERSION.RELEASE.orEmpty(),
            apiLevel = Build.VERSION.SDK_INT,
            securityPatch = Build.VERSION.SECURITY_PATCH.orEmpty(),
            buildFingerprint = Build.FINGERPRINT.orEmpty(),
            hyperOs = HyperOSDetector.detect(),
            display = displayController.snapshot(),
            battery = batteryController.snapshot(),
            powerSaveMode = powerController.isPowerSaveMode(),
            deviceIdle = powerController.isDeviceIdle(),
            thermalLabel = powerController.thermalLabel(powerController.thermalStatus()),
            dreamServiceAvailable = dreamAvailable,
            dreamEnabledByUser = if (dreamEnabled.isEmpty()) "not set" else dreamEnabled,
        )
    }
}
