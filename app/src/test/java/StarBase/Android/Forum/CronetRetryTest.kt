package StarBase.Android.Forum

import StarBase.Android.Forum.net.CronetAttempt
import StarBase.Android.Forum.net.CronetFallbackInterceptor
import StarBase.Android.Forum.net.CronetRouteMemory
import StarBase.Android.Forum.net.EchSocketFactory
import StarBase.Android.Forum.net.Net
import StarBase.Android.Forum.net.canRetryWithCronet
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Proxy
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException

class CronetRetryTest {
    @Test fun healthyHttpsKeepsTheConfiguredResponseTimeout() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost")
            .addSubjectAlternativeName("127.0.0.1").addSubjectAlternativeName("::1").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        for (protocols in listOf(listOf(Protocol.HTTP_1_1), listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))) {
            MockWebServer().use { server ->
                server.protocols = protocols
                server.useHttps(serverTls.sslSocketFactory(), false)
                server.enqueue(MockResponse().setBody("delayed page").setBodyDelay(1500, TimeUnit.MILLISECONDS))
                val client = Net.client.newBuilder().proxy(Proxy.NO_PROXY)
                    .dispatcher(okhttp3.Dispatcher()).connectionPool(okhttp3.ConnectionPool())
                    .protocols(protocols)
                    .sslSocketFactory(EchSocketFactory(clientTls.sslSocketFactory(), handshakeTimeoutMs = 1_000), clientTls.trustManager)
                    .readTimeout(5, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS)
                    .addInterceptor(CronetFallbackInterceptor { _, _, _ -> throw AssertionError("Healthy TLS must not fall back") })
                    .build()
                try {
                    client.newCall(Request.Builder().url(server.url("/")).build()).execute().use {
                        assertEquals(protocols.first(), it.protocol)
                        assertEquals("delayed page", it.body!!.string())
                    }
                } finally {
                    client.connectionPool.evictAll()
                    client.dispatcher.executorService.shutdownNow()
                }
            }
        }
    }

    @Test fun subsequentPagesReuseTheWorkingFallback() {
        var tcpCalls = 0
        var fallbackCalls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(CronetFallbackInterceptor { request, _, _ ->
                fallbackCalls++
                response(request)
            })
            .addInterceptor { tcpCalls++; throw IOException("Connection reset") }
            .build()
        for (path in listOf("/", "/next")) {
            client.newCall(Request.Builder().url("https://linux.sb$path").build()).execute().close()
        }
        assertEquals("Do not repeat a failed handshake for every page", 1, tcpCalls)
        assertEquals(2, fallbackCalls)
    }

    @Test fun aStalledTlsHandshakeLeavesTimeForTheFallback() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.STALL_SOCKET_AT_START))
            val client = Net.client.newBuilder().proxy(Proxy.NO_PROXY)
                .dispatcher(okhttp3.Dispatcher()).connectionPool(okhttp3.ConnectionPool())
                .retryOnConnectionFailure(false)
                .readTimeout(15, TimeUnit.SECONDS).callTimeout(6, TimeUnit.SECONDS)
                .addInterceptor(CronetFallbackInterceptor { request, call, _ ->
                    assertFalse("TCP timeout must not cancel the whole call", call.isCanceled())
                    response(request)
                }).build()
            try {
                val url = server.url("/").newBuilder().scheme("https").build()
                val outcome = runCatching { client.newCall(Request.Builder().url(url).build()).execute() }
                assertTrue("Fallback must run before total timeout: ${outcome.exceptionOrNull()}", outcome.isSuccess)
                outcome.getOrNull()?.close()
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    @Test fun aFailedPreferredRouteReturnsToTcpAndIsForgotten() {
        var tcpCalls = 0
        var fallbackCalls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(CronetFallbackInterceptor { request, _, _ ->
                if (++fallbackCalls > 1) throw IOException("UDP unavailable")
                response(request)
            })
            .addInterceptor { chain ->
                if (++tcpCalls == 1) throw IOException("Connection reset")
                response(chain.request())
            }.build()
        repeat(3) { client.newCall(page()).execute().close() }
        assertEquals(3, tcpCalls)
        assertEquals("The failed preference must not delay the next request", 2, fallbackCalls)
    }

    @Test fun routePreferenceExpiresAndDoesNotCrossNetworksOrHosts() {
        var now = 0L
        var network = "wifi"
        var tcpCalls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(CronetFallbackInterceptor(CronetRouteMemory { now }, { network }) { request, _, _ ->
                response(request)
            })
            .addInterceptor { tcpCalls++; throw IOException("Connection reset") }.build()
        client.newCall(page()).execute().close()
        client.newCall(page()).execute().close()
        assertEquals(1, tcpCalls)
        client.newCall(page("https://images.example.com/")).execute().close()
        assertEquals(2, tcpCalls)
        network = "mobile"
        client.newCall(page()).execute().close()
        assertEquals(3, tcpCalls)
        now += TimeUnit.MINUTES.toNanos(6)
        client.newCall(page()).execute().close()
        assertEquals(4, tcpCalls)
    }

    @Test fun preferredCertificateFailureDoesNotTriggerAnotherTransport() {
        var tcpCalls = 0
        var fallbackCalls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(CronetFallbackInterceptor { request, _, _ ->
                if (++fallbackCalls > 1) throw SSLPeerUnverifiedException("Invalid certificate")
                response(request)
            })
            .addInterceptor { tcpCalls++; throw IOException("Connection reset") }.build()
        client.newCall(page()).execute().close()
        val failure = runCatching { client.newCall(page()).execute().close() }.exceptionOrNull()
        assertTrue(failure is SSLPeerUnverifiedException)
        assertEquals(1, tcpCalls)
    }

    @Test fun aRememberedRouteDoesNotReplayAnUncertainPost() {
        var tcpCalls = 0
        var fallbackCalls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(CronetFallbackInterceptor { request, _, _ ->
                fallbackCalls++
                response(request)
            })
            .addInterceptor { chain ->
                tcpCalls++
                chain.request().tag(CronetAttempt::class.java)?.mayHaveSent = true
                throw IOException("Connection reset")
            }.build()
        client.newCall(page()).execute().close()
        val post = page().newBuilder().post("message=one".toRequestBody()).build()
        assertTrue(runCatching { client.newCall(post).execute().close() }.exceptionOrNull() is IOException)
        assertEquals(2, tcpCalls)
        assertEquals(1, fallbackCalls)
    }

    @Test fun aCanceledPreferredRequestDoesNotStartTcp() {
        var tcpCalls = 0
        var fallbackCalls = 0
        val client = OkHttpClient.Builder()
            .addInterceptor(CronetFallbackInterceptor { request, call, _ ->
                if (++fallbackCalls > 1) {
                    call.cancel()
                    throw IOException("Canceled")
                }
                response(request)
            })
            .addInterceptor { tcpCalls++; throw IOException("Connection reset") }.build()
        client.newCall(page()).execute().close()
        assertTrue(runCatching { client.newCall(page()).execute().close() }.exceptionOrNull() is IOException)
        assertEquals(1, tcpCalls)
    }

    @Test
    fun aPostReceivedBeforeTheConnectionBreaksCannotBeReplayed() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            val attempt = CronetAttempt()
            val request = Request.Builder().url(server.url("/submit"))
                .tag(CronetAttempt::class.java, attempt)
                .post("message=one".toRequestBody()).build()
            val client = Net.client.newBuilder().proxy(Proxy.NO_PROXY)
                .dispatcher(okhttp3.Dispatcher()).connectionPool(okhttp3.ConnectionPool())
                .retryOnConnectionFailure(false).build()
            try {
                val error = runCatching { client.newCall(request).execute().use { } }.exceptionOrNull()
                assertTrue(error is IOException)
                assertTrue("OkHttp must report that the request reached the sending stage", attempt.mayHaveSent)
                val httpsRequest = request.newBuilder().url("https://linux.sb/submit").build()
                assertFalse(canRetryWithCronet(httpsRequest, error as IOException, attempt.mayHaveSent))
            } finally {
                client.connectionPool.evictAll()
                client.dispatcher.executorService.shutdownNow()
            }
        }
    }

    private fun response(request: Request): Response = Response.Builder().request(request)
        .protocol(Protocol.HTTP_2).code(200).message("OK").body("page".toResponseBody()).build()

    private fun page(url: String = "https://linux.sb/"): Request = Request.Builder().url(url).build()
}
