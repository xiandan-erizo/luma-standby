package app.luma.standby.platform.widgets

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SensitiveWidgetReadPolicyTest {
    @Test fun disabledReadDoesNotCheckPermissionLockOrSource() {
        assertNull(SensitiveWidgetReadPolicy.read<String>(false,
            { error("Lock checked") }, { error("Permission checked") }, { error("Source queried") },
        ))
    }

    @Test fun lockedReadDoesNotCheckPermissionOrSource() {
        assertNull(SensitiveWidgetReadPolicy.read<String>(true,
            { false }, { error("Permission checked") }, { error("Source queried") },
        ))
    }

    @Test fun deniedReadDoesNotQuerySource() {
        assertNull(SensitiveWidgetReadPolicy.read<String>(true,
            { true }, { false }, { error("Source queried") },
        ))
    }

    @Test fun lockOrPermissionSecurityExceptionsFailClosed() {
        assertNull(SensitiveWidgetReadPolicy.read(true,
            { throw SecurityException() }, { true }, { "title" },
        ))
        assertNull(SensitiveWidgetReadPolicy.read(true,
            { true }, { throw SecurityException() }, { "title" },
        ))
    }

    @Test fun sourceSecurityExceptionAndUnavailablePlatformFailClosed() {
        assertNull(SensitiveWidgetReadPolicy.read<String>(true,
            { true }, { true }, { throw SecurityException() },
        ))
        assertNull(SensitiveWidgetReadPolicy.read<String>(true,
            { true }, { true }, { throw IllegalStateException() },
        ))
    }

    @Test fun lockDuringReadDiscardsResult() {
        var unlocked = true
        assertNull(SensitiveWidgetReadPolicy.read(true,
            { unlocked }, { true }, { unlocked = false; "title" },
        ))
    }

    @Test fun revocationDuringReadDiscardsResult() {
        var granted = true
        assertNull(SensitiveWidgetReadPolicy.read(true,
            { true }, { granted }, { granted = false; "title" },
        ))
    }

    @Test fun enabledUnlockedGrantedReadReturnsCurrentData() {
        var reads = 0
        assertEquals("title", SensitiveWidgetReadPolicy.read(true,
            { true }, { true }, { reads++; "title" },
        ))
        assertEquals(1, reads)
    }
}
