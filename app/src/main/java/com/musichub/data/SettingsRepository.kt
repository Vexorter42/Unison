package com.musichub.data

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

@kotlinx.serialization.Serializable
enum class Quality(val label: String) {
    HIGH("Высокое"),
    NORMAL("Обычное"),
    LOW("Экономия трафика"),
}

data class AppSettings(
    val yandexToken: String? = null,
    val yandexUid: Long? = null,
    val yandexLogin: String? = null,
    val vkToken: String? = null,
    val vkUserId: Long? = null,
    val vkName: String? = null,
    val searchYoutube: Boolean = true,
    val searchVk: Boolean = true,
    /** Если трек не играет в своём сервисе — искать замену в других. */
    val fallbackToOthers: Boolean = true,
    val mixYoutubeInWave: Boolean = false,
    val mixEvery: Int = 4,
    val quality: Quality = Quality.HIGH,
    val fadeSeconds: Int = 0,
    val autoCacheMb: Int = 4096,
    val waveMood: String = "all",
    val waveDiversity: String = "default",
    /** YouTube через прокси (SOCKS5 / HTTP). */
    val ytProxyEnabled: Boolean = false,
    val ytProxyType: String = "SOCKS",
    val ytProxyHost: String = "127.0.0.1",
    val ytProxyPort: Int = 10808,
    /** Яндекс и ВК напрямую через Wi-Fi/мобильную сеть, мимо VPN. */
    val directBypassVpn: Boolean = false,
    /** Тема Liquid Glass: стекло с преломлением, плавающий бар, живой фон. */
    val liquidGlass: Boolean = true,
    /** Индекс палитры из ui.theme.Palettes. */
    val palette: Int = 0,
    /** Цвета экрана берутся из обложки текущего трека (поверх выбранной палитры). */
    val coverTheme: Boolean = true,
    /** Сессия YouTube Music (cookie после входа) и имя аккаунта. */
    val ytCookie: String? = null,
    val ytAccountName: String? = null,
) {
    val yandexLoggedIn: Boolean get() = yandexToken != null && yandexUid != null
    val vkLoggedIn: Boolean get() = vkToken != null && vkUserId != null
    val ytLoggedIn: Boolean get() = ytCookie?.contains("SAPISID") == true
}

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val yaToken = stringPreferencesKey("ya_token")
        val yaUid = longPreferencesKey("ya_uid")
        val yaLogin = stringPreferencesKey("ya_login")
        val vkToken = stringPreferencesKey("vk_token")
        val vkUid = longPreferencesKey("vk_uid")
        val vkName = stringPreferencesKey("vk_name")
        val searchYt = booleanPreferencesKey("search_yt")
        val searchVk = booleanPreferencesKey("search_vk")
        val fallback = booleanPreferencesKey("fallback")
        val mix = booleanPreferencesKey("mix_yt")
        val mixEvery = intPreferencesKey("mix_every")
        val quality = stringPreferencesKey("quality")
        val fade = intPreferencesKey("fade")
        val cacheMb = intPreferencesKey("cache_mb")
        val mood = stringPreferencesKey("wave_mood")
        val diversity = stringPreferencesKey("wave_diversity")
        val proxyOn = booleanPreferencesKey("yt_proxy_on")
        val proxyType = stringPreferencesKey("yt_proxy_type")
        val proxyHost = stringPreferencesKey("yt_proxy_host")
        val proxyPort = intPreferencesKey("yt_proxy_port")
        val bypass = booleanPreferencesKey("direct_bypass_vpn")
        val liquid = booleanPreferencesKey("liquid_glass")
        val palette = intPreferencesKey("palette")
        val coverTheme = booleanPreferencesKey("cover_theme")
        val ytCookie = stringPreferencesKey("yt_cookie")
        val ytName = stringPreferencesKey("yt_name")
    }

    // Читаем один раз при старте: настройки нужны синхронно (например, при получении ссылки на поток)
    private val state = MutableStateFlow(runBlocking { read(context.dataStore.data.first()) })
    val flow: StateFlow<AppSettings> = state.asStateFlow()
    val current: AppSettings get() = state.value

    suspend fun update(block: (AppSettings) -> AppSettings) {
        context.dataStore.edit { p ->
            val next = block(read(p))
            write(p, next)
            state.value = next
        }
    }

    private fun read(p: Preferences) = AppSettings(
        yandexToken = p[Keys.yaToken],
        yandexUid = p[Keys.yaUid],
        yandexLogin = p[Keys.yaLogin],
        vkToken = p[Keys.vkToken],
        vkUserId = p[Keys.vkUid],
        vkName = p[Keys.vkName],
        searchYoutube = p[Keys.searchYt] ?: true,
        searchVk = p[Keys.searchVk] ?: true,
        fallbackToOthers = p[Keys.fallback] ?: true,
        mixYoutubeInWave = p[Keys.mix] ?: false,
        mixEvery = p[Keys.mixEvery] ?: 4,
        quality = p[Keys.quality]?.let { runCatching { Quality.valueOf(it) }.getOrNull() } ?: Quality.HIGH,
        fadeSeconds = p[Keys.fade] ?: 0,
        autoCacheMb = p[Keys.cacheMb] ?: 4096,
        waveMood = p[Keys.mood] ?: "all",
        waveDiversity = p[Keys.diversity] ?: "default",
        ytProxyEnabled = p[Keys.proxyOn] ?: false,
        ytProxyType = p[Keys.proxyType] ?: "SOCKS",
        ytProxyHost = p[Keys.proxyHost] ?: "127.0.0.1",
        ytProxyPort = p[Keys.proxyPort] ?: 10808,
        directBypassVpn = p[Keys.bypass] ?: false,
        liquidGlass = p[Keys.liquid] ?: true,
        palette = p[Keys.palette] ?: 0,
        coverTheme = p[Keys.coverTheme] ?: true,
        ytCookie = p[Keys.ytCookie],
        ytAccountName = p[Keys.ytName],
    )

    private fun write(p: MutablePreferences, s: AppSettings) {
        p.putOrRemove(Keys.yaToken, s.yandexToken)
        p.putOrRemove(Keys.yaUid, s.yandexUid)
        p.putOrRemove(Keys.yaLogin, s.yandexLogin)
        p.putOrRemove(Keys.vkToken, s.vkToken)
        p.putOrRemove(Keys.vkUid, s.vkUserId)
        p.putOrRemove(Keys.vkName, s.vkName)
        p[Keys.searchYt] = s.searchYoutube
        p[Keys.searchVk] = s.searchVk
        p[Keys.fallback] = s.fallbackToOthers
        p[Keys.mix] = s.mixYoutubeInWave
        p[Keys.mixEvery] = s.mixEvery
        p[Keys.quality] = s.quality.name
        p[Keys.fade] = s.fadeSeconds
        p[Keys.cacheMb] = s.autoCacheMb
        p[Keys.mood] = s.waveMood
        p[Keys.diversity] = s.waveDiversity
        p[Keys.proxyOn] = s.ytProxyEnabled
        p[Keys.proxyType] = s.ytProxyType
        p[Keys.proxyHost] = s.ytProxyHost
        p[Keys.proxyPort] = s.ytProxyPort
        p[Keys.bypass] = s.directBypassVpn
        p[Keys.liquid] = s.liquidGlass
        p[Keys.palette] = s.palette
        p[Keys.coverTheme] = s.coverTheme
        p.putOrRemove(Keys.ytCookie, s.ytCookie)
        p.putOrRemove(Keys.ytName, s.ytAccountName)
    }

    private fun <T> MutablePreferences.putOrRemove(key: Preferences.Key<T>, value: T?) {
        if (value == null) remove(key) else this[key] = value
    }
}
