package app.luma.standby.domain.widget

/**
 * Where widgets sit around the clock. Fixed slots rather than absolute
 * coordinates: absolute positions are a layout engine we do not need yet, and
 * slots keep the face balanced without the user having to nudge anything.
 */
enum class WidgetSlot(
    val label: String,
    /** Top-right column, status-like. */
    val isCorner: Boolean,
) {
    TOP_RIGHT("Top right", true),
    BOTTOM_RIGHT("Bottom right", true),
    BOTTOM_LEFT("Bottom left", false),
}

data class WidgetPlacement(
    val widgetId: String,
    val slot: WidgetSlot,
)

/**
 * Persisted widget configuration. Kept as a list so order is meaningful and
 * JSON round-trips are obvious.
 */
data class WidgetConfig(
    val placements: List<WidgetPlacement> = emptyList(),
) {
    fun isEnabled(widgetId: String): Boolean = placements.any { it.widgetId == widgetId }

    fun widgetIdsIn(slot: WidgetSlot): List<String> =
        placements.filter { it.slot == slot }.map { it.widgetId }

    fun enabledIds(): Set<String> = placements.map { it.widgetId }.toSet()

    /**
     * Move a widget to a slot (or remove it when [slot] is null). One widget may
     * only occupy one slot; placement order within a slot is insertion order so a
     * column reads top-to-bottom.
     */
    fun withPlacement(widgetId: String, slot: WidgetSlot?): WidgetConfig {
        val others = placements.filterNot { it.widgetId == widgetId }
        return if (slot == null) copy(placements = others) else copy(
            placements = others + WidgetPlacement(widgetId, slot),
        )
    }

    fun isValid(): Boolean =
        placements.map { it.widgetId }.toSet().size == placements.size &&
            placements.all { BuiltinWidgets.byId(it.widgetId) != null }
}
