package com.musichub.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.musichub.ui.GlassButton
import com.musichub.ui.LocalEngine
import com.musichub.ui.LocalGraph
import kotlinx.coroutines.launch

/** «Любимое Unison»: свои лайки из всех сервисов; в Яндекс уходят только по кнопке. */
@Composable
fun FavoritesScreen(onBack: (() -> Unit)?) {
    val graph = LocalGraph.current
    val engine = LocalEngine.current
    val items by graph.favorites.items.collectAsState()
    val settings by graph.settings.flow.collectAsState()
    var sending by remember { mutableStateOf<String?>(null) }
    val unsent = items.count { !it.sentYandex }

    TrackListLayout(
        title = "Любимое Unison",
        subtitle = "Ваши лайки из всех сервисов",
        header = { FavoritesHeaderCover() },
        tracks = items.map { it.track },
        loading = false,
        error = null,
        onBack = onBack,
        extraActions = {
            if (settings.yandexLoggedIn && unsent > 0) {
                GlassButton(
                    sending ?: "В Яндекс ($unsent)",
                    Icons.AutoMirrored.Rounded.Send,
                    enabled = sending == null,
                    onClick = {
                        graph.scope.launch {
                            val r = graph.favorites.sendToYandex { done, total -> sending = "Отправляю $done из $total" }
                            sending = null
                            engine.notify(
                                if (r.notFound.isEmpty()) "Отправлено в «Мне нравится»: ${r.sent}"
                                else "Отправлено: ${r.sent}, не нашлось в Яндексе: ${r.notFound.size}",
                            )
                        }
                    },
                )
            }
        },
    )
}
