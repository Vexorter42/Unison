package com.musichub.cloud

import com.musichub.AppGraph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

data class CloudSyncResult(val uploadedBytes: Int, val restored: Boolean)

/** Private Unison account API and revision-checked backup sync. */
class UnisonAccountSync(private val graph: AppGraph) {
    private val json = Json { ignoreUnknownKeys = true }
    private val syncMutex = Mutex()

    suspend fun login(server: String, username: String, password: String): String = withContext(Dispatchers.IO) {
        val body = json.encodeToString(LoginRequest.serializer(), LoginRequest(username.trim(), password))
        val request = Request.Builder().url(apiUrl(server, "v1", "auth", "login"))
            .post(body.toRequestBody(JSON)).build()
        graph.http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error(response.errorMessage("Не удалось войти в Unison"))
            val result = json.decodeFromString(LoginResponse.serializer(), response.body?.string().orEmpty())
            result.token
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        val s = graph.settings.current
        val token = s.cloudToken ?: return@withContext
        val request = Request.Builder().url(apiUrl(s.cloudServer, "v1", "auth", "logout"))
            .header("Authorization", "Bearer $token").post(ByteArray(0).toRequestBody(null)).build()
        graph.http.newCall(request).execute().use { response ->
            if (response.code !in setOf(204, 401)) error(response.errorMessage("Не удалось завершить сессию"))
        }
    }

    suspend fun sync(): CloudSyncResult = syncMutex.withLock { withContext(Dispatchers.IO) {
        val s = graph.settings.current
        val token = s.cloudToken?.takeIf { it.isNotBlank() } ?: error("Сначала войди в аккаунт Unison")
        val url = apiUrl(s.cloudServer, "v1", "sync")
        var remote: ByteArray? = null
        var etag: String? = null
        val get = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        graph.http.newCall(get).execute().use { response ->
            when (response.code) {
                200 -> {
                    remote = response.body?.bytes() ?: error("Пустая копия синхронизации")
                    etag = response.header("ETag") ?: error("Сервер не прислал версию копии")
                }
                404 -> Unit
                401 -> error("Сессия истекла. Войди снова.")
                else -> error(response.errorMessage("Не удалось получить данные"))
            }
        }
        remote?.let { graph.backup.restoreBytes(it) }
        val merged = graph.backup.exportBytes()
        val upload = Request.Builder().url(url).header("Authorization", "Bearer $token")
            .header(if (etag == null) "If-None-Match" else "If-Match", etag ?: "*")
            .put(merged.toRequestBody(JSON)).build()
        graph.http.newCall(upload).execute().use { response ->
            when (response.code) {
                204 -> CloudSyncResult(merged.size, remote != null)
                401 -> error("Сессия истекла. Войди снова.")
                409 -> error("Данные изменились на другом устройстве. Нажми синхронизацию ещё раз.")
                else -> error(response.errorMessage("Не удалось сохранить копию"))
            }
        }
    } }

    private fun apiUrl(server: String, vararg path: String): HttpUrl {
        val base = server.trim().trimEnd('/').toHttpUrlOrNull() ?: error("Неверный адрес Unison API")
        if (base.scheme != "https") error("Для аккаунта Unison нужен HTTPS")
        return path.fold(base.newBuilder()) { builder, segment -> builder.addPathSegment(segment) }.build()
    }

    private fun okhttp3.Response.errorMessage(fallback: String): String {
        val detail = runCatching {
            json.decodeFromString(ApiError.serializer(), body?.string().orEmpty()).error
        }.getOrNull()
        return detail ?: "$fallback (${code})"
    }

    @Serializable private data class LoginRequest(val username: String, val password: String)
    @Serializable private data class LoginResponse(val token: String)
    @Serializable private data class ApiError(val error: String)

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
