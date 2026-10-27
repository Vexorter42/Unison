package com.musichub.net

import okhttp3.Dns
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.UnknownHostException

/** Проверяет запасной DNS по сети: нужен доступ к Cloudflare/Google DoH. */
class FallbackDnsTest {

    /** Ведёт себя как системный DNS под Happ: ничего не находит. */
    private val brokenSystemDns = object : Dns {
        override fun lookup(hostname: String) = throw UnknownHostException("Unable to resolve host \"$hostname\"")
    }

    @Test
    fun resolvesThroughDohWhenSystemDnsIsBroken() {
        val dns = FallbackDns(primary = brokenSystemDns)
        for (host in listOf("api.music.yandex.net", "api.vk.com", "www.youtube.com", "api.github.com")) {
            assertTrue("$host должен резолвиться через DoH", dns.lookup(host).isNotEmpty())
        }
        assertTrue(dns.systemDnsBroken)
    }

    @Test
    fun usesSystemDnsWhenItWorks() {
        val dns = FallbackDns()
        assertTrue(dns.lookup("api.github.com").isNotEmpty())
        assertFalse(dns.systemDnsBroken)
    }
}
