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
    data class Review(val title: String, val target: TransferTarget, val rows: List<TransferRow>) : TransferState
    data class Done(val title: String, val found: Int, val total: Int, val missing: List<Track>, val target: TransferTarget) : TransferState
    data class Failed(val message: String) : TransferState
}

data class TransferCandidate(val track: Track, val confidence: Double)
data class TransferRow(val original: Track, val candidates: List<TransferCandidate>, val selectedKey: String?) {
    val selected: Track? get() = candidates.firstOrNull { it.track.key == selectedKey }?.track
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

                val rows = mutableListOf<TransferRow>()
                tracks.forEachIndexed { i, t ->
                    _state.value = TransferState.Working("Ищу треки в ${target.service}", i, tracks.size)
                    val candidates = when (target) {
                        TransferTarget.YANDEX -> findOnYandex(t)
                        TransferTarget.YOUTUBE -> findOnYoutube(t)
                        TransferTarget.LOCAL -> listOf(TransferCandidate(t, 1.0))
                    }
                    val selected = candidates.firstOrNull()?.takeIf { it.confidence >= AUTO_SELECT_CONFIDENCE }?.track?.key
                    rows += TransferRow(t, candidates, selected)
                    if (candidates.firstOrNull()?.track?.source != t.source) delay(120) // не долбим поиск слишком часто
                }
                _state.value = TransferState.Review(name, target, rows)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = TransferState.Failed(e.message ?: "Ошибка")
            }
        }
    }

    fun selectCandidate(rowIndex: Int, key: String?) {
        val review = _state.value as? TransferState.Review ?: return
        val rows = review.rows.toMutableList()
        val row = rows.getOrNull(rowIndex) ?: return
        if (key != null && row.candidates.none { it.track.key == key }) return
        rows[rowIndex] = row.copy(selectedKey = key)
        _state.value = review.copy(rows = rows)
    }

    fun confirm() {
        val review = _state.value as? TransferState.Review ?: return
        job = graph.scope.launch {
            try {
                val chosen = review.rows.mapNotNull { it.selected }
                val unique = chosen.distinctBy { if (review.target == TransferTarget.YOUTUBE) it.sourceId else it.key }
                if (unique.isEmpty()) error("Выбери хотя бы один трек для переноса")
                _state.value = TransferState.Working("Создаю плейлист…", unique.size, unique.size)
                when (review.target) {
                    TransferTarget.YANDEX -> graph.yandex.createPlaylist(review.title, unique)
                    TransferTarget.YOUTUBE -> graph.ytMusic.createPlaylist(review.title, unique.map { it.sourceId })
                    TransferTarget.LOCAL -> Unit
                }
                val chosenKeys = unique.map { it.key }.toSet()
                val missing = review.rows.filter { row -> row.selected?.key !in chosenKeys }.map { it.original }
                _state.value = TransferState.Done(review.title, unique.size, review.rows.size, missing, review.target)
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

    private suspend fun findOnYandex(t: Track): List<TransferCandidate> {
        if (t.source == Source.YANDEX && ':' in t.sourceId) return listOf(TransferCandidate(t, 1.0))
        t.alternates.firstOrNull { it.source == Source.YANDEX && ':' in it.sourceId }?.let {
            return listOf(TransferCandidate(t.copy(source = Source.YANDEX, sourceId = it.sourceId, alternates = emptyList()), 1.0))
        }
        val q = TrackMatcher.splitYoutube(t)
        val results = runCatching { graph.yandex.search("${q.artists} ${q.title}") }.getOrNull().orEmpty()
        return rank(q, results)
    }

    private suspend fun findOnYoutube(t: Track): List<TransferCandidate> {
        if (t.source == Source.YOUTUBE) return listOf(TransferCandidate(t, 1.0))
        t.alternates.firstOrNull { it.source == Source.YOUTUBE }?.let {
            return listOf(TransferCandidate(t.copy(source = Source.YOUTUBE, sourceId = it.sourceId, alternates = emptyList()), 1.0))
        }
        val q = TrackMatcher.splitYoutube(t)
        val results = runCatching { graph.youtube.search("${q.artists} ${q.title}") }.getOrNull().orEmpty()
        return rank(q, results)
    }

    private fun rank(query: Track, candidates: List<Track>): List<TransferCandidate> =
        candidates.asSequence().map { TransferCandidate(it, TrackMatcher.score(query, it)) }
            .filter { it.confidence >= MIN_CANDIDATE_CONFIDENCE }
            .distinctBy { it.track.key }
            .sortedByDescending { it.confidence }
            .take(MAX_CANDIDATES)
            .toList()

    companion object {
        private const val AUTO_SELECT_CONFIDENCE = 0.82
        private const val MIN_CANDIDATE_CONFIDENCE = 0.35
        private const val MAX_CANDIDATES = 5
    }
}
