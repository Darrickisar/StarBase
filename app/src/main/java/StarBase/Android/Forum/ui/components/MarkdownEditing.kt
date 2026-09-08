package StarBase.Android.Forum.ui.components

data class MarkdownValue(val text: String, val start: Int = text.length, val end: Int = start) {
    fun clamped() = copy(start = start.coerceIn(0, text.length), end = end.coerceIn(0, text.length))
}

enum class MarkdownAction { BOLD, ITALIC, HEADING, QUOTE, BULLET, NUMBERED, INLINE_CODE, CODE_BLOCK, LINK }

/** Text and selection form one undo transaction, including toolbar insertions. */
class MarkdownHistory(initial: MarkdownValue, private val limit: Int = 100) {
    private val past = ArrayDeque<MarkdownValue>()
    private val future = ArrayDeque<MarkdownValue>()
    var value: MarkdownValue = initial.clamped()
        private set
    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    fun update(next: MarkdownValue) {
        val valid = next.clamped()
        if (valid.text != value.text) {
            past.addLast(value)
            while (past.size > limit || (past.size > 1 && past.sumOf { it.text.length.toLong() } > 1_000_000L)) {
                past.removeFirst()
            }
            future.clear()
        }
        value = valid
    }

    fun undo(): MarkdownValue {
        if (canUndo) {
            future.addLast(value)
            value = past.removeLast()
        }
        return value
    }

    fun redo(): MarkdownValue {
        if (canRedo) {
            past.addLast(value)
            value = future.removeLast()
        }
        return value
    }
}

fun insertMarkdown(value: MarkdownValue, insertion: String): MarkdownValue {
    val valid = value.clamped()
    val start = minOf(valid.start, valid.end)
    val end = maxOf(valid.start, valid.end)
    return MarkdownValue(valid.text.replaceRange(start, end, insertion), start + insertion.length)
}

fun formatMarkdown(value: MarkdownValue, action: MarkdownAction): MarkdownValue {
    val valid = value.clamped()
    val start = minOf(valid.start, valid.end)
    val end = maxOf(valid.start, valid.end)
    val selected = valid.text.substring(start, end)

    fun wrap(before: String, after: String): MarkdownValue = MarkdownValue(
        text = valid.text.replaceRange(start, end, before + selected + after),
        start = start + before.length,
        end = end + before.length
    )

    fun prefixLines(prefix: (Int) -> String): MarkdownValue {
        val lineStart = if (start == 0) 0 else valid.text.lastIndexOf('\n', start - 1) + 1
        // A selection ending at the next line's beginning does not include that line.
        val lastSelected = if (end > start && valid.text.getOrNull(end - 1) == '\n') end - 1 else end
        val lineEnd = valid.text.indexOf('\n', lastSelected).takeIf { it >= 0 } ?: valid.text.length
        val original = valid.text.substring(lineStart, lineEnd)
        val replacement = original.split('\n').mapIndexed { index, line -> prefix(index) + line }.joinToString("\n")
        return if (start == end) {
            MarkdownValue(valid.text.replaceRange(lineStart, lineEnd, replacement), start + prefix(0).length)
        } else {
            MarkdownValue(valid.text.replaceRange(lineStart, lineEnd, replacement), lineStart, lineStart + replacement.length)
        }
    }

    return when (action) {
        MarkdownAction.BOLD -> wrap("**", "**")
        MarkdownAction.ITALIC -> wrap("*", "*")
        MarkdownAction.HEADING -> prefixLines { "## " }
        MarkdownAction.QUOTE -> prefixLines { "> " }
        MarkdownAction.BULLET -> prefixLines { "- " }
        MarkdownAction.NUMBERED -> prefixLines { "${it + 1}. " }
        MarkdownAction.INLINE_CODE -> {
            val longest = Regex("`+").findAll(selected).maxOfOrNull { it.value.length } ?: 0
            val delimiter = "`".repeat(longest + 1)
            val padding = if (selected.startsWith('`') || selected.endsWith('`')) " " else ""
            wrap(delimiter + padding, padding + delimiter)
        }
        MarkdownAction.CODE_BLOCK -> {
            val longest = Regex("`+").findAll(selected).maxOfOrNull { it.value.length } ?: 0
            val fence = "`".repeat(maxOf(3, longest + 1))
            val before = (if (start > 0 && valid.text[start - 1] != '\n') "\n" else "") + fence + "\n"
            val after = "\n" + fence + (if (end < valid.text.length && valid.text[end] != '\n') "\n" else "")
            wrap(before, after)
        }
        MarkdownAction.LINK -> {
            val label = selected.ifEmpty { "链接" }
            val insertion = "[$label](https://)"
            val urlStart = start + label.length + 3
            MarkdownValue(valid.text.replaceRange(start, end, insertion), urlStart, urlStart + 8)
        }
    }
}
