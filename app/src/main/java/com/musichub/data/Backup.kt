package com.musichub.data

import android.content.Context
import android.net.Uri
import com.musichub.AppGraph
import com.musichub.data.db.FavoriteTrack
import com.musichub.data.db.LocalPlaylist
import com.musichub.data.db.PlayEvent
import com.musichub.data.db.PlaylistTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Настройки без входов в аккаунты: токены и cookie в файл копии не попадают. */
@Serializable
data class BackupSettings(
    val searchYoutube: Boolean,
    val searchVk: Boolean,
    val fallbackToOthers: Boolean,
    val mixYoutubeInWave: Boolean,
    val mixEvery: Int,
    val quality: Quality,
    val fadeSeconds: Int,
    val autoCacheMb: Int,
    val waveMood: String,
    val waveDiversity: String,
    val ytProxyEnabled: Boolean,
    val ytProxyType: String,
    val ytProxyHost: String,
    val ytProxyPort: Int,
    val liquidGlass: Boolean,
    val palette: Int,
    val coverTheme: Boolean,
    val waveSource: WaveSource = WaveSource.YANDEX,
    val waveLanguage: String = "any",
    val likeTarget: LikeTarget = LikeTarget.UNISON,
)

@Serializable
data class BackupPlaylist(val name: String, val createdAt: Long, val tracks: List<PlaylistTrack>)

@Serializable
data class BackupFile(
    val app: String = "Unison",
    val version: Int = 1,
    val createdAt: Long,
    val settings: BackupSettings,
    val playlists: List<BackupPlaylist>,
    val favorites: List<FavoriteTrack>,
    val stats: List<PlayEvent>,
)

data class RestoreResult(val playlists: Int, val favorites: Int, val stats: Int)

/** Резервная копия в один JSON-файл: свои плейлисты, «Любимое», статистика, настройки. */
class Backup(private val context: Context, private val graph: AppGraph) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun export(uri: Uri): Int = withContext(Dispatchers.IO) {
        val bytes = exportBytes()
        context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(bytes) } ?: error("Не удалось открыть файл")
        graph.db.playlists().allPlaylists().size + graph.db.favorites().all().size
    }

    suspend fun exportBytes(): ByteArray = withContext(Dispatchers.IO) {
        val s = graph.settings.current
        val db = graph.db
        val playlists = db.playlists().allPlaylists().map { p -> BackupPlaylist(p.name, p.createdAt, db.playlists().tracks(p.id)) }
        val file = BackupFile(
            createdAt = System.currentTimeMillis(),
            settings = BackupSettings(
                s.searchYoutube, s.searchVk, s.fallbackToOthers, s.mixYoutubeInWave, s.mixEvery, s.quality, s.fadeSeconds,
                s.autoCacheMb, s.waveMood, s.waveDiversity, s.ytProxyEnabled, s.ytProxyType, s.ytProxyHost, s.ytProxyPort,
                s.liquidGlass, s.palette, s.coverTheme, s.waveSource, s.waveLanguage, s.likeTarget,
            ),
            playlists = playlists,
            favorites = db.favorites().all(),
            stats = db.stats().all(),
        )
        json.encodeToString(BackupFile.serializer(), file).toByteArray()
    }

    /** Восстановление добавляет к текущему, ничего не удаляя: плейлисты — новыми, дубли лайков и статистики пропускаются. */
    suspend fun restore(uri: Uri): RestoreResult = withContext(Dispatchers.IO) {
        val text = context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } ?: error("Не удалось открыть файл")
        restoreBytes(text.encodeToByteArray())
    }

    suspend fun restoreBytes(bytes: ByteArray): RestoreResult = withContext(Dispatchers.IO) {
        val file = runCatching { json.decodeFromString(BackupFile.serializer(), bytes.decodeToString()) }.getOrNull()
            ?.takeIf { it.app == "Unison" } ?: error("Это не резервная копия Unison")
        val db = graph.db
        val knownPlaylists = db.playlists().allPlaylists().mapTo(HashSet()) { it.name to it.createdAt }
        file.playlists.forEach { p ->
            if (knownPlaylists.add(p.name to p.createdAt)) {
                val id = db.playlists().create(LocalPlaylist(name = p.name, createdAt = p.createdAt))
                db.playlists().addTracks(p.tracks.map { it.copy(playlistId = id) })
            }
        }
        db.favorites().insert(file.favorites)
        db.stats().insertAll(file.stats)
        val b = file.settings
        graph.settings.update {
            it.copy(
                searchYoutube = b.searchYoutube, searchVk = b.searchVk, fallbackToOthers = b.fallbackToOthers,
                mixYoutubeInWave = b.mixYoutubeInWave, mixEvery = b.mixEvery, quality = b.quality, fadeSeconds = b.fadeSeconds,
                autoCacheMb = b.autoCacheMb, waveMood = b.waveMood, waveDiversity = b.waveDiversity,
                ytProxyEnabled = b.ytProxyEnabled, ytProxyType = b.ytProxyType, ytProxyHost = b.ytProxyHost, ytProxyPort = b.ytProxyPort,
                liquidGlass = b.liquidGlass, palette = b.palette, coverTheme = b.coverTheme,
                waveSource = b.waveSource, waveLanguage = b.waveLanguage, likeTarget = b.likeTarget,
            )
        }
        RestoreResult(file.playlists.size, file.favorites.size, file.stats.size)
    }
}
