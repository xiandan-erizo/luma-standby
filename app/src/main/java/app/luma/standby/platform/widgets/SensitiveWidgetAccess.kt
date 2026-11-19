package app.luma.standby.platform.widgets

import android.app.KeyguardManager
import android.content.Context

internal object SensitiveWidgetAccess {
    fun isUnlocked(context: Context): Boolean = try {
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        keyguard != null && !keyguard.isKeyguardLocked && !keyguard.isDeviceLocked
    } catch (_: SecurityException) {
        false
    } catch (_: RuntimeException) {
        false
    }
}
