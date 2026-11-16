package app.luma.standby.platform.power

import android.content.Context
import android.os.PowerManager

class PowerController(context: Context) {

    private val powerManager: PowerManager? = context.getSystemService(PowerManager::class.java)

    fun isInteractive(): Boolean = powerManager?.isInteractive ?: true

    fun isPowerSaveMode(): Boolean = powerManager?.isPowerSaveMode ?: false

    fun isDeviceIdle(): Boolean = powerManager?.isDeviceIdleMode ?: false

    fun thermalStatus(): Int =
        powerManager?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE

    fun thermalLabel(status: Int): String = when (status) {
        PowerManager.THERMAL_STATUS_NONE -> "none"
        PowerManager.THERMAL_STATUS_LIGHT -> "light"
        PowerManager.THERMAL_STATUS_MODERATE -> "moderate"
        PowerManager.THERMAL_STATUS_SEVERE -> "severe"
        PowerManager.THERMAL_STATUS_CRITICAL -> "critical"
        PowerManager.THERMAL_STATUS_EMERGENCY -> "emergency"
        PowerManager.THERMAL_STATUS_SHUTDOWN -> "shutdown"
        else -> "unknown(" + status + ")"
    }

    /** Hot devices should dim and drop animation instead of cooking the panel. */
    fun shouldDim(): Boolean = thermalStatus() >= PowerManager.THERMAL_STATUS_SEVERE
}
