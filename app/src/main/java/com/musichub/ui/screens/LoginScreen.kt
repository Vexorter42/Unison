@file:OptIn(ExperimentalMaterial3Api::class)

package com.musichub.ui.screens

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import com.musichub.App
import com.musichub.vk.VkApi
import com.musichub.yandex.YandexApi
import com.musichub.youtube.YtMusicApi
import kotlinx.coroutines.launch

/**
 * Вход через официальную страницу сервиса во встроенном браузере.
 * Пароль вводится на странице Яндекса/VK — приложение получает только токен.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginScreen(service: String, onDone: () -> Unit) {
    val graph = App.graph
    val isVk = service == "vk"
    val isYt = service == "yt"
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var manual by remember { mutableStateOf("") }
    var pageProgress by remember { mutableIntStateOf(0) }
    val context = LocalContext.current

    fun finish(token: String) {
        if (busy || token.isBlank()) return
        busy = true
        status = null
        scope.launch {
            try {
                if (isYt) {
                    // Для YouTube «токен» — это cookie сессии music.youtube.com
                    val name = graph.ytMusic.accountName(token)
                    graph.settings.update { it.copy(ytCookie = token, ytAccountName = name) }
                } else if (isVk) {
                    val (id, name) = graph.vk.profile(token)
                    graph.settings.update { it.copy(vkToken = token, vkUserId = id, vkName = name) }
                } else {
                    val (uid, login) = graph.yandex.account(token)
                    graph.settings.update { it.copy(yandexToken = token, yandexUid = uid, yandexLogin = login) }
                    runCatching { graph.yandex.refreshLikes() }
                }
                onDone()
            } catch (e: Exception) {
                status = "Не получилось: ${e.message}"
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            title = { Text(if (isYt) "Вход в YouTube Music" else if (isVk) "Вход во ВКонтакте" else "Вход в Яндекс") },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
        )
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        status?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        if (isVk) {
            Text(
                "Вход идёт от имени клиента Kate Mobile — иначе VK не отдаёт музыку. Это неофициальный способ.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        if (isYt) {
            Text(
                "Войдите в аккаунт Google — Unison прочитает ваши лайки, плейлисты и загруженные песни YouTube Music. " +
                    "Пароль вводится на странице Google, приложение его не видит.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        val oauthUrl = if (isYt) YtMusicApi.LOGIN_URL else if (isVk) VkApi.OAUTH_URL else YandexApi.OAUTH_URL
        if (pageProgress in 1..99) {
            LinearProgressIndicator(progress = { pageProgress / 100f }, modifier = Modifier.fillMaxWidth())
        }
        AndroidView(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            factory = { ctx ->
                WebView(ctx).apply {
                    // Новый вход VK (id.vk.com) — тяжёлое веб-приложение: без cookies, хранилища
                    // и обычного браузерного User-Agent он показывает белый экран
                    CookieManager.getInstance().setAcceptCookie(true)
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.javaScriptCanOpenWindowsAutomatically = true
                    settings.setSupportMultipleWindows(false)
                    settings.loadWithOverviewMode = true
                    settings.useWideViewPort = true
                    settings.userAgentString = settings.userAgentString.replace("; wv", "").replace(Regex("""Version/\S+\s"""), "")
                    // Google не любит встроенные браузеры: убираем заголовок, по которому их узнают
                    if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                        WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
                    }
                    webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            pageProgress = newProgress
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                            val url = request.url.toString()
                            if (check(url)) return true
                            // vk://, intent:// и прочие схемы во встроенном браузере не открыть
                            return request.url.scheme != "http" && request.url.scheme != "https"
                        }

                        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                            check(url)
                        }

                        override fun onPageFinished(view: WebView, url: String) {
                            // YouTube: вход завершён, когда нас вернуло на music.youtube.com с сессией
                            if (isYt && Uri.parse(url).host == "music.youtube.com") {
                                val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
                                if ("SAPISID" in cookie) finish(cookie)
                            }
                        }

                        override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                            if (request.isForMainFrame) {
                                status = "Страница не загрузилась: ${error.description}. Попробуйте «Открыть в браузере»."
                            }
                        }

                        fun check(url: String): Boolean {
                            if (isYt) return false
                            val token = extractToken(url) ?: return false
                            finish(token)
                            return true
                        }
                    }
                    loadUrl(oauthUrl)
                }
            },
        )
        Text(
            if (isYt) {
                "Если Google пишет, что браузер или приложение небезопасны: войдите в music.youtube.com на компьютере, " +
                    "откройте инструменты разработчика (F12) → Сеть → любой запрос к music.youtube.com → скопируйте " +
                    "значение заголовка Cookie и вставьте сюда."
            } else {
                "Если страница пустая: откройте вход в браузере, войдите, а затем скопируйте адрес страницы " +
                    "(oauth…blank.html#access_token=…) или сам токен и вставьте сюда."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        )
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = manual,
                onValueChange = { manual = it },
                label = { Text(if (isYt) "Cookie с music.youtube.com" else "Токен или адрес страницы") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { finish(if (isYt) manual.trim().removePrefix("Cookie:").trim() else extractToken(manual.trim()) ?: manual.trim()) },
                enabled = manual.isNotBlank() && !busy,
                modifier = Modifier.padding(start = 8.dp),
            ) { Text("OK") }
        }
        if (!isYt) {
            OutlinedButton(
                onClick = {
                    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, oauthUrl.toUri())) }
                },
                modifier = Modifier.padding(start = 16.dp, bottom = 8.dp),
            ) { Text("Открыть в браузере") }
        }
    }
}

/** Достаёт access_token из адреса вида …#access_token=XXX&expires_in=… (или null, если его там нет). */
private fun extractToken(url: String): String? =
    Regex("""access_token=([^&\s]+)""").find(url)?.groupValues?.get(1)?.let(Uri::decode)
