package app.luma.standby.platform.widgets

import app.luma.standby.domain.widget.LiveActivitySource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class XiaomiFocusParserTest {

    @Test fun parsesHyperOs34SuperIslandTaxiPayload() {
        val raw = """
            {
              "param_v2": {
                "protocol": 1,
                "business": "taxi",
                "enableFloat": true,
                "updatable": true,
                "cancel": false,
                "ticker": "司机正向您赶来",
                "tickerPic": "miui.focus.pic_ticker",
                "aodTitle": "京A·88888 白色比亚迪",
                "progressInfo": {
                  "progress": 72,
                  "colorProgress": "#FF7A2F"
                },
                "param_island": {
                  "islandProperty": 1,
                  "bigIslandArea": {
                    "imageTextInfoLeft": {
                      "type": 1,
                      "picInfo": { "type": 1, "pic": "miui.focus.pic_taxi" },
                      "textInfo": { "frontTitle": "距离", "title": "800米", "content": "预计 14:12 到达" }
                    },
                    "imageTextInfoRight": {
                      "type": 1,
                      "textInfo": { "title": "3分钟", "content": "正在驶来" }
                    }
                  },
                  "smallIslandArea": {
                    "picInfo": { "type": 1, "pic": "miui.focus.pic_small" }
                  }
                }
              }
            }
        """.trimIndent()

        val result = XiaomiFocusParser.parseXiaomiFocusJson(
            rawJson = raw,
            key = "0|com.sdu.didi.psnger|1|null|1000",
            appLabel = "滴滴出行",
            packageName = "com.sdu.didi.psnger",
            fallbackTitle = "行程通知",
            fallbackText = "司机正在赶来",
            fallbackProgress = null,
            postTimeMillis = 123456L,
        )

        assertTrue(result is LiveActivityParseResult.Active)
        val active = result as LiveActivityParseResult.Active
        assertEquals(LiveActivitySource.XIAOMI_SUPER_ISLAND, active.info.source)
        assertEquals("距离800米", active.info.title)
        assertEquals("预计 14:12 到达", active.info.subtitle)
        assertEquals("司机正向您赶来", active.info.compactText)
        assertEquals("3分钟", active.info.highlightMetric)
        assertEquals(0.72f, active.info.progress!!, 0.001f)
        assertEquals(0xFFFF7A2F.toInt(), active.info.accentColor)
        assertEquals("miui.focus.pic_taxi", active.focusIconKey)
    }

    @Test fun parsesHyperOs2BaseInfoDeliveryPayload() {
        val raw = """
            {
              "param_v2": {
                "business": "delivery",
                "ticker": "骑手距您 300m",
                "baseInfo": {
                  "title": "骑手正在配送中",
                  "content": "预计 14:18 送达 · 请保持电话畅通",
                  "extraTitle": "4 分钟"
                },
                "progressInfo": {
                  "progress": 80
                }
              }
            }
        """.trimIndent()

        val result = XiaomiFocusParser.parseXiaomiFocusJson(
            rawJson = raw,
            key = "meituan-1",
            appLabel = "美团外卖",
            packageName = "com.sankuai.meituan.takeoutnew",
            fallbackTitle = null,
            fallbackText = null,
            fallbackProgress = null,
            postTimeMillis = 500L,
        )

        val active = result as LiveActivityParseResult.Active
        assertEquals("骑手正在配送中", active.info.title)
        assertEquals("预计 14:18 送达 · 请保持电话畅通", active.info.subtitle)
        assertEquals("骑手距您 300m", active.info.compactText)
        assertEquals("4 分钟", active.info.highlightMetric)
        assertEquals(0.80f, active.info.progress!!, 0.001f)
        assertEquals(0xFFFFB000.toInt(), active.info.accentColor)
    }

    @Test fun cancelFlagEndsSuperIslandCleanly() {
        val raw = """{"param_v2":{"business":"delivery","cancel":true}}"""
        val result = XiaomiFocusParser.parseXiaomiFocusJson(
            rawJson = raw,
            key = "meituan-1",
            appLabel = "美团外卖",
            packageName = "com.sankuai.meituan",
            fallbackTitle = "已送达",
            fallbackText = null,
            fallbackProgress = null,
            postTimeMillis = 600L,
        )
        assertEquals(LiveActivityParseResult.Cancelled, result)
    }

    @Test fun malformedJsonFailsClosedWithoutThrowing() {
        val result = XiaomiFocusParser.parseXiaomiFocusJson(
            rawJson = "{broken json",
            key = "bad-1",
            appLabel = "App",
            packageName = "com.example",
            fallbackTitle = "Title",
            fallbackText = "Body",
            fallbackProgress = null,
            postTimeMillis = 0L,
        )
        assertEquals(LiveActivityParseResult.NotALiveActivity, result)
    }

    @Test fun parsesAndroid16LiveUpdateShortCriticalTextAndIgnoresSystemNoise() {
        val live = XiaomiFocusParser.parseAndroidLiveUpdate(
            key = "a16-1",
            appLabel = "Maps",
            packageName = "com.google.android.apps.maps",
            shortCriticalText = "5 min",
            promotedOngoing = true,
            category = "navigation",
            isOngoing = true,
            showChronometer = false,
            title = "Turn right onto Main St",
            text = "1.2 km remaining",
            subText = null,
            progress = 65,
            progressMax = 100,
            progressIndeterminate = false,
            postTimeMillis = 10L,
        )
        val active = live as LiveActivityParseResult.Active
        assertEquals(LiveActivitySource.ANDROID_LIVE_UPDATE, active.info.source)
        assertEquals("5 min", active.info.highlightMetric)
        assertEquals(0.65f, active.info.progress!!, 0.001f)
        assertNotNull(active.info.accentColor)

        // System USB/background service noise must NOT enter the Dynamic Island.
        val noise = XiaomiFocusParser.parseAndroidLiveUpdate(
            key = "sys-1",
            appLabel = "Android System",
            packageName = "android",
            shortCriticalText = null,
            promotedOngoing = false,
            category = "service",
            isOngoing = true,
            showChronometer = false,
            title = "USB debugging connected",
            text = "Tap to turn off",
            subText = null,
            progress = 0,
            progressMax = 0,
            progressIndeterminate = false,
            postTimeMillis = 10L,
        )
        assertEquals(LiveActivityParseResult.NotALiveActivity, noise)

        // Hex color parser bounds check
        assertNull(XiaomiFocusParser.parseHexColor("not-a-color"))
        assertEquals(0xFF00C853.toInt(), XiaomiFocusParser.parseHexColor("#00C853"))
    }
}
