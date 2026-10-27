package com.musichub.playback

import android.content.Context
import android.media.audiofx.Equalizer
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class EqBand(val centerHz: Int, val level: Int)

data class EqState(
    val available: Boolean = false,
    val enabled: Boolean = false,
    val bands: List<EqBand> = emptyList(),
    val presets: List<String> = emptyList(),
    val preset: Int = -1,
    val minLevel: Int = -1500,
    val maxLevel: Int = 1500,
)

/** Системный эквалайзер Android, привязанный к аудиосессии плеера. Настройки сохраняются. */
class EqualizerController(context: Context) {

    private val prefs = context.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
    private var eq: Equalizer? = null
    private val _state = MutableStateFlow(EqState())
    val state: StateFlow<EqState> = _state.asStateFlow()

    fun attach(sessionId: Int) {
        runCatching { eq?.release() }
        eq = null
        if (sessionId != 0) {
            eq = runCatching { Equalizer(0, sessionId) }.getOrNull()?.also { e ->
                val preset = prefs.getInt("preset", -1)
                if (preset in 0 until e.numberOfPresets) {
                    e.usePreset(preset.toShort())
                } else {
                    for (i in 0 until e.numberOfBands) {
                        val level = prefs.getInt("band_$i", Int.MIN_VALUE)
                        if (level != Int.MIN_VALUE) e.setBandLevel(i.toShort(), level.toShort())
                    }
                }
                e.enabled = prefs.getBoolean("enabled", false)
            }
        }
        publish()
    }

    fun setEnabled(on: Boolean) {
        runCatching { eq?.enabled = on }
        prefs.edit { putBoolean("enabled", on) }
        publish()
    }

    fun usePreset(index: Int) {
        runCatching { eq?.usePreset(index.toShort()) }
        prefs.edit { putInt("preset", index) }
        publish()
    }

    fun setBand(index: Int, level: Int) {
        runCatching { eq?.setBandLevel(index.toShort(), level.toShort()) }
        prefs.edit {
            putInt("preset", -1)
            putInt("band_$index", level)
        }
        publish()
    }

    private fun publish() {
        val e = eq ?: run {
            _state.value = EqState()
            return
        }
        _state.value = runCatching {
            val range = e.bandLevelRange
            EqState(
                available = true,
                enabled = e.enabled,
                bands = (0 until e.numberOfBands).map { i ->
                    EqBand(e.getCenterFreq(i.toShort()) / 1000, e.getBandLevel(i.toShort()).toInt())
                },
                presets = (0 until e.numberOfPresets).map { e.getPresetName(it.toShort()) },
                preset = prefs.getInt("preset", -1),
                minLevel = range[0].toInt(),
                maxLevel = range[1].toInt(),
            )
        }.getOrDefault(EqState())
    }
}
