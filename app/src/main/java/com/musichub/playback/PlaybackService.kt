package com.musichub.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.musichub.App
import com.musichub.MainActivity
import com.musichub.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Фоновое воспроизведение, уведомление с кнопками и управление с экрана блокировки / наушников.
 * В уведомлении — значок Unison и кнопки «Не нравится» / «Нравится», как в Моей волне.
 */
@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {

    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply { setSmallIcon(R.drawable.ic_notification) },
        )
        val graph = App.graph
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        session = MediaSession.Builder(this, graph.engine.player)
            .setSessionActivity(openApp)
            .setCallback(SessionCallback())
            .setMediaButtonPreferences(buttons(liked = false))
            .build()

        // Сердечко в уведомлении закрашено, если текущий трек уже в «Любимом»
        scope.launch {
            combine(graph.engine.currentKey, graph.favorites.keys) { key, liked -> key != null && key in liked }
                .distinctUntilChanged()
                .collect { liked -> session?.setMediaButtonPreferences(buttons(liked)) }
        }
    }

    private fun buttons(liked: Boolean): List<CommandButton> = listOf(
        CommandButton.Builder(CommandButton.ICON_THUMB_DOWN_UNFILLED)
            .setDisplayName("Не нравится")
            .setSessionCommand(DISLIKE)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
        CommandButton.Builder(if (liked) CommandButton.ICON_HEART_FILLED else CommandButton.ICON_HEART_UNFILLED)
            .setDisplayName(if (liked) "Убрать из «Любимого»" else "В «Любимое»")
            .setSessionCommand(LIKE)
            .setSlots(CommandButton.SLOT_OVERFLOW)
            .build(),
    )

    private inner class SessionCallback : MediaSession.Callback {
        override fun onConnect(session: MediaSession, controller: MediaSession.ControllerInfo): MediaSession.ConnectionResult =
            MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                .setAvailableSessionCommands(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon().add(LIKE).add(DISLIKE).build(),
                )
                .build()

        override fun onCustomCommand(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            customCommand: SessionCommand,
            args: Bundle,
        ): ListenableFuture<SessionResult> {
            when (customCommand.customAction) {
                ACTION_LIKE -> App.graph.engine.toggleLike()
                ACTION_DISLIKE -> App.graph.engine.dislike()
            }
            return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
        }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = session?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        // Сам плеер не освобождаем: он общий для всего приложения
        session?.release()
        session = null
        super.onDestroy()
    }

    private companion object {
        const val ACTION_LIKE = "com.musichub.LIKE"
        const val ACTION_DISLIKE = "com.musichub.DISLIKE"
        val LIKE = SessionCommand(ACTION_LIKE, Bundle.EMPTY)
        val DISLIKE = SessionCommand(ACTION_DISLIKE, Bundle.EMPTY)
    }
}
