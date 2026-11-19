package app.luma.standby.ui.clock

import app.luma.standby.domain.widget.WidgetContent
import app.luma.standby.domain.widget.WidgetSlot

/** One rendered widget: where it goes and what it says. Immutable on purpose. */
data class WidgetTile(
    val slot: WidgetSlot,
    val content: WidgetContent,
)
