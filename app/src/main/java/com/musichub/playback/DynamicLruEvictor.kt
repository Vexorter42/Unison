package com.musichub.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/**
 * Как LeastRecentlyUsedCacheEvictor из Media3, но лимит читается при каждой проверке —
 * размер авто-кеша в настройках меняется без перезапуска. Лишнее (давно не слушанное)
 * удаляется, когда в кеш пишется что-то новое.
 */
@OptIn(UnstableApi::class)
class DynamicLruEvictor(private val maxBytes: () -> Long) : CacheEvictor {

    private val leastRecentlyUsed = TreeSet<CacheSpan> { a, b ->
        when {
            a.lastTouchTimestamp != b.lastTouchTimestamp -> a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
            else -> a.compareTo(b)
        }
    }
    private var currentSize = 0L

    override fun requiresCacheSpanTouches() = true

    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.add(span)
        currentSize += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        leastRecentlyUsed.remove(span)
        currentSize -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evict(cache: Cache, requiredSpace: Long) {
        val limit = maxBytes()
        while (currentSize + requiredSpace > limit && leastRecentlyUsed.isNotEmpty()) {
            cache.removeSpan(leastRecentlyUsed.first())
        }
    }
}
