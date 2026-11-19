package app.luma.standby.platform.widgets

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.luma.standby.domain.widget.NotificationInfo

/**
 * Reads the most recent notifications and the current media title.
 *
 * Both need the user to grant notification access in Settings; the OS will refuse
 * to start this service otherwise, and [isEnabled] is the honest way to ask.
 *
 * What we read is deliberately narrow: app label + title text of the newest
 * notifications, and nothing from the content. That is enough for a standby line,
 * and it keeps what we touch proportional to what we show.
 */
class LumaNotificationListener : NotificationListenerService() {

    override fun onListenerConnected() {
        active = this
    }

    override fun onListenerDisconnected() {
        if (active === this) active = null
    }

    override fun onDestroy() {
        if (active === this) active = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var active: LumaNotificationListener? = null

        const val MAX_NOTIFICATIONS = 5

        fun isEnabled(context: Context): Boolean {
            val flat = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            val component = ComponentName(context, LumaNotificationListener::class.java)
            return flat.split(':').any { ComponentName.unflattenFromString(it) == component }
        }

        /** Opens the OS screen where the user grants notification access. */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }

        fun latest(context: Context): List<NotificationInfo> {
            val service = active ?: return emptyList()
            val pm = context.packageManager
            val result = mutableListOf<NotificationInfo>()
            val notifications: Array<StatusBarNotification> = runCatching {
                service.activeNotifications
            }.getOrNull() ?: return emptyList()

            notifications
                .filter { it.packageName != context.packageName }
                .sortedByDescending { it.postTime }
                .take(MAX_NOTIFICATIONS)
                .forEach { sbn ->
                    val extras = sbn.notification.extras
                    val title = extras.getCharSequence("android.title")?.toString()?.trim().orEmpty()
                    if (title.isEmpty()) return@forEach
                    result.add(
                        NotificationInfo(
                            appLabel = labelFor(pm, sbn.packageName),
                            title = title,
                        ),
                    )
                }
            return result
        }

        private fun labelFor(pm: PackageManager, packageName: String): String = runCatching {
            pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
        }.getOrDefault(packageName)
    }
}
