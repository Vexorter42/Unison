package com.musichub.data

/** Google may issue either SAPISID or __Secure-3PAPISID for YouTube Music sessions. */
object YtMusicSession {
    private val sessionCookie = Regex("""(?:^|;\s*)(?:SAPISID|__Secure-3PAPISID)=([^;]+)""")

    fun hasAuthCookie(cookie: String?): Boolean = cookie?.let(sessionCookie::containsMatchIn) == true

    fun authCookieValue(cookie: String): String? = sessionCookie.find(cookie)?.groupValues?.get(1)
}
