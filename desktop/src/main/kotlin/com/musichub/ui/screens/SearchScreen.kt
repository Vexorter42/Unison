package com.musichub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musichub.data.model.Track
import com.musichub.ui.Hint
import com.musichub.ui.LocalEngine
import com.musichub.ui.LocalGraph
import com.musichub.ui.TrackRow
import com.musichub.ui.glassFill
import com.musichub.ui.onEnter
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Состояние поиска живёт выше экрана, чтобы результаты не пропадали при переходах. */
class SearchState {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<Track>>(emptyList())
    var errors by mutableStateOf<List<String>>(emptyList())
    var loading by mutableStateOf(false)
    var searched by mutableStateOf(false)
}

@Composable
fun SearchScreen(state: SearchState) {
    val graph = LocalGraph.current
    val engine = LocalEngine.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    fun run() {
        val q = state.query.trim()
        if (q.isEmpty()) return
        job?.cancel()
        job = scope.launch {
            state.loading = true
            val r = graph.search.search(q)
            state.results = r.tracks
            state.errors = r.errors
            state.loading = false
            state.searched = true
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp, vertical = 24.dp)) {
        Text("Поиск", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(glassFill(strong = true)).padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Search, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.size(10.dp))
            Box(Modifier.weight(1f)) {
                if (state.query.isEmpty()) Text("Трек, исполнитель — Яндекс, YouTube и ВК сразу", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 16.sp)
                BasicTextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus).onEnter { run() },
                )
            }
            if (state.loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSurface)
        }
        if (state.errors.isNotEmpty()) {
            Text(
                state.errors.joinToString("\n"),
                color = MaterialTheme.colorScheme.error,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        when {
            state.results.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(bottom = 16.dp)) {
                itemsIndexed(state.results, key = { _, t -> t.key }) { i, t ->
                    TrackRow(t, index = i, onPlay = { engine.playTracks(state.results, i) })
                }
            }
            state.searched && !state.loading -> Hint("Ничего не нашлось")
            !state.searched -> Hint("Одинаковые треки из разных сервисов склеиваются в одну строку — значки показывают, где ещё он есть")
        }
    }
}
