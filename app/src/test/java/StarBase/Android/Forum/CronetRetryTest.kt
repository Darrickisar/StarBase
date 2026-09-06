package StarBase.Android.Forum

import StarBase.Android.Forum.net.CronetAttempt
import StarBase.Android.Forum.net.Net
import StarBase.Android.Forum.net.canRetryWithCronet
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.Proxy

class CronetRetryTest {
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
}
