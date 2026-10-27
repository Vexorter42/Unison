package com.musichub.net

import android.util.Base64
import kotlinx.serialization.json.addAll
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Профиль маршрутизации для VPN-клиента Happ (https://www.happ.su/main/ru/dev-docs/routing):
 * российские сервисы (Яндекс, ВК и весь geosite:category-ru) — напрямую, всё остальное (YouTube) — через VPN.
 * Это лучший вариант: работает для всего телефона, а не только для приложения.
 */
object HappRouting {

    const val PROFILE_NAME = "Unison: RU напрямую"

    val profileJson: String by lazy {
        buildJsonObject {
            put("Name", PROFILE_NAME)
            put("GlobalProxy", "true")
            put("RemoteDNSType", "DoH")
            put("RemoteDNSDomain", "https://cloudflare-dns.com/dns-query")
            put("RemoteDNSIP", "1.1.1.1")
            put("DomesticDNSType", "DoU")
            put("DomesticDNSDomain", "")
            put("DomesticDNSIP", "77.88.8.8")
            put("Geoipurl", "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geoip.dat")
            put("Geositeurl", "https://github.com/Loyalsoldier/v2ray-rules-dat/releases/latest/download/geosite.dat")
            putJsonObject("DnsHosts") {
                put("cloudflare-dns.com", "1.1.1.1")
            }
            putJsonArray("DirectSites") {
                addAll(
                    listOf(
                        "geosite:yandex", "geosite:vk", "geosite:category-ru",
                        "domain:yandex.net", "domain:yandex.ru", "domain:ya.ru",
                        "domain:vk.com", "domain:vk.ru", "domain:userapi.com", "domain:vkuseraudio.net",
                        "domain:vk-cdn.net", "domain:vkuser.net", "domain:mycdn.me", "domain:okcdn.ru",
                    ),
                )
            }
            putJsonArray("DirectIp") {
                addAll(
                    listOf(
                        "geoip:ru", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16",
                        "169.254.0.0/16", "224.0.0.0/4", "255.255.255.255",
                    ),
                )
            }
            putJsonArray("ProxySites") { addAll(listOf("geosite:youtube", "geosite:google")) }
            put("DomainStrategy", "IPIfNonMatch")
            put("FakeDNS", "false")
        }.toString()
    }

    /** Добавляет профиль в Happ и сразу делает его активным. */
    val deeplink: String by lazy {
        "happ://routing/onadd/" + Base64.encodeToString(profileJson.toByteArray(), Base64.NO_WRAP)
    }
}
