package com.musichub.playback

import com.musichub.data.WaveSource
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSnapshotTest {
    @Test fun roundTripPreservesQueuePositionAndRadio() {
        val tracks = listOf(Track(Source.YANDEX, "1:2", "First", "Artist"), Track(Source.YOUTUBE, "abc", "Second", "Artist"))
        val saved = PlaybackSnapshot(tracks, 1, 12345, true, WaveSource.YANDEX, WaveSource.YANDEX, "user:onyourwave", "Моя волна", "batch", false, 0)
        val restored = snapshotJson.decodeFromString(PlaybackSnapshot.serializer(), snapshotJson.encodeToString(PlaybackSnapshot.serializer(), saved))
        assertEquals(saved, restored)
    }

    @Test fun classifiesCommonNetworkErrors() {
        assertEquals("Не удалось подключиться. Проверьте интернет и настройки сети.", playbackProblem("UnknownHostException"))
        assertEquals("Сервис долго не отвечает. Проверьте сеть и попробуйте ещё раз.", playbackProblem("SocketTimeoutException"))
    }
}
