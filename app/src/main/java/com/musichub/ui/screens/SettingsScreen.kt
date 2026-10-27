package com.musichub.ui.screens

import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import com.musichub.ui.liquid.liquidClickable
import com.musichub.ui.theme.Palettes
import com.musichub.update.UpdateState
import com.musichub.ui.liquid.LocalBottomInset
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.webkit.CookieManager
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Button
import com.musichub.ui.liquid.GlassPanel
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import com.musichub.ui.liquid.LiquidSwitch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import com.musichub.App
import com.musichub.data.AppSettings
import com.musichub.data.Quality
import com.musichub.net.HappRouting
import kotlinx.coroutines.launch

private val cacheSizes = listOf(512, 1024, 2048, 4096, 8192)

@Composable
fun SettingsScreen(nav: NavController) {
    val graph = App.graph
    val s by graph.settings.flow.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    fun update(block: (AppSettings) -> AppSettings) = scope.launch { graph.settings.update(block) }

    var usage by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var usageReload by remember { mutableIntStateOf(0) }
    LaunchedEffect(usageReload) { usage = graph.engine.cacheUsage() }
    var diagnostics by remember { mutableStateOf<List<String>?>(null) }
    var diagnosing by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(bottom = LocalBottomInset.current)) {
        Text("Настройки", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp))

        Group("Оформление")
        SwitchRow(
            "Liquid Glass",
            "Стекло с преломлением, живой фон и плавающий бар-капсула",
            s.liquidGlass,
        ) { v -> update { it.copy(liquidGlass = v) } }
        Text("Цвет: ${Palettes.getOrElse(s.palette) { Palettes[0] }.name}", modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Palettes.forEachIndexed { i, p ->
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(p.gradient)
                        .border(if (i == s.palette) 3.dp else 0.dp, Color.White, CircleShape)
                        .liquidClickable(pressedScale = 0.85f) { update { it.copy(palette = i) } },
                )
            }
        }

        Group("Аккаунты")
        AccountRow(
            title = "Яндекс Музыка",
            account = s.yandexLogin.takeIf { s.yandexLoggedIn },
            onLogin = { nav.navigate("login/ya") },
            onLogout = {
                CookieManager.getInstance().removeAllCookies(null)
                update { it.copy(yandexToken = null, yandexUid = null, yandexLogin = null) }
            },
        )
        AccountRow(
            title = "ВКонтакте",
            account = s.vkName.takeIf { s.vkLoggedIn },
            onLogin = { nav.navigate("login/vk") },
            onLogout = {
                CookieManager.getInstance().removeAllCookies(null)
                update { it.copy(vkToken = null, vkUserId = null, vkName = null) }
            },
        )

        Group("Поиск")
        SwitchRow("Искать на YouTube", null, s.searchYoutube) { v -> update { it.copy(searchYoutube = v) } }
        SwitchRow("Искать во ВКонтакте", "Нужен вход во ВК", s.searchVk) { v -> update { it.copy(searchVk = v) } }

        Group("Воспроизведение")
        SwitchRow(
            "Замена из других сервисов",
            "Если трек не играет в своём сервисе (нет подписки, удалён, регион) — найти его в другом",
            s.fallbackToOthers,
        ) { v -> update { it.copy(fallbackToOthers = v) } }
        SwitchRow(
            "Подмешивать YouTube в Мою волну",
            "Похожие треки с YouTube между треками волны",
            s.mixYoutubeInWave,
        ) { v -> update { it.copy(mixYoutubeInWave = v) } }
        if (s.mixYoutubeInWave) {
            SliderRow("Каждый ${s.mixEvery}-й трек", s.mixEvery.toFloat(), 2f..10f, steps = 7) { v -> update { it.copy(mixEvery = v.toInt()) } }
        }
        Text("Качество", modifier = Modifier.padding(start = 16.dp, top = 8.dp))
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Quality.entries.forEach { q ->
                FilterChip(selected = s.quality == q, onClick = { update { it.copy(quality = q) } }, label = { Text(q.label) })
            }
        }
        SliderRow(
            if (s.fadeSeconds == 0) "Плавные переходы: выкл" else "Плавные переходы: ${s.fadeSeconds} с",
            s.fadeSeconds.toFloat(), 0f..12f, steps = 11,
        ) { v -> update { it.copy(fadeSeconds = v.toInt()) } }
        NavRow("Эквалайзер") { nav.navigate("equalizer") }

        Group("Сеть и VPN")
        GlassPanel(Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("Happ: Яндекс и ВК напрямую, YouTube через VPN", fontWeight = FontWeight.SemiBold)
                Text(
                    "Правило в самом Happ, работает для всего телефона. После добавления переподключитесь к серверу.\n\n" +
                        "Полный — Яндекс, ВК и все российские сайты напрямую. Happ сначала скачает геофайлы (~27 МБ); " +
                        "пока они не скачались, интернет через VPN может не работать.\n" +
                        "Простой — только Яндекс и ВК, работает сразу.\n\n" +
                        "Если интернет пропал — «Отключить в Happ» и переподключитесь.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { openHapp(context, HappRouting.simpleDeeplink) }) { Text("Простой") }
                    OutlinedButton(onClick = { openHapp(context, HappRouting.deeplink) }) { Text("Полный") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { openHapp(context, HappRouting.OFF_DEEPLINK) }) { Text("Отключить в Happ") }
                    TextButton(onClick = { copy(context, HappRouting.simpleDeeplink) }) { Text("Копировать простой") }
                }
            }
        }
        SwitchRow(
            "YouTube через прокси",
            "Для случая, когда приложение исключено из VPN. В Happ: Настройки → Расширенные → «Разрешить LAN-подключения», порт SOCKS5 обычно 10808",
            s.ytProxyEnabled,
        ) { v -> update { it.copy(ytProxyEnabled = v) } }
        if (s.ytProxyEnabled) {
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("SOCKS", "HTTP").forEach { t ->
                    FilterChip(selected = s.ytProxyType == t, onClick = { update { it.copy(ytProxyType = t) } }, label = { Text(t) })
                }
            }
            Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var host by remember { mutableStateOf(s.ytProxyHost) }
                var port by remember { mutableStateOf(s.ytProxyPort.toString()) }
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it.trim(); update { st -> st.copy(ytProxyHost = host.ifEmpty { "127.0.0.1" }) } },
                    label = { Text("Адрес") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { v ->
                        port = v.filter(Char::isDigit).take(5)
                        port.toIntOrNull()?.takeIf { it in 1..65535 }?.let { p -> update { st -> st.copy(ytProxyPort = p) } }
                    },
                    label = { Text("Порт") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp),
                )
            }
        }
        SwitchRow(
            "Яндекс и ВК мимо VPN",
            "Только для VPN, которые разрешают обход. С Happ не работает и не нужен — используйте профиль Happ выше",
            s.directBypassVpn,
        ) { v -> update { it.copy(directBypassVpn = v) } }
        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(enabled = !diagnosing, onClick = {
                diagnosing = true
                scope.launch {
                    diagnostics = graph.router.diagnose()
                    diagnosing = false
                }
            }) { Text(if (diagnosing) "Проверяю…" else "Проверить маршруты") }
        }
        diagnostics?.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodySmall,
                color = if ("ошибка" in line) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
            )
        }

        Group("Кеш и загрузки")
        val sizeIndex = cacheSizes.indexOf(s.autoCacheMb).coerceAtLeast(0)
        SliderRow(
            "Авто-кеш: до ${formatMb(s.autoCacheMb.toLong())} (применится после перезапуска)",
            sizeIndex.toFloat(), 0f..(cacheSizes.size - 1).toFloat(), steps = cacheSizes.size - 2,
        ) { v -> update { it.copy(autoCacheMb = cacheSizes[v.toInt()]) } }
        usage?.let { (auto, downloads) ->
            Text(
                "Занято кешем: ${formatMb(auto / 1_048_576)} · скачано: ${formatMb(downloads / 1_048_576)}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        TextButton(onClick = {
            scope.launch {
                graph.engine.clearAutoCache()
                usageReload++
            }
        }, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Очистить кеш (скачанное останется)") }

        Group("Обновления")
        val updater = graph.updater
        val updateState by updater.state.collectAsStateWithLifecycle()
        ListItem(
            headlineContent = { Text("Unison ${updater.currentVersion}") },
            supportingContent = {
                Text(
                    when (val u = updateState) {
                        UpdateState.Checking -> "Проверяю…"
                        UpdateState.UpToDate -> "У вас последняя версия"
                        is UpdateState.Available -> "Доступна версия ${u.release.version}"
                        is UpdateState.Downloading -> "Скачиваю ${(u.progress * 100).toInt()}%"
                        is UpdateState.Installing -> "Устанавливаю…"
                        is UpdateState.Failed -> u.message
                        UpdateState.Idle -> "Обновления приходят с GitHub: ${updater.repo}"
                    },
                )
            },
            trailingContent = {
                TextButton(onClick = { updater.check() }, enabled = updateState !is UpdateState.Checking) { Text("Проверить") }
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )

        Group("О приложении")
        Text(
            "Unison — личный клиент для Яндекс Музыки, ВКонтакте и YouTube. Использует неофициальные API: " +
                "что-то может перестать работать после обновлений сервисов. Тексты песен — LRCLIB.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}

private fun formatMb(mb: Long) = if (mb >= 1024) "%.1f ГБ".format(mb / 1024.0) else "$mb МБ"

private fun openHapp(context: Context, link: String) {
    val intent = Intent(Intent.ACTION_VIEW, link.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }.onFailure {
        // Happ также подхватывает ссылку из буфера обмена
        copy(context, link)
        Toast.makeText(context, "Happ не открылся — ссылка скопирована, вставьте её в Happ", Toast.LENGTH_LONG).show()
    }
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Happ routing", text))
    Toast.makeText(context, "Скопировано", Toast.LENGTH_SHORT).show()
}

@Composable
private fun Group(title: String) {
    Text(
        title.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = { LiquidSwitch(checked = checked, onCheckedChange = onChange) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { onChange(!checked) },
    )
}

@Composable
private fun SliderRow(title: String, value: Float, range: ClosedFloatingPointRange<Float>, steps: Int, onChange: (Float) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        Text(title)
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps)
    }
}

@Composable
private fun NavRow(title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        trailingContent = { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun AccountRow(title: String, account: String?, onLogin: () -> Unit, onLogout: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(account?.let { "Вы вошли: $it" } ?: "Не подключено") },
        trailingContent = {
            if (account != null) TextButton(onClick = onLogout) { Text("Выйти") }
            else Button(onClick = onLogin) { Text("Войти") }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
