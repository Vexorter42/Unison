package com.musichub.search

import com.musichub.data.model.Track
import java.text.Normalizer

/** Lexical relevance shared by local and remote results; stable ties retain service ordering. */
object SearchRelevance {
    private val wordBreak = Regex("[^\\p{L}\\p{N}]+")
    private val transliteration = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e",
        'ж' to "zh", 'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m",
        'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u",
        'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch", 'ш' to "sh", 'щ' to "shch",
        'ы' to "y", 'э' to "e", 'ю' to "yu", 'я' to "ya", 'ь' to "", 'ъ' to "",
    )

    private fun normalized(value: String): String = Normalizer.normalize(value.lowercase().replace('ё', 'е'), Normalizer.Form.NFD)
        .replace("и\u0306", "й").replace(Regex("\\p{M}+"), "").replace(wordBreak, " ").trim()

    private fun romanize(value: String): String = buildString { value.forEach { append(transliteration[it] ?: it.toString()) } }

    private fun close(a: String, b: String): Boolean {
        if (a == b) return true
        if (b.startsWith(a)) return true
        if (a.length < 5 || kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when { a.length > b.length -> i++; b.length > a.length -> j++; else -> { i++; j++ } }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }

    fun score(query: String, text: String): Double {
        val q = normalized(query)
        val t = normalized(text)
        if (q.isBlank() || t.isBlank()) return 0.0
        val words = q.split(' ').filter(String::isNotBlank)
        val queryWords = words.filterNot { it in setOf("feat", "ft", "featuring", "and", "the", "и", "x") }.ifEmpty { words }
        val targetWords = t.split(' ').filter(String::isNotBlank)
        val matched = queryWords.count { word ->
            val roman = romanize(word)
            targetWords.any { candidate -> close(word, candidate) || close(roman, romanize(candidate)) }
        }
        val coverage = matched.toDouble() / queryWords.size
        if (coverage < if (queryWords.size >= 3) 0.66 else 1.0) return 0.0
        val phraseBonus = if (t == q) 1.0 else if (t.startsWith(q)) 0.5 else if (q.length >= 3 && t.contains(q)) 0.25 else 0.0
        return coverage + phraseBonus
    }

    fun <T> rank(query: String, items: List<T>, text: (T) -> String): List<T> {
        if (normalized(query).isBlank()) return items
        return items.mapIndexed { index, item -> Triple(item, score(query, text(item)), index) }
            .filter { it.second > 0 }
            .sortedWith(compareByDescending<Triple<T, Double, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
    }

    fun trackText(track: Track): String = "${track.title} ${track.artists}"

    fun rankTracks(query: String, tracks: List<Track>): List<Track> {
        val artistTerms = normalized(query).split(' ').filter { it.length >= 3 && it !in setOf("and", "feat", "featuring", "the") }
        return tracks.mapIndexed { index, track ->
            val base = score(query, trackText(track))
            val artistMatch = artistTerms.any { score(it, track.artists) > 0 }
            Triple(track, if (base > 0 && artistMatch) base + 0.6 else base, index)
        }.filter { it.second > 0 }.sortedWith(compareByDescending<Triple<Track, Double, Int>> { it.second }.thenBy { it.third }).map { it.first }
    }
}

/** Preserve authoritative individual names; when only a display string exists, retain the full name as a choice too. */
fun Track.artistChoices(): List<String> {
    val names = artistNames.map(String::trim).filter(String::isNotEmpty).distinct()
    if (names.isNotEmpty()) return names
    val original = artists.trim()
    if (original.isEmpty()) return emptyList()
    val parts = original.split(Regex("""\s*(?:,|;|\s+(?:feat\.?|ft\.?|featuring|&|x)\s+)\s*""", RegexOption.IGNORE_CASE))
        .map(String::trim).filter(String::isNotEmpty).distinct()
    return if (parts.size > 1) listOf(original) + parts else listOf(original)
}
