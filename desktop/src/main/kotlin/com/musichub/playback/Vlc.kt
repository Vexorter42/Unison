package com.musichub.playback

import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.factory.discovery.provider.DiscoveryDirectoryProvider
import java.io.File

/**
 * VLC, который едет в установщике (папка ресурсов приложения), находится раньше установленного в систему.
 * Регистрируется через META-INF/services.
 */
class BundledVlcDirectoryProvider : DiscoveryDirectoryProvider {
    override fun priority(): Int = 10

    override fun directories(): Array<String> = listOfNotNull(System.getProperty("compose.application.resources.dir"))
        .filter { File(it, "libvlc.dll").exists() }
        .toTypedArray()

    override fun supported(): Boolean = true
}

object Vlc {
    /** null — VLC не найден ни в приложении, ни в системе. */
    fun createFactory(): MediaPlayerFactory? {
        if (!NativeDiscovery().discover()) return null
        return runCatching {
            MediaPlayerFactory(
                "--no-video",
                "--quiet",
                "--intf=dummy",
                "--network-caching=1500",
                "--no-metadata-network-access",
                "--no-media-library",
            )
        }.getOrNull()
    }
}
