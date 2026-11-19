package app.luma.standby.platform.widgets

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.luma.standby.domain.widget.NotificationInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Event-fed, sanitized, process-memory-only notification snapshot. No body text,
 * notification Bundles, disk cache or recurring activeNotifications query.
 * The collector explicitly opts in. Locked/disabled/disconnected sources erase it.
 */
class LumaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() = synchronized(monitor) {
        active = this
        snapshot.clear()
        if (collectionEnabled) seedOnce()
        changed()
    }

    override fun onListenerDisconnected() = synchronized(monitor) {
        if (active === this) {
            active = null
            snapshot.clear()
            changed()
        }
    }

    override fun onDestroy() {
        onListenerDisconnected()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) = onNotificationPosted(sbn, null)

    override fun onNotificationPosted(sbn: StatusBarNotification?, rankingMap: RankingMap?) {
        synchronized(monitor) {
            if (active !== this) return
            guardedUpdate {
                if (sbn != null) capture(sbn, rankingMap)
            }
            changed()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        synchronized(monitor) {
            if (active !== this) return
            sbn?.key?.let(snapshot::removed)
            changed()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?) =
        onNotificationRemoved(sbn)

    override fun onNotificationRemoved(sbn: StatusBarNotification?, rankingMap: RankingMap?, reason: Int) =
        onNotificationRemoved(sbn)

    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) {
        synchronized(monitor) {
            if (active !== this) return
            guardedUpdate {
                for (key in snapshot.keys()) {
                    snapshot.restrictVisibility(key, rankingVisibility(key, rankingMap))
                }
            }
            changed()
        }
    }

    private fun guardedUpdate(update: () -> Unit) {
        if (!collectionEnabled) return
        try {
            if (!SensitiveWidgetAccess.isUnlocked(this) || !isEnabled(this)) {
                snapshot.clear()
                return
            }
            update()
            if (!SensitiveWidgetAccess.isUnlocked(this) || !isEnabled(this)) snapshot.clear()
        } catch (_: SecurityException) {
            snapshot.clear()
        } catch (_: RuntimeException) {
            snapshot.clear()
        }
    }

    /** One bootstrap at opt-in/reconnection; subsequent updates come only from events. */
    private fun seedOnce() = guardedUpdate {
        val ranking = currentRanking
        for (sbn in activeNotifications.orEmpty()) capture(sbn, ranking)
    }

    private fun capture(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val notification = sbn.notification
        val visibility = NotificationPrivacyPolicy.visibility(
            visibilityOf(notification.visibility),
            rankingVisibility(sbn.key, rankingMap),
        )
        val mode = NotificationPrivacyPolicy.titleMode(
            visibility = visibility,
            ongoing = sbn.isOngoing,
            groupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
            ownNotification = sbn.packageName == packageName,
        )
        // Replacements must remove a previously-public cached title even if now omitted.
        snapshot.removed(sbn.key)
        if (mode == NotificationTitleMode.OMIT) return
        val label = try {
            val appInfo = packageManager.getApplicationInfo(sbn.packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            return
        }
        val info = NotificationPrivacyPolicy.info(mode, label) {
            // ONLY public title; never EXTRA_TEXT, BIG_TEXT, MESSAGES or publicVersion extras.
            notification.extras?.getCharSequence(Notification.EXTRA_TITLE)
        }
        snapshot.posted(sbn.key, sbn.postTime, info, visibility)
    }

    private fun rankingVisibility(key: String, map: RankingMap?): NotificationVisibility {
        val ranking = Ranking()
        if (map == null || !map.getRanking(key, ranking)) return NotificationVisibility.UNKNOWN
        return if (ranking.lockscreenVisibilityOverride == Ranking.VISIBILITY_NO_OVERRIDE) {
            NotificationVisibility.PUBLIC
        } else visibilityOf(ranking.lockscreenVisibilityOverride)
    }

    companion object {
        private val monitor = Any()
        private var active: LumaNotificationListener? = null
        private var collectionEnabled = false
        private val snapshot = NotificationMemorySnapshot()
        private val mutableChanges = MutableStateFlow(0L)
        val changes: StateFlow<Long> = mutableChanges.asStateFlow()

        const val MAX_NOTIFICATIONS = 5

        private fun changed() { mutableChanges.value += 1 }

        internal fun componentName(context: Context): ComponentName =
            ComponentName(context, LumaNotificationListener::class.java)

        /** Public platform API checks this exact service grant, never a package-name match. */
        fun isEnabled(context: Context): Boolean = try {
            context.getSystemService(NotificationManager::class.java)
                ?.isNotificationListenerAccessGranted(componentName(context)) == true
        } catch (_: SecurityException) {
            false
        } catch (_: RuntimeException) {
            false
        }

        fun setCollectionEnabled(enabled: Boolean) = synchronized(monitor) {
            if (collectionEnabled == enabled) return@synchronized
            collectionEnabled = enabled
            snapshot.setEnabled(enabled)
            if (enabled) active?.seedOnce()
            changed()
        }

        /** Latest sanitized snapshot only; never interrogates Android's active notifications. */
        fun latest(context: Context): List<NotificationInfo> = synchronized(monitor) {
            val result = SensitiveWidgetReadPolicy.read(
                enabled = collectionEnabled,
                isUnlocked = { SensitiveWidgetAccess.isUnlocked(context) },
                hasAccess = { active != null && isEnabled(context) },
            ) { snapshot.latest(MAX_NOTIFICATIONS) }
            if (result == null) snapshot.clear()
            result.orEmpty()
        }

        /** Opens Settings only on a caller's explicit user action; grants nothing itself. */
        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }

        private fun visibilityOf(value: Int): NotificationVisibility = when (value) {
            Notification.VISIBILITY_PUBLIC -> NotificationVisibility.PUBLIC
            Notification.VISIBILITY_PRIVATE -> NotificationVisibility.PRIVATE
            Notification.VISIBILITY_SECRET -> NotificationVisibility.SECRET
            else -> NotificationVisibility.UNKNOWN
        }
    }
}
