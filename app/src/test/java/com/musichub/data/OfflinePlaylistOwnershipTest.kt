package com.musichub.data

import org.junit.Assert.assertEquals
import org.junit.Test

class OfflinePlaylistOwnershipTest {
    @Test fun sharedFilesSurviveUntilLastGroupIsRemoved() {
        val first = OfflinePlaylist("first", "First", listOf("shared", "only", "independent"), listOf("shared", "only"))
        val second = OfflinePlaylist("second", "Second", listOf("shared"), emptyList())
        val (remaining, deleted) = OfflinePlaylistOwnership.remove(listOf(first, second), "first")
        assertEquals(setOf("only"), deleted)
        assertEquals(listOf("shared"), remaining.single().owned)
        assertEquals(setOf("shared"), OfflinePlaylistOwnership.remove(remaining, "second").second)
    }

    @Test fun removingUnknownGroupDoesNothing() {
        val item = OfflinePlaylist("one", "One", listOf("key"), listOf("key"))
        assertEquals(listOf(item) to emptySet<String>(), OfflinePlaylistOwnership.remove(listOf(item), "missing"))
    }
}
