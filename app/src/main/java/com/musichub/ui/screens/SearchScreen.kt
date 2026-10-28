package com.musichub.ui.screens

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.musichub.App
import com.musichub.data.model.Source
import com.musichub.search.SearchResult
import com.musichub.ui.GlassChip
import com.musichub.ui.TrackRow
import com.musichub.ui.liquid.LocalBottomInset
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class SearchViewModel : ViewModel() {
    data class Ui(val loading: Boolean = false, val result: SearchResult? = null)

    val query = MutableStateFlow("")
    val ui = MutableStateFlow(Ui())

    init {
        @OptIn(FlowPreview::class)
        viewModelScope.launch {
            query.debounce(450).map { it.trim() }.distinctUntilChanged().collectLatest { q ->
                if (q.length < 2) {
                    ui.value = Ui()
                    return@collectLatest
                }
                ui.value = ui.value.copy(loading = true)
                ui.value = Ui(loading = false, result = App.graph.search.search(q))
            }
        }
    }
}

@Composable
fun SearchScreen(vm: SearchViewModel = viewModel()) {
    val query by vm.query.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    var filter by rememberSaveable { mutableStateOf<Source?>(null) }
    val engine = App.graph.engine

    val tracks = ui.result?.tracks.orEmpty().filter { t ->
        filter == null || t.source == filter || t.alternates.any { it.source == filter }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        OutlinedTextField(
            value = query,
            onValueChange = { vm.query.value = it },
            placeholder = { Text("Трек, исполнитель…") },
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Rounded.Close, "Очистить") }
            },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
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
            itemsIndexed(tracks, key = { _, t -> t.key }) { i, t ->
                TrackRow(t, onClick = { engine.playTracks(tracks, i) })
            }
        }
    }
}
