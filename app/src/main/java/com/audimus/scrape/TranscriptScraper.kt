package com.audimus.scrape

import android.view.accessibility.AccessibilityNodeInfo

/**
 * Extracts transcript text from another app's on-screen UI via the accessibility node tree — no
 * audio involved. Transcription/caption apps (Live Transcribe, Pixel Live Caption, browser
 * captions) render their transcript as real text views, so the text is exposed through
 * [AccessibilityNodeInfo.getText].
 *
 * The hard part is emitting only *new* words. Different sources behave differently:
 *  - Live Transcribe grows a long transcript (mostly append-only).
 *  - Pixel **Live Caption** shows a small ROLLING window: old lines scroll off the top while new
 *    ones appear at the bottom, AND the current line is revised in place as recognition firms up
 *    (e.g. "what's" → "what's 1" → "what's 1 plus 1?"). A naive "did the text grow / does it start
 *    with the last text" diff therefore re-emits the whole visible block on every update.
 *
 * So [scrape] works at the WORD level: it keeps a bounded buffer of recently emitted words and, on
 * each update, finds the longest overlap between the tail of what we've already emitted and the head
 * of the currently-visible text, then emits only the words past that overlap. This is robust to the
 * window scrolling and to in-place revision of the trailing line.
 */
class TranscriptScraper {

    /** Called with each newly appeared chunk of transcript text (never a repeat of prior words). */
    var onNewText: ((String) -> Unit)? = null

    /** The whole visible block last seen, to skip no-op updates cheaply. */
    private var lastFull: String = ""

    /** Normalized words already emitted (bounded), used to find the overlap with new updates. */
    private val emitted = ArrayDeque<String>()

    fun scrape(root: AccessibilityNodeInfo?) {
        root ?: return
        val sb = StringBuilder()
        collect(root, sb, depth = 0)
        val full = sb.toString().trim().replace(Regex("\\s+"), " ")
        if (full.isEmpty() || full == lastFull) return
        lastFull = full

        val rawWords = full.split(' ').filter { it.isNotBlank() }
        if (rawWords.isEmpty()) return
        val normWords = rawWords.map(::normalize)

        // Find the largest k where the last k emitted words equal the first k words of this update.
        // That prefix is text we've already sent (it's still visible because the window scrolls);
        // only the words after it are new.
        val maxK = minOf(emitted.size, normWords.size)
        var overlap = 0
        for (k in maxK downTo 1) {
            if (tailEquals(emitted, normWords, k)) { overlap = k; break }
        }

        val newRaw = rawWords.drop(overlap)
        val newNorm = normWords.drop(overlap)
        // Drop words that became blank after normalization (pure punctuation) from the buffer,
        // but keep them in the emitted text so the transcript still reads naturally.
        for (n in newNorm) if (n.isNotEmpty()) {
            emitted.addLast(n)
            if (emitted.size > MAX_BUFFER) emitted.removeFirst()
        }

        val delta = newRaw.joinToString(" ").trim()
        if (delta.isNotBlank()) onNewText?.invoke(delta)
    }

    fun reset() {
        lastFull = ""
        emitted.clear()
    }

    /** True if the last [k] words of [buffer] equal the first [k] of [head]. */
    private fun tailEquals(buffer: ArrayDeque<String>, head: List<String>, k: Int): Boolean {
        val base = buffer.size - k
        for (i in 0 until k) {
            if (buffer.elementAt(base + i) != head[i]) return false
        }
        return true
    }

    /** Lowercase and strip surrounding punctuation so "Hello" / "hello!" compare equal. */
    private fun normalize(word: String): String =
        word.lowercase().trim { !it.isLetterOrDigit() }

    private fun collect(node: AccessibilityNodeInfo?, out: StringBuilder, depth: Int) {
        node ?: return
        if (depth > 12) return
        val text = node.text?.toString()?.trim()
        if (!text.isNullOrEmpty()) {
            if (out.isNotEmpty()) out.append(' ')
            out.append(text)
        }
        for (i in 0 until node.childCount) {
            collect(node.getChild(i), out, depth + 1)
        }
    }

    private companion object {
        /** How many recent words to keep for overlap matching — larger than any caption window. */
        const val MAX_BUFFER = 60
    }
}
