package StarBase.Android.Forum.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

/**
 * Where this app's own builds are published.
 *
 * [REPO] is the only place the slug lives; pointing the update check at a
 * different repository is this one line.
 */
object Github {
    /** owner/repo of the repository whose Releases this app checks. */
    const val REPO = "Darrickisar/StarBase"

    val latestApi: String get() = "https://api.github.com/repos/$REPO/releases/latest"
    val releasesPage: String get() = "https://github.com/$REPO/releases"
}

/**
 * One published release, as far as the updater cares about it.
 *
 * [apkUrl] is null for a release that has no `.apk` attached - the tag exists,
 * but there is nothing to install, so the UI offers the Release page instead of
 * a download button.
 */
data class ReleaseInfo(
    val tag: String,
    val name: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String? = null,
    val apkName: String = "",
    val apkSize: Long = 0L,
    val publishedAt: String = "",
    val prerelease: Boolean = false
)

/**
 * The GitHub Releases side of the app.
 *
 * The parsing and comparing halves are pure and take strings, so they are unit
 * tested without a network or an Android runtime. JSON is read as a tree rather
 * than deserialised into classes: R8 strips this build, and a reflective model
 * would need keep rules to survive it.
 */
object Releases {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Reads `/releases/latest`. Returns null when the payload is not a release
     * object at all - GitHub answers a repo with no published release with a
     * `{"message":"Not Found"}` body, which is not an error worth shouting
     * about: it just means there is nothing to update to.
     */
    fun parse(body: String): ReleaseInfo? {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw SiteException("看不懂 GitHub 返回的内容", SiteException.Kind.PARSE)
        }
        val tag = root.text("tag_name")
        if (tag.isEmpty()) return null
        if (root.flag("draft")) return null

        val asset = root["assets"]?.let { assets ->
            runCatching { assets.jsonArray }.getOrNull()
        }?.mapNotNull { element ->
            runCatching { element.jsonObject }.getOrNull()
        }?.firstOrNull { it.text("name").endsWith(".apk", ignoreCase = true) }

