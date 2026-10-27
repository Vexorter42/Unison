@file:OptIn(ExperimentalMaterial3Api::class)

package com.musichub.ui.screens

import com.musichub.ui.liquid.LocalBottomInset
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import com.musichub.ui.liquid.LiquidSwitch
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.musichub.App

@Composable
fun EqualizerScreen(onBack: () -> Unit) {
    val eq = App.graph.equalizer
    val state by eq.state.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
            title = { Text("Эквалайзер") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Назад") } },
            actions = { Box(Modifier.padding(end = 12.dp)) { LiquidSwitch(checked = state.enabled, onCheckedChange = eq::setEnabled, enabled = state.available) } },
        )
        if (!state.available) {
            Text(
                "Эквалайзер появится, когда начнёт играть музыка (или устройство его не поддерживает).",
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return
        }
        Column(Modifier.verticalScroll(rememberScrollState()).padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = LocalBottomInset.current + 16.dp)) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.presets.forEachIndexed { i, name ->
                    FilterChip(selected = state.preset == i, onClick = { eq.usePreset(i) }, label = { Text(name) }, enabled = state.enabled)
                }
            }
            state.bands.forEachIndexed { i, band ->
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (band.centerHz >= 1000) "${band.centerHz / 1000} кГц" else "${band.centerHz} Гц", modifier = Modifier.width(72.dp))
                    Slider(
                        value = band.level.toFloat(),
                        onValueChange = { eq.setBand(i, it.toInt()) },
                        valueRange = state.minLevel.toFloat()..state.maxLevel.toFloat(),
                        enabled = state.enabled,
                        modifier = Modifier.weight(1f),
                    )
                    Text("${"%+.0f".format(band.level / 100f)} дБ", modifier = Modifier.width(56.dp))
                }
            }
        }
    }
}
