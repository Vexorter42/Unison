package com.musichub.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/** Одно прослушивание — основа статистики. */
@Entity(tableName = "play_events", indices = [Index("startedAt"), Index("trackKey")])
data class PlayEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trackKey: String,
    val title: String,
    val artists: String,
    val source: String,
    val startedAt: Long,
    val playedMs: Long,
    val durationMs: Long,
    val skipped: Boolean,
    val fromWave: Boolean,
    val trackJson: String,
)

@Entity(tableName = "playlists")
data class LocalPlaylist(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "playlist_tracks", primaryKeys = ["playlistId", "trackKey"], indices = [Index("playlistId")])
data class PlaylistTrack(
    val playlistId: Long,
    val trackKey: String,
    val position: Int,
    val trackJson: String,
)

@Entity(tableName = "downloads")
data class DownloadedTrack(
    @PrimaryKey val trackKey: String,
    val trackJson: String,
    val downloadedAt: Long,
    val bytes: Long,
)

data class LocalPlaylistInfo(val id: Long, val name: String, val count: Int)
data class Totals(val ms: Long, val plays: Int, val skips: Int, val uniqueTracks: Int)
data class TopTrackRow(val trackKey: String, val title: String, val artists: String, val plays: Int, val ms: Long)
data class TopArtistRow(val artists: String, val plays: Int, val ms: Long)
data class SourceRow(val source: String, val ms: Long)
data class HourRow(val hour: Int, val ms: Long)

@Dao
interface StatsDao {
    /** Запись обновляется по ходу прослушивания (тот же id), поэтому REPLACE. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(e: PlayEvent)

    @Query(
        "SELECT COALESCE(SUM(playedMs), 0) AS ms, COUNT(*) AS plays, COALESCE(SUM(skipped), 0) AS skips, " +
            "COUNT(DISTINCT trackKey) AS uniqueTracks FROM play_events WHERE startedAt >= :since"
    )
    suspend fun totals(since: Long): Totals

    @Query(
        "SELECT trackKey, title, artists, COUNT(*) AS plays, SUM(playedMs) AS ms FROM play_events " +
            "WHERE startedAt >= :since AND skipped = 0 GROUP BY trackKey ORDER BY plays DESC, ms DESC LIMIT :limit"
    )
    suspend fun topTracks(since: Long, limit: Int): List<TopTrackRow>

    @Query(
        "SELECT artists, COUNT(*) AS plays, SUM(playedMs) AS ms FROM play_events " +
            "WHERE startedAt >= :since AND skipped = 0 GROUP BY artists ORDER BY ms DESC LIMIT :limit"
    )
    suspend fun topArtists(since: Long, limit: Int): List<TopArtistRow>

    @Query("SELECT source, SUM(playedMs) AS ms FROM play_events WHERE startedAt >= :since GROUP BY source ORDER BY ms DESC")
    suspend fun bySource(since: Long): List<SourceRow>

    @Query(
        "SELECT CAST(strftime('%H', startedAt / 1000, 'unixepoch', 'localtime') AS INTEGER) AS hour, " +
            "SUM(playedMs) AS ms FROM play_events WHERE startedAt >= :since GROUP BY hour"
    )
    suspend fun byHour(since: Long): List<HourRow>

    @Query(
        "SELECT * FROM play_events WHERE id IN (SELECT MAX(id) FROM play_events GROUP BY trackKey) " +
            "ORDER BY startedAt DESC LIMIT :limit"
    )
    fun recent(limit: Int): Flow<List<PlayEvent>>
}

@Dao
interface PlaylistDao {
    @Query(
        "SELECT p.id AS id, p.name AS name, (SELECT COUNT(*) FROM playlist_tracks t WHERE t.playlistId = p.id) AS count " +
            "FROM playlists p ORDER BY p.createdAt DESC"
    )
    fun observeAll(): Flow<List<LocalPlaylistInfo>>

    @Insert
    suspend fun create(p: LocalPlaylist): Long

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :id")
    suspend fun deleteTracks(id: Long)

    @Query("SELECT * FROM playlist_tracks WHERE playlistId = :id ORDER BY position")
    suspend fun tracks(id: Long): List<PlaylistTrack>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_tracks WHERE playlistId = :id")
    suspend fun maxPosition(id: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addTracks(tracks: List<PlaylistTrack>)

    @Query("DELETE FROM playlist_tracks WHERE playlistId = :id AND trackKey = :key")
    suspend fun removeTrack(id: Long, key: String)
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloadedAt DESC")
    fun observeAll(): Flow<List<DownloadedTrack>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(d: DownloadedTrack)

    @Query("DELETE FROM downloads WHERE trackKey = :key")
    suspend fun delete(key: String)
}

@Database(
    entities = [PlayEvent::class, LocalPlaylist::class, PlaylistTrack::class, DownloadedTrack::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun stats(): StatsDao
    abstract fun playlists(): PlaylistDao
    abstract fun downloads(): DownloadDao
}
