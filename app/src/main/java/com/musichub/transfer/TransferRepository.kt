package com.musichub.transfer

import com.musichub.AppGraph
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import com.musichub.search.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TransferTarget(val label: String, val service: String) {
    YANDEX("Новый плейлист в Яндекс Музыке", "Яндекс Музыке"),
    YOUTUBE("Новый плейлист в YouTube Music", "YouTube Music"),
    LOCAL("Мои плейлисты (в приложении)", "Unison"),
}

sealed interface TransferState {
    data object Idle : TransferState
    data class Working(val step: String, val done: Int, val total: Int) : TransferState
    data class Done(val title: String, val found: Int, val total: Int, val missing: List<Track>, val target: TransferTarget) : TransferState
    data class Failed(val message: String) : TransferState
}

/**
 * Перенос плейлистов из любого сервиса в любой: Яндекс, YouTube Music или свои плейлисты.
 * Каждый трек ищется в целевом сервисе по названию, исполнителю и длительности.
 * Работает в фоне приложения — можно уйти с экрана, прогресс сохранится.
 */
class TransferRepository(private val graph: AppGraph) {

    private val _state = MutableStateFlow<TransferState>(TransferState.Idle)
    val state: StateFlow<TransferState> = _state.asStateFlow()
    private var job: Job? = null

    fun start(target: TransferTarget, load: suspend () -> Pair<String, List<Track>>) {
        job?.cancel()
        job = graph.scope.launch {
            try {
                val s = graph.settings.current
                if (target == TransferTarget.YANDEX && !s.yandexLoggedIn) error("Сначала войдите в Яндекс в настройках")
                if (target == TransferTarget.YOUTUBE && !s.ytLoggedIn) error("Сначала войдите в YouTube в настройках")
                _state.value = TransferState.Working("Загружаю плейлист…", 0, 0)
                val (name, tracks) = load()
                if (tracks.isEmpty()) error("Плейлист пустой или недоступен")

                // В свои плейлисты можно класть треки любого сервиса — ничего искать не нужно
                if (target == TransferTarget.LOCAL) {
                    val id = graph.library.create(name)
                    graph.library.add(id, tracks)
                    _state.value = TransferState.Done(name, tracks.size, tracks.size, emptyList(), target)
                    return@launch
                }

                val matched = linkedMapOf<Int, Track>()
                tracks.forEachIndexed { i, t ->
                    _state.value = TransferState.Working("Ищу треки в ${target.service}", i, tracks.size)
                    val found = when (target) {
                        TransferTarget.YANDEX -> findOnYandex(t)
                        TransferTarget.YOUTUBE -> findOnYoutube(t)
                        TransferTarget.LOCAL -> t
                    }
                    found?.let { matched[i] = it }
                    if (found?.source != t.source) delay(120) // не долбим поиск слишком часто
                }
                val missing = tracks.filterIndexed { i, _ -> i !in matched }

                _state.value = TransferState.Working("Сохраняю…", tracks.size, tracks.size)
                when (target) {
                    TransferTarget.YANDEX -> graph.yandex.createPlaylist(name, matched.values.toList())
                    TransferTarget.YOUTUBE -> graph.ytMusic.createPlaylist(name, matched.values.map { it.sourceId })
                    TransferTarget.LOCAL -> Unit
                }
                _state.value = TransferState.Done(name, matched.size, tracks.size, missing, target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = TransferState.Failed(e.message ?: "Ошибка")
            }
        }
    }

    fun reset() {
        job?.cancel()
        _state.value = TransferState.Idle
    }

    private suspend fun findOnYandex(t: Track): Track? {
        if (t.source == Source.YANDEX && ':' in t.sourceId) return t
        t.alternates.firstOrNull { it.source == Source.YANDEX && ':' in it.sourceId }?.let {
            return t.copy(source = Source.YANDEX, sourceId = it.sourceId, alternates = emptyList())
        }
        val q = TrackMatcher.splitYoutube(t)
        val results = runCatching { graph.yandex.search("${q.artists} ${q.title}") }.getOrNull() ?: return null
        return TrackMatcher.best(q, results, threshold = 0.65)
    }

    private suspend fun findOnYoutube(t: Track): Track? {
        if (t.source == Source.YOUTUBE) return t
        t.alternates.firstOrNull { it.source == Source.YOUTUBE }?.let {
            return t.copy(source = Source.YOUTUBE, sourceId = it.sourceId, alternates = emptyList())
        }
        return runCatching { graph.youtube.findMatch(t) }.getOrNull()
    }
}
