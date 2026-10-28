package com.musichub.playback

import com.musichub.util.hiResCover
import android.content.Context
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.core.net.toUri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheKeyFactory
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import com.musichub.AppGraph
import com.musichub.data.db.DownloadedTrack
import com.musichub.data.db.PlayEvent
import com.musichub.data.model.Source
import com.musichub.data.model.Track
import com.musichub.search.TrackMatcher
import com.musichub.yandex.YandexApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Прогресс пачки загрузок: сколько всего, сколько готово и как идёт текущий трек. */
data class DownloadBatch(
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val currentTitle: String? = null,
    val currentProgress: Float = 0f,
) {
    val finished: Int get() = done + failed
    val active: Boolean get() = finished < total
    val progress: Float get() = if (total == 0) 0f else ((finished + currentProgress) / total).coerceIn(0f, 1f)
}

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
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val sleepAtMs: Long? = null,
    val sleepEndOfTrack: Boolean = false,
    val queue: List<Track> = emptyList(),
    val index: Int = 0,
)

/**
 * Сердце приложения: ExoPlayer + кеш + Моя волна + статистика + таймер сна + плавные переходы.
 * Живёт всё время, пока живёт процесс; сервис уведомлений только оборачивает [player].
 */
@OptIn(UnstableApi::class)
class PlaybackEngine(context: Context, private val graph: AppGraph) {

    private val appContext: Context = context.applicationContext

    private val scope = graph.scope
    val resolver = StreamResolver(graph)

    // ---------- Кеш: авто (LRU, то что слушали) + загрузки (не вытесняются) ----------

    private val dbProvider = StandaloneDatabaseProvider(context)
    private val autoCache = SimpleCache(
        File(context.cacheDir, "media_auto"),
        // Лимит читается из настроек на лету — менять размер можно без перезапуска
        DynamicLruEvictor { graph.settings.current.autoCacheMb * 1024L * 1024L },
        dbProvider,
    )
    private val downloadCache = SimpleCache(File(context.filesDir, "media_downloads"), NoOpCacheEvictor(), dbProvider)

