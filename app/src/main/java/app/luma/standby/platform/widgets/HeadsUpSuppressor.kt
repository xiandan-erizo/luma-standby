package app.luma.standby.platform.widgets

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Pure policy calculator for suppressing OS floating heads-up banners
 * (`SUPPRESSED_EFFECT_PEEK`) during Standby without silencing calls, alarms,
 * media, or notification sounds.
 */
internal object HeadsUpPolicyMath {
    const val ALL_AUDIBLE_CATEGORIES: Int =
        NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS or
            NotificationManager.Policy.PRIORITY_CATEGORY_MEDIA or
            NotificationManager.Policy.PRIORITY_CATEGORY_SYSTEM or
            NotificationManager.Policy.PRIORITY_CATEGORY_REMINDERS or
            NotificationManager.Policy.PRIORITY_CATEGORY_EVENTS or
            NotificationManager.Policy.PRIORITY_CATEGORY_MESSAGES or
            NotificationManager.Policy.PRIORITY_CATEGORY_CALLS or
            NotificationManager.Policy.PRIORITY_CATEGORY_REPEAT_CALLERS or
            NotificationManager.Policy.PRIORITY_CATEGORY_CONVERSATIONS

    fun suppressedVisualEffects(existing: Int): Int =
        existing or NotificationManager.Policy.SUPPRESSED_EFFECT_PEEK

    /**
     * When the phone is not already in DND (`INTERRUPTION_FILTER_ALL`), switches to
     * `INTERRUPTION_FILTER_PRIORITY` with every audible category allowed so ONLY
     * heads-up peeking banners are hidden. If the user already enabled a stricter
     * DND filter, we preserve their filter untouched.
     */
    fun targetFilter(currentFilter: Int): Int =
        if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
            NotificationManager.INTERRUPTION_FILTER_PRIORITY
        } else {
            currentFilter
        }
}

/**
 * Temporarily suppresses Android/HyperOS floating heads-up banners while
 * [app.luma.standby.StandbyActivity] is in the foreground, and faithfully
 * restores the user's original Do Not Disturb filter and policy on pause,
 * screen-off, or destroy.
 *
 * Requires explicit user opt-in via Notification Policy Access in Settings;
 * fails closed (does nothing) whenever access is not granted.
 */
class HeadsUpSuppressor(private val context: Context) {

    private var active = false
    private var savedFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL
    private var savedPolicy: NotificationManager.Policy? = null

    fun suppress() {
        if (active) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!isAccessGranted(context)) return

        runCatching {
            val currentFilter = nm.currentInterruptionFilter
            val currentPolicy = nm.notificationPolicy
            savedFilter = currentFilter
            savedPolicy = currentPolicy

            val targetFilter = HeadsUpPolicyMath.targetFilter(currentFilter)
            val categories = if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                HeadsUpPolicyMath.ALL_AUDIBLE_CATEGORIES
            } else {
                currentPolicy.priorityCategories
            }
            val callSenders = if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                NotificationManager.Policy.PRIORITY_SENDERS_ANY
            } else {
                currentPolicy.priorityCallSenders
            }
            val msgSenders = if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                NotificationManager.Policy.PRIORITY_SENDERS_ANY
            } else {
                currentPolicy.priorityMessageSenders
            }
            val visual = HeadsUpPolicyMath.suppressedVisualEffects(currentPolicy.suppressedVisualEffects)

            val newPolicy = if (Build.VERSION.SDK_INT >= 30) {
                val convSenders = if (currentFilter == NotificationManager.INTERRUPTION_FILTER_ALL) {
                    NotificationManager.Policy.CONVERSATION_SENDERS_ANYONE
                } else {
                    currentPolicy.priorityConversationSenders
                }
                NotificationManager.Policy(categories, callSenders, msgSenders, visual, convSenders)
            } else {
                NotificationManager.Policy(categories, callSenders, msgSenders, visual)
            }

            nm.notificationPolicy = newPolicy
            if (targetFilter != currentFilter) {
                nm.setInterruptionFilter(targetFilter)
            }
            active = true
        }
    }

    fun restore() {
        if (!active) return
        active = false
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!isAccessGranted(context)) {
            savedPolicy = null
            return
        }
        runCatching {
            savedPolicy?.let { nm.notificationPolicy = it }
            nm.setInterruptionFilter(savedFilter)
        }
        savedPolicy = null
    }

    companion object {
        fun isAccessGranted(context: Context): Boolean = runCatching {
            context.getSystemService(NotificationManager::class.java)
                ?.isNotificationPolicyAccessGranted == true
        }.getOrDefault(false)

        fun openSettings(context: Context) {
            val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(intent) }
        }
    }
}
