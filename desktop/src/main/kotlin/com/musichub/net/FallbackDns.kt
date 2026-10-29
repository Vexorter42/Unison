package com.musichub.net

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.dnsoverhttps.DnsOverHttps
import java.net.InetAddress
import java.net.UnknownHostException

/**
 * DNS с запасным путём. Некоторые VPN (например, Happ с профилем маршрутизации) ломают системный DNS
 * для обычных приложений, а браузер при этом работает — у него свой DNS-over-HTTPS. Делаем так же:
 * если системный DNS не ответил, спрашиваем адрес через DoH по прямым IP (им самим DNS не нужен).
 */
class FallbackDns(private val primary: Dns = Dns.SYSTEM) : Dns {

    private val bootstrap = OkHttpClient()

    private val resolvers = listOf(
        DnsOverHttps.Builder().client(bootstrap)
            .url("https://cloudflare-dns.com/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("1.1.1.1"), InetAddress.getByName("1.0.0.1"))
            .build(),
        DnsOverHttps.Builder().client(bootstrap)
            .url("https://dns.google/dns-query".toHttpUrl())
            .bootstrapDnsHosts(InetAddress.getByName("8.8.8.8"), InetAddress.getByName("8.8.4.4"))
            .build(),
    )

    /** Когда системный DNS последний раз не ответил (0 — не было такого). */
    @Volatile var systemFailedAt = 0L
        private set

    val systemDnsBroken: Boolean get() = System.currentTimeMillis() - systemFailedAt < RECHECK_MS

    override fun lookup(hostname: String): List<InetAddress> {
        // Пока системный DNS недавно ломался — сразу идём через DoH, не тратя время на ожидание
        if (!systemDnsBroken) {
            try {
                return primary.lookup(hostname)
            } catch (e: UnknownHostException) {
                systemFailedAt = System.currentTimeMillis()
            }
        }
        for (resolver in resolvers) {
            runCatching { resolver.lookup(hostname) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        // DoH тоже не помог — последняя попытка системным, чтобы вернуть его родную ошибку
        return primary.lookup(hostname)
    }

    private companion object {
        const val RECHECK_MS = 2 * 60_000L
    }
}
