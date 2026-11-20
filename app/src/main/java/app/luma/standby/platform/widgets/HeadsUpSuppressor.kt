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

    /**
     * On Android 15+ (SDK >= 35), calling `setInterruptionFilter(INTERRUPTION_FILTER_ALL)`
     * from an app only deactivates that app's own implicit ZenRule without disabling the
     * user's manual DND or Bedtime rules. On older SDKs, restores the exact pre-Standby filter.
     */
    fun restoreFilter(savedFilter: Int, sdkInt: Int): Int =
        if (sdkInt >= 35) NotificationManager.INTERRUPTION_FILTER_ALL else savedFilter
}

/**
 * Temporarily suppresses Android/HyperOS floating heads-up banners while
 * [app.luma.standby.StandbyActivity] is in the foreground, and faithfully
 * restores the user's original Do Not Disturb filter and policy on pause,
 * screen-off, destroy, or crash/process-kill recovery.
 *
 * Requires explicit user opt-in via Notification Policy Access in Settings;
 * fails closed (does nothing) whenever access is not granted.
 */
class HeadsUpSuppressor(private val context: Context) {

    private val prefs by lazy {
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    private var active = false
    private var savedFilter: Int = NotificationManager.INTERRUPTION_FILTER_ALL
    private var savedPolicy: NotificationManager.Policy? = null

    fun suppress() {
        if (active) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!isAccessGranted(context)) return

        runCatching {
            val hadLeakedSession = prefs.getBoolean(KEY_ACTIVE, false)
            val currentFilter = nm.currentInterruptionFilter
            val currentPolicy = nm.notificationPolicy

            // Never overwrite the true pre-Standby baseline if a previous process died mid-Standby.
            if (hadLeakedSession) {
                savedFilter = prefs.getInt(KEY_SAVED_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
                savedPolicy = readSavedPolicy() ?: currentPolicy
            } else {
                val effectiveBaselineFilter = if (Build.VERSION.SDK_INT >= 35 && hasActiveOwnImplicitRule(nm)) {
                    NotificationManager.INTERRUPTION_FILTER_ALL
                } else {
                    currentFilter
                }
                savedFilter = effectiveBaselineFilter
                savedPolicy = currentPolicy
                persistBaseline(effectiveBaselineFilter, currentPolicy)
            }

            val baselinePolicy = savedPolicy ?: currentPolicy
            val targetFilter = HeadsUpPolicyMath.targetFilter(savedFilter)
            val fromNormal = savedFilter == NotificationManager.INTERRUPTION_FILTER_ALL
            val categories = if (fromNormal) {
                HeadsUpPolicyMath.ALL_AUDIBLE_CATEGORIES
            } else {
                baselinePolicy.priorityCategories
            }
            val callSenders = if (fromNormal) {
                NotificationManager.Policy.PRIORITY_SENDERS_ANY
            } else {
                baselinePolicy.priorityCallSenders
            }
            val msgSenders = if (fromNormal) {
                NotificationManager.Policy.PRIORITY_SENDERS_ANY
            } else {
                baselinePolicy.priorityMessageSenders
            }
            val visual = HeadsUpPolicyMath.suppressedVisualEffects(baselinePolicy.suppressedVisualEffects)

            val newPolicy = if (Build.VERSION.SDK_INT >= 30) {
                val convSenders = if (fromNormal) {
                    NotificationManager.Policy.CONVERSATION_SENDERS_ANYONE
                } else {
                    baselinePolicy.priorityConversationSenders
                }
                NotificationManager.Policy(categories, callSenders, msgSenders, visual, convSenders)
            } else {
                NotificationManager.Policy(categories, callSenders, msgSenders, visual)
            }

            nm.notificationPolicy = newPolicy
            if (targetFilter != currentFilter || (Build.VERSION.SDK_INT >= 35 && fromNormal)) {
                nm.setInterruptionFilter(targetFilter)
            }
            active = true
        }
    }

    fun restore() {
        val leaked = prefs.getBoolean(KEY_ACTIVE, false)
        val nm = context.getSystemService(NotificationManager::class.java)
        val ownRuleStuck = nm != null && Build.VERSION.SDK_INT >= 35 && isAccessGranted(context) &&
            hasActiveOwnImplicitRule(nm)

        if (!active && !leaked && !ownRuleStuck) return
        active = false

        val policyToRestore = savedPolicy ?: readSavedPolicy()
        val filterToRestore = if (leaked) {
            prefs.getInt(KEY_SAVED_FILTER, NotificationManager.INTERRUPTION_FILTER_ALL)
        } else {
            savedFilter
        }
        clearBaseline()
        savedPolicy = null

        if (nm == null || !isAccessGranted(context)) return
        runCatching {
            policyToRestore?.let { nm.notificationPolicy = it }
            nm.setInterruptionFilter(HeadsUpPolicyMath.restoreFilter(filterToRestore, Build.VERSION.SDK_INT))
        }
    }

    /**
     * Safe to call from MainActivity / outside Standby: heals any DND state left behind
     * if StandbyActivity was killed by the OS or app upgrade before onPause could run.
     */
    fun restoreIfLeaked() {
        val leaked = prefs.getBoolean(KEY_ACTIVE, false)
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!isAccessGranted(context)) {
            if (leaked) clearBaseline()
            return
        }
        val ownRuleStuck = Build.VERSION.SDK_INT >= 35 && hasActiveOwnImplicitRule(nm)
        if (leaked || ownRuleStuck) {
            restore()
        }
    }

