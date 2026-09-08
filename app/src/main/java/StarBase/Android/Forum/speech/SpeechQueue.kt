package StarBase.Android.Forum.speech

data class SpeechPosition(val revision: Long, val topicId: Long, val sentence: Int)

/** Main-thread owned by the controller; kept Android-free to test callback races. */
internal class SpeechQueue {
    companion object {
        const val MAX_TOPICS = 12
        const val MAX_CHARACTERS = 240_000
    }

    private val entries = mutableListOf<SpeechTopic>()
    private var revision = 0L
    var accountId: Int? = null
        private set
    var topicIndex = 0
        private set
    var sentenceIndex = 0
        private set
    val topics: List<SpeechTopic> get() = entries.toList()
    val current: SpeechTopic? get() = entries.getOrNull(topicIndex)
    val sentence: SpeechSentence? get() = current?.sentences?.getOrNull(sentenceIndex)
    val position: SpeechPosition? get() = current?.let { SpeechPosition(revision, it.id, sentenceIndex) }
    val hasPrevious: Boolean get() = current != null && (sentenceIndex > 0 || topicIndex > 0)
    val hasNext: Boolean get() = current?.let { sentenceIndex < it.sentences.lastIndex || topicIndex < entries.lastIndex } == true

    /** The first bind and same-account Activity recreation must preserve a live session. */
    fun bindAccount(id: Int): Boolean {
        val changed = accountId != null && accountId != id
        accountId = id
        if (changed) clear()
        return changed
    }

    fun accepts(topic: SpeechTopic, replace: Boolean = false): Boolean {
        return !(topic.sentences.isEmpty() || topic.sentences.size > SpeechText.MAX_SENTENCES ||
            topic.sentences.any { it.text.isBlank() || it.text.length > SpeechText.MAX_UTTERANCE_CHARS } ||
            topic.characterCount > SpeechText.MAX_TOPIC_CHARS ||
            (!replace && (entries.size >= MAX_TOPICS || entries.any { it.id == topic.id } ||
            entries.sumOf { it.characterCount } + topic.characterCount > MAX_CHARACTERS)))
    }

    fun enqueue(topic: SpeechTopic): Boolean {
        if (!accepts(topic)) return false
        entries += topic
        return true
    }

    fun invalidate() { revision++ }

    fun clear() {
        invalidate()
        entries.clear()
        topicIndex = 0
        sentenceIndex = 0
    }

    fun matches(expected: SpeechPosition): Boolean = expected == position

    fun complete(expected: SpeechPosition): Boolean {
        if (!matches(expected)) return false
        next()
        return true
    }

    fun next() {
        val topic = current ?: return
        invalidate()
        if (sentenceIndex < topic.sentences.lastIndex) sentenceIndex++
        else if (topicIndex < entries.lastIndex) { topicIndex++; sentenceIndex = 0 }
        else clear()
    }

    fun previous() {
        if (!hasPrevious) return
        invalidate()
        if (sentenceIndex > 0) sentenceIndex--
        else { topicIndex--; sentenceIndex = current!!.sentences.lastIndex }
    }

    fun select(id: Long): Boolean {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return false
        invalidate()
        topicIndex = index
        sentenceIndex = 0
        return true
    }

    fun remove(id: Long): Boolean {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0) return false
        when {
            index < topicIndex -> { entries.removeAt(index); topicIndex-- }
            index > topicIndex -> entries.removeAt(index)
            index < entries.lastIndex -> { invalidate(); entries.removeAt(index); sentenceIndex = 0 }
            else -> clear()
        }
        return true
    }
}
