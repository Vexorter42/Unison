package com.musichub.transfer

import com.musichub.AppGraph
import com.musichub.data.model.Track
import com.musichub.search.TrackMatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class TransferTarget(val label: String) {
    YANDEX("Новый плейлист в Яндекс Музыке"),
    LOCAL("Мои плейлисты (в приложении)"),
}

sealed interface TransferState {
    data object Idle : TransferState
    data class Working(val step: String, val done: Int, val total: Int) : TransferState
    data class Done(val title: String, val found: Int, val total: Int, val missing: List<Track>, val target: TransferTarget) : TransferState
    data class Failed(val message: String) : TransferState
}

/**
 * Перенос плейлистов из YouTube / ВК в Яндекс Музыку или в свои плейлисты.
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
                if (target == TransferTarget.YANDEX && !graph.settings.current.yandexLoggedIn) {
                    error("Сначала войдите в Яндекс в настройках")
                }
                _state.value = TransferState.Working("Загружаю плейлист…", 0, 0)
                val (name, tracks) = load()
                if (tracks.isEmpty()) error("Плейлист пустой или недоступен")

                val matched = linkedMapOf<Int, Track>()
                tracks.forEachIndexed { i, t ->
                    _state.value = TransferState.Working("Ищу треки в Яндекс Музыке", i, tracks.size)
                    findOnYandex(t)?.let { matched[i] = it }
                    delay(120) // не долбим API слишком часто
                }
                val missing = tracks.filterIndexed { i, _ -> i !in matched }

                _state.value = TransferState.Working("Сохраняю…", tracks.size, tracks.size)
                when (target) {
                    TransferTarget.YANDEX -> graph.yandex.createPlaylist(name, matched.values.toList())
                    TransferTarget.LOCAL -> {
                        // Сохраняем порядок; что не нашлось в Яндексе — остаётся из исходного сервиса
                        val ordered = tracks.mapIndexed { i, t -> matched[i] ?: t }
                        val id = graph.library.create(name)
                        graph.library.add(id, ordered)
                    }
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
        val q = TrackMatcher.splitYoutube(t)
        val results = runCatching { graph.yandex.search("${q.artists} ${q.title}") }.getOrNull() ?: return null
        return TrackMatcher.best(q, results, threshold = 0.65)
    }
}
