package com.musichub.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.musichub.data.SettingsRepository
import com.musichub.util.obj
import com.musichub.util.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.UnknownHostException

/**
 * Раздельная маршрутизация трафика по сервисам:
 *  - YouTube можно пустить через прокси (например, локальный SOCKS5 VPN-клиента: v2rayNG, Hiddify, NekoBox, Clash);
 *  - Яндекс и ВК можно пустить напрямую через Wi-Fi/мобильную сеть в обход VPN.
 *
 * Второе работает, только если VPN разрешает приложениям обход (allowBypass). Самый надёжный вариант:
 * исключить MusicHub из VPN в настройках VPN-клиента и включить прокси для YouTube.
 */
class NetworkRouter(
    context: Context,
    private val settings: SettingsRepository,
    private val base: OkHttpClient,
    private val dns: FallbackDns,
) {

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

    private class BypassCheck(val network: Network, val vpn: Boolean, val ok: Boolean, val at: Long)

    @Volatile private var bypassCheck: BypassCheck? = null

    /** Можно ли привязать сокет к обычной сети в обход VPN (VPN-клиент должен разрешать allowBypass). */
    fun bypassAllowed(): Boolean {
        val network = underlyingNetwork() ?: return false
        val vpn = vpnActive()
        // Ответ зависит от того, включён ли VPN, поэтому кешируем ненадолго и с учётом его состояния
        bypassCheck?.takeIf { it.network == network && it.vpn == vpn && System.currentTimeMillis() - it.at < 60_000 }
            ?.let { return it.ok }
        val ok = runCatching { Socket().use { network.bindSocket(it) } }.isSuccess
        bypassCheck = BypassCheck(network, vpn, ok, System.currentTimeMillis())
        return ok
    }

    /** Обход упал на настоящем запросе — запоминаем, что он запрещён. */
    private fun markBypassBlocked() {
        val network = underlyingNetwork() ?: return
        bypassCheck = BypassCheck(network, vpnActive(), false, System.currentTimeMillis())
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
            // Страховка: если VPN всё же не пустил мимо себя (EPERM) — повторяем запрос обычным путём
            .addInterceptor { chain ->
                try {
                    chain.proceed(chain.request())
                } catch (e: IOException) {
                    val eperm = generateSequence<Throwable>(e) { it.cause }.any { it.message?.contains("EPERM") == true }
                    if (!eperm) throw e
                    markBypassBlocked()
                    base.newCall(chain.request()).execute()
                }
            }
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

    private fun fetchText(url: String): String =
        callFactory.newCall(Request.Builder().url(url).header("User-Agent", "curl/8").build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            resp.body?.string().orEmpty()
        }

    /**
     * Проверка маршрутов. Главное — какой IP видят российские и зарубежные сайты: если разный,
     * значит раздельная маршрутизация (профиль Happ) работает.
     */
    suspend fun diagnose(): List<String> = withContext(Dispatchers.IO) {
        val vpn = vpnActive()
        val out = mutableListOf("VPN сейчас: ${if (vpn) "включён" else "выключен"}")
        if (settings.current.directBypassVpn && vpn && !bypassAllowed()) {
            out += "Переключатель «Яндекс и ВК мимо VPN» с этим VPN не работает: он запрещает приложениям обход. " +
                "Выключите его — с профилем Happ он и не нужен."
        }

        // ip.beget.ru — российский хостинг (Happ отправляет напрямую), ipinfo.io — зарубежный (через VPN)
        val ruIp = runCatching { fetchText("https://ip.beget.ru/").trim() }
        val world = runCatching {
            val o = Json.parseToJsonElement(fetchText("https://ipinfo.io/json")).obj()
            (o?.str("ip") ?: "?") to (o?.str("country") ?: "?")
        }
        out += "Российские сайты видят IP: " + ruIp.fold({ it }, { "ошибка: ${it.message}" })
        out += "Зарубежные сайты видят IP: " + world.fold({ "${it.first} (${it.second})" }, { "ошибка: ${it.message}" })
        val ru = ruIp.getOrNull()
        val foreign = world.getOrNull()
        if (ru != null && foreign != null) {
            out += when {
                ru != foreign.first -> "✓ Раздельная маршрутизация работает: российские сервисы напрямую, остальное через VPN (${foreign.second})"
                vpn -> "✗ Всё идёт одним путём. Проверьте, что в Happ активен профиль Unison, и переподключитесь к серверу"
                else -> "VPN выключен — всё идёт напрямую"
            }
        }

        val checks = listOf(
            "Яндекс" to "https://api.music.yandex.net/genres",
            "ВК" to "https://api.vk.com/method/utils.getServerTime?v=5.131",
            "YouTube" to "https://www.youtube.com/generate_204",
        )
        val results = checks.map { (name, url) ->
            val request = Request.Builder().url(url).build()
            val started = System.currentTimeMillis()
            val result = runCatching { callFactory.newCall(request).execute().use { it.code } }
            Triple("$name — ${routeFor(request.url.host).label}", result, System.currentTimeMillis() - started)
        }

        out.add(
            1,
            if (dns.systemDnsBroken) "DNS: системный не отвечает (так бывает с Happ) — Unison использует свой, DNS-over-HTTPS"
            else "DNS: системный, работает",
        )

        // Не резолвится вообще ничего, даже через DoH — значит, трафик не проходит через сам VPN-клиент
        val all = listOf(ruIp, world) + results.map { it.second }
        if (vpn && all.all { r -> r.exceptionOrNull()?.let { it is UnknownHostException } == true }) {
            val directDns = runCatching { underlyingNetwork()?.getAllByName("ya.ru")?.isNotEmpty() == true }.getOrDefault(false)
            out += "✗ Через VPN не работает даже DNS — проблема в Happ, а не в Unison." +
                (if (directDns) " Без VPN сеть в порядке." else "") +
                " Проверьте, открываются ли сайты в браузере. Если нет: в Happ отключите профиль маршрутизации " +
                "(кнопка «Отключить в Happ» выше) или выберите «Простой», затем переподключитесь к серверу. " +
                "Красный «!» у профиля в Happ значит, что не скачались геофайлы."
        }

        out + results.map { (label, result, took) ->
            "$label: " + result.fold(
                onSuccess = { "OK (HTTP $it, $took мс)" },
                onFailure = { "ошибка: ${it.message ?: it.javaClass.simpleName}" },
            )
        }
    }
}
