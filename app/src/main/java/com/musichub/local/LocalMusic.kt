package com.musichub.local

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Музыка, которая лежит на самом телефоне (MediaStore): MP3, FLAC, M4A и т.д. */
class LocalMusic(private val context: Context) {

    val permission: String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    @Volatile private var cache: List<Track>? = null

    suspend fun tracks(refresh: Boolean = false): List<Track> = withContext(Dispatchers.IO) {
        if (!hasPermission()) return@withContext emptyList()
        if (!refresh) cache?.let { return@withContext it }
        val result = mutableListOf<Track>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= 30000",
            null,
            "${MediaStore.Audio.Media.DATE_ADDED} DESC",
        )?.use { c ->
            val id = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val title = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artist = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val album = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumId = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val duration = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            while (c.moveToNext()) {
                val artistName = c.getString(artist)?.takeUnless { it == MediaStore.UNKNOWN_STRING || it.isBlank() }
                result += Track(
                    source = Source.LOCAL,
                    sourceId = c.getLong(id).toString(),
                    title = c.getString(title) ?: "Без названия",
                    artists = artistName ?: "Неизвестный исполнитель",
                    album = c.getString(album),
                    durationMs = c.getLong(duration),
                    coverUrl = ContentUris.withAppendedId(ALBUM_ART, c.getLong(albumId)).toString(),
                )
            }
        }
        cache = result
        result
    }

    /** Поиск по своим файлам — для единого поиска. */
    suspend fun search(query: String): List<Track> {
        val words = query.lowercase().split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()
        return tracks().filter { t ->
            val hay = "${t.artists} ${t.title} ${t.album.orEmpty()}".lowercase()
            words.all { it in hay }
        }.take(30)
    }

    companion object {
        private val ALBUM_ART: Uri = Uri.parse("content://media/external/audio/albumart")

        fun contentUri(sourceId: String): Uri =
            ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, sourceId.toLong())
    }
}
