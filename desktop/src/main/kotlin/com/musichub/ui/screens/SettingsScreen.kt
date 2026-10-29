package com.musichub.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musichub.data.AppDirs
import com.musichub.data.AppSettings
import com.musichub.data.Quality
import com.musichub.ui.GlassButton
import com.musichub.ui.GlassChip
import com.musichub.ui.LocalGraph
import com.musichub.ui.PrimaryPill
import com.musichub.ui.glassFill
import com.musichub.ui.onEnter
import com.musichub.ui.theme.LocalPalette
import com.musichub.ui.theme.Palettes
import com.musichub.vk.VkApi
import com.musichub.yandex.YandexApi
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI
import java.net.URLDecoder

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen() {
    val graph = LocalGraph.current
    val s by graph.settings.flow.collectAsState()
    fun set(block: (AppSettings) -> AppSettings) = graph.scope.launch { graph.settings.update(block) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp, vertical = 24.dp)) {
        Text("Аккаунты и настройки", fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))

        AccountCard(
            title = "Яндекс Музыка",
            loggedInAs = s.yandexLogin?.let { "Вход выполнен: $it" }.takeIf { s.yandexLoggedIn },
            hint = "Нажмите «Открыть вход», войдите в Яндекс и разрешите доступ. Браузер перейдёт на страницу, " +
                "в адресе которой есть access_token=… — скопируйте этот адрес целиком (или только токен) и вставьте сюда.",
            loginUrl = YandexApi.OAUTH_URL,
            placeholder = "Адрес страницы после входа или токен",
            onSubmit = { input ->
                val token = extractToken(input) ?: input
                val (uid, login) = graph.yandex.account(token)
                graph.settings.update { it.copy(yandexToken = token, yandexUid = uid, yandexLogin = login) }
                runCatching { graph.yandex.refreshLikes() }
            },
            onLogout = { set { it.copy(yandexToken = null, yandexUid = null, yandexLogin = null) } },
        )
        Spacer(Modifier.height(14.dp))
        AccountCard(
            title = "ВКонтакте",
            loggedInAs = s.vkName?.let { "Вход выполнен: $it" }.takeIf { s.vkLoggedIn },
            hint = "Вход через Kate Mobile: после входа скопируйте адрес страницы oauth.vk.com/blank.html#access_token=… " +
                "и вставьте сюда. ВК иногда ограничивает доступ к музыке — тогда поиск в ВК просто пропускается.",
            loginUrl = VkApi.OAUTH_URL,
            placeholder = "Адрес blank.html#access_token=… или токен",
            onSubmit = { input ->
                val token = extractToken(input) ?: input
                val (id, name) = graph.vk.profile(token)
                graph.settings.update { it.copy(vkToken = token, vkUserId = id, vkName = name) }
            },
            onLogout = { set { it.copy(vkToken = null, vkUserId = null, vkName = null) } },
        )
        Spacer(Modifier.height(14.dp))
        AccountCard(
            title = "YouTube Music",
            loggedInAs = s.ytAccountName?.let { "Вход выполнен: $it" }.takeIf { s.ytLoggedIn },
            hint = "Необязательно: поиск и воспроизведение YouTube работают без входа. Для своих лайков и плейлистов " +
                "откройте music.youtube.com в браузере, F12 → Сеть → любой запрос к music.youtube.com → скопируйте заголовок Cookie.",
            loginUrl = "https://music.youtube.com",
            placeholder = "Cookie с music.youtube.com",
            onSubmit = { input ->
                val cookie = input.removePrefix("Cookie:").trim()
                val name = graph.ytMusic.accountName(cookie)
                graph.settings.update { it.copy(ytCookie = cookie, ytAccountName = name) }
            },
            onLogout = { set { it.copy(ytCookie = null, ytAccountName = null) } },
        )

        Spacer(Modifier.height(28.dp))
        Text("Поиск и воспроизведение", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Toggle("Искать на YouTube", s.searchYoutube) { v -> set { it.copy(searchYoutube = v) } }
        Toggle("Искать во ВКонтакте", s.searchVk) { v -> set { it.copy(searchVk = v) } }
        Toggle(
            "Замена, если трек не играет",
            s.fallbackToOthers,
            note = "Ищет тот же трек в другом сервисе: например, YouTube просит «подтвердить, что вы не бот» — играет из Яндекса",
        ) { v -> set { it.copy(fallbackToOthers = v) } }
        Spacer(Modifier.height(10.dp))
        Text("Качество", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Quality.entries.forEach { q -> GlassChip(q.label, s.quality == q) { set { it.copy(quality = q) } } }
        }

        Spacer(Modifier.height(28.dp))
        Text("Оформление", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Toggle("Цвета из обложки", s.coverTheme, note = "Палитра окна берётся из обложки текущего трека") { v -> set { it.copy(coverTheme = v) } }
        Spacer(Modifier.height(10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Palettes.forEachIndexed { i, p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { set { it.copy(palette = i) } }) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(p.gradient)
                            .then(if (s.palette == i) Modifier.border(2.dp, Color.White, CircleShape) else Modifier),
                    )
                    Text(p.name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        Text(
            "Данные приложения: ${AppDirs.root.absolutePath}\nUnison — свободная программа (GPL-3.0), использует NewPipe Extractor и libVLC.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun AccountCard(
    title: String,
    loggedInAs: String?,
    hint: String,
    loginUrl: String,
    placeholder: String,
    onSubmit: suspend (String) -> Unit,
    onLogout: () -> Unit,
) {
    val graph = LocalGraph.current
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val palette = LocalPalette.current

    fun submit() {
        val value = input.trim()
        if (value.isEmpty() || busy) return
        busy = true
        error = null
        graph.scope.launch {
            runCatching { onSubmit(value) }
                .onSuccess { input = "" }
                .onFailure { error = it.message ?: it.javaClass.simpleName }
            busy = false
        }
    }

    Column(Modifier.widthIn(max = 760.dp).fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(glassFill()).padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (loggedInAs != null) GlassButton("Выйти", Icons.AutoMirrored.Rounded.Logout, onClick = onLogout)
        }
        if (loggedInAs != null) {
            Text(loggedInAs, color = palette.accent, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            return@Column
        }
        Text(hint, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton("Открыть вход", Icons.AutoMirrored.Rounded.OpenInNew, onClick = { openInBrowser(loginUrl) })
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(glassFill(strong = true)).padding(horizontal = 14.dp, vertical = 11.dp),
            ) {
                if (input.isEmpty()) {
                    Text(placeholder, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, fontSize = 14.sp),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurface),
                    modifier = Modifier.fillMaxWidth().onEnter { submit() },
                )
            }
            PrimaryPill(if (busy) "Проверяю…" else "Войти", null, onClick = { submit() }, enabled = input.isNotBlank() && !busy)
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp)) }
    }
}

@Composable
private fun Toggle(title: String, checked: Boolean, note: String? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.widthIn(max = 760.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { onChange(!checked) }.padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp)
            if (note != null) Text(note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedTrackColor = LocalPalette.current.b,
                checkedThumbColor = Color.White,
                uncheckedTrackColor = glassFill(strong = true),
                uncheckedBorderColor = Color.Transparent,
            ),
        )
    }
}

/** Достаёт access_token из адреса вида …#access_token=XXX&expires_in=… (или null, если его там нет). */
private fun extractToken(url: String): String? =
    Regex("""access_token=([^&\s]+)""").find(url)?.groupValues?.get(1)?.let { URLDecoder.decode(it, Charsets.UTF_8) }

fun openInBrowser(url: String) {
    runCatching { Desktop.getDesktop().browse(URI(url)) }
        .recoverCatching { ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start() }
}
