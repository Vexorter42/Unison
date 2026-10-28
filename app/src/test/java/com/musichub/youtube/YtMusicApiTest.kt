package com.musichub.youtube

import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Разбор настоящего ответа YouTube Music на публичном плейлисте (вход не нужен; нужен интернет). */
class YtMusicApiTest {

    private val api = YtMusicApi(OkHttpClient()) { null }

    @Test
    fun parsesPublicPlaylist() {
        val tracks = api.browseTracksBlocking("VLPL9LkJszkF_Z6bJ82689htd2wch-HVbzCO")
        assertTrue("ожидали больше 10 треков, получили ${tracks.size}", tracks.size > 10)
        val first = tracks.first()
        assertEquals(11, first.sourceId.length) // videoId
        assertTrue(first.title.isNotBlank())
        assertTrue("исполнитель не разобран: ${first.artists}", first.artists != "YouTube")
        assertTrue("длительность не разобрана", tracks.count { it.durationMs > 0 } > tracks.size / 2)
        assertTrue(first.coverUrl?.startsWith("https://") == true)
    }
}