    private fun hasActiveOwnImplicitRule(nm: NotificationManager): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < 35) return@runCatching false
        val ownPkg = context.packageName
        nm.automaticZenRules.entries.any { (id, rule) ->
            id.contains(ownPkg) && rule.isEnabled
        }
    }.getOrDefault(false)

    private fun persistBaseline(filter: Int, policy: NotificationManager.Policy) {
        prefs.edit()
            .putBoolean(KEY_ACTIVE, true)
            .putInt(KEY_SAVED_FILTER, filter)
            .putBoolean(KEY_HAS_POLICY, true)
            .putInt(KEY_CATS, policy.priorityCategories)
            .putInt(KEY_CALL_SENDERS, policy.priorityCallSenders)
            .putInt(KEY_MSG_SENDERS, policy.priorityMessageSenders)
            .putInt(KEY_VISUAL, policy.suppressedVisualEffects)
            .putInt(KEY_CONV_SENDERS, if (Build.VERSION.SDK_INT >= 30) policy.priorityConversationSenders else 0)
            .apply()
    }

    private fun readSavedPolicy(): NotificationManager.Policy? {
        if (!prefs.getBoolean(KEY_HAS_POLICY, false)) return null
        val cats = prefs.getInt(KEY_CATS, HeadsUpPolicyMath.ALL_AUDIBLE_CATEGORIES)
        val calls = prefs.getInt(KEY_CALL_SENDERS, NotificationManager.Policy.PRIORITY_SENDERS_ANY)
        val msgs = prefs.getInt(KEY_MSG_SENDERS, NotificationManager.Policy.PRIORITY_SENDERS_ANY)
        val visual = prefs.getInt(KEY_VISUAL, 0)
        return if (Build.VERSION.SDK_INT >= 30) {
            val conv = prefs.getInt(KEY_CONV_SENDERS, NotificationManager.Policy.CONVERSATION_SENDERS_ANYONE)
            NotificationManager.Policy(cats, calls, msgs, visual, conv)
        } else {
            NotificationManager.Policy(cats, calls, msgs, visual)
        }
    }

    private fun clearBaseline() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val PREFS_NAME = "luma_headsup_guard"
        private const val KEY_ACTIVE = "active"
        private const val KEY_SAVED_FILTER = "saved_filter"
        private const val KEY_HAS_POLICY = "has_policy"
        private const val KEY_CATS = "cats"
        private const val KEY_CALL_SENDERS = "call_senders"
        private const val KEY_MSG_SENDERS = "msg_senders"
        private const val KEY_VISUAL = "visual"
        private const val KEY_CONV_SENDERS = "conv_senders"

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
