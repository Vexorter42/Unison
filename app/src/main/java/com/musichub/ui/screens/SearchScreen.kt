package com.musichub.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.musichub.App
import com.musichub.data.model.Source
import com.musichub.search.SearchResult
import com.musichub.search.SearchCategory
import com.musichub.ui.GlassChip
import com.musichub.ui.TrackRow
import com.musichub.ui.liquid.LocalBottomInset
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    data class Ui(val loading: Boolean = false, val result: SearchResult? = null)

    val query = MutableStateFlow("")
    val category = MutableStateFlow(SearchCategory.TRACKS)
    val ui = MutableStateFlow(Ui())

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            combine(query, category) { q, c -> q.trim() to c }
                .debounce(450).distinctUntilChanged().collectLatest { (q, c) ->
                if (q.length < 2) {
                    ui.value = Ui()
                    return@collectLatest
                }
                ui.value = ui.value.copy(loading = true)
                ui.value = Ui(loading = false, result = App.graph.search.search(q, c))
            }
        }
    }
}

@Composable
fun SearchScreen(initialQuery: String = "", onBack: () -> Unit, nav: NavController, vm: SearchViewModel = viewModel()) {
    val graph = App.graph
    val query by vm.query.collectAsStateWithLifecycle()
    val category by vm.category.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val history by graph.searchHistory.items.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<Source?>(null) }
    val engine = graph.engine
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    // Предзаполненный запрос (тап по исполнителю) — сразу ищем, клавиатуру не открываем
    LaunchedEffect(Unit) {
        if (initialQuery.isNotBlank() && vm.query.value.isBlank()) vm.query.value = initialQuery
        if (vm.query.value.isEmpty()) focus.requestFocus()
    }

    val tracks = ui.result?.tracks.orEmpty().filter { t ->
        filter == null || t.source == filter || t.alternates.any { it.source == filter }
    }
    val artists = ui.result?.artists.orEmpty().filter { filter == null || it.source == filter }
    val albums = ui.result?.albums.orEmpty().filter { filter == null || it.source == filter }
    val playlists = ui.result?.playlists.orEmpty().filter { filter == null || it.source == filter }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") }
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                placeholder = { Text("Трек, автор, альбом или плейлист…") },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Rounded.Close, "Очистить") }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    graph.searchHistory.add(query)
                    keyboard?.hide()
                }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
        }

        if (query.isBlank()) {
            SearchHistoryList(
                history = history,
                onPick = { vm.query.value = it },
                onRemove = graph.searchHistory::remove,
                onClear = graph.searchHistory::clear,
            )
            return@Column
        }

        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchCategory.entries.forEach { item ->
                GlassChip(item.label, selected = category == item, onClick = {
                    vm.category.value = item
                })
            }
        }
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassChip("Все", selected = filter == null, onClick = { filter = null })
            Source.entries.forEach { s ->
                GlassChip(s.label, selected = filter == s, onClick = { filter = s })
            }
        }
        if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        ui.result?.errors?.forEach { err ->
            Text(err, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
            when (category) {
                SearchCategory.TRACKS -> itemsIndexed(tracks, key = { _, t -> t.key }) { i, t ->
                    TrackRow(t, onClick = {
                        graph.searchHistory.add(query)
                        engine.playTracks(tracks, i)
                    })
                }
                SearchCategory.ARTISTS -> items(artists, key = { "${it.source}:${it.id}" }) { artist ->
                    ListItem(
                        headlineContent = { Text(artist.name) },
                        supportingContent = { Text(artist.subtitle) },
                        leadingContent = { Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            vm.category.value = SearchCategory.TRACKS
                            vm.query.value = artist.name
                        },
                    )
                }
                SearchCategory.ALBUMS -> items(albums, key = { "${it.source}:${it.id}" }) { album ->
                    ListItem(
                        headlineContent = { Text(album.title) },
                        supportingContent = { Text(album.subtitle) },
                        leadingContent = { Icon(Icons.Rounded.Album, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Text(album.source.label, style = MaterialTheme.typography.labelSmall) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            graph.searchHistory.add(query)
                            when (album.source) {
                                Source.YANDEX -> nav.navigate("tracks/yaalbum/${android.net.Uri.encode(album.id)}/${android.net.Uri.encode(album.title)}")
                                Source.YOUTUBE -> nav.navigate("tracks/ytsearch/${android.net.Uri.encode(album.url ?: album.id)}/${android.net.Uri.encode(album.title)}")
                                else -> Unit
                            }
                        },
                    )
                }
                SearchCategory.PLAYLISTS -> items(playlists, key = { "${it.source}:${it.id}" }) { playlist ->
                    ListItem(
                        headlineContent = { Text(playlist.title) },
                        supportingContent = { Text(playlist.subtitle) },
                        leadingContent = { Icon(Icons.Rounded.PlaylistPlay, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = { Text(playlist.source.label, style = MaterialTheme.typography.labelSmall) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable {
                            graph.searchHistory.add(query)
                            when (playlist.source) {
                                Source.YANDEX -> nav.navigate("tracks/yapl/${android.net.Uri.encode(playlist.id)}/${android.net.Uri.encode(playlist.title)}")
                                Source.YOUTUBE -> nav.navigate("tracks/ytsearch/${android.net.Uri.encode(playlist.url ?: playlist.id)}/${android.net.Uri.encode(playlist.title)}")
                                else -> Unit
                            }
                        },
                    )
                }
            }
            if (!ui.loading && ui.result != null && when (category) {
                    SearchCategory.TRACKS -> tracks.isEmpty()
                    SearchCategory.ARTISTS -> artists.isEmpty()
                    SearchCategory.ALBUMS -> albums.isEmpty()
                    SearchCategory.PLAYLISTS -> playlists.isEmpty()
                }) item { Text("Ничего не нашлось", modifier = Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun SearchHistoryList(history: List<String>, onPick: (String) -> Unit, onRemove: (String) -> Unit, onClear: () -> Unit) {
    if (history.isEmpty()) {
        Text(
            "Ищите треки, авторов, альбомы и плейлисты. Треки ищутся в Яндексе, YouTube, SoundCloud, ВКонтакте и файлах.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(20.dp),
        )
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Недавние запросы", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("Очистить") }
            }
        }
        items(history, key = { it }) { q ->
            ListItem(
                headlineContent = { Text(q) },
                leadingContent = { Icon(Icons.Rounded.History, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                trailingContent = {
                    IconButton(onClick = { onRemove(q) }) { Icon(Icons.Rounded.Close, "Удалить из истории", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                },
                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                modifier = Modifier.clickable { onPick(q) },
            )
        }
    }
}
