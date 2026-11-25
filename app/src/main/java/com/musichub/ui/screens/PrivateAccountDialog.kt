package com.musichub.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.musichub.App
import kotlinx.coroutines.launch

@Composable
fun PrivateAccountDialog(onDismiss: () -> Unit) {
    val graph = App.graph
    val settings by graph.settings.flow.collectAsState()
    val scope = rememberCoroutineScope()
    var username by remember(settings.cloudUsername) { mutableStateOf(settings.cloudUsername.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val connected = settings.cloudUsername != null && settings.cloudToken != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Аккаунт Unison") },
        text = {
            Column {
                Text("Закрытый аккаунт Unison для друзей. Логины создаёт владелец сервера.",
                    style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(username, { username = it }, label = { Text("Имя пользователя") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                if (!connected) {
                    OutlinedTextField(password, { password = it }, label = { Text("Пароль") }, singleLine = true,
                        visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Text("Используй отдельный пароль Unison. Он не передаётся музыкальным сервисам.",
                        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                } else {
                    Text("Вход выполнен: ${settings.cloudUsername}", style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp))
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
                        graph.settings.update { it.copy(cloudUsername = username.trim(), cloudToken = token) }
                        password = ""
                        message = "Вход выполнен. Нажми «Синхронизировать», чтобы перенести библиотеку."
                    }.onFailure { message = it.message ?: "Не удалось войти" }
                    busy = false
                }
            }) { Text(if (connected) "Вход выполнен" else "Войти") }
        },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (connected) TextButton(enabled = !busy, onClick = {
                    busy = true
                    message = null
                    scope.launch {
                        runCatching { graph.accountSync.sync() }
                            .onSuccess { message = "Готово: синхронизация завершена (${it.uploadedBytes / 1024} КБ)" }
                            .onFailure { message = it.message ?: "Не удалось синхронизировать" }
                        busy = false
                    }
                }) { Text("Синхронизировать") }
                if (connected) TextButton(enabled = !busy, onClick = {
                    scope.launch {
                        runCatching { graph.accountSync.logout() }
                        graph.settings.update { it.copy(cloudUsername = null, cloudToken = null) }
                        password = ""
                        message = "Вход отключён"
                    }
                }) { Text("Выйти") }
                TextButton(onClick = onDismiss) { Text("Закрыть") }
            }
        },
    )
}
