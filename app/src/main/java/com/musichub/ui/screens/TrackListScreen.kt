package com.musichub.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CloudDownload
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musichub.App
import com.musichub.data.SendResult
import com.musichub.data.model.Track
import com.musichub.links.Links
import com.musichub.ui.AddToPlaylistDialog
import com.musichub.ui.Cover
import com.musichub.ui.CoverBackdrop
import com.musichub.ui.GeneratedCover
import com.musichub.ui.GlassChip
import com.musichub.ui.GlassIconButton
import com.musichub.ui.MetaChip
import com.musichub.ui.PrimaryPill
import com.musichub.ui.TrackRow
import com.musichub.ui.liquid.GlassPanel
import com.musichub.ui.liquid.LocalBottomInset
import com.musichub.util.tracksCount
import com.musichub.vk.VkPlaylist
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Список треков. kind: liked | yapl (owner:kind) | vkmy | vkpl (owner:id:key) | local (id) | downloads
 */
@Composable
fun TrackListScreen(kind: String, arg: String, title: String, onBack: () -> Unit) {
    val graph = App.graph
    val engine = graph.engine
    val scope = rememberCoroutineScope()
    var tracks by remember { mutableStateOf<List<Track>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var addAll by remember { mutableStateOf(false) }
    var seedDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) reload++
    }

    LaunchedEffect(kind, arg, reload) {
        error = null
        if (kind == "downloads") {
            graph.db.downloads().observeAll().collect { list ->
                tracks = list.mapNotNull { runCatching { Track.fromJson(it.trackJson) }.getOrNull() }
            }
        } else if (kind == "favorites") {
            // «Любимое» обновляется на лету — лайк в плеере сразу виден в списке
            graph.favorites.tracks.collect { tracks = it }
        } else {
            try {
                tracks = graph.sources.load(kind, arg)
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    val list = tracks
    val heroCover = list?.firstNotNullOfOrNull { it.coverUrl }

    Box(Modifier.fillMaxSize()) {
        CoverBackdrop(heroCover, strength = 0.8f)
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                GlassIconButton(Icons.AutoMirrored.Rounded.ArrowBack, "Назад", onBack)
            }
            when {
                error != null -> Column(Modifier.padding(16.dp)) {
                    ErrorText(error)
                    PrimaryPill("Повторить", null, onClick = { reload++ })
                }
                list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomInset.current)) {
                    item {
                        Hero(
                            kind = kind,
                            title = title,
                            cover = heroCover,
                            tracks = list,
                            onPlay = { engine.playTracks(list) },
                            onShuffle = { engine.playTracks(list, shuffle = true) },
                            onDownload = { engine.download(list) },
                            onAddAll = { addAll = true },
                            onShare = { shareList(context, kind, arg, title, list) },
                        )
                    }
                    if (kind == "favorites") item { FavoritesPanel(onSeed = { seedDialog = true }) }
                    if (kind == "files" && !graph.localMusic.hasPermission()) {
                        item {
                            GlassPanel(Modifier.padding(16.dp)) {
                                Column(Modifier.padding(16.dp)) {
                                    Text("Unison нужен доступ к музыке на телефоне, чтобы показать ваши файлы.")
                                    Box(Modifier.padding(top = 10.dp)) {
                                        PrimaryPill("Разрешить доступ", null, onClick = { permissionLauncher.launch(graph.localMusic.permission) })
                                    }
                                }
                            }
                        }
                    }
                    itemsIndexed(list, key = { _, t -> t.key }) { i, t ->
                        val extra = when (kind) {
                            "local" -> listOf("Убрать из плейлиста" to {
                                scope.launch {
                                    graph.library.remove(arg.toLong(), t.key)
                                    reload++
                                }
                                Unit
                            })
                            else -> emptyList()
                        }
                        TrackRow(t, onClick = { engine.playTracks(list, i) }, extraActions = extra)
                    }
                }
            }
        }
    }
    if (addAll) AddToPlaylistDialog(tracks.orEmpty(), defaultName = title, onDismiss = { addAll = false })
    if (seedDialog) SeedFavoritesDialog(onDismiss = { seedDialog = false })
}

