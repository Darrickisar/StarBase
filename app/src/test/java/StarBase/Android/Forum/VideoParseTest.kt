package StarBase.Android.Forum

import StarBase.Android.Forum.net.Parse
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.*
import org.junit.Test

class VideoParseTest {
    private fun body(markup: String) = Parse.topic(20390, 1, """
        <h1 class="post-topic-title">Media</h1>
        <ul class="topic-post-list"><li class="post-item">
        <div class="post-content">$markup</div></li></ul>
    """).opening!!.blocks

    @Test fun deferredDouyinEmbedFrom20390SurvivesBlankSrc() {
        val blocks = body("""<div data-long-content-fold><div><iframe src="about:blank"
            data-nb-editor-douyin-src="https://open.douyin.com/player/video?vid=7682252376552023985&amp;autoplay=0"
            title="Douyin"></iframe></div></div>""")
        assertEquals(1, blocks.size)
        assertEquals("VIDEO", blocks.single().type.name)
        val url = blocks.single().src.toHttpUrl()
        assertEquals("open.douyin.com", url.host)
        assertEquals("7682252376552023985", url.queryParameter("vid"))
        assertEquals("0", url.queryParameter("autoplay"))
        assertEquals("100%", url.queryParameter("width"))
        assertEquals("100vh", url.queryParameter("height"))
    }

    @Test fun nativeVideoSourcesPreserveOrderAndDoNotDuplicateFallbackText() {
        val blocks = body("""<p>Before</p><video controls poster="/poster.jpg">
            <source src="/movie.mp4" type="video/mp4">Unsupported video</video><p>After</p>""")
        assertEquals(listOf("PARA", "VIDEO", "PARA"), blocks.map { it.type.name })
        assertEquals("https://linux.sb/movie.mp4", blocks[1].src)
    }

    @Test fun nestedMediaAndDirectVideoLinksRemainPlayable() {
        val blocks = body("""<p>Watch <video src="https://media.example/video.webm"></video></p>
            <p><a href="https://media.example/stream.m3u8?token=demo">Stream</a></p>""")
        assertEquals(2, blocks.count { it.type.name == "VIDEO" })
    }

    @Test fun untrustedFramesAndExecutableSourcesAreNotPlayers() {
        val blocks = body("""<iframe src="https://open.douyin.com.evil.example/player/video?vid=1"></iframe>
            <iframe src="javascript:alert(1)"></iframe><video src="file:///secret.mp4"></video>
            <script>privateScript()</script><p>Still visible</p>""")
        assertFalse(blocks.any { it.type.name == "VIDEO" })
        assertEquals(listOf("Still visible"), blocks.map { it.text })
    }

    @Test fun sourcesCanFallBackWhenTheFirstOneIsNotUsable() {
        val blocks = body("""<video src="blob:expired"><source src="https://cdn.example/movie.mp4"></video>""")
        assertEquals("https://cdn.example/movie.mp4", blocks.single().src)
    }
}
