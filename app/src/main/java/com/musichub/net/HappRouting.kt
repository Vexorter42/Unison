package com.musichub.net

import android.util.Base64
import kotlinx.serialization.json.addAll
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Профили маршрутизации для VPN-клиента Happ (https://www.happ.su/main/ru/dev-docs/routing):
 * Яндекс и ВК — напрямую, остальное (YouTube) — через VPN. Работают для всего телефона.
 */
object HappRouting {

    const val PROFILE_NAME = "Unison: RU напрямую"
    const val SIMPLE_PROFILE_NAME = "Unison: Яндекс и ВК напрямую"

    /** Отключает маршрутизацию в Happ — если с профилем что-то пошло не так. */
    const val OFF_DEEPLINK = "happ://routing/off"

    private val privateIps = listOf("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "169.254.0.0/16", "224.0.0.0/4", "255.255.255.255")

    private val serviceDomains = listOf(
        "domain:yandex.net", "domain:yandex.ru", "domain:yandex.com", "domain:ya.ru", "domain:yastatic.net",
        "domain:vk.com", "domain:vk.ru", "domain:userapi.com", "domain:vkuseraudio.net",
        "domain:vk-cdn.net", "domain:vkuser.net", "domain:mycdn.me", "domain:okcdn.ru",
    )

    private fun profile(name: String, directSites: List<String>, directIp: List<String>, proxySites: List<String>): String =
        buildJsonObject {
            put("Name", name)
            put("GlobalProxy", "true")
            put("RemoteDNSType", "DoH")
            put("RemoteDNSDomain", "https://cloudflare-dns.com/dns-query")
            put("RemoteDNSIP", "1.1.1.1")
            put("DomesticDNSType", "DoU")
            put("DomesticDNSDomain", "")
            put("DomesticDNSIP", "77.88.8.8")
            put("Geoipurl", "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat")
            put("Geositeurl", "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat")
            putJsonObject("DnsHosts") { put("cloudflare-dns.com", "1.1.1.1") }
            putJsonArray("DirectSites") { addAll(directSites) }
            putJsonArray("DirectIp") { addAll(directIp) }
            putJsonArray("ProxySites") { addAll(proxySites) }
            put("DomainStrategy", "IPIfNonMatch")
            put("FakeDNS", "false")
        }.toString()

    /** Полный: Яндекс, ВК и все российские сайты/IP напрямую. Нужны геофайлы (~27 МБ), Happ качает их сам. */
    val profileJson: String by lazy {
        profile(
            PROFILE_NAME,
            directSites = listOf("geosite:yandex", "geosite:vk", "geosite:category-ru") + serviceDomains,
            directIp = listOf("geoip:ru") + privateIps,
            proxySites = listOf("geosite:youtube", "geosite:google"),
        )
    }

    /** Простой: только домены Яндекса и ВК, без геофайлов — заработает сразу. */
    val simpleProfileJson: String by lazy {
        profile(SIMPLE_PROFILE_NAME, directSites = serviceDomains, directIp = privateIps, proxySites = emptyList())
    }

    private fun onAdd(json: String) = "happ://routing/onadd/" + Base64.encodeToString(json.toByteArray(), Base64.NO_WRAP)

    /** Добавляют профиль в Happ и сразу делают его активным. */
    val deeplink: String by lazy { onAdd(profileJson) }
    val simpleDeeplink: String by lazy { onAdd(simpleProfileJson) }
}
