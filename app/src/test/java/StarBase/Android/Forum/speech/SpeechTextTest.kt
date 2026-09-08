package StarBase.Android.Forum.speech

import StarBase.Android.Forum.data.LiveBlock
import StarBase.Android.Forum.data.Post
import StarBase.Android.Forum.data.TopicDetail
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    private fun post(id: Int, authorId: Int = 1, text: String = "Post $id.") = Post(
        id.toString(), "Author $authorId", authorId, blocks = listOf(LiveBlock(LiveBlock.Type.PARA, text)), floor = id
    )
    private fun detail() = TopicDetail(7, "Topic", opening = post(1).copy(isOpening = true),
        comments = listOf(post(2), post(3, 2), post(4), post(4)), commentCount = 60, page = 3, lastPage = 8)

    @Test fun modesIncludeOnlyMatchingLoadedPostsAndDeduplicateReplies() {
        val detail = detail()
        assertEquals(listOf("1"), SpeechText.topic(detail, SpeechMode.OPENING)!!.sentences.map { it.postId })
        assertEquals(listOf("1", "2", "4"), SpeechText.topic(detail, SpeechMode.AUTHOR)!!.sentences.map { it.postId })
        val all = SpeechText.topic(detail, SpeechMode.LOADED)!!
        assertEquals(listOf("1", "2", "3", "4"), all.sentences.map { it.postId })
        assertEquals(3, all.loadedReplies)
        assertEquals(60, all.totalReplies)
        assertTrue(all.scopeLabel.contains("3/60"))
    }

    @Test fun authorModeUsesIdentityAndDoesNotConfuseEqualNames() {
        val detail = detail().copy(comments = listOf(post(2, 99).copy(author = "Author 1")))
        assertEquals(listOf("1"), SpeechText.topic(detail, SpeechMode.AUTHOR)!!.sentences.map { it.postId })
    }

    @Test fun missingOpeningNeverInventsAnAuthorOrOpening() {
        val detail = detail().copy(opening = null)
        assertNull(SpeechText.topic(detail, SpeechMode.OPENING))
        assertNull(SpeechText.topic(detail, SpeechMode.AUTHOR))
        assertEquals(3, SpeechText.topic(detail, SpeechMode.LOADED)!!.sentences.size)
    }

    @Test fun codeAndMediaAreExcludedButHumanLinkLabelsAreReadable() {
        val blocks = listOf(
            LiveBlock(LiveBlock.Type.CODE, "secret command"),
            LiveBlock(LiveBlock.Type.IMAGE, "image alternative", src = "https://images.test/a.png"),
            LiveBlock(LiveBlock.Type.VIDEO, "video caption", src = "https://video.test/a.mp4"),
            LiveBlock(LiveBlock.Type.RULE, "separator"),
            LiveBlock(LiveBlock.Type.LINK, "Documentation", href = "https://docs.test/secret"),
            LiveBlock(LiveBlock.Type.PARA, "Read `rm -rf sample` carefully. Visit https://example.test/path then return."),
            LiveBlock(LiveBlock.Type.LINK, "https://example.test/only")
        )
        val item = SpeechText.topic(detail().copy(opening = post(1).copy(blocks = blocks)), SpeechMode.OPENING)!!
        val spoken = item.sentences.joinToString(" ") { it.text }
        assertTrue(spoken.contains("Documentation"))
        assertTrue(spoken.contains("carefully"))
        listOf("secret", "image alternative", "video caption", "separator", "rm -rf", "http", "example.test").forEach { assertFalse(it, spoken.contains(it)) }
    }

    @Test fun rawUrlsEmailAndCodeFencesDoNotReachSpeech() {
        val text = "Before. ```shell\nprivate-command\n``` www.example.com a@example.com ftp://server.test/file example.org/path mailto:user@example.org After."
        assertEquals("Before. After.", SpeechText.clean(text))
        assertEquals("Before.", SpeechText.clean("Before. ```unclosed code"))
        assertTrue(SpeechText.chunks("https://example.test").isEmpty())
    }

    @Test fun parsedRelativeAddressesAreSkippedWithoutLosingHumanLinkLabels() {
        val text = "Read /topic/123 or the guide."
        val block = LiveBlock(LiveBlock.Type.PARA, text, links = listOf(
            LiveBlock.Link(5, 15, "https://linux.sb/topic/123"),
            LiveBlock.Link(19, 28, "https://example.test/guide")
        ))
        val topic = SpeechText.topic(detail().copy(opening = post(1).copy(blocks = listOf(block,
            LiveBlock(LiveBlock.Type.LINK, "../topic/456", href = "https://linux.sb/topic/456")))), SpeechMode.OPENING)!!
        assertEquals("Read or the guide.", topic.sentences.joinToString(" ") { it.text })
    }

    @Test fun longDottedInputDoesNotOverflowTheRegexStack() {
        val block = LiveBlock(LiveBlock.Type.PARA, "a.".repeat(12_000) + "com")
        SpeechText.topic(detail().copy(opening = post(1).copy(blocks = listOf(block))), SpeechMode.OPENING)
    }

    @Test fun chinesePunctuationAroundUrlsAndSentenceBoundariesArePreserved() {
        val text = "\u4f60\u597d\u3002\u8bbf\u95eehttps://example.test/path\u3002\u7ed3\u675f\uff01"
        assertEquals(listOf("\u4f60\u597d\u3002", "\u8bbf\u95ee \u3002", "\u7ed3\u675f\uff01"), SpeechText.chunks(text))
        assertEquals(listOf("First sentence.", "Second sentence!"), SpeechText.chunks("First sentence. Second sentence!"))
    }

    @Test fun longUnpunctuatedTextIsBoundedWithoutLosingWords() {
        val text = "abcdefgh".repeat(1000)
        val chunks = SpeechText.chunks(text, 53)
        assertTrue(chunks.all { it.length in 1..53 })
        assertEquals(text, chunks.joinToString(""))
    }

    @Test fun supplementaryChineseCharactersAreNotDroppedOrSplit() {
        val text = "\uD840\uDC00".repeat(100)
        val chunks = SpeechText.chunks(text, 7)
        assertTrue(chunks.all { it.length <= 7 && !it.first().isLowSurrogate() && !it.last().isHighSurrogate() })
        assertEquals(text, chunks.joinToString(""))
        assertEquals(Locale.SIMPLIFIED_CHINESE, SpeechText.localeFor(text))
    }

    @Test fun topicWorkAndOutputAreBoundedAndTruncationIsVisible() {
        val opening = post(1).copy(blocks = List(2000) { LiveBlock(LiveBlock.Type.PARA, "Words for speech. ".repeat(100)) })
        val topic = SpeechText.topic(detail().copy(opening = opening), SpeechMode.OPENING)!!
        assertTrue(topic.truncated)
        assertTrue(topic.characterCount <= SpeechText.MAX_TOPIC_CHARS)
        assertTrue(topic.sentences.size <= SpeechText.MAX_SENTENCES)
        assertTrue(topic.sentences.all { it.text.length <= SpeechText.MAX_UTTERANCE_CHARS })
    }

    @Test fun topicsWithOnlySkippedBlocksAreNotQueued() {
        val detail = detail().copy(opening = post(1).copy(blocks = listOf(LiveBlock(LiveBlock.Type.CODE, "println(1)"))))
        assertNull(SpeechText.topic(detail, SpeechMode.OPENING))
        assertNotNull(SpeechText.topic(detail, SpeechMode.LOADED))
    }
}
