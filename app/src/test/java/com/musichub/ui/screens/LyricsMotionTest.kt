package com.musichub.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsMotionTest {
    @Test
    fun activeLineSettlesAboveViewportCenter() {
        assertEquals(120, lyricScrollDistance(itemOffset = 400, viewportHeight = 1000))
        assertEquals(-80, lyricScrollDistance(itemOffset = 200, viewportHeight = 1000))
    }
}
