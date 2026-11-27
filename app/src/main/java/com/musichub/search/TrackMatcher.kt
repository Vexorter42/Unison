package com.musichub.search

import com.musichub.data.model.Source
import com.musichub.data.model.Track
import kotlin.math.abs

/** Сопоставление одного и того же трека между сервисами по названию, исполнителю и длительности. */
object TrackMatcher {

    private val noiseInBrackets = Regex(
        """[(\[][^)\]]*(official|video|audio|lyric|remaster|clip|клип|премьера|visualizer|hd|hq|4k|mv)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val featTail = Regex("""\s(feat|ft)\.?\s.*$""", RegexOption.IGNORE_CASE)
    private val nonWord = Regex("""[^\p{L}\p{N}]+""")

    fun normalize(s: String): String = s.lowercase()
        .replace('ё', 'е')
        .replace(noiseInBrackets, " ")
        .replace(featTail, " ")
        .replace(nonWord, " ")
        .trim()

    private fun tokens(s: String): Set<String> = normalize(s).split(' ').filter { it.isNotBlank() }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

    private fun containment(small: Set<String>, big: Set<String>): Double =
        if (small.isEmpty()) 0.0 else small.count { it in big }.toDouble() / small.size

    fun score(a: Track, b: Track): Double {
        val titleA = tokens(a.title)
        val titleB = tokens(b.title)
        val artistA = tokens(a.artists)
        val artistB = tokens(b.artists)
        // На YouTube часто «Исполнитель - Название» в заголовке, поэтому сравниваем и с полной строкой
        val fullA = artistA + titleA
        val fullB = artistB + titleB
        val titleScore = maxOf(jaccard(titleA, titleB), minOf(containment(titleA, fullB), containment(titleB, fullA)))
        val artistScore = maxOf(jaccard(artistA, artistB), containment(artistA, fullB), containment(artistB, fullA))
        val durationScore = if (a.durationMs <= 0 || b.durationMs <= 0) 0.5 else {
            val diffSec = abs(a.durationMs - b.durationMs) / 1000.0
            when {
                diffSec <= 3 -> 1.0
                diffSec <= 10 -> 0.6
                diffSec <= 30 -> 0.2
                else -> 0.0
            }
        }
        return titleScore * 0.5 + artistScore * 0.3 + durationScore * 0.2
    }

    fun best(target: Track, candidates: List<Track>, threshold: Double = 0.7): Track? =
        candidates.map { it to score(target, it) }
            .filter { it.second >= threshold && canMerge(target, it.first) }
            .maxByOrNull { it.second }
            ?.first

    /** Stronger than fuzzy matching: unrelated recordings must never become alternate sources. */
    fun canMerge(first: Track, second: Track): Boolean {
        val a = splitYoutube(first)
        val b = splitYoutube(second)
        val aTitle = tokens(a.title)
        val bTitle = tokens(b.title)
        val aArtist = tokens(a.artists) - setOf("the")
        val bArtist = tokens(b.artists) - setOf("the")
        if (aTitle.isEmpty() || bTitle.isEmpty() || aArtist.isEmpty() || bArtist.isEmpty()) return false
        if (jaccard(aTitle, bTitle) < 0.65 || minOf(containment(aTitle, bTitle), containment(bTitle, aTitle)) < 0.8) return false
        if (jaccard(aArtist, bArtist) < 0.5) return false
        if (a.durationMs > 0 && b.durationMs > 0 && abs(a.durationMs - b.durationMs) > 12_000) return false
        val versions = setOf("live", "remix", "acoustic", "slowed", "sped", "instrumental", "karaoke", "cover", "концерт", "ремикс")
        fun versionsIn(text: String) = text.lowercase().replace(nonWord, " ").split(' ').toSet().intersect(versions)
        if (versionsIn(first.title) != versionsIn(second.title)) return false
        return true
    }

    /** «Artist - Title (Official Video)» с YouTube → отдельные исполнитель и название. */
    fun splitYoutube(t: Track): Track {
        if ((t.source != Source.YOUTUBE && t.source != Source.SOUNDCLOUD) || " - " !in t.title) return t
        val artist = t.title.substringBefore(" - ").trim()
        val title = t.title.substringAfter(" - ").replace(noiseInBrackets, "").trim()
        return t.copy(artists = artist, title = title)
    }
}
