package app.luma.standby

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import app.luma.standby.domain.model.LumaSettings
import app.luma.standby.domain.model.StoredPlacement
import app.luma.standby.domain.widget.NotificationInfo
import app.luma.standby.domain.widget.WidgetData
import app.luma.standby.platform.widgets.WidgetRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetRendererLocaleTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun privateNotificationLabelIsNotRepeatedAsSecondary() {
        val settings = LumaSettings(
            widgetPlacements = listOf(StoredPlacement("notification", "TOP_RIGHT")),
        )
        val data = WidgetData(
            notifications = listOf(NotificationInfo("小米互联通信服务", "小米互联通信服务")),
        )
        val tiles = WidgetRenderer.tiles(settings, data, context = context)
        assertEquals(1, tiles.size)
        assertEquals("小米互联通信服务", tiles.first().content.primary)
        assertNull(tiles.first().content.secondary)
    }

    @Test
    fun publicNotificationKeepsItsTitle() {
        val settings = LumaSettings(
            widgetPlacements = listOf(StoredPlacement("notification", "TOP_RIGHT")),
        )
        val data = WidgetData(notifications = listOf(NotificationInfo("WeChat", "newest")))
        val tile = WidgetRenderer.tiles(settings, data, context = context).single()
        assertEquals("newest", tile.content.primary)
        assertEquals("WeChat", tile.content.secondary)
    }
}