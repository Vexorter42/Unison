package com.musichub.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.musichub.App
import com.musichub.ui.icons.UnisonIcons
import com.musichub.ui.liquid.AmbientBackground
import com.musichub.ui.liquid.LiquidNavBar
import com.musichub.ui.liquid.LocalBottomInset
import com.musichub.ui.liquid.LocalLiquidGlass
import com.musichub.ui.liquid.NavItem
import com.musichub.ui.screens.EqualizerScreen
import com.musichub.ui.screens.HomeScreen
import com.musichub.ui.screens.LibraryScreen
import com.musichub.ui.screens.LoginScreen
import com.musichub.ui.screens.PlayerScreen
import com.musichub.ui.screens.SearchScreen
import com.musichub.ui.screens.SettingsScreen
import com.musichub.ui.screens.StatsScreen
import com.musichub.ui.screens.TrackListScreen
import com.musichub.ui.screens.TransferScreen
import kotlinx.coroutines.launch

private val tabRoutes = listOf("home", "search", "library", "stats", "settings")
private val navItems = listOf(
    NavItem("Главная", UnisonIcons.Home, UnisonIcons.HomeActive),
    NavItem("Поиск", UnisonIcons.Search, UnisonIcons.SearchActive),
    NavItem("Медиатека", UnisonIcons.Library, UnisonIcons.LibraryActive),
    NavItem("Статистика", UnisonIcons.Stats, UnisonIcons.StatsActive),
    NavItem("Настройки", UnisonIcons.Settings, UnisonIcons.SettingsActive),
)

@Composable
fun MusicHubApp() {
    val graph = App.graph
    val nav = rememberNavController()
    val engine = graph.engine
    val settings by graph.settings.flow.collectAsStateWithLifecycle()
    val playerState by engine.state.collectAsStateWithLifecycle()
    val downloadBatch by engine.downloadBatch.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        graph.updater.checkOnStartup()
        engine.messages.collect { msg -> launch { snackbar.showSnackbar(msg) } }
    }
    UpdateDialog()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val tabIndex = tabRoutes.indexOf(route)
    val isTab = tabIndex >= 0
    val fullScreen = route == "player" || route?.startsWith("login") == true
    val showMini = !fullScreen && playerState.track != null
    val showBar = !fullScreen && isTab
    val showDownloads = !fullScreen && downloadBatch != null

    // Всё, что под плавающими элементами, записывается в этот слой — из него стекло берёт картинку
    val backdrop = rememberLayerBackdrop()
    val systemNav = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomInset = systemNav + 12.dp + (if (showBar) 74.dp else 0.dp) + (if (showMini) 70.dp else 0.dp) + (if (showDownloads) 58.dp else 0.dp)

    CompositionLocalProvider(
        LocalLiquidGlass provides settings.liquidGlass,
        LocalBottomInset provides bottomInset,
        // Раньше цвет текста задавал Scaffold; теперь задаём сами, иначе он чёрный
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
    ) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                if (settings.liquidGlass) AmbientBackground()
                else Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
                NavHost(
                    nav,
                    startDestination = "home",
                    modifier = Modifier.fillMaxSize(),
                    enterTransition = { fadeIn(tween(180)) },
                    exitTransition = { fadeOut(tween(140)) },
                ) {
                    composable("home") { HomeScreen(nav) }
                    composable("search") { SearchScreen() }
                    composable("library") { LibraryScreen(nav) }
                    composable("stats") { StatsScreen() }
                    composable("settings") { SettingsScreen(nav) }
                    composable(
                        "player",
                        enterTransition = { slideInVertically(tween(320)) { it } },
                        // Закрывается сам свайпом/анимацией, поэтому здесь только быстрый fade
                        popExitTransition = { fadeOut(tween(90)) },
                    ) { PlayerScreen(onBack = { nav.popBackStack() }) }
                    composable("login/{service}") { e ->
                        LoginScreen(e.arguments?.getString("service") ?: "ya", onDone = { nav.popBackStack() })
                    }
                    composable("tracks/{kind}/{arg}/{title}") { e ->
                        val args = e.arguments
                        TrackListScreen(
                            kind = args?.getString("kind").orEmpty(),
                            arg = args?.getString("arg").orEmpty(),
                            title = args?.getString("title").orEmpty(),
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("transfer") { TransferScreen(onBack = { nav.popBackStack() }) }
                    composable("equalizer") { EqualizerScreen(onBack = { nav.popBackStack() }) }
                }
            }

            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 14.dp, end = 14.dp, bottom = 8.dp),
            ) {
                if (showDownloads) DownloadPill(backdrop)
                if (showMini) MiniPlayer(onOpen = { nav.navigate("player") }, backdrop = backdrop)
                if (showBar) {
                    LiquidNavBar(
                        items = navItems,
                        selected = tabIndex,
                        onSelect = { i ->
                            nav.navigate(tabRoutes[i]) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        backdrop = backdrop,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset))
        }
    }
}
