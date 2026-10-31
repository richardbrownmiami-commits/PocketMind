package com.pocketmind.core

/** Host-side convention for asking the model to request one live search. */
internal object SelfSearch {
    const val SYSTEM_RULE = "If you need live or recent information, reply with one line only: SEARCH: <query>. Otherwise answer normally."

    private const val PREFIX = "SEARCH:"

    fun directiveComplete(text: String): Boolean {
        val clean = text.trimStart()
        val lineEnd = clean.indexOfAny(charArrayOf('\n', '\r'))
        if (lineEnd < 0) return false
        return parseQuery(clean.substring(0, lineEnd)) != null
    }

    fun parseQuery(text: String): String? {
        val line = text.trimStart().substringBeforeAny('\n', '\r').trim()
        if (!line.startsWith(PREFIX, ignoreCase = true)) return null
        return line.substring(PREFIX.length).trim().take(240).takeIf { it.isNotEmpty() }
    }

    /** True for a valid or incomplete leading directive, including a split token prefix. */
    fun isDirectiveCandidate(text: String): Boolean {
        val line = text.trimStart().substringBeforeAny('\n', '\r')
        if (line.isEmpty()) return false
        return if (line.length <= PREFIX.length) {
            PREFIX.startsWith(line, ignoreCase = true)
        } else {
            line.startsWith(PREFIX, ignoreCase = true)
        }
    }

    /** Whether more output could still turn this buffered prefix into a directive. */
    fun mayBeDirective(text: String): Boolean {
        val candidate = text.trimStart()
        if (candidate.isEmpty()) return true
        val line = candidate.substringBeforeAny('\n', '\r')
        return if (line.length <= PREFIX.length) {
            PREFIX.startsWith(line, ignoreCase = true)
        } else {
            line.startsWith(PREFIX, ignoreCase = true)
        }
    }

    private fun String.substringBeforeAny(vararg separators: Char): String {
        val index = indexOfAny(separators)
        return if (index < 0) this else substring(0, index)
    }
}

/** Buffers only an ambiguous leading prefix; ordinary chat resumes streaming unchanged. */
internal class SearchStreamGate(private val downstream: (String) -> Unit) {
    private val held = StringBuilder()
    private var decided = false
    private var suppress = false

    fun accept(chunk: String) {
        if (chunk.isEmpty()) return
        if (decided) {
            if (!suppress) downstream(chunk)
            return
        }
        held.append(chunk)
        val buffered = held.toString()
        if (!SelfSearch.mayBeDirective(buffered)) {
            decided = true
            downstream(buffered)
            held.setLength(0)
        } else if (buffered.contains('\n') || buffered.contains('\r')) {
            decided = true
            suppress = true
            held.setLength(0)
        }
    }

    fun finish() {
        if (decided) return
        val buffered = held.toString()
        held.setLength(0)
        decided = true
        if (!SelfSearch.isDirectiveCandidate(buffered)) downstream(buffered)
        else suppress = true
    }
}
