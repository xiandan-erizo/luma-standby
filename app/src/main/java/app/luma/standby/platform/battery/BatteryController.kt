package app.luma.standby.platform.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.core.content.ContextCompat

data class BatterySnapshot(
    val levelPercent: Int,
    val charging: Boolean,
    val pluggedLabel: String,
    val statusLabel: String,
    val temperatureC: Float,
    val voltageMv: Int,
)

const val PLUGGED_UNKNOWN = "unknown"

class BatteryController(private val context: Context) {

    fun snapshot(): BatterySnapshot {
        val intent: Intent? = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (intent == null) {
            return BatterySnapshot(
                levelPercent = -1,
                charging = false,
                pluggedLabel = PLUGGED_UNKNOWN,
                statusLabel = "unavailable",
                temperatureC = 0f,
                voltageMv = 0,
            )
        }
        return parse(intent)
    }

    private fun parse(intent: Intent): BatterySnapshot {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        val percent = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0)

        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL ||
            plugged != 0

        return BatterySnapshot(
            levelPercent = percent,
            charging = charging,
            pluggedLabel = pluggedLabel(plugged),
            statusLabel = statusLabel(status),
            temperatureC = tempTenths / 10f,
            voltageMv = voltage,
        )
    }

    private fun pluggedLabel(plugged: Int): String = when (plugged) {
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
        BatteryManager.BATTERY_PLUGGED_DOCK -> "dock"
        0 -> "unplugged"
        else -> "other(" + plugged + ")"
    }

    private fun statusLabel(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }

    /** One receiver for the lifetime of the caller; returns an unregister lambda. */
    fun observe(onChange: (BatterySnapshot) -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent != null) onChange(parse(intent))
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        return {
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
