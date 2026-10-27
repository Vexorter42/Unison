package com.musichub.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.edit
import com.musichub.BuildConfig
import com.musichub.util.arr
import com.musichub.util.long
import com.musichub.util.obj
import com.musichub.util.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

data class Release(val version: String, val notes: String, val apkUrl: String, val sizeBytes: Long, val pageUrl: String)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val release: Release) : UpdateState
    data class Downloading(val release: Release, val progress: Float) : UpdateState
    data class Installing(val release: Release) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * OTA-обновления через GitHub Releases: берём последний релиз репозитория, сравниваем версию,
 * скачиваем APK и ставим системным установщиком (пользователь подтверждает установку).
 */
class Updater(private val context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private var job: Job? = null

    val currentVersion: String get() = BuildConfig.VERSION_NAME
    val repo: String get() = BuildConfig.UPDATE_REPO

    /** Тихая проверка при запуске — не чаще раза в 12 часов, пропущенную версию не предлагаем снова. */
    fun checkOnStartup() {
        val last = prefs.getLong("last_check", 0)
        if (System.currentTimeMillis() - last < 12 * 3_600_000L) return
        check(silent = true)
    }

    fun check(silent: Boolean = false) {
        if (_state.value is UpdateState.Downloading || _state.value is UpdateState.Installing) return
        job?.cancel()
        job = scope.launch {
            _state.value = UpdateState.Checking
            try {
                val release = withContext(Dispatchers.IO) { fetchLatest() }
                prefs.edit { putLong("last_check", System.currentTimeMillis()) }
                _state.value = when {
                    release == null || !isNewer(release.version, currentVersion) -> UpdateState.UpToDate
                    silent && prefs.getString("skipped", null) == release.version -> UpdateState.Idle
                    else -> UpdateState.Available(release)
                }
            } catch (e: Exception) {
                _state.value = if (silent) UpdateState.Idle else UpdateState.Failed("Не удалось проверить: ${e.message}")
            }
        }
    }

    fun skip(release: Release) {
        prefs.edit { putString("skipped", release.version) }
        _state.value = UpdateState.Idle
    }

    fun dismiss() {
        _state.value = UpdateState.Idle
    }

    fun downloadAndInstall(release: Release) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            // Один раз нужно разрешить приложению ставить APK
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            Toast.makeText(context, "Разрешите установку обновлений и нажмите «Обновить» ещё раз", Toast.LENGTH_LONG).show()
            return
        }
        job?.cancel()
        job = scope.launch {
            try {
                _state.value = UpdateState.Downloading(release, 0f)
                val apk = withContext(Dispatchers.IO) { download(release) }
                _state.value = UpdateState.Installing(release)
                withContext(Dispatchers.IO) { install(apk) }
            } catch (e: Exception) {
                _state.value = UpdateState.Failed("Обновление не скачалось: ${e.message}")
            }
        }
    }

    private fun fetchLatest(): Release? {
        val request = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .build()
        http.newCall(request).execute().use { resp ->
            if (resp.code == 404) return null
            if (!resp.isSuccessful) error("GitHub HTTP ${resp.code}")
            val o = Json.parseToJsonElement(resp.body?.string().orEmpty()).obj() ?: return null
            val asset = o["assets"].arr().mapNotNull { it.obj() }.firstOrNull { it.str("name")?.endsWith(".apk") == true } ?: return null
            return Release(
                version = o.str("tag_name")?.removePrefix("v") ?: return null,
                notes = o.str("body").orEmpty().trim(),
                apkUrl = asset.str("browser_download_url") ?: return null,
                sizeBytes = asset.long("size") ?: 0,
                pageUrl = o.str("html_url").orEmpty(),
            )
        }
    }

    private fun download(release: Release): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "Unison-${release.version}.apk")
        http.newCall(Request.Builder().url(release.apkUrl).build()).execute().use { resp ->
            if (!resp.isSuccessful) error("HTTP ${resp.code}")
            val body = resp.body ?: error("пустой ответ")
            val total = body.contentLength().takeIf { it > 0 } ?: release.sizeBytes
            body.byteStream().use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) _state.value = UpdateState.Downloading(release, done.toFloat() / total)
                    }
                }
            }
        }
        return file
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        if (Build.VERSION.SDK_INT >= 31) params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val callback = PendingIntent.getBroadcast(
                context, sessionId,
                Intent(context, InstallResultReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(callback.intentSender)
        }
    }

    internal fun onInstallFailed(message: String) {
        _state.value = UpdateState.Failed(message)
    }

    companion object {
        /** "0.10.1" > "0.9.3" — сравнение по числам, а не по строке. */
        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.', '-').map { it.toIntOrNull() ?: 0 }
            val b = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }
    }
}

/** Получает результат установки: просит подтверждение у пользователя или сообщает об ошибке. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // приложение перезапустится уже новой версией
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "код $status"
                val text = if (status == PackageInstaller.STATUS_FAILURE_CONFLICT || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE) {
                    "Обновление подписано другим ключом — удалите текущую версию и поставьте новую вручную ($msg)"
                } else "Установка не удалась: $msg"
                com.musichub.App.graph.updater.onInstallFailed(text)
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }
    }
}