        return ReleaseInfo(
            tag = tag,
            name = root.text("name").ifEmpty { tag },
            notes = root.text("body").trim(),
            pageUrl = root.text("html_url").ifEmpty { Github.releasesPage },
            apkUrl = asset?.text("browser_download_url")?.ifEmpty { null },
            apkName = asset?.text("name") ?: "",
            apkSize = asset?.get("size")?.let { it.jsonPrimitive.content.toLongOrNull() } ?: 0L,
            publishedAt = root.text("published_at"),
            prerelease = root.flag("prerelease")
        )
    }

    private fun JsonObject.text(key: String): String =
        this[key]?.let { value ->
            runCatching { value.jsonPrimitive.content }.getOrNull()
        }?.takeIf { it != "null" } ?: ""

    private fun JsonObject.flag(key: String): Boolean =
        this[key]?.let { value ->
            runCatching { value.jsonPrimitive.content == "true" }.getOrNull()
        } ?: false

    /**
     * Compares two version strings the way a release tag is actually written.
     *
     * Whatever comes *before* the numbers is decoration and is ignored: this
     * project tags its releases `StarBaseV1.0.2`, so a comparison that only knew
     * how to drop a leading `v` would read the whole tag as version 0 and never
     * find an update. The numbers are then compared segment by segment, and a
     * missing segment counts as zero, so `1.1` and `1.1.0` are one version.
     *
     * Anything *after* the numbers - `-beta`, `-rc1` - sorts below the plain
     * version, because a pre-release of 1.1.0 is not 1.1.0 yet.
     */
    fun compare(a: String, b: String): Int {
        val (leftCore, leftTail) = split(a)
        val (rightCore, rightTail) = split(b)
        for (i in 0 until maxOf(leftCore.size, rightCore.size)) {
            val left = leftCore.getOrElse(i) { 0 }
            val right = rightCore.getOrElse(i) { 0 }
            if (left != right) return left.compareTo(right)
        }
        if (leftTail == rightTail) return 0
        if (leftTail.isEmpty()) return 1
        if (rightTail.isEmpty()) return -1
        return leftTail.compareTo(rightTail)
    }

    /** True when [tag] names a version above [current]. */
    fun isNewer(tag: String, current: String): Boolean =
        tag.isNotBlank() && compare(tag, current) > 0

    /** The version inside a tag: `1.2.3`, preferred over a bare `2`. */
    private val dotted = Regex("""\d+(?:\.\d+)+""")
    private val bare = Regex("""\d+""")

    /** `StarBaseV1.2.3-rc1` -> `[1, 2, 3]` plus `-rc1`. */
    private fun split(raw: String): Pair<List<Int>, String> {
        val text = raw.trim()
        // A dotted run first, so a name that happens to carry a digit of its own
        // ("StarBase2V1.0.3") still hands back the version and not the name.
        val found = dotted.find(text) ?: bare.find(text) ?: return listOf(0) to text.lowercase()
        val numbers = found.value.split('.').mapNotNull { it.toIntOrNull() }
        val tail = text.substring(found.range.last + 1).lowercase()
        return (numbers.ifEmpty { listOf(0) }) to tail
    }

    /**
     * A tag as a version, for showing next to this build's own: the project's
     * `StarBaseV1.0.3` reads as `v1.0.3` beside `v1.0.2`, rather than making the
     * reader compare two differently-shaped strings.
     */
    fun label(tag: String): String {
        val (numbers, tail) = split(tag)
        if (numbers == listOf(0) && tail.isNotEmpty()) return tag.trim()
        return "v" + numbers.joinToString(".") + tail
    }

    /**
     * Its own client, deliberately not [Net.client]: that one carries the
     * WebView cookie jar and a linux.sb `Referer`, and neither belongs in a
     * request to github.com.
     */
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.SECONDS)
            .build()
    }

    private fun request(url: String): Request.Builder =
        Request.Builder()
            .url(url)
            .header("User-Agent", "StarBase-Android")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")

    /**
     * The newest published release, or null when the repository has none.
     * Blocking - callers run it off the main thread.
     */
    fun latest(): ReleaseInfo? {
        val body = try {
            client.newCall(request(Github.latestApi).build()).execute().use { response ->
                when {
                    // No release published yet, or the repo is private to us.
                    response.code == 404 -> return null
                    response.code == 403 || response.code == 429 ->
                        throw SiteException("GitHub 限制了请求频率，稍后再试")
                    !response.isSuccessful ->
                        throw SiteException("GitHub 返回 ${response.code}")
                    else -> response.body?.string().orEmpty()
                }
            }
        } catch (e: SiteException) {
            throw e
        } catch (e: UnknownHostException) {
            throw SiteException("连不上 GitHub，请检查网络", SiteException.Kind.NETWORK)
        } catch (e: SocketTimeoutException) {
            throw SiteException("GitHub 响应超时，请重试", SiteException.Kind.NETWORK)
        } catch (e: IOException) {
            throw SiteException("检查更新失败：${e.message ?: "网络错误"}", SiteException.Kind.NETWORK)
        }
        return parse(body)
    }

    /**
     * Streams [url] into [dest], reporting bytes read and the total length
     * (0 when the server does not say). Blocking.
     *
     * [onProgress] is also the cancellation point: a caller that throws from it
     * - `ensureActive()` in a coroutine, say - aborts the copy and takes the
     * half-written file with it.
     */
    fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        dest.parentFile?.mkdirs()
        try {
            client.newCall(request(url).header("Accept", "*/*").build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw SiteException("下载失败，GitHub 返回 ${response.code}")
                }
                val body = response.body ?: throw SiteException("下载失败：没有内容")
                val total = body.contentLength().coerceAtLeast(0L)
                var read = 0L
                onProgress(0L, total)
                body.byteStream().use { input ->
                    dest.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            read += n
                            onProgress(read, total)
                        }
                        output.flush()
                    }
                }
            }
        } catch (e: SiteException) {
            dest.delete()
            throw e
        } catch (e: InterruptedIOException) {
            dest.delete()
            throw SiteException("下载已取消", SiteException.Kind.NETWORK)
        } catch (e: IOException) {
            dest.delete()
            throw SiteException("下载失败：${e.message ?: "网络错误"}", SiteException.Kind.NETWORK)
        } catch (e: Throwable) {
            dest.delete()
            throw e
        }
    }
}
