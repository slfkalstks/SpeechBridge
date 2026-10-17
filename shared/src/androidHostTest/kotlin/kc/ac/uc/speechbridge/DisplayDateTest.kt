package kc.ac.uc.speechbridge

import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class DisplayDateTest {
    @Test fun localDateCrossesUtcMidnightAndInvalidInputIsSafe() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            assertEquals("2026.10.18", displayDate("2026-10-17T15:30:00Z"))
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals("2026.10.17", displayDate("2026-10-18T01:30:00Z"))
            assertEquals("invalid", displayDate("invalid"))
        } finally { TimeZone.setDefault(original) }
    }
}
