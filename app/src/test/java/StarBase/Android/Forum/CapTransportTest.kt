package StarBase.Android.Forum

import StarBase.Android.Forum.net.CapChallenge
import StarBase.Android.Forum.net.CapTransport
import StarBase.Android.Forum.net.Net
import StarBase.Android.Forum.net.Site
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class CapTransportTest {
    private val cap = CapChallenge("https://cap.linux.sb/example/", "https://cap.linux.sb/assets/widget.js",
        "https://cap.linux.sb/assets/widget.wasm", "cap_token")

    @Test fun assetsAndApiBodiesUseTheSuppliedResolverAndClient() {
        MockWebServer().use { server ->
            var resolutions = 0
            val client = client(server).newBuilder().dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    assertEquals("cap.linux.sb", hostname)
                    resolutions++
                    return listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
                }
            }).build()
            CapTransport(cap, client).use { transport ->
                for ((url, method, body) in listOf(Triple(cap.scriptUrl, "GET", ""), Triple(cap.wasmUrl, "GET", ""),
                    Triple(cap.apiEndpoint + "challenge", "POST", ""),
                    Triple(cap.apiEndpoint + "redeem", "POST", "{\"token\":\"fixture\",\"solutions\":[1,2]}"))) {
                    server.enqueue(MockResponse().setBody("response"))
                    assertEquals("response", transport.execute(url, method, body).bytes.toString(Charsets.UTF_8))
                    val received = server.takeRequest(2, TimeUnit.SECONDS)!!
                    assertEquals(method, received.method)
                    assertEquals(body, received.body.readUtf8())
                    assertEquals(Site.BASE, received.getHeader("Origin"))
                    assertEquals(Net.userAgent(), received.getHeader("User-Agent"))
                    assertNull(received.getHeader("Cookie"))
                }
                assertTrue(resolutions > 0)
            }
        }
    }

    @Test fun unexpectedUrlsAndMethodsNeverReachTheNetwork() {
        MockWebServer().use { server ->
            CapTransport(cap, client(server)).use { transport ->
                for ((url, method) in listOf(cap.apiEndpoint + "challenge" to "GET", cap.scriptUrl to "POST",
                    "https://linux.sb/login" to "POST", "https://cap.linux.sb/other/redeem" to "POST",
                    "http://cap.linux.sb/example/redeem" to "POST")) {
                    assertThrows(IllegalArgumentException::class.java) { transport.execute(url, method) }
                }
                assertThrows(IllegalArgumentException::class.java) {
                    transport.execute(cap.apiEndpoint + "redeem", "POST", "x".repeat(256 * 1024 + 1))
                }
                assertEquals(0, server.requestCount)
            }
        }
    }

    @Test fun redirectsAndOversizedResponsesAreRejected() {
        MockWebServer().use { server ->
            CapTransport(cap, client(server)).use { transport ->
                server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.org/"))
                assertThrows(IOException::class.java) { transport.execute(cap.scriptUrl, "GET") }
                assertEquals(1, server.requestCount)
                server.enqueue(MockResponse().setBody("x".repeat(1024 * 1024 + 1)))
                assertThrows(IOException::class.java) { transport.execute(cap.apiEndpoint + "challenge", "POST") }
            }
        }
    }

    @Test fun closingTheDialogCancelsAnActiveRequestAndRejectsAnother() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val transport = CapTransport(cap, client(server))
            val pending = CompletableFuture.supplyAsync { runCatching { transport.execute(cap.scriptUrl, "GET") }.exceptionOrNull() }
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS))
            transport.close()
            assertTrue(pending.get(2, TimeUnit.SECONDS) is IOException)
            assertThrows(IllegalStateException::class.java) { transport.execute(cap.scriptUrl, "GET") }
        }
    }

    private fun client(server: MockWebServer): OkHttpClient {
        server.start()
        return OkHttpClient.Builder().proxy(Proxy.NO_PROXY).followRedirects(false)
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
            })
            .addInterceptor { chain ->
                val url = chain.request().url.newBuilder().scheme("http").port(server.port).build()
                chain.proceed(chain.request().newBuilder().url(url).build())
            }.build()
    }
}
