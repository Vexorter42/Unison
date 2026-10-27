package com.musichub.ui.screens

import com.musichub.ui.liquid.LocalBottomInset
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import com.musichub.ui.liquid.GlassPanel
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.musichub.util.tracksCount
import com.musichub.App
import com.musichub.data.db.LocalPlaylistInfo
import com.musichub.ui.Cover
import com.musichub.ui.GeneratedCover
import com.musichub.ui.GlassChip
import com.musichub.ui.PrimaryPill
import com.musichub.ui.openTracks
import com.musichub.vk.VkPlaylist
import com.musichub.yandex.YaPlaylist
import kotlinx.coroutines.launch

private enum class LibFilter(val label: String) { ALL("Все"), YANDEX("Яндекс"), VK("ВКонтакте"), MINE("Мои") }

private val Violet = Color(0xFF7A4DFF)
private val Blue = Color(0xFF4D71FF)
private val Teal = Color(0xFF2EC4B6)
private val Green = Color(0xFF3DDC97)
private val Orange = Color(0xFFFF8A4D)

@Composable
fun LibraryScreen(nav: NavController) {
    val graph = App.graph
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val local by graph.library.playlists.collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(LibFilter.ALL) }
    var createDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<LocalPlaylistInfo?>(null) }

    val yaPlaylists by produceState<Result<List<YaPlaylist>>?>(null, settings.yandexUid) {
        value = if (settings.yandexLoggedIn) runCatching { graph.yandex.playlists() } else null
    }
    val vkPlaylists by produceState<Result<List<VkPlaylist>>?>(null, settings.vkUserId) {
        value = if (settings.vkLoggedIn) runCatching { graph.vk.playlists() } else null
    }
    val show = { f: LibFilter -> filter == LibFilter.ALL || filter == f }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(150.dp),
        modifier = Modifier.statusBarsPadding(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = LocalBottomInset.current),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        fullLine {
            Text("Медиатека", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        }
        fullLine {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LibFilter.entries.forEach { f -> GlassChip(f.label, selected = filter == f, onClick = { filter = f }) }
            }
        }

        if (show(LibFilter.YANDEX)) {
            if (!settings.yandexLoggedIn) {
                fullLine { LoginCard("Яндекс Музыка", "Моя волна, лайки и плейлисты") { nav.navigate("login/ya") } }
            } else {
                item { GeneratedTile("Мне нравится", "Яндекс Музыка", Icons.Rounded.Favorite, Violet) { nav.openTracks("liked", "-", "Мне нравится") } }
                yaPlaylists?.exceptionOrNull()?.let { e -> fullLine { ErrorText(e.message) } }
                items(yaPlaylists?.getOrNull().orEmpty(), key = { "ya${it.ownerUid}:${it.kind}" }) { p ->
                    CoverTile(p.coverUrl, p.title, "${tracksCount(p.trackCount)} · Яндекс") {
                        nav.openTracks("yapl", "${p.ownerUid}:${p.kind}", p.title)
                    }
                }
            }
        }

        if (show(LibFilter.VK)) {
            if (!settings.vkLoggedIn) {
                fullLine { LoginCard("ВКонтакте", "Моя музыка, плейлисты и рекомендации") { nav.navigate("login/vk") } }
            } else {
                item { GeneratedTile("Моя музыка", "ВКонтакте", Icons.Rounded.LibraryMusic, Blue) { nav.openTracks("vkmy", "-", "Моя музыка ВК") } }
                vkPlaylists?.exceptionOrNull()?.let { e -> fullLine { ErrorText(e.message) } }
                items(vkPlaylists?.getOrNull().orEmpty(), key = { "vk${it.ownerId}_${it.id}" }) { p ->
                    CoverTile(p.coverUrl, p.title, "${tracksCount(p.count)} · ВК") {
                        nav.openTracks("vkpl", "${p.ownerId}:${p.id}:${p.accessKey.orEmpty()}", p.title)
                    }
                }
            }
        }

        if (show(LibFilter.MINE)) {
            items(local, key = { "local${it.id}" }) { p ->
                GeneratedTile(
                    p.name, "${tracksCount(p.count)} · мой плейлист", Icons.AutoMirrored.Rounded.QueueMusic, Teal,
                    onLongClick = { deleteTarget = p },
                ) { nav.openTracks("local", p.id.toString(), p.name) }
            }
            item { GeneratedTile("Новый плейлист", "Из любых сервисов", Icons.Rounded.Add, Color(0xFF6B6B78)) { createDialog = true } }
            item { GeneratedTile("Скачанное", "Без интернета", Icons.Rounded.CloudDownload, Green) { nav.openTracks("downloads", "-", "Скачанное") } }
            item { GeneratedTile("Перенос плейлистов", "YouTube / ВК → Яндекс", Icons.Rounded.SwapHoriz, Orange) { nav.navigate("transfer") } }
        }
    }

    if (createDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { createDialog = false },
            title = { Text("Новый плейлист") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Название") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch { graph.library.create(name.trim()) }
                    createDialog = false
                }) { Text("Создать") }
            },
            dismissButton = { TextButton(onClick = { createDialog = false }) { Text("Отмена") } },
        )
    }
    deleteTarget?.let { p ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Удалить «${p.name}»?") },
            text = { Text("Сами треки в сервисах не пострадают.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { graph.library.delete(p.id) }
                    deleteTarget = null
                }) { Text("Удалить", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("Отмена") } },
        )
    }
}

private fun LazyGridScope.fullLine(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TileFrame(title: String, subtitle: String, onClick: () -> Unit, onLongClick: (() -> Unit)? = null, art: @Composable (Modifier) -> Unit) {
    Column(Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        art(Modifier.fillMaxWidth().aspectRatio(1f))
        Text(title, fontWeight = FontWeight.Medium, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        Text(subtitle, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CoverTile(url: String?, title: String, subtitle: String, onClick: () -> Unit) =
    TileFrame(title, subtitle, onClick) { m -> Cover(url, modifier = m, corner = 14.dp) }

@Composable
private fun GeneratedTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    hue: Color,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) = TileFrame(title, subtitle, onClick, onLongClick) { m -> GeneratedCover(icon, hue, m) }

@Composable
private fun LoginCard(service: String, what: String, onLogin: () -> Unit) {
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(service, fontWeight = FontWeight.SemiBold)
            Text(what, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp, modifier = Modifier.padding(bottom = 10.dp))
            PrimaryPill("Войти", null, onLogin)
        }
    }
}

@Composable
internal fun ErrorText(message: String?) {
    Text(
        message ?: "Ошибка",
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}
