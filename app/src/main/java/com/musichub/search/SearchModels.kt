package com.musichub.search

import com.musichub.data.model.Source

enum class SearchCategory(val label: String) {
    TRACKS("Треки"), ARTISTS("Авторы"), ALBUMS("Альбомы"), PLAYLISTS("Плейлисты"),
}

enum class CollectionKind { ALBUM, PLAYLIST }

data class ArtistSearchResult(
    val source: Source,
    val id: String,
    val name: String,
    val subtitle: String = "",
    val coverUrl: String? = null,
    val url: String? = null,
)

data class CollectionSearchResult(
    val source: Source,
    val kind: CollectionKind,
    val id: String,
    val title: String,
    val subtitle: String = "",
    val coverUrl: String? = null,
    val trackCount: Long? = null,
    val url: String? = null,
)

data class SearchResult(
    val tracks: List<com.musichub.data.model.Track> = emptyList(),
    val artists: List<ArtistSearchResult> = emptyList(),
    val albums: List<CollectionSearchResult> = emptyList(),
    val playlists: List<CollectionSearchResult> = emptyList(),
    val errors: List<String> = emptyList(),
)