@Composable
private fun Hero(
    kind: String,
    title: String,
    cover: String?,
    tracks: List<Track>,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onDownload: () -> Unit,
    onAddAll: () -> Unit,
    onShare: () -> Unit,
) {
    val totalMin = tracks.sumOf { it.durationMs } / 60_000
    val duration = if (totalMin >= 60) "${totalMin / 60} ч ${totalMin % 60} мин" else "$totalMin мин"
    val subtitle = when (kind) {
        "liked", "yapl" -> "Яндекс Музыка"
        "vkmy", "vkpl" -> "ВКонтакте"
        "local" -> "Мой плейлист"
        "downloads" -> "Скачано на устройство"
        "favorites" -> "Unison"
        "files" -> "Файлы на телефоне"
        "yaalbum" -> "Альбом · Яндекс Музыка"
        "yauuid", "yatrack" -> "Яндекс Музыка"
        "ytliked", "ytuploads", "ytpl", "ytvideo" -> "YouTube Music"
        else -> ""
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val artModifier = Modifier.size(128.dp)
            when (kind) {
                "liked" -> GeneratedCover(Icons.Rounded.Favorite, Color(0xFF7A4DFF), artModifier)
                "vkmy" -> GeneratedCover(Icons.Rounded.LibraryMusic, Color(0xFF4D71FF), artModifier)
                "downloads" -> GeneratedCover(Icons.Rounded.CloudDownload, Color(0xFF3DDC97), artModifier)
                "local" -> GeneratedCover(Icons.AutoMirrored.Rounded.QueueMusic, Color(0xFF2EC4B6), artModifier)
                "favorites" -> GeneratedCover(Icons.Rounded.Favorite, Color(0xFFFF4D8D), artModifier)
                "files" -> GeneratedCover(Icons.Rounded.PhoneAndroid, Color(0xFF2EC4B6), artModifier)
                "ytliked" -> GeneratedCover(Icons.Rounded.Favorite, Color(0xFFFF3D3D), artModifier)
                "ytuploads" -> GeneratedCover(Icons.Rounded.CloudUpload, Color(0xFFFF3D3D), artModifier)
                else -> Cover(cover, 128.dp, corner = 12.dp)
            }
            Column(Modifier.padding(start = 16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                MetaChip("${tracksCount(tracks.size)} · $duration")
                if (kind != "downloads") {
                    val downloaded by App.graph.engine.downloadedKeys.collectAsStateWithLifecycle()
                    val offline = tracks.count { it.key in downloaded }
                    if (offline > 0) {
                        Box(Modifier.padding(top = 6.dp)) { MetaChip("Офлайн: $offline из ${tracks.size}") }
                    }
                }
            }
        }
        Row(
            Modifier.padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryPill("Слушать", Icons.Rounded.PlayArrow, onPlay, enabled = tracks.isNotEmpty())
            GlassIconButton(Icons.Rounded.Shuffle, "Вперемешку", onShuffle)
            if (kind != "downloads" && kind != "files") GlassIconButton(Icons.Rounded.Download, "Скачать всё", onDownload)
            if (kind !in setOf("local", "downloads", "favorites")) GlassIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "В мои плейлисты", onAddAll)
            GlassIconButton(Icons.Rounded.Share, "Поделиться", onShare)
        }
    }
}

/** Ссылка на плейлист в его сервисе, а для своих списков — сам список текстом. */
private fun shareList(context: Context, kind: String, arg: String, title: String, tracks: List<Track>) {
    val text = Links.shareUrl(kind, arg)?.let { "$title\n$it" } ?: Links.asText(title, tracks)
    context.startActivity(
        Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Поделиться"),
    )
}

