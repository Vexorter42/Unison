package com.musichub.playback

import com.musichub.data.Favorites
import com.musichub.data.SettingsRepository
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import com.musichub.search.TrackMatcher
import com.musichub.yandex.YandexApi
import com.musichub.youtube.YouTubeApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import java.util.concurrent.Executors

enum class RepeatMode { OFF, ALL, ONE }

data class PlayerState(
    val track: Track? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val hasNext: Boolean = false,
    val waveMode: Boolean = false,
    /** «Моя волна», «Радио: …» или null, если играет обычная очередь. */
    val radioTitle: String? = null,
    val viaFallback: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val volume: Int = 80,
    val queue: List<Track> = emptyList(),
    val index: Int = -1,
)

/**
 * Сердце плеера: libVLC + очередь + Моя волна с отзывами Яндексу + замена источника, если трек не играет.
 * Вся логика крутится на одном потоке [engineThread], чтобы не ловить гонок с событиями VLC.
 */
class PlaybackEngine(
    vlcFactory: MediaPlayerFactory,
    private val settings: SettingsRepository,
    private val resolver: StreamResolver,
    private val proxy: StreamProxy,
    private val yandex: YandexApi,
    private val youtube: YouTubeApi,
    private val favorites: Favorites,
) {
    private val engineThread = Executors.newSingleThreadExecutor { r -> Thread(r, "playback").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + engineThread)

    private val player: MediaPlayer = vlcFactory.mediaPlayers().newMediaPlayer()

    private val _state = MutableStateFlow(PlayerState(volume = settings.current.volume))
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    // ---------- Очередь ----------

    private var queue: List<Track> = emptyList()
    /** Порядок воспроизведения: индексы [queue] (при перемешивании — случайный). */
    private var order: List<Int> = emptyList()
    private var pos = -1
    private var shuffle = false
    private var repeat = RepeatMode.OFF
    private var loadJob: Job? = null
    /** Адрес потока, который сейчас открыт в VLC. */
    private var currentMrl: String? = null
    private var viaFallback = false
    /** Попытка проиграть текущий трек: 0 — свой сервис, 1 — уже пробуем замену. */
    private var attempt = 0
    private var consecutiveErrors = 0
    private var buffering = false

    // ---------- Моя волна ----------

    private var waveMode = false
    /** Станция Яндекса текущего радио (null — радио из похожих на YouTube) и его название. */
    private var radioStation: String? = null
    private var radioTitle: String? = null
    private var waveBatchId: String? = null
    private var waveLoading = false
    private val recentKeys = ArrayDeque<String>()

    // ---------- Текущее прослушивание (для отзывов волне) ----------

    private var cur: Track? = null
    private var curPlayedMs = 0L
    private var curFromWave = false
    private var curStation: String? = null

    private val current: Track? get() = order.getOrNull(pos)?.let { queue.getOrNull(it) }

    init {
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) = post {
                buffering = false
                consecutiveErrors = 0
                applyVolume()
                publish()
            }

            override fun paused(mediaPlayer: MediaPlayer) = post { publish() }
            override fun stopped(mediaPlayer: MediaPlayer) = post { publish() }
            override fun buffering(mediaPlayer: MediaPlayer, newCache: Float) = post {
                val b = newCache < 100f
                if (b != buffering) {
                    buffering = b
                    publish()
                }
            }

            // События старого трека могут прийти, когда уже грузится следующий, — их пропускаем
            override fun finished(mediaPlayer: MediaPlayer) = post { if (isCurrentMedia()) onTrackEnded() }
            override fun error(mediaPlayer: MediaPlayer) = post { if (isCurrentMedia()) onTrackError() }
        })
        scope.launch { tickLoop() }
    }

    private fun isCurrentMedia(): Boolean = currentMrl != null && player.media().info()?.mrl() == currentMrl

    private fun post(block: () -> Unit) {
        scope.launch { block() }
    }

    // ---------- Управление ----------

    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) = post {
        if (tracks.isEmpty()) return@post
        stopRadio()
        this.shuffle = shuffle
        queue = tracks
        val start = if (shuffle) tracks.indices.random() else startIndex.coerceIn(tracks.indices)
        if (shuffle) {
            order = listOf(start) + tracks.indices.filter { it != start }.shuffled()
            load(0)
        } else {
            order = tracks.indices.toList()
            load(start)
        }
    }

    fun playNext(t: Track) = post {
        if (queue.isEmpty()) return@post playNow(listOf(t))
        insertAfterCurrent(t)
        _messages.tryEmit("Следующим: ${t.title}")
        publish()
    }

    fun enqueue(t: Track) = post {
        if (queue.isEmpty()) return@post playNow(listOf(t))
        queue = queue + t
        order = order + queue.lastIndex
        _messages.tryEmit("Добавлено в очередь: ${t.title}")
        publish()
    }

    private fun playNow(tracks: List<Track>) {
        queue = tracks
        order = tracks.indices.toList()
        load(0)
    }

    private fun insertAfterCurrent(t: Track) {
        queue = queue + t
        order = order.toMutableList().apply { add((pos + 1).coerceAtMost(size), queue.lastIndex) }
    }

    fun togglePlay() = post {
        when {
            player.status().isPlaying -> player.controls().setPause(true)
            current == null -> Unit
            player.status().state() == uk.co.caprica.vlcj.player.base.State.PAUSED -> player.controls().setPause(false)
            else -> load(pos)
        }
        publish()
    }

    fun next() = post {
        finishCurrent()
        advance(userSkip = true)
    }

    fun previous() = post {
        if (player.status().time() > 3000 || pos <= 0) {
            player.controls().setTime(0)
        } else {
            finishCurrent()
            load(pos - 1)
        }
    }

    fun seekTo(ms: Long) = post {
        player.controls().setTime(ms.coerceAtLeast(0))
        _state.update { it.copy(positionMs = ms) }
    }

    /** Перейти к треку очереди (индекс в порядке воспроизведения, как в [PlayerState.queue]). */
    fun skipTo(index: Int) = post {
        if (index !in order.indices) return@post
        finishCurrent()
        load(index)
    }

    fun setVolume(v: Int) = post {
        val vol = v.coerceIn(0, 100)
        _state.update { it.copy(volume = vol) }
        applyVolume()
        scope.launch(Dispatchers.IO) { settings.update { it.copy(volume = vol) } }
    }

    fun toggleShuffle() = post {
        if (waveMode) return@post
        shuffle = !shuffle
        val curIdx = order.getOrNull(pos)
        order = if (shuffle) {
            listOfNotNull(curIdx) + queue.indices.filter { it != curIdx }.shuffled()
        } else {
            queue.indices.toList()
        }
        pos = curIdx?.let { order.indexOf(it) } ?: -1
        publish()
    }

    fun cycleRepeat() = post {
        repeat = when (repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        publish()
    }

    fun notify(message: String) {
        _messages.tryEmit(message)
    }

    // ---------- Моя волна и радио ----------

    fun startWave() = startYandexStation(YandexApi.WAVE, "Моя волна", seed = null)

    /**
     * Бесконечное радио «похожее на этот трек». Для Яндекса — его станция по треку (учится на пропусках),
     * для остального трек сначала ищется в Яндексе, иначе — похожие с YouTube.
     */
    fun startRadio(track: Track) = post {
        val title = "Радио: ${track.title}"
        scope.launch {
            val loggedIn = settings.current.yandexLoggedIn
            val yandexId = when {
                track.source == Source.YANDEX -> track.yandexId
                !loggedIn -> null
                else -> track.alternates.firstOrNull { it.source == Source.YANDEX }?.sourceId?.substringBefore(':')
                    ?: findOnYandex(track)?.yandexId
            }
            if (yandexId != null && loggedIn) startYandexStation("track:$yandexId", title, seed = track)
            else startYoutubeRadio(track, title)
        }
    }

    private fun startYandexStation(station: String, title: String, seed: Track?) = post {
        if (!settings.current.yandexLoggedIn) {
            _messages.tryEmit("Войдите в Яндекс, чтобы включить ${if (seed == null) "Мою волну" else "радио"}")
            return@post
        }
        scope.launch {
            try {
                val batch = yandex.stationTracks(station, seed?.yandexId)
                if (batch.tracks.isEmpty()) {
                    _messages.tryEmit("Станция вернула пустой список")
                    return@launch
                }
                finishCurrent()
                beginRadio(station, title, batch.batchId)
                scope.launch { yandex.waveFeedback("radioStarted", batch.batchId, station = station) }
                setRadioQueue(listOfNotNull(seed) + batch.tracks.filter { it.key != seed?.key })
            } catch (e: Exception) {
                _messages.tryEmit("Не удалось запустить радио: ${e.message}")
            }
        }
    }

    private suspend fun startYoutubeRadio(seed: Track, title: String) {
        try {
            val videoId = if (seed.source == Source.YOUTUBE) seed.sourceId
            else seed.alternates.firstOrNull { it.source == Source.YOUTUBE }?.sourceId ?: youtube.findMatch(seed)?.sourceId
            val related = videoId?.let { youtube.related(it) }.orEmpty()
            if (related.isEmpty()) {
                _messages.tryEmit("Не нашёл похожих треков для радио")
                return
            }
            finishCurrent()
            beginRadio(station = null, title = title, batchId = null)
            setRadioQueue(listOf(seed) + related.filter { it.key != seed.key }.distinctBy { it.key })
        } catch (e: Exception) {
            _messages.tryEmit("Не удалось запустить радио: ${e.message}")
        }
    }

    private fun beginRadio(station: String?, title: String, batchId: String?) {
        waveMode = true
        radioStation = station
        radioTitle = title
        waveBatchId = batchId
    }

    private fun stopRadio() {
        finishCurrent()
        waveMode = false
        radioStation = null
        radioTitle = null
    }

    private fun setRadioQueue(tracks: List<Track>) {
        shuffle = false
        repeat = RepeatMode.OFF
        queue = tracks
        order = tracks.indices.toList()
        load(0)
    }

    private fun maybeRefillWave() {
        if (!waveMode || waveLoading || order.size - pos > 3) return
        waveLoading = true
        scope.launch {
            try {
                val station = radioStation
                val fresh = if (station != null) {
                    val lastYandex = queue.lastOrNull { it.source == Source.YANDEX }
                    val batch = yandex.stationTracks(station, lastYandex?.yandexId)
                    waveBatchId = batch.batchId ?: waveBatchId
                    batch.tracks
                } else {
                    // Радио на YouTube: продолжаем от последнего трека очереди
                    val last = queue.lastOrNull { it.source == Source.YOUTUBE } ?: return@launch
                    youtube.related(last.sourceId).take(10)
                }
                if (!waveMode) return@launch
                val skip = queue.mapTo(HashSet()) { it.key } + recentKeys
                val add = fresh.filter { it.key !in skip }.distinctBy { it.key }
                val base = queue.size
                queue = queue + add
                order = order + add.indices.map { base + it }
                publish()
            } catch (e: Exception) {
                _messages.tryEmit("Радио: ${e.message}")
            } finally {
                waveLoading = false
            }
        }
    }

    private suspend fun findOnYandex(t: Track): Track? {
        val q = TrackMatcher.splitYoutube(t)
        val results = runCatching { yandex.search("${q.artists} ${q.title}") }.getOrNull() ?: return null
        return TrackMatcher.best(q, results, threshold = 0.65)
    }

    // ---------- Лайки ----------

    /** Сердечко кладёт трек в «Любимое Unison» (или убирает). В сервисы ничего не уходит. */
    fun toggleLike(track: Track? = null) {
        val t = track ?: _state.value.track ?: return
        scope.launch {
            val added = favorites.toggle(t)
            _messages.tryEmit(if (added) "Добавлено в «Любимое»" else "Убрано из «Любимого»")
        }
    }

    /** «Не нравится»: дизлайк в Яндексе (волна учтёт) и следующий трек. */
    fun dislike() = post {
        val t = current ?: return@post
        if (t.source == Source.YANDEX && settings.current.yandexLoggedIn) {
            scope.launch { runCatching { yandex.dislike(t) } }
        }
        finishCurrent()
        advance(userSkip = true)
    }

    // ---------- Внутреннее ----------

    /** Загрузить и проиграть трек на позиции [index] порядка воспроизведения. */
    private fun load(index: Int, skipPrimary: Boolean = false) {
        attempt = if (skipPrimary) 1 else 0
        pos = if (index in order.indices) index else 0
        val t = current ?: return
        if (cur?.key != t.key) beginCurrent(t)
        loadJob?.cancel()
        currentMrl = null
        player.controls().stop()
        buffering = true
        viaFallback = false
        publish()
        maybeRefillWave()
        loadJob = scope.launch {
            val result = withContext(Dispatchers.IO) {
                if (skipPrimary) resolver.forget(t)
                runCatching { resolver.resolve(t, skipPrimary) }
            }
            if (current?.key != t.key) return@launch
            result.onSuccess { stream ->
                viaFallback = stream.viaFallback
                if (stream.viaFallback) _messages.tryEmit("«${t.title}» играет из замены")
                val mrl = proxy.register(stream.url)
                currentMrl = mrl
                val ok = player.media().play(mrl, ":no-video", ":http-reconnect")
                if (!ok) onTrackError()
                publish()
            }.onFailure { e ->
                buffering = false
                _messages.tryEmit("Не удалось воспроизвести «${t.title}»: ${e.message}")
                skipAfterError()
            }
        }
    }

    private fun onTrackEnded() {
        finishCurrent()
        if (repeat == RepeatMode.ONE) return load(pos)
        advance(userSkip = false)
    }

    private fun advance(userSkip: Boolean) {
        when {
            pos + 1 < order.size -> load(pos + 1)
            repeat == RepeatMode.ALL || (userSkip && !waveMode && order.isNotEmpty()) -> load(0)
            waveMode -> {
                // Волна ещё догружается — попробуем чуть позже
                scope.launch {
                    delay(1500)
                    if (pos + 1 < order.size) load(pos + 1)
                }
            }
            else -> {
                player.controls().stop()
                publish()
            }
        }
    }

    /** VLC не смог открыть поток: пробуем замену из других сервисов, потом — следующий трек. */
    private fun onTrackError() {
        val t = current ?: return
        if (attempt == 0 && settings.current.fallbackToOthers) return load(pos, skipPrimary = true)
        _messages.tryEmit("Не удалось воспроизвести «${t.title}»")
        skipAfterError()
    }

    private fun skipAfterError() {
        buffering = false
        // Пропускаем битый трек, но не бесконечно (например, без интернета)
        if (++consecutiveErrors < 5 && pos + 1 < order.size) load(pos + 1) else publish()
    }

    private fun beginCurrent(t: Track) {
        finishCurrent()
        cur = t
        curPlayedMs = 0
        curFromWave = waveMode
        curStation = radioStation
        recentKeys.addLast(t.key)
        if (recentKeys.size > 200) recentKeys.removeFirst()
        val station = curStation
        if (curFromWave && station != null && t.source == Source.YANDEX) {
            val batch = waveBatchId
            scope.launch { yandex.waveFeedback("trackStarted", batch, t.sourceId, station = station) }
        }
    }

    private fun isSkip(t: Track, played: Long) = played < 30_000 && (t.durationMs <= 0 || played < t.durationMs / 2)

    /** Отзыв волне: дослушал или пропустил. */
    private fun finishCurrent() {
        val t = cur ?: return
        cur = null
        val played = curPlayedMs
        val station = curStation
        if (curFromWave && station != null && t.source == Source.YANDEX) {
            val type = if (isSkip(t, played)) "skip" else "trackFinished"
            val batch = waveBatchId
            scope.launch { yandex.waveFeedback(type, batch, t.sourceId, played / 1000.0, station) }
        }
    }

    private fun applyVolume() {
        player.audio().setVolume(_state.value.volume)
    }

    private suspend fun tickLoop() {
        var last = System.nanoTime()
        while (true) {
            delay(250)
            val now = System.nanoTime()
            val playing = player.status().isPlaying
            if (playing) curPlayedMs += (now - last) / 1_000_000
            last = now
            if (playing) {
                // VLC иногда сбрасывает громкость при старте потока — держим нашу
                if (player.audio().volume() != _state.value.volume && player.audio().volume() >= 0) applyVolume()
                val len = player.status().length()
                _state.update {
                    it.copy(
                        positionMs = player.status().time().coerceAtLeast(0),
                        durationMs = if (len > 0) len else it.durationMs,
                        isPlaying = true,
                    )
                }
            }
        }
    }

    private fun publish() {
        val t = current
        val len = player.status().length()
        val isPlaying = player.status().isPlaying
        _state.update {
            PlayerState(
                track = t,
                isPlaying = isPlaying,
                isBuffering = buffering && t != null,
                positionMs = if (t?.key == it.track?.key) player.status().time().coerceAtLeast(0) else 0,
                durationMs = if (len > 0) len else (t?.durationMs ?: 0),
                hasNext = pos + 1 < order.size || waveMode,
                waveMode = waveMode,
                radioTitle = radioTitle,
                viaFallback = viaFallback,
                shuffle = shuffle,
                repeat = repeat,
                volume = it.volume,
                queue = order.mapNotNull { i -> queue.getOrNull(i) },
                index = pos,
            )
        }
    }

    /** Останавливает плеер на его же потоке, чтобы опрос позиции не обратился к уже освобождённому VLC. */
    fun release() {
        runBlocking(engineThread) {
            scope.coroutineContext[Job]?.cancelAndJoin()
            runCatching { player.controls().stop() }
            runCatching { player.release() }
        }
    }
}
