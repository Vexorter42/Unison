package com.musichub.data

import android.content.Context
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class OfflinePlaylist(val id: String, val title: String, val keys: List<String>, val owned: List<String>)

internal object OfflinePlaylistOwnership {
    fun remove(items: List<OfflinePlaylist>, id: String): Pair<List<OfflinePlaylist>, Set<String>> {
        val item = items.firstOrNull { it.id == id } ?: return items to emptySet()
        var remaining = items.filterNot { it.id == id }
        val removeFiles = mutableSetOf<String>()
        for (key in item.owned) {
            val owner = remaining.firstOrNull { key in it.keys }
            if (owner == null) removeFiles += key
            else remaining = remaining.map { if (it.id == owner.id) it.copy(owned = (it.owned + key).distinct()) else it }
        }
        return remaining to removeFiles
    }
}

/** Tracks that existed before a playlist download remain independent of that playlist. */
class OfflinePlaylists(context: Context) {
    private val prefs = context.getSharedPreferences("offline_playlists", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(OfflinePlaylist.serializer())
    private val mutable = MutableStateFlow(runCatching { json.decodeFromString(serializer, prefs.getString("items", "[]")!!) }.getOrDefault(emptyList()))
    val items = mutable.asStateFlow()

    fun register(id: String, title: String, tracks: List<Track>, alreadyPresent: Set<String>) {
        val eligible = tracks.filter { it.source != Source.LOCAL && it.source != Source.SOUNDCLOUD }.map { it.key }.distinct()
        if (eligible.isEmpty()) return
        val old = mutable.value.firstOrNull { it.id == id }
        val existingOwned = mutable.value.flatMap { it.owned }.toSet()
        val keys = (old?.keys.orEmpty() + eligible).distinct()
        val owned = (old?.owned.orEmpty() + eligible.filter { it !in alreadyPresent && it !in existingOwned }).distinct()
        write(mutable.value.filterNot { it.id == id } + OfflinePlaylist(id, title, keys, owned))
    }

    /** Removes the group and transfers ownership of shared downloads to another group. */
    fun removalPlan(id: String): Pair<List<OfflinePlaylist>, Set<String>> = OfflinePlaylistOwnership.remove(mutable.value, id)

    fun applyRemoval(remaining: List<OfflinePlaylist>) = write(remaining)

    fun clear() = write(emptyList())

    fun keepIndependent(keys: Set<String>) {
        if (keys.isEmpty()) return
        write(mutable.value.map { item -> item.copy(owned = item.owned.filterNot { it in keys }) })
    }

    private fun write(value: List<OfflinePlaylist>) {
        prefs.edit().putString("items", json.encodeToString(serializer, value)).apply()
        mutable.value = value
    }
}
