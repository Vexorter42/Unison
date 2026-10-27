package com.musichub.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musichub.App
import com.musichub.update.UpdateState

/** Окно «Доступно обновление» с описанием изменений и прогрессом загрузки. */
@Composable
fun UpdateDialog() {
    val updater = App.graph.updater
    val state by updater.state.collectAsStateWithLifecycle()
    when (val s = state) {
        is UpdateState.Available -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Доступна версия ${s.release.version}") },
            text = {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                    Text("Сейчас у вас ${updater.currentVersion}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (s.release.notes.isNotBlank()) {
                        Text("Что нового", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                        Text(plainNotes(s.release.notes), style = MaterialTheme.typography.bodyMedium)
                    }
                    if (s.release.sizeBytes > 0) {
                        Text(
                            "Размер: ${"%.1f".format(s.release.sizeBytes / 1_048_576.0)} МБ",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { updater.downloadAndInstall(s.release) }) { Text("Обновить") } },
            dismissButton = { TextButton(onClick = { updater.skip(s.release) }) { Text("Пропустить") } },
        )
        is UpdateState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Скачиваю ${s.release.version}…") },
            text = {
                Column {
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(s.progress * 100).toInt()}%", modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {},
        )
        is UpdateState.Installing -> AlertDialog(
            onDismissRequest = updater::dismiss,
            title = { Text("Устанавливаю ${s.release.version}") },
            text = { Text("Подтвердите установку в системном окне. Приложение перезапустится.") },
            confirmButton = { TextButton(onClick = updater::dismiss) { Text("Ок") } },
        )
        else -> Unit
    }
}

/** Описание релиза с GitHub приходит в markdown — показываем его простым текстом. */
private fun plainNotes(markdown: String): String = markdown.lines()
    .filterNot { it.trimStart().startsWith("#") }
    .joinToString("\n") { line ->
        line.trimEnd()
            .replace(Regex("""^\s*[-*]\s+"""), "• ")
            .replace(Regex("""^\s*>\s?"""), "")
            .replace("**", "")
            .replace("`", "")
    }
    .replace(Regex("""\n{3,}"""), "\n\n")
    .trim()
