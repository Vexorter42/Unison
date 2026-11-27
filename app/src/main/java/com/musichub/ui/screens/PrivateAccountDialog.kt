package com.musichub.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import java.text.DateFormat
import java.util.Date
import com.musichub.App
import kotlinx.coroutines.launch

@Composable
fun PrivateAccountDialog(onDismiss: () -> Unit) {
    val graph = App.graph
    val settings by graph.settings.flow.collectAsState()
    val sync by graph.accountSync.status.collectAsState()
    val scope = rememberCoroutineScope()
    var username by remember(settings.cloudUsername) { mutableStateOf(settings.cloudUsername.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val connected = settings.cloudUsername != null && settings.cloudToken != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Аккаунт Unison", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("Закрытый аккаунт Unison для друзей. Логины создаёт владелец сервера.",
                    style = MaterialTheme.typography.bodySmall)
                if (!connected) OutlinedTextField(username, { username = it }, label = { Text("Имя пользователя") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (!connected) {
                    OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("Используй отдельный пароль Unison. Он не передаётся музыкальным сервисам.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                } else {
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp).background(
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f), RoundedCornerShape(20.dp)).padding(16.dp)) {
                        Text(settings.cloudUsername.orEmpty(), fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                        Text("Аккаунт подключён", style = MaterialTheme.typography.bodySmall)
                        Text(when {
                            sync.active -> "Синхронизация…"
                            sync.error != null -> "Ошибка: ${sync.error}"
                            settings.cloudSyncAt > 0 -> "Последняя синхронизация: ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(settings.cloudSyncAt))}"
                            else -> "Синхронизация ещё не выполнялась"
                        }, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodyMedium,
                            color = if (sync.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Синхронизировать при запуске", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                        Switch(settings.cloudAutoSync, onCheckedChange = { value -> scope.launch { graph.settings.update { it.copy(cloudAutoSync = value) } } })
                    }
                }
                message?.let { Text(it, color = if (it.startsWith("Готово")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp)) }
                if (busy) CircularProgressIndicator(Modifier.padding(top = 12.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = !busy && !connected, onClick = {
                busy = true
                message = null
                scope.launch {
                    runCatching {
                        val token = graph.accountSync.login(settings.cloudServer, username, password)
                        graph.accountSync.resetStatus()
                        graph.settings.update { it.copy(cloudUsername = username.trim(), cloudToken = token, cloudSyncAt = if (it.cloudUsername == username.trim()) it.cloudSyncAt else 0) }
                        password = ""
                        message = "Вход выполнен. Нажми «Синхронизировать», чтобы перенести библиотеку."
                    }.onFailure { message = it.message ?: "Не удалось войти" }
                    busy = false
                }
            }) { Text(if (connected) "Вход выполнен" else "Войти") }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (connected) TextButton(enabled = !busy && !sync.active, onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        runCatching { graph.accountSync.sync() }
                            .onSuccess { message = "Готово: синхронизация завершена (${it.uploadedBytes / 1024} КБ)" }
                            .onFailure { message = it.message ?: "Не удалось синхронизировать" }
                        busy = false
                    }
                }) { Text("Синхронизировать") }
                if (connected) TextButton(enabled = !busy && !sync.active, onClick = {
                    scope.launch {
                        runCatching { graph.accountSync.logout() }
                        graph.accountSync.resetStatus()
                        graph.settings.update { it.copy(cloudUsername = null, cloudToken = null, cloudSyncAt = 0) }
                        password = ""
                        message = "Вход отключён"
                    }
                }) { Text("Выйти") }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
    )
}
