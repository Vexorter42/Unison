package com.musichub.search

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** История поиска: последние запросы, новые сверху, без повторов. */
class SearchHistory(context: Context) {

    private val prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    private val serializer = ListSerializer(String.serializer())
    private val _items = MutableStateFlow(load())
    val items: StateFlow<List<String>> = _items.asStateFlow()

    fun add(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        save(listOf(q) + _items.value.filterNot { it.equals(q, ignoreCase = true) }.take(MAX - 1))
    }

    fun remove(query: String) = save(_items.value - query)

    fun clear() = save(emptyList())

    private fun load(): List<String> =
        prefs.getString(KEY, null)?.let { runCatching { Json.decodeFromString(serializer, it) }.getOrNull() } ?: emptyList()

    private fun save(list: List<String>) {
        _items.value = list
        prefs.edit { putString(KEY, Json.encodeToString(serializer, list)) }
    }

    private companion object {
        const val KEY = "items"
        const val MAX = 20
    }
}
