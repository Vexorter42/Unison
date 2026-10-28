package com.musichub.playback

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.musichub.App
import com.musichub.MainActivity
import com.musichub.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

/**
 * Держит загрузку живой, пока приложение свёрнуто, и показывает её прогресс в уведомлении.
 * Сам ничего не качает — только следит за очередью PlaybackEngine и закрывается, когда она пуста.
 */
class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }

    override fun onBind(intent: Intent?): IBinder? = null

    @OptIn(FlowPreview::class)
    override fun onCreate() {
        super.onCreate()
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Загрузки", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Прогресс скачивания музыки для офлайна"
            },
        )
        val engine = App.graph.engine
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, build(engine.downloadBatch.value),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.launch {
            // Прогресс меняется очень часто — обновляем уведомление не чаще двух раз в секунду
            engine.downloadBatch.sample(500).collect { batch ->
                if (batch == null) {
                    ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    notifications.notify(NOTIFICATION_ID, build(batch))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) App.graph.engine.cancelDownloads()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun build(batch: DownloadBatch?) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle(
            when {
                batch == null -> "Загрузка"
                batch.active -> "Скачиваю ${batch.finished + 1} из ${batch.total}"
                else -> "Скачано ${batch.done} из ${batch.total}"
            },
        )
        .setContentText(batch?.currentTitle.orEmpty())
        .setProgress(100, ((batch?.progress ?: 0f) * 100).toInt(), batch == null)
        .setOngoing(batch?.active == true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setContentIntent(
            PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
        )
        .apply {
            if (batch?.active == true) {
                addAction(
                    0, "Отменить",
                    PendingIntent.getService(
                        this@DownloadService, 1,
                        Intent(this@DownloadService, DownloadService::class.java).setAction(ACTION_CANCEL),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
            }
        }
        .build()

    companion object {
        private const val CHANNEL = "downloads"
        private const val NOTIFICATION_ID = 42
        private const val ACTION_CANCEL = "com.musichub.CANCEL_DOWNLOADS"

        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, DownloadService::class.java)) }
        }
    }
}