    private val keyFactory = CacheKeyFactory { spec ->
        if (spec.uri.scheme == StreamResolver.SCHEME) StreamResolver.cacheKey(spec.uri) else spec.key ?: spec.uri.toString()
    }
    private val network = ResolvingDataSource.Factory(OkHttpDataSource.Factory(graph.router.callFactory)) { spec ->
        if (spec.uri.scheme == StreamResolver.SCHEME) spec.withUri(resolver.resolve(spec.uri)) else spec
    }
    private val autoCacheFactory = CacheDataSource.Factory()
        .setCache(autoCache)
        .setCacheKeyFactory(keyFactory)
        .setUpstreamDataSourceFactory(network)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    // Сначала смотрим в загрузки (только чтение), потом в авто-кеш, потом в сеть
    private val playbackFactory = CacheDataSource.Factory()
        .setCache(downloadCache)
        .setCacheKeyFactory(keyFactory)
        .setUpstreamDataSourceFactory(autoCacheFactory)
        .setCacheWriteDataSinkFactory(null)
    private val downloadFactory = CacheDataSource.Factory()
        .setCache(downloadCache)
        .setCacheKeyFactory(keyFactory)
        .setUpstreamDataSourceFactory(network)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(
                SchemeRoutingDataSource.Factory(playbackFactory, DefaultDataSource.Factory(context)),
                DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true),
            ),
        )
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            true,
        )
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    // ---------- Состояние для UI ----------

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    val currentKey: StateFlow<String?> = state.map { it.track?.key }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    private val _downloading = MutableStateFlow<Map<String, Float>>(emptyMap())
    val downloading: StateFlow<Map<String, Float>> = _downloading.asStateFlow()

    /** Общий прогресс текущей пачки загрузок (null — ничего не качается). */
    private val _downloadBatch = MutableStateFlow<DownloadBatch?>(null)
    val downloadBatch: StateFlow<DownloadBatch?> = _downloadBatch.asStateFlow()
    private val pendingDownloads = ArrayDeque<Track>()
    private var downloadWorker: Job? = null
    @Volatile private var currentWriter: CacheWriter? = null
    @Volatile private var cancelRequested = false
    val downloadedKeys: StateFlow<Set<String>> = graph.db.downloads().observeAll()
        .map { list -> list.map { it.trackKey }.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    // ---------- Моя волна ----------

    private var waveMode = false
    /** Станция Яндекса текущего радио (null — радио из похожих на YouTube) и его название. */
    private var radioStation: String? = null
    private var radioTitle: String? = null
    private var waveBatchId: String? = null
    private var waveLoading = false
    private var waveCounter = 0
    private val recentKeys = ArrayDeque<String>()

    // ---------- Текущее прослушивание (статистика и отзывы волне) ----------

    private var cur: Track? = null
    private var curEventId = 0L
    private var curStartedAt = 0L
    private var curPlayedMs = 0L
    private var curFromWave = false
    /** Станция Яндекса, на которой начался текущий трек (для отзывов «дослушал / пропустил»). */
    private var curStation: String? = null
    private var lastStatsFlush = 0L
    private var consecutiveErrors = 0
    private var queueCache: List<Track> = emptyList()

    private var sleepAt: Long? = null
    private var sleepEndOfTrack = false

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            finishCurrent()
            beginCurrent(mediaItem?.track)
            maybeRefillWave()
            publish()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            queueCache = List(player.mediaItemCount) { player.getMediaItemAt(it).track }.filterNotNull()
            publish()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) consecutiveErrors = 0 else flushStats()
            publish()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) finishCurrent()
            publish()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            if (reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM && sleepEndOfTrack) {
                sleepEndOfTrack = false
                player.pauseAtEndOfMediaItems = false
            }
            publish()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = publish()
        override fun onRepeatModeChanged(repeatMode: Int) = publish()

        override fun onPlayerError(error: PlaybackException) {
            val reason = generateSequence<Throwable>(error) { it.cause }.mapNotNull { it.message }.lastOrNull()
            _messages.tryEmit("Не удалось воспроизвести «${player.currentMediaItem?.track?.title}»: ${reason ?: error.errorCodeName}")
            // Пропускаем битый трек, но не бесконечно (например, без интернета)
            if (++consecutiveErrors < 5 && player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
                player.play()
            }
        }

        override fun onAudioSessionIdChanged(audioSessionId: Int) = graph.equalizer.attach(audioSessionId)
    }

    init {
        player.addListener(listener)
        graph.equalizer.attach(player.audioSessionId)
        scope.launch { tickLoop() }
        scope.launch { resolver.fallbackKeys.collect { publish() } }
    }

    /** Показать сообщение внизу экрана. */
    fun notify(message: String) {
        _messages.tryEmit(message)
    }

    private val MediaItem.track: Track? get() = localConfiguration?.tag as? Track

    private fun toMediaItem(t: Track): MediaItem = MediaItem.Builder()
        .setMediaId(t.key)
        .setUri(StreamResolver.uriFor(t))
        .setTag(t)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(t.title)
                .setArtist(t.artists)
                .setAlbumTitle(t.album)
                .setArtworkUri(hiResCover(t.coverUrl)?.toUri())
                .setIsPlayable(true)
                .setIsBrowsable(false)
                .build(),
        )
        .build()

    private fun queueKeys(): Set<String> = queueCache.mapTo(HashSet()) { it.key }

    // ---------- Управление ----------

    fun playTracks(tracks: List<Track>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        waveMode = false
        radioStation = null
        radioTitle = null
        player.shuffleModeEnabled = shuffle
        val start = if (shuffle) tracks.indices.random() else startIndex.coerceIn(tracks.indices)
        player.setMediaItems(tracks.map(::toMediaItem), start, 0)
        player.prepare()
        player.play()
    }

    fun playNext(t: Track) {
        if (player.mediaItemCount == 0) return playTracks(listOf(t))
        player.addMediaItem(player.currentMediaItemIndex + 1, toMediaItem(t))
        _messages.tryEmit("Следующим: ${t.title}")
    }

    fun enqueue(t: Track) {
        if (player.mediaItemCount == 0) return playTracks(listOf(t))
        player.addMediaItem(toMediaItem(t))
        _messages.tryEmit("Добавлено в очередь: ${t.title}")
    }

    fun togglePlay() {
        if (player.isPlaying) {
            player.pause()
            return
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
        player.play()
    }

    fun next() = player.seekToNextMediaItem()

    fun previous() {
        if (player.currentPosition > 3000 || !player.hasPreviousMediaItem()) player.seekTo(0)
        else player.seekToPreviousMediaItem()
    }

    fun seekTo(ms: Long) = player.seekTo(ms)

    fun skipTo(index: Int) = player.seekTo(index, 0)

    fun toggleShuffle() {
        player.shuffleModeEnabled = !player.shuffleModeEnabled
    }

    fun cycleRepeat() {
        player.repeatMode = when (player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    // ---------- Моя волна и радио ----------

    fun startWave() = startYandexStation(YandexApi.WAVE, "Моя волна", seed = null)

    /**
     * Бесконечное радио «похожее на этот трек» из любого сервиса. Для Яндекса — его станция по треку
     * (учится на пропусках), для остального трек сначала ищется в Яндексе, иначе — похожие с YouTube.
     */
    fun startRadio(track: Track) {
        scope.launch {
            val title = "Радио: ${track.title}"
            val yandexLoggedIn = graph.settings.current.yandexLoggedIn
            val yandexId = when {
                track.source == Source.YANDEX -> track.yandexId
                !yandexLoggedIn -> null
                else -> track.alternates.firstOrNull { it.source == Source.YANDEX }?.sourceId?.substringBefore(':')
                    ?: findOnYandex(track)?.yandexId
            }
            if (yandexId != null && yandexLoggedIn) startYandexStation("track:$yandexId", title, seed = track)
            else startYoutubeRadio(track, title)
        }
    }

    private fun startYandexStation(station: String, title: String, seed: Track?) {
        scope.launch {
            if (!graph.settings.current.yandexLoggedIn) {
                _messages.emit("Войдите в Яндекс в настройках, чтобы включить ${if (seed == null) "Мою волну" else "радио"}")
                return@launch
            }
            try {
                val batch = graph.yandex.stationTracks(station, seed?.yandexId)
                if (batch.tracks.isEmpty()) {
                    _messages.emit("Станция вернула пустой список")
                    return@launch
                }
                beginRadio(station, title, batch.batchId)
                launch { graph.yandex.waveFeedback("radioStarted", batch.batchId, station = station) }
                val tracks = listOfNotNull(seed) + batch.tracks.filter { it.key != seed?.key }
                setRadioQueue(tracks)
                if (station == YandexApi.WAVE) scheduleMix(batch.tracks)
            } catch (e: Exception) {
                _messages.emit("Не удалось запустить радио: ${e.message}")
            }
        }
    }

    private suspend fun startYoutubeRadio(seed: Track, title: String) {
        try {
            val videoId = if (seed.source == Source.YOUTUBE) seed.sourceId
            else seed.alternates.firstOrNull { it.source == Source.YOUTUBE }?.sourceId ?: graph.youtube.findMatch(seed)?.sourceId
            val related = videoId?.let { graph.youtube.related(it) }.orEmpty()
            if (related.isEmpty()) {
                _messages.emit("Не нашёл похожих треков для радио")
                return
            }
            beginRadio(station = null, title = title, batchId = null)
            setRadioQueue(listOf(seed) + related.filter { it.key != seed.key }.distinctBy { it.key })
        } catch (e: Exception) {
            _messages.emit("Не удалось запустить радио: ${e.message}")
        }
    }

    private fun beginRadio(station: String?, title: String, batchId: String?) {
        waveMode = true
        radioStation = station
        radioTitle = title
        waveBatchId = batchId
        waveCounter = 0
    }

    private fun setRadioQueue(tracks: List<Track>) {
        player.shuffleModeEnabled = false
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.setMediaItems(tracks.map(::toMediaItem))
        player.prepare()
        player.play()
        publish()
    }

    private fun maybeRefillWave() {
        if (!waveMode || waveLoading || player.mediaItemCount - player.currentMediaItemIndex > 3) return
        waveLoading = true
        scope.launch {
            try {
                val station = radioStation
                val fresh = if (station != null) {
                    val lastYandex = queueCache.lastOrNull { it.source == Source.YANDEX }
                    val batch = graph.yandex.stationTracks(station, lastYandex?.yandexId)
                    waveBatchId = batch.batchId ?: waveBatchId
                    batch.tracks
                } else {
                    // Радио на YouTube: продолжаем от последнего трека очереди
                    val last = queueCache.lastOrNull { it.source == Source.YOUTUBE } ?: return@launch
                    graph.youtube.related(last.sourceId).take(10)
                }
                if (!waveMode) return@launch
                val skip = queueKeys() + recentKeys
                val add = fresh.filter { it.key !in skip }.distinctBy { it.key }
                player.addMediaItems(add.map(::toMediaItem))
                if (station == YandexApi.WAVE) scheduleMix(add)
            } catch (e: Exception) {
                _messages.emit("Радио: ${e.message}")
            } finally {
                waveLoading = false
            }
        }
    }

    private suspend fun findOnYandex(t: Track): Track? {
        val q = TrackMatcher.splitYoutube(t)
        val results = runCatching { graph.yandex.search("${q.artists} ${q.title}") }.getOrNull() ?: return null
        return TrackMatcher.best(q, results, threshold = 0.65)
    }

    /** Подмешивание YouTube в волну: после каждого N-го трека — похожий трек с YouTube. */
    private fun scheduleMix(batch: List<Track>) {
        val s = graph.settings.current
        if (!s.mixYoutubeInWave) return
        val seeds = batch.filter { ++waveCounter % s.mixEvery.coerceAtLeast(2) == 0 }
        if (seeds.isEmpty()) return
        scope.launch {
            for (seed in seeds) {
                val extra = runCatching { youtubeNeighbour(seed) }.getOrNull() ?: continue
                if (!waveMode) return@launch
                val idx = queueCache.indexOfFirst { it.key == seed.key }
                if (idx < player.currentMediaItemIndex) continue
                player.addMediaItem(idx + 1, toMediaItem(extra.copy(mixed = true)))
            }
        }
    }

    private suspend fun youtubeNeighbour(seed: Track): Track? {
        val match = withContext(Dispatchers.IO) { graph.youtube.findMatchBlocking(seed) } ?: return null
        val related = graph.youtube.related(match.sourceId)
        val skip = queueKeys() + recentKeys
        return related.firstOrNull { it.key !in skip }
    }

    // ---------- Лайки ----------

    /**
     * Сердечко кладёт трек в «Любимое Unison» (или убирает). В сервисы ничего не уходит —
     * для этого в «Любимом» есть кнопка «Отправить в Яндекс / YouTube».
     */
    fun toggleLike(track: Track? = null) {
        val t = track ?: player.currentMediaItem?.track ?: return
        scope.launch {
            val added = graph.favorites.toggle(t)
            _messages.emit(if (added) "Добавлено в «Любимое»" else "Убрано из «Любимого»")
        }
    }

    fun dislike() {
        val t = player.currentMediaItem?.track ?: return
        if (t.source == Source.YANDEX && graph.settings.current.yandexLoggedIn) {
            scope.launch { runCatching { graph.yandex.dislike(t) } }
        }
        next()
    }

    // ---------- Таймер сна ----------

    fun setSleepTimer(minutes: Int?) {
        sleepAt = minutes?.let { System.currentTimeMillis() + it * 60_000L }
        sleepEndOfTrack = false
        player.pauseAtEndOfMediaItems = false
        publish()
    }

    fun setSleepEndOfTrack() {
        sleepAt = null
        sleepEndOfTrack = true
        player.pauseAtEndOfMediaItems = true
        publish()
    }

    // ---------- Загрузки и кеш ----------

    /**
     * Ставит треки в очередь загрузки. Очередь одна на всё приложение: можно добавлять плейлисты,
     * пока скачивается другой, — общий прогресс считается по всем.
     */
    fun download(tracks: List<Track>) {
        val queued = pendingDownloads.mapTo(HashSet()) { it.key }
        val todo = tracks.distinctBy { it.key }
            .filter { it.source != Source.LOCAL } // файлы и так на телефоне
            .filter { it.key !in downloadedKeys.value && it.key !in _downloading.value && it.key !in queued }
        if (todo.isEmpty()) {
            if (tracks.size > 1) notify("Всё уже скачано")
            return
        }
        pendingDownloads.addAll(todo)
        _downloading.update { m -> m + todo.associate { it.key to 0f } }
        _downloadBatch.update { b ->
            val base = b?.takeIf { it.active } ?: DownloadBatch()
            base.copy(total = base.total + todo.size)
        }
        DownloadService.start(appContext)
        if (downloadWorker?.isActive != true) downloadWorker = scope.launch { runDownloadQueue() }
    }

    /** Отменяет текущую и все ожидающие загрузки (уже скачанное остаётся). */
    fun cancelDownloads() {
        if (_downloadBatch.value?.active != true) return
        cancelRequested = true
        val dropped = pendingDownloads.map { it.key }.toSet()
        pendingDownloads.clear()
        _downloading.update { it - dropped }
        currentWriter?.cancel()
    }

    private suspend fun runDownloadQueue() {
        while (true) {
            val t = pendingDownloads.removeFirstOrNull() ?: break
            _downloadBatch.update { it?.copy(currentTitle = t.title, currentProgress = 0f) }
            var ok = false
            try {
                val bytes = withContext(Dispatchers.IO) {
                    var total = 0L
                    val writer = CacheWriter(downloadFactory.createDataSource(), DataSpec(StreamResolver.uriFor(t)), null) { length, cached, _ ->
                        total = cached
                        if (length > 0) {
                            val f = cached.toFloat() / length
                            _downloading.update { it + (t.key to f) }
                            _downloadBatch.update { it?.copy(currentProgress = f) }
                        }
                    }
                    currentWriter = writer
                    writer.cache()
                    total
                }
                graph.db.downloads().put(DownloadedTrack(t.key, t.toJson(), System.currentTimeMillis(), bytes))
                ok = true
            } catch (e: Exception) {
                // Недокачанный кусок не нужен — иначе он занимает место, а трек всё равно не скачан
                withContext(Dispatchers.IO) { runCatching { downloadCache.removeResource(t.key) } }
                if (!cancelRequested) _messages.emit("Не скачалось «${t.title}»: ${e.message}")
            } finally {
                currentWriter = null
                _downloading.update { it - t.key }
            }
            if (cancelRequested) break
            _downloadBatch.update { b -> b?.copy(done = b.done + if (ok) 1 else 0, failed = b.failed + if (ok) 0 else 1, currentProgress = 0f) }
        }

        val b = _downloadBatch.value
        when {
            cancelRequested -> _messages.emit("Загрузка отменена")
            b != null && b.total > 1 -> _messages.emit(if (b.failed == 0) "Скачано треков: ${b.done}" else "Скачано: ${b.done}, не удалось: ${b.failed}")
        }
        if (cancelRequested) {
            // После отмены плашку убираем сразу — показывать «Скачано N из N» тут нечего
            cancelRequested = false
            _downloadBatch.value = null
            return
        }
        // Даём полоске показать 100%, потом убираем (если за это время не добавили новых)
        _downloadBatch.update { it?.copy(currentTitle = null) }
        scope.launch {
            delay(1500)
            if (downloadWorker?.isActive != true && pendingDownloads.isEmpty()) _downloadBatch.value = null
        }
    }

    fun removeDownload(t: Track) {
        scope.launch {
            withContext(Dispatchers.IO) { downloadCache.removeResource(t.key) }
            graph.db.downloads().delete(t.key)
        }
    }

    /** (авто-кеш, загрузки) в байтах */
    suspend fun cacheUsage(): Pair<Long, Long> = withContext(Dispatchers.IO) { autoCache.cacheSpace to downloadCache.cacheSpace }

    suspend fun clearAutoCache() = withContext(Dispatchers.IO) {
        autoCache.keys.toList().forEach { autoCache.removeResource(it) }
    }

    /** Удаляет всё скачанное для офлайна (идущие загрузки сначала отменяются). Возвращает, сколько треков удалено. */
    suspend fun clearDownloads(): Int {
        cancelDownloads()
        val count = graph.db.downloads().observeAll().first().size
        withContext(Dispatchers.IO) { downloadCache.keys.toList().forEach { downloadCache.removeResource(it) } }
        graph.db.downloads().deleteAll()
        return count
    }

    // ---------- Внутреннее ----------

    private fun beginCurrent(t: Track?) {
        cur = t
        curStartedAt = System.currentTimeMillis()
        // Свой id, чтобы обновлять одну и ту же запись, пока трек играет
        curEventId = curStartedAt * 1000 + (0 until 1000).random()
        curPlayedMs = 0
        curFromWave = waveMode
        curStation = radioStation
        if (t == null) return
        recentKeys.addLast(t.key)
        if (recentKeys.size > 200) recentKeys.removeFirst()
        val station = curStation
        if (curFromWave && station != null && t.source == Source.YANDEX) {
            scope.launch { graph.yandex.waveFeedback("trackStarted", waveBatchId, t.sourceId, station = station) }
        }
    }

    private fun isSkip(t: Track, played: Long) = played < 30_000 && (t.durationMs <= 0 || played < t.durationMs / 2)

    /**
     * Сохраняет текущее прослушивание. Вызывается по ходу (каждые 10 с и на паузе), а не только
     * в конце трека — иначе прослушивание терялось бы, если приложение закрыли посреди песни.
     */
    private fun flushStats() {
        val t = cur ?: return
        val played = curPlayedMs
        if (played < 5_000) return
        lastStatsFlush = SystemClock.elapsedRealtime()
        val event = PlayEvent(
            id = curEventId,
            trackKey = t.key,
            title = t.title,
            artists = t.artists,
            source = t.source.name,
            startedAt = curStartedAt,
            playedMs = played,
            durationMs = t.durationMs,
            skipped = isSkip(t, played),
            fromWave = curFromWave,
            trackJson = t.copy(alternates = emptyList(), mixed = false).toJson(),
        )
        scope.launch(Dispatchers.IO) { graph.db.stats().upsert(event) }
    }

    private fun finishCurrent() {
        val t = cur ?: return
        flushStats()
        cur = null
        val played = curPlayedMs
        val skipped = isSkip(t, played)
        val station = curStation
        if (curFromWave && station != null && t.source == Source.YANDEX) {
            val type = if (skipped) "skip" else "trackFinished"
            scope.launch { graph.yandex.waveFeedback(type, waveBatchId, t.sourceId, played / 1000.0, station) }
        }
    }

    private suspend fun tickLoop() {
        var last = SystemClock.elapsedRealtime()
        while (true) {
            delay(250)
            val now = SystemClock.elapsedRealtime()
            if (player.isPlaying) curPlayedMs += now - last
            last = now
            if (player.isPlaying && now - lastStatsFlush > 10_000) flushStats()
            applyVolume()
            sleepAt?.let {
                if (System.currentTimeMillis() >= it) {
                    sleepAt = null
                    player.pause()
                    player.volume = 1f
                    _messages.tryEmit("Таймер сна: музыка на паузе")
                    publish()
                }
            }
            if (player.isPlaying) {
                _state.update {
                    it.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0),
                        durationMs = player.duration.takeIf { d -> d != C.TIME_UNSET } ?: it.durationMs,
                    )
                }
            }
        }
    }

    /** Плавное затухание в конце трека и нарастание в начале + затухание перед таймером сна. */
    private fun applyVolume() {
        var v = 1f
        val fadeMs = graph.settings.current.fadeSeconds * 1000L
        val dur = player.duration
        if (fadeMs > 0 && dur != C.TIME_UNSET && dur > fadeMs * 3) {
            val pos = player.currentPosition
            val fadeIn = minOf(fadeMs, 1500L)
            v = minOf(v, pos.toFloat() / fadeIn, (dur - pos).toFloat() / fadeMs)
        }
        sleepAt?.let {
            val left = it - System.currentTimeMillis()
            if (left < SLEEP_FADE_MS) v = minOf(v, left.toFloat() / SLEEP_FADE_MS)
        }
        val target = v.coerceIn(0f, 1f)
        if (player.volume != target) player.volume = target
    }

    private fun publish() {
        val t = player.currentMediaItem?.track
        _state.value = PlayerState(
            track = t,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            positionMs = player.currentPosition.coerceAtLeast(0),
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: (t?.durationMs ?: 0),
            hasNext = player.hasNextMediaItem(),
            waveMode = waveMode,
            radioTitle = radioTitle,
            viaFallback = t != null && t.key in resolver.fallbackKeys.value,
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            sleepAtMs = sleepAt,
            sleepEndOfTrack = sleepEndOfTrack,
            queue = queueCache,
            index = player.currentMediaItemIndex,
        )
    }

    private companion object {
        const val SLEEP_FADE_MS = 15_000L
    }
}
