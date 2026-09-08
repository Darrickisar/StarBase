package StarBase.Android.Forum.net

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

/** Media URLs are parsed before either native playback or a scripted player is created. */
object MediaLinks {
    private val base = Site.BASE.toHttpUrl()

    fun https(raw: String): HttpUrl? = base.resolve(raw.trim())?.takeIf {
        raw.isNotBlank() && it.isHttps && it.username.isEmpty() && it.password.isEmpty()
    }

    fun direct(raw: String): String? = https(raw)?.takeIf {
        it.pathSegments.lastOrNull().orEmpty().substringAfterLast('.').lowercase() in
            setOf("mp4", "m4v", "webm", "m3u8", "mov", "mpd")
    }?.toString()

    fun embed(raw: String): String? = https(raw)?.takeIf { url ->
        when (url.host) {
            "open.douyin.com" -> url.encodedPath == "/player/video" &&
                url.queryParameter("vid")?.all(Char::isDigit) == true &&
                !url.queryParameter("vid").isNullOrEmpty()
            "player.bilibili.com" -> url.encodedPath == "/player.html" || url.encodedPath == "/player.html/"
            "www.youtube.com", "www.youtube-nocookie.com" -> url.encodedPath.startsWith("/embed/")
            "player.vimeo.com" -> url.encodedPath.startsWith("/video/")
            else -> false
        }
    }?.let { url ->
        url.newBuilder().setQueryParameter("autoplay", "0").apply {
            if (url.host == "open.douyin.com") {
                setQueryParameter("width", "100%")
                // The provider's root has no explicit height, so percentages collapse.
                setQueryParameter("height", "100vh")
            }
        }.build().toString()
    }

    fun provider(raw: String): String = when (https(raw)?.host) {
        "open.douyin.com" -> "抖音"
        "player.bilibili.com" -> "哔哩哔哩"
        "www.youtube.com", "www.youtube-nocookie.com" -> "YouTube"
        "player.vimeo.com" -> "Vimeo"
        else -> "视频"
    }
}
