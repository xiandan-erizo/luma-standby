package app.luma.standby.domain.model

import app.luma.standby.domain.widget.WidgetConfig
import app.luma.standby.domain.widget.WidgetPlacement
import app.luma.standby.domain.widget.WidgetSlot

/** Converts between the persisted placement records and the domain config. */
fun List<StoredPlacement>.toWidgetConfig(): WidgetConfig = WidgetConfig(
    placements = mapNotNull { stored ->
        val slot = runCatching { WidgetSlot.valueOf(stored.slot) }.getOrNull()
            ?: return@mapNotNull null
        WidgetPlacement(stored.widgetId, slot)
    },
)

fun WidgetConfig.toStored(): List<StoredPlacement> =
    placements.map { StoredPlacement(it.widgetId, it.slot.name) }

val LumaSettings.widgetConfig: WidgetConfig
    get() = widgetPlacements.toWidgetConfig()
