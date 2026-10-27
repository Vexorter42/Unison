package com.musichub.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.musichub.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket

/**
 * Раздельная маршрутизация трафика по сервисам:
 *  - YouTube можно пустить через прокси (например, локальный SOCKS5 VPN-клиента: v2rayNG, Hiddify, NekoBox, Clash);
 *  - Яндекс и ВК можно пустить напрямую через Wi-Fi/мобильную сеть в обход VPN.
 *
 * Второе работает, только если VPN разрешает приложениям обход (allowBypass). Самый надёжный вариант:
 * исключить MusicHub из VPN в настройках VPN-клиента и включить прокси для YouTube.
 */
class NetworkRouter(context: Context, private val settings: SettingsRepository, private val base: OkHttpClient) {

    enum class Route(val label: String) {
        DEFAULT("как в системе"),
        PROXY("через прокси"),
        DIRECT("напрямую, мимо VPN"),
    }

    private val cm = context.getSystemService(ConnectivityManager::class.java)

    private val youtubeHosts = listOf(
        "youtube.com", "googlevideo.com", "ytimg.com", "youtu.be", "ggpht.com",
        "youtube-nocookie.com", "youtubei.googleapis.com",
    )
    private val directHosts = listOf(
        "yandex.net", "yandex.ru", "yandex.com", "yandex.by", "yandex.kz", "ya.ru",
        "vk.com", "vk.ru", "userapi.com", "vkuseraudio.net", "vkuser.net", "vk-cdn.net", "mycdn.me", "okcdn.ru",
    )

    private fun matches(host: String, list: List<String>) = list.any { host == it || host.endsWith(".$it") }

    fun routeFor(host: String): Route {
        val s = settings.current
        return when {
            s.ytProxyEnabled && matches(host, youtubeHosts) -> Route.PROXY
            // Если VPN-клиент запрещает обход (Happ так делает), не ломаем запросы, а идём как обычно
            s.directBypassVpn && matches(host, directHosts) && bypassAllowed() -> Route.DIRECT
            else -> Route.DEFAULT
        }
    }

    @Volatile private var bypassCheck: Pair<Network, Boolean>? = null

    /** Можно ли привязать сокет к обычной сети в обход VPN (VPN-клиент должен разрешать allowBypass). */
    fun bypassAllowed(): Boolean {
        val network = underlyingNetwork() ?: return false
        bypassCheck?.takeIf { it.first == network }?.let { return it.second }
        val ok = runCatching { Socket().use { network.bindSocket(it) } }.isSuccess
        bypassCheck = network to ok
        return ok
    }

    fun clientFor(host: String): OkHttpClient = when (routeFor(host)) {
        Route.PROXY -> proxyClient()
        Route.DIRECT -> directClient() ?: base
        Route.DEFAULT -> base
    }

    /** Отдаёт каждому запросу клиента по его домену. Используется везде: API, плеер, картинки. */
    val callFactory = Call.Factory { request -> clientFor(request.url.host).newCall(request) }

    @Volatile private var proxyCache: Pair<String, OkHttpClient>? = null
    @Volatile private var directCache: Pair<Network, OkHttpClient>? = null

    private fun proxyClient(): OkHttpClient {
        val s = settings.current
        val signature = "${s.ytProxyType}|${s.ytProxyHost}|${s.ytProxyPort}"
        proxyCache?.takeIf { it.first == signature }?.let { return it.second }
        val type = if (s.ytProxyType == "HTTP") Proxy.Type.HTTP else Proxy.Type.SOCKS
        // Для SOCKS OkHttp сам отдаёт имя хоста прокси — DNS YouTube резолвится на той стороне
        val client = base.newBuilder().proxy(Proxy(type, InetSocketAddress(s.ytProxyHost, s.ytProxyPort))).build()
        proxyCache = signature to client
        return client
    }

    private fun directClient(): OkHttpClient? {
        val network = underlyingNetwork() ?: return null
        directCache?.takeIf { it.first == network }?.let { return it.second }
        val client = base.newBuilder()
            .socketFactory(network.socketFactory)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = network.getAllByName(hostname).toList()
            })
            .build()
        directCache = network to client
        return client
    }

    /** Обычная сеть (Wi-Fi / мобильная) под VPN-туннелем. */
    @Suppress("DEPRECATION")
    fun underlyingNetwork(): Network? = cm.allNetworks
        .mapNotNull { n -> cm.getNetworkCapabilities(n)?.let { n to it } }
        .filter { (_, c) -> c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !c.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        .sortedByDescending { (_, c) -> c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
        .firstOrNull()?.first

    fun vpnActive(): Boolean =
        cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true

    /** Проверка: доходит ли каждый сервис по своему маршруту. */
    suspend fun diagnose(): List<String> = withContext(Dispatchers.IO) {
        val checks = listOf(
            "Яндекс" to "https://api.music.yandex.net/genres",
            "ВК" to "https://api.vk.com/method/utils.getServerTime?v=5.131",
            "YouTube" to "https://www.youtube.com/generate_204",
        )
        val header = buildList {
            add("VPN сейчас: ${if (vpnActive()) "включён" else "выключен"}")
            if (settings.current.directBypassVpn && vpnActive() && !bypassAllowed()) {
                add("⚠ VPN-клиент запрещает приложениям обход туннеля, поэтому Яндекс и ВК идут через VPN. Используйте «Добавить в Happ» — правило в самом Happ.")
            }
        }
        header + checks.map { (name, url) ->
            val request = Request.Builder().url(url).build()
            val route = routeFor(request.url.host)
            val started = System.currentTimeMillis()
            val result = runCatching { callFactory.newCall(request).execute().use { it.code } }
            val took = System.currentTimeMillis() - started
            "$name — ${route.label}: " + result.fold(
                onSuccess = { "OK (HTTP $it, $took мс)" },
                onFailure = { "ошибка: ${it.message ?: it.javaClass.simpleName}" },
            )
        }
    }
}