/** Панель «Любимого»: взять за основу плейлист и отправить новые лайки в сервисы. */
@Composable
private fun FavoritesPanel(onSeed: () -> Unit) {
    val graph = App.graph
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val unsentYandex by graph.favorites.unsentYandex.collectAsStateWithLifecycle(0)
    val unsentYoutube by graph.favorites.unsentYoutube.collectAsStateWithLifecycle(0)
    val scope = rememberCoroutineScope()
    var progress by remember { mutableStateOf<String?>(null) }

    fun send(service: String, block: suspend ((Int, Int) -> Unit) -> SendResult) {
        if (progress != null) return
        progress = "Отправляю в $service…"
        scope.launch {
            val r = runCatching { block { done, total -> progress = "Отправляю в $service: $done из $total" } }
            progress = null
            graph.engine.notify(
                r.fold(
                    { if (it.notFound.isEmpty()) "Отправлено в $service: ${it.sent}" else "Отправлено: ${it.sent}, в $service не нашлось: ${it.notFound.size}" },
                    { "Не получилось: ${it.message}" },
                ),
            )
        }
    }

    Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
        Text(
            "Сердечко в Unison кладёт трек сюда и больше никуда. В сервисы лайки уходят только по кнопке.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            GlassChip("Взять за основу…", selected = false, onClick = onSeed, icon = Icons.Rounded.Add)
            if (settings.yandexLoggedIn) {
                GlassChip(
                    if (unsentYandex > 0) "В Яндекс ($unsentYandex)" else "В Яндексе всё есть",
                    selected = false,
                    icon = Icons.Rounded.CloudUpload,
                    onClick = { if (unsentYandex > 0) send("Яндекс") { graph.favorites.sendToYandex(it) } },
                )
            }
            if (settings.ytLoggedIn) {
                GlassChip(
                    if (unsentYoutube > 0) "В YouTube ($unsentYoutube)" else "В YouTube всё есть",
                    selected = false,
                    icon = Icons.Rounded.CloudUpload,
                    onClick = { if (unsentYoutube > 0) send("YouTube") { graph.favorites.sendToYoutube(it) } },
                )
            }
        }
        progress?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
    }
}

/** Выбор плейлиста, который станет основой «Любимого» (треки добавятся, дубли — нет). */
@Composable
private fun SeedFavoritesDialog(onDismiss: () -> Unit) {
    val graph = App.graph
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf<String?>(null) }

    val options by produceState<List<SeedOption>?>(null) {
        val list = mutableListOf<SeedOption>()
        if (settings.yandexLoggedIn) {
            list += SeedOption("Мне нравится", "Яндекс Музыка", inYandex = true) { graph.yandex.likedTracks() }
            runCatching { graph.yandex.playlists() }.getOrDefault(emptyList()).forEach { p ->
                list += SeedOption(p.title, "Плейлист Яндекса") { graph.yandex.playlistTracks(p.ownerUid.toString(), p.kind) }
            }
        }
        if (settings.ytLoggedIn) {
            list += SeedOption("Понравилось", "YouTube Music", inYoutube = true) { graph.ytMusic.liked() }
            runCatching { graph.ytMusic.playlists() }.getOrDefault(emptyList()).forEach { p ->
                list += SeedOption(p.title, "Плейлист YouTube") { graph.ytMusic.playlistTracks(p.browseId) }
            }
        }
        if (settings.vkLoggedIn) list += SeedOption("Моя музыка", "ВКонтакте") { graph.vk.myAudios() }
        graph.library.playlists.first().forEach { p ->
            list += SeedOption(p.name, "Мой плейлист") { graph.library.tracks(p.id) }
        }
        value = list
    }

    AlertDialog(
        onDismissRequest = { if (busy == null) onDismiss() },
        title = { Text("Взять за основу") },
        text = {
            val list = options
            when {
                busy != null -> Column {
                    Text(busy.orEmpty())
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                }
                list == null -> CircularProgressIndicator()
                list.isEmpty() -> Text("Войдите в Яндекс, YouTube или создайте свой плейлист — будет из чего выбрать.")
                else -> LazyColumn(Modifier.height(380.dp)) {
                    items(list.size) { i ->
                        val o = list[i]
                        ListItem(
                            headlineContent = { Text(o.title) },
                            supportingContent = { Text(o.subtitle) },
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            modifier = Modifier.clickable {
                                busy = "Загружаю «${o.title}»…"
                                scope.launch {
                                    val result = runCatching {
                                        graph.favorites.add(o.load(), alreadyInYandex = o.inYandex, alreadyInYoutube = o.inYoutube)
                                    }
                                    graph.engine.notify(result.fold({ "Добавлено в «Любимое»: $it" }, { "Не получилось: ${it.message}" }))
                                    onDismiss()
                                }
                            },
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = busy == null) { Text("Закрыть") } },
    )
}

private class SeedOption(
    val title: String,
    val subtitle: String,
    val inYandex: Boolean = false,
    val inYoutube: Boolean = false,
    val load: suspend () -> List<Track>,
)
