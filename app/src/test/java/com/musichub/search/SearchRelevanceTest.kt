package com.musichub.search

import com.musichub.data.model.Source
import com.musichub.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchRelevanceTest {
    @Test fun ranksExactResultsAndDropsUnrelatedOnes() {
        val tracks = listOf(
            Track(Source.YOUTUBE, "a", "Random song", "Different"),
            Track(Source.YANDEX, "b", "Пачка сигарет", "Кино, Виктор Цой"),
            Track(Source.YOUTUBE, "c", "Виктор Цой - Пачка сигарет", "Кино"),
        )
        val result = SearchRelevance.rank("цой пачка", tracks, SearchRelevance::trackText)
        assertEquals(listOf("c", "b"), result.map { it.sourceId })
        assertTrue(SearchRelevance.score("tsoy", "Виктор Цой") > 0)
        assertTrue(SearchRelevance.score("пачк", "Пачка сигарет") > 0)
        assertTrue(SearchRelevance.score("Alice feat Bob", "Alice, Bob") > 0)
    }

    @Test fun requiresTitleArtistAndCompatibleVersionBeforeMerging() {
        val original = Track(Source.YANDEX, "a", "Creep", "Radiohead", durationMs = 238000)
        val youtube = Track(Source.YOUTUBE, "b", "Radiohead - Creep (Official Video)", "Radiohead - Topic", durationMs = 240000)
        assertTrue(TrackMatcher.canMerge(original, youtube))
        assertFalse(TrackMatcher.canMerge(original, youtube.copy(artists = "Another", title = "Another - Creep")))
        assertFalse(TrackMatcher.canMerge(original, youtube.copy(title = "Radiohead - Creep (Official Live Video)")))
        assertFalse(TrackMatcher.canMerge(original, youtube.copy(durationMs = 310000)))
        val a = Track(Source.YANDEX, "c", "Intro", "The Weeknd", durationMs = 120000)
        val b = Track(Source.YOUTUBE, "d", "Intro", "The Beatles", durationMs = 120000)
        assertFalse(TrackMatcher.canMerge(a, b))
        assertEquals(null, TrackMatcher.best(a, listOf(b), threshold = 0.5))
    }

    @Test fun originalArtistRanksAboveCoverWithFullQueryInTitle() {
        val cover = Track(Source.YOUTUBE, "cover", "Кино группа крови (кавер)", "Другой автор")
        val original = Track(Source.YANDEX, "original", "Группа крови", "Кино")
        assertEquals(listOf("original", "cover"), SearchRelevance.rankTracks("кино группа крови", listOf(cover, original)).map { it.sourceId })
    }

    @Test fun offersIndividualAuthorsWithoutSplittingKnownGroupNames() {
        val authoritative = Track(Source.YANDEX, "a", "Song", "Earth, Wind & Fire, Other", artistNames = listOf("Earth, Wind & Fire", "Other"))
        assertEquals(listOf("Earth, Wind & Fire", "Other"), authoritative.artistChoices())
        val displayOnly = Track(Source.VK, "b", "Song", "Alice, Bob")
        assertEquals(listOf("Alice, Bob", "Alice", "Bob"), displayOnly.artistChoices())
    }
}
