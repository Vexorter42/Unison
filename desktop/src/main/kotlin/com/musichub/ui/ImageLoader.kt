package com.musichub.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jetbrains.skia.Image
import java.util.concurrent.ConcurrentHashMap

/** Обложки: загрузка через OkHttp (тот же DNS, что у API) и LRU-кеш в памяти. */
class ImageLoader(private val http: OkHttpClient) {

    private val cache = object : LinkedHashMap<String, ImageBitmap>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 300
    }
    private val inFlight = ConcurrentHashMap<String, Mutex>()

    fun cached(url: String): ImageBitmap? = synchronized(cache) { cache[url] }

    suspend fun load(url: String): ImageBitmap? {
        cached(url)?.let { return it }
        // Одну и ту же обложку часто просят сразу несколько мест — качаем один раз
        val lock = inFlight.getOrPut(url) { Mutex() }
        return lock.withLock {
            cached(url) ?: withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null
                        val bytes = resp.body?.bytes() ?: return@use null
                        Image.makeFromEncoded(bytes).toComposeImageBitmap()
                    }
                }.getOrNull()
            }?.also { img -> synchronized(cache) { cache[url] = img } }
        }.also { inFlight.remove(url) }
    }
}

@Composable
fun rememberImage(url: String?): ImageBitmap? {
    val loader = LocalGraph.current.images
    val state by produceState(url?.let { loader.cached(it) }, url) {
        value = url?.let { loader.load(it) }
    }
    return state
}
