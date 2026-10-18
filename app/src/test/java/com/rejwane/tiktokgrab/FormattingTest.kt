package com.rejwane.tiktokgrab

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The formatting the whole UI leans on, and the name handling that decides where a file lands and who
 * it belongs to. All of it is pure Kotlin, so it runs on the JVM with no device and no emulator —
 * which is exactly why it is the first thing worth a test: these are the regressions nobody notices
 * until a build is already on the phone.
 */
class FormattingTest {

    @Test
    fun sizesReadTheWayTheUiShowsThem() {
        assertEquals("0 B", GridAdapter.mb(0))
        assertEquals("999 B", GridAdapter.mb(999))
        assertEquals("1 KB", GridAdapter.mb(1_000))
        assertEquals("10 KB", GridAdapter.mb(10_000))
        assertEquals("1.0 MB", GridAdapter.mb(1_000_000))
        assertEquals("2.5 MB", GridAdapter.mb(2_500_000))
    }

    @Test
    fun countsUseTikTokShorthand() {
        assertEquals("999", GridAdapter.short(999))
        assertEquals("1.0k", GridAdapter.short(1_000))
        assertEquals("1.5k", GridAdapter.short(1_500))
        assertEquals("1.0M", GridAdapter.short(1_000_000))
        assertEquals("2.3M", GridAdapter.short(2_300_000))
    }

    @Test
    fun clockIsMinutesAndPaddedSeconds() {
        assertEquals("0:00", GridAdapter.clock(0))
        assertEquals("1:05", GridAdapter.clock(65))
        assertEquals("59:59", GridAdapter.clock(3599))
    }

    @Test
    fun agesStopAtTheRightUnit() {
        val now = System.currentTimeMillis() / 1000
        val day = 86_400L
        assertEquals("", GridAdapter.ago(0))
        assertEquals("5h ago", GridAdapter.ago(now - 5 * 3_600))
        assertEquals("3d ago", GridAdapter.ago(now - 3 * day))
        assertEquals("2w ago", GridAdapter.ago(now - 60 * day))
        assertEquals("1y ago", GridAdapter.ago(now - 400 * day))
    }

    @Test
    fun fileNamesLoseOnlyWhatTheFileSystemForbids() {
        assertEquals("my clip", LocalVideos.sanitize("my clip"))
        assertEquals("abc", LocalVideos.sanitize("a/b:c"))
        assertEquals("Unassigned", LocalVideos.sanitize(""))
        assertEquals("Unassigned", LocalVideos.sanitize("   "))
        assertEquals("Unassigned", LocalVideos.sanitize("..."))
    }

    @Test
    fun anyInputLandsOnTheAuthor() {
        assertEquals("rejwan", TikTokApi.username("@rejwan"))
        assertEquals("rejwan", TikTokApi.username("rejwan"))
        assertEquals("rejwan", TikTokApi.username("https://www.tiktok.com/@rejwan"))
        assertEquals("rejwan", TikTokApi.username("https://www.tiktok.com/@rejwan/video/7312345678901234567"))
        assertEquals("rejwan", TikTokApi.username("look at this https://www.tiktok.com/@rejwan/video/73 …"))
        assertEquals("rejwan", TikTokApi.username("  @rejwan  "))
    }
}
