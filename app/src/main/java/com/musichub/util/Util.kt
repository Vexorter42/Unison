package com.musichub.util

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.io.IOException

fun JsonElement?.obj(): JsonObject? = this as? JsonObject

fun JsonElement?.arr(): List<JsonElement> = (this as? JsonArray) ?: emptyList()

private fun JsonObject.prim(key: String): JsonPrimitive? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }

fun JsonObject.str(key: String): String? = prim(key)?.content

fun JsonObject.long(key: String): Long? = prim(key)?.content?.toDoubleOrNull()?.toLong()

fun JsonObject.bool(key: String): Boolean? = prim(key)?.booleanOrNull

fun formatTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/** Русское склонение по числу: plural(5, "трек", "трека", "треков") → "5 треков". */
fun plural(n: Int, one: String, few: String, many: String): String {
    val mod100 = n % 100
    val mod10 = n % 10
    val word = when {
        mod100 in 11..14 -> many
        mod10 == 1 -> one
        mod10 in 2..4 -> few
        else -> many
    }
    return "$n $word"
}

fun tracksCount(n: Int) = plural(n, "трек", "трека", "треков")

/**
 * Обложки YouTube в высоком качестве: поиск отдаёт миниатюры 60–120 px.
 * lh3.googleusercontent…=w120-h120… → 544×544, i.ytimg.com/vi/<id>/default.jpg → hqdefault.
 */
fun hiResCover(url: String?): String? {
    if (url == null) return null
    if ("googleusercontent.com" in url || "ggpht.com" in url) {
        return url.replace(Regex("""=w\d+-h\d+"""), "=w544-h544").replace(Regex("""=s\d+"""), "=s544")
    }
    Regex("""i\.ytimg\.com/vi(?:_webp)?/([^/]+)/""").find(url)?.let { m ->
        if ("hqdefault" !in url && "maxresdefault" !in url && "sddefault" !in url) return "https://i.ytimg.com/vi/${m.groupValues[1]}/hqdefault.jpg"
    }
    return url
}

fun Throwable.asIo(): IOException = this as? IOException ?: IOException(message, this)
