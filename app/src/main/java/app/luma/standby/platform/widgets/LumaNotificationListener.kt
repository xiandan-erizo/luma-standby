package app.luma.standby.platform.widgets

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import app.luma.standby.domain.widget.LiveActivityInfo
import app.luma.standby.domain.widget.NotificationInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Event-fed, sanitized, process-memory-only notification snapshot. No notification
 * Bundles, disk cache or recurring activeNotifications query. When unlocked and the
 * user opts into previews, a short single-line message snippet is held in RAM only
 * while Standby is visible. Locked/disabled/disconnected sources erase everything.
 */
class LumaNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() = synchronized(monitor) {
        active = this
        clearAllInMemory()
        if (collectionEnabled) seedOnce()
        changed()
    }

    override fun onListenerDisconnected() = synchronized(monitor) {
        if (active === this) {
            active = null
            clearAllInMemory()
            iconCache.clear()
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
            val key = sbn?.key
            if (key != null) {
                snapshot.removed(key)
                liveActivities.remove(key)
                focusIconCache.remove(key)
            }
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
                val allowUnlockedPreview = previewEnabled && SensitiveWidgetAccess.isUnlocked(this)
                for (key in snapshot.keys()) {
                    snapshot.restrictVisibility(
                        key = key,
                        visibilityOverride = rankingVisibility(key, rankingMap),
                        allowUnlockedPreview = allowUnlockedPreview,
                    )
                }
                for (key in liveActivities.keys.toList()) {
                    if (rankingVisibility(key, rankingMap) == NotificationVisibility.SECRET) {
                        liveActivities.remove(key)
                        focusIconCache.remove(key)
                    }
                }
            }
            changed()
        }
    }

    private fun guardedUpdate(update: () -> Unit) {
        if (!collectionEnabled) return
        try {
            if (!SensitiveWidgetAccess.isUnlocked(this) || !isEnabled(this)) {
                clearAllInMemory()
                return
            }
            update()
            if (!SensitiveWidgetAccess.isUnlocked(this) || !isEnabled(this)) clearAllInMemory()
        } catch (_: SecurityException) {
            clearAllInMemory()
        } catch (_: RuntimeException) {
            clearAllInMemory()
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
        // Replacements must remove previously cached entries even if now omitted/cancelled.
        snapshot.removed(sbn.key)
        liveActivities.remove(sbn.key)
        focusIconCache.remove(sbn.key)

        val isGroupSummary = notification.flags and Notification.FLAG_GROUP_SUMMARY != 0
        val isOwn = sbn.packageName == packageName
        if (isGroupSummary || isOwn || visibility == NotificationVisibility.SECRET) return

        val appInfo = try {
            packageManager.getApplicationInfo(sbn.packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return
        }
        val label = packageManager.getApplicationLabel(appInfo).toString()
        cacheIcon(sbn.packageName) { packageManager.getApplicationIcon(appInfo) }

        // 1. Check Xiaomi HyperOS Super Island (miui.focus.param) & Android 16 Live Updates
        if (liveActivitiesEnabled && SensitiveWidgetAccess.isUnlocked(this)) {
            when (val parsed = parseLiveActivity(sbn, label)) {
                LiveActivityParseResult.Cancelled -> return
                is LiveActivityParseResult.Active -> {
                    storeLiveActivity(parsed.info)
                    parsed.focusIconKey?.let { iconKey ->
                        loadFocusDrawable(notification.extras, iconKey)?.let { drawable ->
                            cacheFocusIcon(sbn.key, drawable)
                        }
                    }
                    // Super Island / Live Update entries live in the Dynamic Island, not chat alerts.
                    return
                }
                LiveActivityParseResult.NotALiveActivity -> Unit
            }
        }

        val mode = NotificationPrivacyPolicy.titleMode(
            visibility = visibility,
            ongoing = sbn.isOngoing,
            groupSummary = isGroupSummary,
            ownNotification = isOwn,
        )
        if (mode == NotificationTitleMode.OMIT) return

        val allowUnlockedPreview = previewEnabled &&
            visibility != NotificationVisibility.SECRET &&
            SensitiveWidgetAccess.isUnlocked(this)

        val effectiveMode = if (allowUnlockedPreview && mode == NotificationTitleMode.APP_LABEL) {
            NotificationTitleMode.PUBLIC_TITLE
        } else {
            mode
        }

        val info = NotificationPrivacyPolicy.info(
            mode = effectiveMode,
            appLabel = label,
            packageName = sbn.packageName,
            postTimeMillis = sbn.postTime,
            key = sbn.key,
        ) {
            notification.extras?.getCharSequence(Notification.EXTRA_TITLE)
        }?.let { base ->
            if (!allowUnlockedPreview) {
                base
            } else {
                val rawBody = listOf(
                    notification.extras?.getCharSequence(Notification.EXTRA_TEXT),
                    notification.extras?.getCharSequence(Notification.EXTRA_BIG_TEXT),
                ).firstNotNullOfOrNull { cs ->
                    cs?.toString()
                        ?.replace(Regex("[\r\n\t]+"), " ")
                        ?.trim()
                        ?.take(160)
                        ?.ifEmpty { null }
                }
                base.copy(body = rawBody)
            }
        }
        snapshot.posted(sbn.key, sbn.postTime, info, visibility)
    }

    private fun parseLiveActivity(sbn: StatusBarNotification, appLabel: String): LiveActivityParseResult {
        val notification = sbn.notification
        val extras = notification.extras ?: return LiveActivityParseResult.NotALiveActivity
        val fallbackTitle = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val fallbackText = listOf(
            extras.getCharSequence(Notification.EXTRA_TEXT),
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        ).firstNotNullOfOrNull { it?.toString()?.trim()?.ifEmpty { null } }
        val progress = extras.getInt(Notification.EXTRA_PROGRESS, 0)
        val progressMax = extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
        val indeterminate = extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)
        val fallbackProgress = if (progressMax > 0 && !indeterminate && progress in 0..progressMax) {
            (progress.toFloat() / progressMax.toFloat()).coerceIn(0f, 1f)
        } else null

        val miuiFocusJson = extras.getString("miui.focus.param")
        if (!miuiFocusJson.isNullOrBlank()) {
            val result = XiaomiFocusParser.parseXiaomiFocusJson(
                rawJson = miuiFocusJson,
                key = sbn.key,
                appLabel = appLabel,
                packageName = sbn.packageName,
                fallbackTitle = fallbackTitle,
                fallbackText = fallbackText,
                fallbackProgress = fallbackProgress,
                postTimeMillis = sbn.postTime,
            )
            if (result !is LiveActivityParseResult.NotALiveActivity) return result
        }

        val shortCritical = extras.getCharSequence("android.shortCriticalText")?.toString()
        val promotedByFlag = (notification.flags and FLAG_PROMOTED_ONGOING) != 0 ||
            extras.getBoolean("android.requestPromotedOngoing", false)
        val showChronometer = extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        return XiaomiFocusParser.parseAndroidLiveUpdate(
            key = sbn.key,
            appLabel = appLabel,
            packageName = sbn.packageName,
            shortCriticalText = shortCritical,
            promotedOngoing = promotedByFlag,
            category = notification.category,
            isOngoing = sbn.isOngoing,
            showChronometer = showChronometer,
            title = fallbackTitle,
            text = fallbackText,
            subText = subText,
            progress = progress,
            progressMax = progressMax,
            progressIndeterminate = indeterminate,
            postTimeMillis = sbn.postTime,
        )
    }

    private fun loadFocusDrawable(extras: Bundle?, iconKey: String): Drawable? {
        if (extras == null || iconKey.isBlank()) return null
        return runCatching {
            val pics = extras.getBundle("miui.focus.pics")
            val icon = getIconParcelable(pics, iconKey) ?: getIconParcelable(extras, iconKey)
            icon?.loadDrawable(this)
        }.getOrNull()
    }

    @Suppress("DEPRECATION")
    private fun getIconParcelable(bundle: Bundle?, key: String): Icon? {
        if (bundle == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            bundle.getParcelable(key, Icon::class.java)
        } else {
            bundle.getParcelable(key) as? Icon
        }
    }

    private fun rankingVisibility(key: String, map: RankingMap?): NotificationVisibility {
        val ranking = Ranking()
        if (map == null || !map.getRanking(key, ranking)) return NotificationVisibility.UNKNOWN
        return if (ranking.lockscreenVisibilityOverride == Ranking.VISIBILITY_NO_OVERRIDE) {
            NotificationVisibility.PUBLIC
        } else visibilityOf(ranking.lockscreenVisibilityOverride)
    }

    companion object {
        private const val FLAG_PROMOTED_ONGOING = 0x200000
        private val monitor = Any()
        private var active: LumaNotificationListener? = null
        private var collectionEnabled = false
        private var previewEnabled = false
        private var liveActivitiesEnabled = false
        private val snapshot = NotificationMemorySnapshot()
        private val liveActivities = LinkedHashMap<String, LiveActivityInfo>(8, 0.75f, true)
        private val iconCache = LinkedHashMap<String, Drawable>(8, 0.75f, true)
        private val focusIconCache = LinkedHashMap<String, Drawable>(8, 0.75f, true)
        private val mutableChanges = MutableStateFlow(0L)
        val changes: StateFlow<Long> = mutableChanges.asStateFlow()

        const val MAX_NOTIFICATIONS = 5
        const val MAX_LIVE_ACTIVITIES = 6

        private fun changed() { mutableChanges.value += 1 }

        private fun clearAllInMemory() {
            snapshot.clear()
            liveActivities.clear()
            focusIconCache.clear()
        }

        private fun storeLiveActivity(info: LiveActivityInfo) {
            liveActivities.remove(info.key)
            if (liveActivities.size >= MAX_LIVE_ACTIVITIES) {
                liveActivities.keys.firstOrNull()?.let { eldest ->
                    liveActivities.remove(eldest)
                    focusIconCache.remove(eldest)
                }
            }
            liveActivities[info.key] = info
        }

        private fun cacheIcon(pkg: String, loader: () -> Drawable) {
            if (iconCache.containsKey(pkg)) return
            val drawable = runCatching { loader() }.getOrNull() ?: return
            if (iconCache.size >= 8) {
                iconCache.keys.firstOrNull()?.let(iconCache::remove)
            }
            iconCache[pkg] = drawable
        }

        private fun cacheFocusIcon(key: String, drawable: Drawable) {
            if (focusIconCache.size >= 8) {
                focusIconCache.keys.firstOrNull()?.let(focusIconCache::remove)
            }
            focusIconCache[key] = drawable
        }

        /** In-memory launcher icon captured by the bound NotificationListenerService. */
        fun appIconFor(packageName: String?): Drawable? = synchronized(monitor) {
            if (!collectionEnabled || packageName.isNullOrBlank()) null else iconCache[packageName]
        }

        /** Prefers Xiaomi Super Island focus icon when present, falling back to app launcher icon. */
        fun islandIconFor(key: String?, packageName: String?): Drawable? = synchronized(monitor) {
            if (!collectionEnabled) return@synchronized null
            if (!key.isNullOrBlank()) focusIconCache[key]?.let { return@synchronized it }
            if (!packageName.isNullOrBlank()) iconCache[packageName] else null
        }

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

        fun setPreviewEnabled(enabled: Boolean) = synchronized(monitor) {
            if (previewEnabled == enabled) return@synchronized
            previewEnabled = enabled
            if (collectionEnabled) {
                snapshot.clear()
                active?.seedOnce()
                changed()
            }
        }

        fun setLiveActivitiesEnabled(enabled: Boolean) = synchronized(monitor) {
            if (liveActivitiesEnabled == enabled) return@synchronized
            liveActivitiesEnabled = enabled
            if (!enabled) {
                liveActivities.clear()
                focusIconCache.clear()
            } else if (collectionEnabled) {
                active?.seedOnce()
            }
            changed()
        }

        fun setCollectionEnabled(enabled: Boolean) = synchronized(monitor) {
            if (collectionEnabled == enabled) return@synchronized
            collectionEnabled = enabled
            snapshot.setEnabled(enabled)
            if (!enabled) {
                clearAllInMemory()
                iconCache.clear()
            }
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
            if (result == null) clearAllInMemory()
            result.orEmpty()
        }

        /** Latest active Xiaomi Super Islands & Android 16 Live Updates (newest first). */
        fun latestLiveActivities(context: Context): List<LiveActivityInfo> = synchronized(monitor) {
            val result = SensitiveWidgetReadPolicy.read(
                enabled = collectionEnabled && liveActivitiesEnabled,
                isUnlocked = { SensitiveWidgetAccess.isUnlocked(context) },
                hasAccess = { active != null && isEnabled(context) },
            ) {
                liveActivities.values
                    .sortedByDescending { it.postTimeMillis }
                    .take(MAX_LIVE_ACTIVITIES)
            }
            if (result == null) {
                liveActivities.clear()
                focusIconCache.clear()
            }
            result.orEmpty()
        }

        /**
         * Dismisses a single user-visible notification by key, both from Standby's
         * in-memory snapshot and from Android's system notification shade.
         */
        fun dismiss(key: String?) = synchronized(monitor) {
            if (key.isNullOrBlank()) return@synchronized
            snapshot.removed(key)
            runCatching { active?.cancelNotification(key) }
            changed()
        }

        /**
         * Dismisses only the specified notification keys currently surfaced in Standby
         * (never calls cancelAllNotifications, leaving hidden/ongoing notifications intact).
         */
        fun dismissAll(keys: Collection<String>) = synchronized(monitor) {
            if (keys.isEmpty()) return@synchronized
            for (key in keys) {
                if (key.isBlank()) continue
                snapshot.removed(key)
                runCatching { active?.cancelNotification(key) }
            }
            changed()
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
