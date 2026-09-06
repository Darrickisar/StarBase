package StarBase.Android.Forum

import StarBase.Android.Forum.net.Doh
import StarBase.Android.Forum.net.DohResolver
import StarBase.Android.Forum.net.Net
import StarBase.Android.Forum.net.Parse
import StarBase.Android.Forum.net.Site
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/** Opt-in network acceptance test: STARBASE_LIVE_DOH=1. No system-DNS fallback. */
class DohConnectivityTest {
    @Test
    fun dohAddressesReachTheWebsiteAndLoginOverVerifiedDirectHttps() {
        assumeTrue(System.getenv("STARBASE_LIVE_DOH") == "1")
        val resolver = DohResolver(system = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                throw UnknownHostException("System DNS fallback is forbidden: $hostname")
        })
        resolver.configure(true, Doh.DEFAULT_SERVER)
        val addresses = resolver.lookup("linux.sb")
        assertTrue(addresses.isNotEmpty())
        assertTrue(resolver.lastError.isEmpty())

        val client = OkHttpClient.Builder()
            .dns(resolver)
            .proxy(Proxy.NO_PROXY)
            .connectTimeout(10, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
        try {
            val request = Request.Builder().url(Site.BASE)
                .header("User-Agent", Net.userAgent()).build()
            client.newCall(request).execute().use { response ->
                assertTrue("HTTP ${response.code}", response.isSuccessful)
                val document = Jsoup.parse(response.body?.string().orEmpty())
                assertTrue("Unexpected website: ${document.title()}",
                    document.title().contains("LINUX SB", ignoreCase = true))
                println("DoH ${resolver.server}: ${addresses.joinToString { it.hostAddress.orEmpty() }}")
                println("Direct HTTPS: HTTP ${response.code}, ${response.handshake?.tlsVersion}, ${document.title()}")
            }
            client.newCall(Request.Builder().url(Site.LOGIN).header("User-Agent", Net.userAgent()).build())
                .execute().use { response ->
                    assertTrue("Login HTTP ${response.code}", response.isSuccessful)
                    val html = response.body?.string().orEmpty()
                    assertTrue("Expected the native login form", Parse.isLoginPage(html))
                    val document = Jsoup.parse(html)
                    val fields = document.select("form:has(input[name=password]) input[name]").map { it.attr("name") }
                    val widgets = document.select("[data-native-captcha]").size
                    assertTrue("Login form unrecognized: fields=$fields, native widgets=$widgets, " +
                        "csrf length=${Parse.csrfOf(document).length}", Parse.loginForm(html) != null)
                    println("Direct login: HTTP ${response.code}, ${response.handshake?.tlsVersion}, form parsed")
                }
        } finally {
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
        }
    }
}
