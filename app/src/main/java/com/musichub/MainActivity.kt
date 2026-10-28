package com.musichub

import android.Manifest
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.musichub.links.Links
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
        handleLink(intent)
        setContent {
            MusicHubTheme {
                MusicHubApp()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLink(intent)
    }

    /** Ссылка из «Поделиться» или «Открыть в» → откроется нужный плейлист / трек. */
    private fun handleLink(intent: Intent?) {
        val text = when (intent?.action) {
            Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return
        val target = Links.parse(text)
        if (target != null) App.graph.pendingLink.value = target
        else Toast.makeText(this, "Unison не узнаёт эту ссылку", Toast.LENGTH_SHORT).show()
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
