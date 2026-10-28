@file:OptIn(ExperimentalMaterial3Api::class)

package com.musichub.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musichub.App
import com.musichub.data.TrackSource
import com.musichub.links.Links
import com.musichub.transfer.TransferState
import com.musichub.transfer.TransferTarget
import com.musichub.ui.PrimaryPill
import com.musichub.ui.liquid.GlassPanel
import com.musichub.ui.liquid.LocalBottomInset

/** Перенос плейлиста из любого сервиса (или по ссылке) в Яндекс, YouTube Music или свои плейлисты. */
@Composable
fun TransferScreen(onBack: () -> Unit) {
    val graph = App.graph
    val repo = graph.transfer
    val state by repo.state.collectAsStateWithLifecycle()
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    var source by remember { mutableStateOf<TrackSource?>(null) }
    var link by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf(TransferTarget.LOCAL) }
    var picker by remember { mutableStateOf(false) }
    val working = state is TransferState.Working
    val linkTarget = Links.parse(link)

    fun run() {
        val src = source
        val (kind, arg, title) = when {
            src != null -> Triple(src.kind, src.arg, src.title)
            linkTarget != null -> Triple(linkTarget.kind, linkTarget.arg, linkTarget.title)
            else -> return
        }
        repo.start(target) { title to graph.sources.load(kind, arg) }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            title = { Text("Перенос плейлистов") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
        )
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = LocalBottomInset.current + 16.dp),
        ) {
            Text("Откуда", fontWeight = FontWeight.Bold)
            GlassPanel(Modifier.padding(top = 8.dp).clickable(enabled = !working) { picker = true }) {
                ListItem(
                    headlineContent = { Text(source?.title ?: "Выбрать плейлист…") },
                    supportingContent = { Text(source?.subtitle ?: "Яндекс, YouTube, ВК, свои плейлисты, «Любимое», файлы") },
                    trailingContent = { Icon(Icons.Rounded.SwapHoriz, null) },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                )
            }
            OutlinedTextField(
                value = link,
                onValueChange = { link = it; if (it.isNotBlank()) source = null },
                label = { Text("…или ссылка на плейлист") },
                singleLine = true,
                isError = link.isNotBlank() && linkTarget == null,
                supportingText = { if (link.isNotBlank() && linkTarget == null) Text("Не узнаю эту ссылку") },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )

            Text("Куда", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
            TransferTarget.entries.forEach { t ->
                val enabled = when (t) {
                    TransferTarget.YANDEX -> settings.yandexLoggedIn
                    TransferTarget.YOUTUBE -> settings.ytLoggedIn
                    TransferTarget.LOCAL -> true
                }
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = enabled) { target = t },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = target == t, onClick = { target = t }, enabled = enabled)
                    Column {
                        Text(t.label, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (!enabled) Text("Нужен вход в настройках", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Box(Modifier.padding(top = 12.dp)) {
                PrimaryPill("Перенести", Icons.Rounded.SwapHoriz, onClick = ::run, enabled = !working && (source != null || linkTarget != null))
            }
            Text(
                "В Яндекс и YouTube каждый трек ищется по названию, исполнителю и длительности. " +
                    "В свои плейлисты треки копируются как есть — из любых сервисов.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 12.dp),
            )

            when (val s = state) {
                TransferState.Idle -> Unit
                is TransferState.Working -> Column(Modifier.padding(top = 16.dp)) {
                    Text(if (s.total > 0) "${s.step}: ${s.done} из ${s.total}" else s.step)
                    if (s.total > 0) {
                        LinearProgressIndicator(progress = { s.done.toFloat() / s.total }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    TextButton(onClick = repo::reset) { Text("Отменить") }
                }
                is TransferState.Done -> GlassPanel(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Готово: «${s.title}» → ${s.target.service}", fontWeight = FontWeight.Bold)
                        Text("Перенесено: ${s.found} из ${s.total}")
                        if (s.missing.isNotEmpty()) {
                            Text("Не нашлись:", modifier = Modifier.padding(top = 8.dp))
                            s.missing.take(50).forEach { Text("• ${it.artists} — ${it.title}", style = MaterialTheme.typography.bodySmall) }
                            if (s.missing.size > 50) Text("…и ещё ${s.missing.size - 50}", style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = repo::reset) { Text("Ок") }
                    }
                }
                is TransferState.Failed -> Column(Modifier.padding(top = 16.dp)) {
                    ErrorText(s.message)
                    TextButton(onClick = repo::reset) { Text("Ок") }
                }
            }
        }
    }

    if (picker) {
        val sources by produceState<List<TrackSource>?>(null) { value = graph.sources.available() }
        AlertDialog(
            onDismissRequest = { picker = false },
            title = { Text("Откуда перенести") },
            text = {
                val list = sources
                if (list == null) {
                    CircularProgressIndicator()
                } else {
                    LazyColumn(Modifier.height(400.dp)) {
                        items(list, key = { "${it.kind}/${it.arg}" }) { src ->
                            ListItem(
                                headlineContent = { Text(src.title) },
                                supportingContent = { Text(src.subtitle) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.clickable {
                                    source = src
                                    link = ""
                                    picker = false
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picker = false }) { Text("Закрыть") } },
        )
    }
}
