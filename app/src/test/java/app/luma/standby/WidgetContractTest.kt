package app.luma.standby

import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.model.StoredPlacement
import app.luma.standby.domain.model.widgetConfig
import app.luma.standby.domain.widget.BuiltinWidgets
import app.luma.standby.domain.widget.BatteryInfo
import app.luma.standby.domain.widget.NotificationInfo
import app.luma.standby.domain.widget.WidgetConfig
import app.luma.standby.domain.widget.WidgetData
import app.luma.standby.domain.widget.WidgetGlyph
import app.luma.standby.domain.widget.WidgetPermission
import app.luma.standby.domain.widget.WidgetSlot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetContractTest {

    @Test
    fun everyWidgetHasStableUniqueIdsAndLabels() {
        val ids = BuiltinWidgets.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        BuiltinWidgets.all.forEach { widget ->
            assertTrue(widget.id.isNotBlank())
            assertTrue(widget.label.isNotBlank())
            assertEquals(widget, BuiltinWidgets.byId(widget.id))
        }
    }

    @Test
    fun permissionFreeWidgetsDoNotRequireAccess() {
        assertEquals(WidgetPermission.NONE, BuiltinWidgets.battery.permission)
        assertEquals(WidgetPermission.NONE, BuiltinWidgets.alarm.permission)
        assertEquals(WidgetPermission.NONE, BuiltinWidgets.weather.permission)
        assertEquals(WidgetPermission.NOTIFICATION_ACCESS, BuiltinWidgets.notification.permission)
        assertEquals(WidgetPermission.CALENDAR, BuiltinWidgets.calendar.permission)
    }

    @Test
    fun widgetsReturnNullWhenDataIsMissingInsteadOfPlaceholders() {
        val empty = WidgetData()
        BuiltinWidgets.all.forEach { widget ->
            assertNull(widget.id + " must render nothing without data", widget.render(empty))
        }
    }

    @Test
    fun batteryWidgetShowsBoltWhileCharging() {
        val content = BuiltinWidgets.battery.render(
            WidgetData(battery = BatteryInfo(percent = 77, charging = true)),
        )
        assertEquals("77%", content?.primary)
        assertEquals(WidgetGlyph.BOLT, content?.glyph)

        val idle = BuiltinWidgets.battery.render(
            WidgetData(battery = BatteryInfo(percent = 40, charging = false)),
        )
        assertEquals(WidgetGlyph.BATTERY, idle?.glyph)
        assertNull(idle?.secondary)
    }

    @Test
    fun mediaWidgetStaysSilentWhenNothingIsPlaying() {
        assertNull(
            BuiltinWidgets.media.render(
                WidgetData(media = app.luma.standby.domain.widget.MediaInfo("x", null, false)),
            ),
        )
    }

    @Test
    fun notificationWidgetShowsTheNewestEntry() {
        val content = BuiltinWidgets.notification.render(
            WidgetData(
                notifications = listOf(
                    NotificationInfo("WeChat", "newest"),
                    NotificationInfo("Mail", "older"),
                ),
            ),
        )
        assertEquals("newest", content?.primary)
        assertEquals("WeChat", content?.secondary)
    }

    @Test
    fun placementMovesAndRemovesWithoutDuplicates() {
        val config = WidgetConfig()
            .withPlacement("battery", WidgetSlot.TOP_RIGHT)
            .withPlacement("weather", WidgetSlot.TOP_RIGHT)
        assertEquals(listOf("battery", "weather"), config.widgetIdsIn(WidgetSlot.TOP_RIGHT))

        val moved = config.withPlacement("battery", WidgetSlot.BOTTOM_RIGHT)
        assertEquals(listOf("weather"), moved.widgetIdsIn(WidgetSlot.TOP_RIGHT))
        assertEquals(listOf("battery"), moved.widgetIdsIn(WidgetSlot.BOTTOM_RIGHT))
        assertTrue(moved.isValid())

        val removed = moved.withPlacement("battery", null)
        assertFalse(removed.isEnabled("battery"))
    }

    @Test
    fun unknownWidgetIdsAreInvalid() {
        val config = WidgetConfig(
            placements = listOf(app.luma.standby.domain.widget.WidgetPlacement("nope", WidgetSlot.TOP_RIGHT)),
        )
        assertFalse(config.isValid())
    }

    @Test
    fun settingsRoundTripPlacementsThroughJson() {
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val settings = LumaSettings(
            widgetPlacements = listOf(
                StoredPlacement("battery", WidgetSlot.TOP_RIGHT.name),
                StoredPlacement("notification", WidgetSlot.BOTTOM_RIGHT.name),
            ),
        )
        val encoded = json.encodeToString(LumaSettings.serializer(), settings)
        val decoded = json.decodeFromString(LumaSettings.serializer(), encoded)
        assertEquals(2, decoded.widgetConfig.placements.size)
        assertTrue(decoded.widgetConfig.isValid())
    }

    @Test
    fun oldSettingsWithoutWidgetFieldsStillDecode() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = """{"themeId":"burgundy","mode":"STANDARD","brightness":0.2}"""
        val decoded = json.decodeFromString(LumaSettings.serializer(), legacy)
        assertEquals("burgundy", decoded.themeId)
        assertTrue(decoded.widgetConfig.placements.isEmpty())
    }
}
