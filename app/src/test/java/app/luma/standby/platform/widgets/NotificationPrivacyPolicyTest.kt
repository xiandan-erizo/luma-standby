package app.luma.standby.platform.widgets

import app.luma.standby.domain.widget.NotificationInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPrivacyPolicyTest {
    @Test fun publicTitleIsReadOnlyForAnEligiblePublicNotification() {
        var reads = 0
        val info = NotificationPrivacyPolicy.info(
            NotificationPrivacyPolicy.titleMode(NotificationVisibility.PUBLIC), " Mail ",
        ) { reads++; " Subject " }
        assertEquals(NotificationInfo("Mail", "Subject"), info)
        assertEquals(1, reads)
    }

    @Test fun privateAndUnknownNeverReadTitleAndShowOnlyAppLabel() {
        for (visibility in listOf(NotificationVisibility.PRIVATE, NotificationVisibility.UNKNOWN)) {
            val info = NotificationPrivacyPolicy.info(
                NotificationPrivacyPolicy.titleMode(visibility), "Mail",
            ) { error("Private title must not be read") }
            assertEquals(NotificationInfo("Mail", "Mail"), info)
        }
    }

    @Test fun secretOngoingSummaryAndOwnNotificationsAreOmittedWithoutReadingTitle() {
        val modes = listOf(
            NotificationPrivacyPolicy.titleMode(NotificationVisibility.SECRET),
            NotificationPrivacyPolicy.titleMode(NotificationVisibility.PUBLIC, ongoing = true),
            NotificationPrivacyPolicy.titleMode(NotificationVisibility.PUBLIC, groupSummary = true),
            NotificationPrivacyPolicy.titleMode(NotificationVisibility.PUBLIC, ownNotification = true),
        )
        for (mode in modes) {
            assertNull(NotificationPrivacyPolicy.info(mode, "App") { error("Must not read") })
        }
    }

    @Test fun userRankingOverrideCanRestrictButNeverUpgradeAppVisibility() {
        assertEquals(NotificationVisibility.PRIVATE, NotificationPrivacyPolicy.visibility(
            NotificationVisibility.PUBLIC, NotificationVisibility.PRIVATE,
        ))
        assertEquals(NotificationVisibility.PRIVATE, NotificationPrivacyPolicy.visibility(
            NotificationVisibility.PRIVATE, NotificationVisibility.PUBLIC,
        ))
        assertEquals(NotificationVisibility.SECRET, NotificationPrivacyPolicy.visibility(
            NotificationVisibility.PUBLIC, NotificationVisibility.SECRET,
        ))
        assertEquals(NotificationVisibility.UNKNOWN, NotificationPrivacyPolicy.visibility(
            NotificationVisibility.PUBLIC, NotificationVisibility.UNKNOWN,
        ))
    }

    @Test fun blankTitleFallsBackToAppLabelAndTextIsBounded() {
        assertEquals("App", NotificationPrivacyPolicy.info(NotificationTitleMode.PUBLIC_TITLE, "App") { " " }?.title)
        assertEquals(160, NotificationPrivacyPolicy.info(NotificationTitleMode.PUBLIC_TITLE, "App") { "x".repeat(200) }?.title?.length)
        assertNull(NotificationPrivacyPolicy.info(NotificationTitleMode.PUBLIC_TITLE, " ") { error("No label") })
    }

    @Test fun disabledSnapshotCannotStoreAndDisablingErasesExistingTitles() {
        val memory = NotificationMemorySnapshot()
        memory.posted("1", 1, NotificationInfo("App", "secret title"), NotificationVisibility.PUBLIC)
        memory.setEnabled(true)
        assertTrue(memory.latest(5).isEmpty())
        memory.posted("1", 1, NotificationInfo("App", "title"), NotificationVisibility.PUBLIC)
        assertEquals(1, memory.latest(5).size)
        memory.setEnabled(false)
        memory.setEnabled(true)
        assertTrue(memory.latest(5).isEmpty())
    }

    @Test fun postingRemovalAndRedactedReplacementUpdateMemoryWithoutPolling() {
        val memory = NotificationMemorySnapshot()
        memory.setEnabled(true)
        memory.posted("older", 1, NotificationInfo("Mail", "old"), NotificationVisibility.PUBLIC)
        memory.posted("newer", 2, NotificationInfo("Chat", "new"), NotificationVisibility.PUBLIC)
        assertEquals(listOf("new", "old"), memory.latest(5).map { it.title })
        memory.posted("newer", 3, null, NotificationVisibility.SECRET)
        assertEquals(listOf("old"), memory.latest(5).map { it.title })
        memory.removed("older")
        assertTrue(memory.latest(5).isEmpty())
    }

    @Test fun visibilityDowngradeErasesTitleAndCannotRestoreItFromOldEvent() {
        val memory = NotificationMemorySnapshot()
        memory.setEnabled(true)
        memory.posted("1", 1, NotificationInfo("Mail", "subject"), NotificationVisibility.PUBLIC)
        memory.restrictVisibility("1", NotificationVisibility.PRIVATE)
        assertEquals(NotificationInfo("Mail", "Mail"), memory.latest(5).single())
        memory.restrictVisibility("1", NotificationVisibility.PUBLIC)
        assertEquals("Mail", memory.latest(5).single().title)
        memory.restrictVisibility("1", NotificationVisibility.SECRET)
        assertTrue(memory.latest(5).isEmpty())
    }

    @Test fun unlockedPreviewPreservesSenderAndBodyAcrossPrivateRankingButStillOmitsSecret() {
        val memory = NotificationMemorySnapshot()
        memory.setEnabled(true)
        memory.posted(
            key = "wechat-1",
            postTime = 10L,
            info = NotificationInfo("微信", "产品设计群", body = "张三：马上开始评审"),
            visibility = NotificationVisibility.PRIVATE,
        )
        memory.restrictVisibility("wechat-1", NotificationVisibility.PRIVATE, allowUnlockedPreview = true)
        val preserved = memory.latest(5).single()
        assertEquals("产品设计群", preserved.title)
        assertEquals("张三：马上开始评审", preserved.body)

        // Turning preview off redacts private title and strips body.
        memory.restrictVisibility("wechat-1", NotificationVisibility.PRIVATE, allowUnlockedPreview = false)
        val redacted = memory.latest(5).single()
        assertEquals("微信", redacted.title)
        assertNull(redacted.body)

        // SECRET visibility is always omitted even when unlocked preview is enabled.
        memory.posted(
            key = "secret-1",
            postTime = 20L,
            info = NotificationInfo("微信", "私密", body = "验证码"),
            visibility = NotificationVisibility.PRIVATE,
        )
        memory.restrictVisibility("secret-1", NotificationVisibility.SECRET, allowUnlockedPreview = true)
        assertEquals(listOf("微信"), memory.latest(5).map { it.title })
    }

    @Test fun snapshotIsBoundedAndClearDropsAllEntries() {
        val memory = NotificationMemorySnapshot(capacity = 2)
        memory.setEnabled(true)
        for (i in 1..3) memory.posted("$i", i.toLong(), NotificationInfo("App", "$i"), NotificationVisibility.PUBLIC)
        assertEquals(listOf("3", "2"), memory.latest(5).map { it.title })
        assertEquals(1, memory.latest(1).size)
        memory.clear()
        assertTrue(memory.latest(5).isEmpty())
    }
}
