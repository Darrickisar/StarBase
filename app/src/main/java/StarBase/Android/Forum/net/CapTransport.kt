package StarBase.Android.Forum.net

import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.UUID

/** The widget can fetch only its declared assets and two verification endpoints. */
internal class CapTransport(private val challenge: CapChallenge, private val client: OkHttpClient) : AutoCloseable {
    data class Result(val status: Int, val mimeType: String, val bytes: ByteArray)

    private val endpoint = challenge.apiEndpoint.toHttpUrl().newBuilder().apply {
        if (!challenge.apiEndpoint.toHttpUrl().encodedPath.endsWith('/')) addPathSegment("")
    }.build()
    private val apiUrls = setOf(endpoint.resolve("challenge").toString(), endpoint.resolve("redeem").toString())
    private val calls = mutableMapOf<String, Call>()
    private var closed = false

    fun isAsset(url: String): Boolean = url == challenge.scriptUrl || url == challenge.wasmUrl

    fun execute(url: String, method: String, body: String = "", id: String = UUID.randomUUID().toString()): Result {
        val asset = method == "GET" && isAsset(url) && body.isEmpty()
        val api = method == "POST" && url in apiUrls
        require(asset || api) { "Unsupported verification request" }
        require(body.toByteArray(Charsets.UTF_8).size <= 256 * 1024) { "Verification request too large" }
        val request = Request.Builder().url(url)
            .header("User-Agent", Net.userAgent())
            .header("Accept-Language", "zh-CN,zh;q=0.9")
            .header("Origin", Site.BASE)
            .header("Referer", Site.BASE + "/")
            .apply {
                if (api) post(body.toRequestBody("application/json".toMediaType())) else get()
            }.build()
        val call = client.newCall(request)
        synchronized(calls) {
            check(!closed && id !in calls && calls.size < 8) { "Verification request unavailable" }
            calls[id] = call
        }
        try {
            return call.execute().use { response ->
                if (response.code in 300..399) throw IOException("Verification redirect refused")
                val payload = response.body ?: throw IOException("Empty verification response")
                val limit = if (asset) 4L * 1024 * 1024 else 1024L * 1024
                if (payload.contentLength() > limit || payload.source().request(limit + 1)) {
                    throw IOException("Verification response too large")
                }
                val type = payload.contentType()
                Result(response.code, if (type == null) "application/octet-stream" else "${type.type}/${type.subtype}",
                    payload.bytes())
            }
        } finally { synchronized(calls) { calls.remove(id) } }
    }

    fun cancel(id: String) { synchronized(calls) { calls[id] }?.cancel() }

    override fun close() {
        val pending = synchronized(calls) { closed = true; calls.values.toList() }
        pending.forEach { it.cancel() }
    }
}
