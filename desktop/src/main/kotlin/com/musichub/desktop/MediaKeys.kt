package com.musichub.desktop

import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinUser

/**
 * Медиаклавиши Windows (Play/Pause, Next, Previous, Stop) через RegisterHotKey — работают,
 * даже когда окно Unison не в фокусе. Слушает свой поток с очередью сообщений.
 */
class MediaKeys(
    private val onPlayPause: () -> Unit,
    private val onNext: () -> Unit,
    private val onPrevious: () -> Unit,
    private val onStop: () -> Unit,
) {
    private var threadId = 0

    fun start() {
        if (!System.getProperty("os.name").orEmpty().startsWith("Windows")) return
        Thread({ loop() }, "media-keys").apply { isDaemon = true }.start()
    }

    private fun loop() {
        val u = User32.INSTANCE
        threadId = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetCurrentThreadId()
        val keys = mapOf(
            1 to VK_MEDIA_PLAY_PAUSE,
            2 to VK_MEDIA_NEXT_TRACK,
            3 to VK_MEDIA_PREV_TRACK,
            4 to VK_MEDIA_STOP,
        )
        // Клавишу может уже держать другое приложение — тогда просто без неё
        val registered = keys.filter { (id, vk) -> u.RegisterHotKey(null, id, 0, vk) }.keys
        if (registered.isEmpty()) return
        val msg = WinUser.MSG()
        while (u.GetMessage(msg, null, 0, 0) > 0) {
            if (msg.message == WinUser.WM_HOTKEY) {
                when (msg.wParam.toInt()) {
                    1 -> onPlayPause()
                    2 -> onNext()
                    3 -> onPrevious()
                    4 -> onStop()
                }
            }
        }
        registered.forEach { u.UnregisterHotKey(null, it) }
    }

    fun stop() {
        if (threadId != 0) User32.INSTANCE.PostThreadMessage(threadId, WinUser.WM_QUIT, null, null)
    }

    private companion object {
        const val VK_MEDIA_NEXT_TRACK = 0xB0
        const val VK_MEDIA_PREV_TRACK = 0xB1
        const val VK_MEDIA_STOP = 0xB2
        const val VK_MEDIA_PLAY_PAUSE = 0xB3
    }
}
