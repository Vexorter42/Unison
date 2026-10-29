package com.musichub.playback

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Локальный HTTP-прокси между VLC и сервисами. VLC ходит на 127.0.0.1, а настоящий запрос делает OkHttp:
 * с нашим DNS (DoH, когда VPN ломает системный), нужным User-Agent для googlevideo и поддержкой Range для перемотки.
 */
class StreamProxy(private val http: OkHttpClient) {

    private val routes = ConcurrentHashMap<String, String>()

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
        executor = Executors.newCachedThreadPool { r -> Thread(r, "stream-proxy").apply { isDaemon = true } }
        createContext("/s/") { ex -> runCatching { handle(ex) }; ex.close() }
        start()
    }

    /** Локальный адрес, по которому VLC получит [upstream]. Старые маршруты не нужны — оставляем только последние. */
    fun register(upstream: String): String {
        if (routes.size > 32) routes.clear()
        val id = UUID.randomUUID().toString()
        routes[id] = upstream
        return "http://127.0.0.1:${server.address.port}/s/$id"
    }

    private fun handle(ex: HttpExchange) {
        val id = ex.requestURI.path.removePrefix("/s/")
        val upstream = routes[id] ?: return ex.sendResponseHeaders(404, -1)
        val head = ex.requestMethod.equals("HEAD", ignoreCase = true)
        val req = Request.Builder().url(upstream)
            .apply { ex.requestHeaders.getFirst("Range")?.let { header("Range", it) } }
            .apply { if (head) head() }
            .build()
        try {
            http.newCall(req).execute().use { resp ->
                val out = ex.responseHeaders
                for (name in listOf("Content-Type", "Content-Range", "Accept-Ranges", "Last-Modified", "ETag")) {
                    resp.header(name)?.let { out.set(name, it) }
                }
                if (out.getFirst("Accept-Ranges") == null) out.set("Accept-Ranges", "bytes")
                val length = resp.body?.contentLength() ?: -1L
                when {
                    head || resp.code == 416 -> ex.sendResponseHeaders(resp.code, -1)
                    else -> {
                        // 0 — длина неизвестна, отдаём chunked
                        ex.sendResponseHeaders(resp.code, if (length > 0) length else 0)
                        resp.body?.byteStream()?.use { input -> ex.responseBody.use { input.copyTo(it, 64 * 1024) } }
                    }
                }
            }
        } catch (e: IOException) {
            // VLC закрыл соединение (перемотка или следующий трек) — это нормально
            runCatching { ex.sendResponseHeaders(502, -1) }
        }
    }
}
