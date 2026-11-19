package app.luma.standby.platform.widgets

/** Pure, lazy gate shared by notifications and media; disabled sources do no platform work. */
internal object SensitiveWidgetReadPolicy {
    fun <T> read(
        enabled: Boolean,
        isUnlocked: () -> Boolean,
        hasAccess: () -> Boolean,
        source: () -> T?,
    ): T? {
        if (!enabled) return null
        return try {
            if (!isUnlocked() || !hasAccess()) return null
            val result = source()
            // Permission revocation or locking during a read must not publish its result.
            if (isUnlocked() && hasAccess()) result else null
        } catch (_: SecurityException) {
            null
        } catch (_: RuntimeException) {
            // Unavailable system services/OEM failures are not permission to show old data.
            null
        }
    }
}
