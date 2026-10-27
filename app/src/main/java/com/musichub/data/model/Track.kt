package com.musichub.data.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
enum class Source(val prefix: String, val short: String, val label: String) {
    YANDEX("ya", "Я", "Яндекс Музыка"),
    VK("vk", "VK", "ВКонтакте"),
    YOUTUBE("yt", "YT", "YouTube"),
}

/** Ссылка на тот же трек в другом сервисе (находится при объединённом поиске). */
@Serializable
data class TrackRef(val source: Source, val sourceId: String)

@Serializable
data class Track(
    val source: Source,
    /** Яндекс: "trackId:albumId", ВК: "ownerId_audioId[_accessKey]", YouTube: videoId */
    val sourceId: String,
    val title: String,
    val artists: String,
    val album: String? = null,
    val durationMs: Long = 0,
    val coverUrl: String? = null,
    val available: Boolean = true,
    val alternates: List<TrackRef> = emptyList(),
    /** Трек подмешан в Мою волну из YouTube. */
    val mixed: Boolean = false,
) {
    val key: String get() = "${source.prefix}:$sourceId"

    /** Id трека в Яндексе без альбома — так их хранит список лайков. */
    val yandexId: String? get() = if (source == Source.YANDEX) sourceId.substringBefore(':') else null

    fun toJson(): String = TrackJson.encodeToString(serializer(), this)

    companion object {
        fun fromJson(json: String): Track = TrackJson.decodeFromString(serializer(), json)
    }
}

val TrackJson = Json { ignoreUnknownKeys = true }
