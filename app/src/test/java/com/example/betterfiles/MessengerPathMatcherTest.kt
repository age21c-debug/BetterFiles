package com.example.betterfiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessengerPathMatcherTest {

    @Test
    fun whatsappPath_isDetected() {
        val path = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/IMG_20260217.jpg"

        assertTrue(MessengerPathMatcher.isMessengerPath(path))
        assertEquals("WhatsApp", MessengerPathMatcher.detectSourceName(path))
    }

    @Test
    fun orderedSegmentsFallback_detectsKakaoTalk() {
        val path = "/storage/emulated/0/Download/archive/2026/KakaoTalk/sample.pdf"

        assertTrue(MessengerPathMatcher.isMessengerPath(path))
        assertEquals("KakaoTalk", MessengerPathMatcher.detectSourceName(path))
    }

    @Test
    fun transientFolder_isExcluded() {
        val path = "/storage/emulated/0/Android/media/com.whatsapp/WhatsApp Images/cache/tmp.jpg"

        assertFalse(MessengerPathMatcher.isMessengerPath(path))
        assertEquals("Other", MessengerPathMatcher.detectSourceName(path))
    }

    @Test
    fun unknownPath_returnsOther() {
        val path = "/storage/emulated/0/Documents/Notes/meeting.txt"

        assertFalse(MessengerPathMatcher.isMessengerPath(path))
        assertEquals("Other", MessengerPathMatcher.detectSourceName(path))
    }

    @Test
    fun messengerPath_neverReturnsOtherSourceName() {
        val samplePaths = listOf(
            "/storage/emulated/0/Android/media/org.telegram.messenger/Telegram/Telegram Images/a.jpg",
            "/storage/emulated/0/Android/media/com.discord/files/attachments/b.png",
            "/storage/emulated/0/Download/Slack/thread.zip",
            "/storage/emulated/0/Pictures/Facebook/photo.webp"
        )

        for (path in samplePaths) {
            assertTrue(MessengerPathMatcher.isMessengerPath(path))
            assertNotEquals("Other", MessengerPathMatcher.detectSourceName(path))
        }
    }
}
