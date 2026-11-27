package com.musichub.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackupCrossfadeTest {
    @Test fun settingRoundTripsAndOlderCopiesRemainReadable() {
        val settings = BackupSettings(
            searchYoutube = true, searchVk = true, fallbackToOthers = true,
            mixYoutubeInWave = false, mixEvery = 4, quality = Quality.HIGH,
            fadeSeconds = 4, autoCacheMb = 4096, waveMood = "all", waveDiversity = "default",
            ytProxyEnabled = false, ytProxyType = "SOCKS", ytProxyHost = "127.0.0.1", ytProxyPort = 10808,
            liquidGlass = true, palette = 0, coverTheme = true, crossfadeEnabled = false,
        )
        val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        val encoded = json.encodeToString(BackupSettings.serializer(), settings)
        assertEquals(false, json.decodeFromString(BackupSettings.serializer(), encoded).crossfadeEnabled)
        val original = json.parseToJsonElement(encoded) as JsonObject
        val old = JsonObject(original.filterKeys { it != "crossfadeEnabled" })
        assertNull(json.decodeFromJsonElement(BackupSettings.serializer(), old).crossfadeEnabled)
    }
}
