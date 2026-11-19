package app.luma.standby.platform.widgets

import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.model.widgetConfig
import app.luma.standby.domain.widget.BuiltinWidgets
import app.luma.standby.domain.widget.WidgetData
import app.luma.standby.domain.widget.WidgetSlot
import app.luma.standby.ui.clock.WidgetTile

/**
 * Turns settings + data into drawable tiles. Pure: no Context, no drawing, so the
 * mapping is unit-testable and the clock stays a renderer.
 */
object WidgetRenderer {

    fun tiles(
        settings: LumaSettings,
        data: WidgetData,
        includeNotification: (String) -> Boolean = { BuiltinWidgets.byId(it) != null },
    ): List<WidgetTile> {
        val config = settings.widgetConfig
        if (!config.isValid()) return emptyList()

        return config.placements.mapNotNull { placement ->
            val widget = BuiltinWidgets.byId(placement.widgetId) ?: return@mapNotNull null
            if (!includeNotification(placement.widgetId)) return@mapNotNull null
            val content = widget.render(data) ?: return@mapNotNull null
            WidgetTile(slot = placement.slot, content = content)
        }
    }

    /** Widgets the user has placed left-to-right, for the settings summary. */
    fun slotsFor(settings: LumaSettings): Map<String, WidgetSlot> =
        settings.widgetConfig.placements.associate { it.widgetId to it.slot }
}
