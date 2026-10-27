package com.musichub.data

import com.musichub.data.db.AppDatabase
import com.musichub.data.db.LocalPlaylist
import com.musichub.data.db.LocalPlaylistInfo
import com.musichub.data.db.PlaylistTrack
import com.musichub.data.model.Track
import kotlinx.coroutines.flow.Flow

/** Свои плейлисты: могут содержать треки из любых сервисов вперемешку. */
class LibraryRepository(private val db: AppDatabase) {

    val playlists: Flow<List<LocalPlaylistInfo>> = db.playlists().observeAll()

    suspend fun create(name: String): Long = db.playlists().create(LocalPlaylist(name = name))

    suspend fun delete(id: Long) {
        db.playlists().deleteTracks(id)
        db.playlists().delete(id)
    }

    suspend fun add(playlistId: Long, tracks: List<Track>): Int {
        val start = db.playlists().maxPosition(playlistId) + 1
        val rows = tracks.distinctBy { it.key }.mapIndexed { i, t -> PlaylistTrack(playlistId, t.key, start + i, t.toJson()) }
        db.playlists().addTracks(rows)
        return rows.size
    }

    suspend fun tracks(playlistId: Long): List<Track> =
        db.playlists().tracks(playlistId).mapNotNull { runCatching { Track.fromJson(it.trackJson) }.getOrNull() }

    suspend fun remove(playlistId: Long, trackKey: String) = db.playlists().removeTrack(playlistId, trackKey)
}
