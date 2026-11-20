package app.luma.standby.ui.clock

import app.luma.standby.domain.widget.NotificationInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** In-memory only debug alert channel; only collected when FLAG_DEBUGGABLE is set. */
internal object DebugAlertBus {
    private val mutable = MutableSharedFlow<StandbyNotificationAlert>(extraBufferCapacity = 2)
    val alerts = mutable.asSharedFlow()
    fun emit(alert: StandbyNotificationAlert) {
        mutable.tryEmit(alert)
    }
}

/**
 * Transient, in-memory-only full-screen iOS StandBy notification alert.
 * Never persisted to disk and cleared immediately on pause or screen-off.
 */
data class StandbyNotificationAlert(
    val appLabel: String,
    val title: String,
    val packageName: String? = null,
    val unreadCount: Int = 1,
    val tapToReveal: Boolean = false,
    val revealed: Boolean = false,
    val isPrivateRedacted: Boolean = false,
) {
    /** True when the alert masks its content until the user explicitly taps to reveal. */
    val isMasked: Boolean
        get() = isPrivateRedacted || (tapToReveal && !revealed)
}

/** Sleek top-bar Liquid Glass indicator reminding the user of active unread notifications. */
data class UnreadNotificationSummary(
    val appLabel: String,
    val packageName: String? = null,
    val count: Int,
)

/**
 * Pure event-driven tracker: seeds existing notifications on standby start so stale
 * items never pop up unexpectedly, and only triggers full-screen alerts for genuinely
 * new arrivals while visible.
 */
class StandbyAlertTracker {
    private val seen = HashSet<String>()
    private var seeded = false

    fun reset() {
        seen.clear()
        seeded = false
    }

    /**
     * Feeds the current sorted (newest-first) notification snapshot.
     * Returns the newly arrived top notification that should trigger an incoming alert,
     * or null if nothing new arrived.
     */
    fun onSnapshot(latest: List<NotificationInfo>): NotificationInfo? {
        if (!seeded) {
            for (n in latest) seen.add(identityOf(n))
            seeded = true
            return null
        }
        val currentIds = latest.mapTo(HashSet(latest.size)) { identityOf(it) }
        // Retain seen entries that still exist so removals don't leak memory.
        seen.retainAll(currentIds)

        val newest = latest.firstOrNull() ?: return null
        val id = identityOf(newest)
        return if (seen.add(id)) {
            for (n in latest) seen.add(identityOf(n))
            newest
        } else {
            null
        }
    }

    private fun identityOf(n: NotificationInfo): String =
        if (!n.key.isNullOrEmpty()) "${n.key}@${n.postTimeMillis}"
        else "${n.appLabel}|${n.title}@${n.postTimeMillis}"
}
