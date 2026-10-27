@file:OptIn(ExperimentalMaterial3Api::class)

package com.musichub.ui.screens

import com.musichub.ui.liquid.LocalBottomInset
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import com.musichub.ui.liquid.GlassPanel
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musichub.util.tracksCount
import com.musichub.App
import com.musichub.transfer.TransferState
import com.musichub.transfer.TransferTarget
import com.musichub.vk.VkPlaylist

@Composable
fun TransferScreen(onBack: () -> Unit) {
    val graph = App.graph
    val repo = graph.transfer
    val state by repo.state.collectAsStateWithLifecycle()
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    var link by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf(TransferTarget.YANDEX) }
    var vkPicker by remember { mutableStateOf(false) }
    val working = state is TransferState.Working

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            title = { Text("Перенос плейлистов") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = LocalBottomInset.current + 16.dp)) {
            Text("Куда", fontWeight = FontWeight.Bold)
            TransferTarget.entries.forEach { t ->
                Row(Modifier.fillMaxWidth().clickable { target = t }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = target == t, onClick = { target = t })
                    Text(t.label)
                }
            }
            if (target == TransferTarget.YANDEX && !settings.yandexLoggedIn) {
                ErrorText("Для переноса в Яндекс войдите в аккаунт в настройках")
            }

            Text("Откуда", fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 16.dp))
            OutlinedTextField(
                value = link,
                onValueChange = { link = it },
                label = { Text("Ссылка на плейлист YouTube / YouTube Music") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { repo.start(target) { graph.youtube.playlist(link) } },
                enabled = link.isNotBlank() && !working,
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Перенести с YouTube") }

            if (settings.vkLoggedIn) {
                OutlinedButton(onClick = { vkPicker = true }, enabled = !working, modifier = Modifier.padding(top = 8.dp)) {
                    Text("Выбрать плейлист ВКонтакте")
                }
            }
            Text(
                "Каждый трек ищется в Яндекс Музыке по названию, исполнителю и длительности. " +
                    "Перенос в YouTube невозможен без входа в Google — его приложение не поддерживает.",
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
                        Text("Готово: «${s.title}»", fontWeight = FontWeight.Bold)
                        Text("Найдено в Яндексе: ${s.found} из ${s.total}")
                        if (s.target == TransferTarget.LOCAL && s.missing.isNotEmpty()) {
                            Text("Ненайденные добавлены как есть из исходного сервиса", style = MaterialTheme.typography.bodySmall)
                        }
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

    if (vkPicker) {
        val playlists by produceState<Result<List<VkPlaylist>>?>(null) { value = runCatching { graph.vk.playlists() } }
        AlertDialog(
            onDismissRequest = { vkPicker = false },
            title = { Text("Плейлист ВКонтакте") },
            text = {
                val list = playlists
                when {
                    list == null -> CircularProgressIndicator()
                    list.isFailure -> ErrorText(list.exceptionOrNull()?.message)
                    else -> LazyColumn(Modifier.height(360.dp)) {
                        item {
                            ListItem(
                                headlineContent = { Text("Моя музыка") },
                                modifier = Modifier.clickable {
                                    vkPicker = false
                                    repo.start(target) { "Моя музыка ВК" to graph.vk.myAudios() }
                                },
                            )
                        }
                        items(list.getOrDefault(emptyList())) { p ->
                            ListItem(
                                headlineContent = { Text(p.title) },
                                supportingContent = { Text(tracksCount(p.count)) },
                                modifier = Modifier.clickable {
                                    vkPicker = false
                                    repo.start(target) { p.title to graph.vk.playlistTracks(p) }
                                },
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { vkPicker = false }) { Text("Закрыть") } },
        )
    }
}
