package com.musichub.playback

import com.musichub.data.WaveSource
import com.musichub.data.model.Track
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
internal data class PlaybackSnapshot(
    val tracks: List<Track>, val index: Int, val positionMs: Long,
    val waveMode: Boolean = false, val provider: WaveSource? = null,
    val waveSource: WaveSource? = null, val station: String? = null,
    val title: String? = null, val batchId: String? = null,
    val shuffle: Boolean = false, val repeatMode: Int = 0,
)

internal val snapshotJson = Json { ignoreUnknownKeys = true }
