package com.musichub

import android.Manifest
import android.content.ComponentName
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.musichub.playback.PlaybackService
import com.musichub.ui.MusicHubApp
import com.musichub.ui.theme.MusicHubTheme

class MainActivity : ComponentActivity() {

    private var controller: ListenableFuture<MediaController>? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Приложение всегда тёмное — значки строки состояния всегда светлые
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        if (Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            MusicHubTheme {
                MusicHubApp()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Подключение контроллера запускает сервис — он держит музыку в фоне и рисует уведомление
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }
}
