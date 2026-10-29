package com.musichub.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musichub.ui.screens.FavoritesScreen
import com.musichub.ui.screens.HomeScreen
import com.musichub.ui.screens.LibraryScreen
import com.musichub.ui.screens.SearchScreen
import com.musichub.ui.screens.SearchState
import com.musichub.ui.screens.SettingsScreen
import com.musichub.ui.screens.TrackListScreen
import com.musichub.ui.screens.TrackSource
import com.musichub.ui.theme.LocalPalette
import com.musichub.ui.theme.UnisonTheme

private enum class Tab(val title: String, val icon: ImageVector) {
    HOME("Главная", Icons.Rounded.Home),
    SEARCH("Поиск", Icons.Rounded.Search),
    LIBRARY("Медиатека", Icons.Rounded.LibraryMusic),
    FAVORITES("Любимое", Icons.Rounded.Favorite),
    SETTINGS("Настройки", Icons.Rounded.Settings),
}

@Composable
fun UnisonApp() {
    val engine = LocalEngine.current
    val state by engine.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.HOME) }
    /** Открытый плейлист поверх вкладки «Медиатека». */
    var opened by remember { mutableStateOf<TrackSource?>(null) }
    var panel by remember { mutableStateOf(SidePanel.NONE) }
    val search = remember { SearchState() }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        engine.messages.collect { snackbar.showSnackbar(it) }
    }

    UnisonTheme(coverUrl = state.track?.coverUrl) {
        Box(Modifier.fillMaxSize()) {
            CoverBackdrop(state.track?.coverUrl)
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    Sidebar(tab) {
                        tab = it
                        opened = null
                    }
                    Spacer(Modifier.width(12.dp))
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                        Crossfade(tab to opened) { (t, o) ->
                            when {
                                o != null -> TrackListScreen(o, onBack = { opened = null })
                                t == Tab.HOME -> HomeScreen(openSettings = { tab = Tab.SETTINGS })
                                t == Tab.SEARCH -> SearchScreen(search)
                                t == Tab.LIBRARY -> LibraryScreen(
                                    open = { opened = it },
                                    openFavorites = { tab = Tab.FAVORITES },
                                    openSettings = { tab = Tab.SETTINGS },
                                )
                                t == Tab.FAVORITES -> FavoritesScreen(onBack = null)
                                else -> SettingsScreen()
                            }
                        }
                    }
                    AnimatedVisibility(panel != SidePanel.NONE) {
                        Row {
                            Spacer(Modifier.width(12.dp))
                            when (panel) {
                                SidePanel.LYRICS -> LyricsPanel(Modifier.width(380.dp))
                                else -> QueuePanel(Modifier.width(380.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                PlayerBar(panel, onPanel = { panel = it })
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = 116.dp)) { data ->
                Snackbar(data, containerColor = Color(0xFF26262E), contentColor = Color.White, shape = RoundedCornerShape(14.dp))
            }
        }
    }
}

@Composable
private fun Sidebar(selected: Tab, onSelect: (Tab) -> Unit) {
    val palette = LocalPalette.current
    Column(
        Modifier
            .width(220.dp)
            .fillMaxHeight()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xFF16161B).copy(alpha = 0.55f))
            .background(glassFill())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 6.dp, top = 6.dp, bottom = 18.dp)) {
            Box(Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)).background(palette.gradient))
            Spacer(Modifier.width(10.dp))
            Text("Unison", fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
        }
        Tab.entries.forEach { t ->
            val hover = remember { MutableInteractionSource() }
            val hovered by hover.collectIsHoveredAsState()
            val isSelected = t == selected
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isSelected) glassFill(strong = true) else if (hovered) glassFill() else Color.Transparent)
                    .hoverable(hover)
                    .clickable { onSelect(t) }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    t.icon, null,
                    tint = if (isSelected) palette.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    t.title,
                    fontSize = 14.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
