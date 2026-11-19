package app.luma.standby.platform.widgets

import android.content.Context
import android.os.BatteryManager
import app.luma.standby.domain.widget.AlarmInfo
import app.luma.standby.domain.widget.BatteryInfo
import android.app.AlarmManager

/**
 * Permission-free sources. Each returns null rather than a fake value when the
 * platform cannot answer.
 */
class SystemWidgetSource(private val context: Context) {

    private val batteryManager: BatteryManager? =
        context.getSystemService(BatteryManager::class.java)

    private val alarmManager: AlarmManager? =
        context.getSystemService(AlarmManager::class.java)

    fun battery(): BatteryInfo? {
        val level = batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: return null
        if (level < 0) return null
        val plugged = context.registerReceiver(
            null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED),
        )?.getIntExtra("plugged", 0) ?: 0
        return BatteryInfo(percent = level, charging = plugged != 0)
    }

    /**
     * The next alarm only exists if some clock app set one. A phone with no alarm
     * app installed simply has nothing here - not an error.
     */
    fun nextAlarm(): AlarmInfo? {
        val clock = alarmManager?.nextAlarmClock ?: return null
        val trigger = clock.triggerTime
        val calendar = java.util.Calendar.getInstance().apply { timeInMillis = trigger }
        return AlarmInfo(
            hour = calendar.get(java.util.Calendar.HOUR_OF_DAY),
            minute = calendar.get(java.util.Calendar.MINUTE),
        )
    }
}
