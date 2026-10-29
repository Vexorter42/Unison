package com.musichub.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Файлы с телефона (content://, file://) читаем напрямую, а сетевые треки — через кеш.
 * Иначе локальная музыка копировалась бы в кеш и занимала место дважды.
 * SoundCloud (mh://sc/…) — HLS-плейлист со ссылками, которые быстро истекают: его не кешируем.
 */
@OptIn(UnstableApi::class)
class SchemeRoutingDataSource(
    private val remote: DataSource,
    private val local: DataSource,
    private val uncached: DataSource,
) : DataSource {

    private var current: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        remote.addTransferListener(transferListener)
        local.addTransferListener(transferListener)
        uncached.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val source = when {
            uri.scheme in LOCAL_SCHEMES -> local
            uri.scheme == StreamResolver.SCHEME && uri.authority == "sc" -> uncached
            else -> remote
        }
        current = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        current?.read(buffer, offset, length) ?: error("DataSource не открыт")

    override fun getUri(): Uri? = current?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            current?.close()
        } finally {
            current = null
        }
    }

    class Factory(
        private val remote: DataSource.Factory,
        private val local: DataSource.Factory,
        private val uncached: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            SchemeRoutingDataSource(remote.createDataSource(), local.createDataSource(), uncached.createDataSource())
    }

    private companion object {
        val LOCAL_SCHEMES = setOf("content", "file", "android.resource")
    }
}
