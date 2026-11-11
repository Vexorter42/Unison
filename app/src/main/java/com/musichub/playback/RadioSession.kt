package com.musichub.playback

import com.musichub.data.model.Track
import java.util.concurrent.atomic.AtomicLong

internal class RadioSession {
    private val generation = AtomicLong()
    fun next(): Long = generation.incrementAndGet()
    fun current(): Long = generation.get()
    fun isCurrent(token: Long): Boolean = token == generation.get()
}

internal fun uniqueRadioTracks(fresh: List<Track>, queued: Collection<String>, recent: Collection<String>): List<Track> {
    val known = HashSet<String>(queued.size + recent.size + fresh.size).apply { addAll(queued); addAll(recent) }
    return fresh.filter { known.add(it.key) }
}
