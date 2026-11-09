package org.stypox.dicio.io.input.corrections

import org.stypox.dicio.skills.local_music.LocalMusicMatcher
import java.util.Locale

/**
 * `dicio-words.txt` (UTF-8). Export always writes the commented header and
 * `heard => correct`. Import also accepts CSV (`heard,correct`, simple quotes)
 * and a single tab between the two columns.
 */
object WordListFile {
    const val MAX_WORDS = 2000
    const val MAX_CORRECTIONS = 2000
    const val MAX_LINE_LENGTH = 200

    data class Correction(val heard: String, val correct: String)

    data class Lists(
        val words: List<String>,
        val corrections: List<Correction>,
    )

    data class Result(
        val words: List<String>,
        val corrections: List<Correction>,
        val skippedLineNumbers: List<Int>,
    ) {
        val wordCount: Int get() = words.size
        val correctionCount: Int get() = corrections.size
        val skippedCount: Int get() = skippedLineNumbers.size
    }

    private enum class Section { NONE, WORDS, CORRECTIONS }

    fun parse(text: String): Result {
        val body = if (text.startsWith("\uFEFF")) text.substring(1) else text
        val lines = body.split("\r\n", "\n", "\r")
        val words = ArrayList<String>()
        val wordKeys = LinkedHashMap<String, Int>()
        val corrections = ArrayList<Correction>()
        val correctionKeys = LinkedHashMap<String, Int>()
        val skipped = ArrayList<Int>()
        var section = Section.NONE

        for ((index, rawLine) in lines.withIndex()) {
            val lineNo = index + 1
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            if (line.length > MAX_LINE_LENGTH) {
                skipped.add(lineNo)
                continue
            }
            when (line.lowercase(Locale.ROOT)) {
                "[words]" -> {
                    section = Section.WORDS
                    continue
                }
                "[corrections]" -> {
                    section = Section.CORRECTIONS
                    continue
                }
            }
            if (line.startsWith("[") && line.endsWith("]")) {
                section = Section.NONE
                skipped.add(lineNo)
                continue
            }
            when (section) {
                Section.NONE -> skipped.add(lineNo)
                Section.WORDS -> {
                    val key = LocalMusicMatcher.normalizeName(line)
                    if (key.isEmpty()) {
                        skipped.add(lineNo)
                    } else if (wordKeys.containsKey(key)) {
                        // Duplicate: keep the first spelling.
                    } else if (words.size >= MAX_WORDS) {
                        skipped.add(lineNo)
                    } else {
                        wordKeys[key] = words.size
                        words.add(line)
                    }
                }
                Section.CORRECTIONS -> {
                    val parsed = parseCorrection(line)
                    if (parsed == null) {
                        skipped.add(lineNo)
                    } else {
                        val key = LocalMusicMatcher.normalizeName(parsed.heard)
                        if (correctionKeys.containsKey(key)) {
                            // Duplicate heard phrase: keep the first correction.
                        } else if (corrections.size >= MAX_CORRECTIONS) {
                            skipped.add(lineNo)
                        } else {
                            correctionKeys[key] = corrections.size
                            corrections.add(parsed)
                        }
                    }
                }
            }
        }
        return Result(words, corrections, skipped)
    }

    fun export(words: List<String>, corrections: List<Correction>): String {
        return buildString {
            appendLine("# Dicio word list. Lines starting with # are notes and are ignored.")
            appendLine("# [words]: one known word or title per line.")
            appendLine("# [corrections]: what Dicio hears  =>  what it should be")
            appendLine()
            appendLine("[words]")
            for (word in words) {
                appendLine(word)
            }
            appendLine()
            appendLine("[corrections]")
            for (correction in corrections) {
                append(correction.heard)
                append(" => ")
                appendLine(correction.correct)
            }
        }
    }

    /**
     * Union for "Add to my lists". [base] wins when the normalised form already
     * exists. The result is capped at [MAX_WORDS] and [MAX_CORRECTIONS].
     */
    fun merge(base: Lists, extra: Lists): Lists {
        val words = ArrayList<String>(base.words.size)
        val wordKeys = HashSet<String>()
        fun addWord(word: String) {
            if (words.size >= MAX_WORDS) return
            val trimmed = word.trim()
            val key = LocalMusicMatcher.normalizeName(trimmed)
            if (key.isEmpty() || !wordKeys.add(key)) return
            words.add(trimmed)
        }
        base.words.forEach(::addWord)
        extra.words.forEach(::addWord)

        val corrections = ArrayList<Correction>(base.corrections.size)
        val correctionKeys = HashSet<String>()
        fun addCorrection(correction: Correction) {
            if (corrections.size >= MAX_CORRECTIONS) return
            val heard = correction.heard.trim()
            val correct = correction.correct.trim()
            val key = LocalMusicMatcher.normalizeName(heard)
            if (key.isEmpty() || correct.isEmpty() || !correctionKeys.add(key)) return
            corrections.add(Correction(heard, correct))
        }
        base.corrections.forEach(::addCorrection)
        extra.corrections.forEach(::addCorrection)
        return Lists(words, corrections)
    }

    private fun parseCorrection(line: String): Correction? {
        val arrow = line.indexOf("=>")
        if (arrow >= 0) {
            return correctionOrNull(line.substring(0, arrow), line.substring(arrow + 2))
        }
        if (line.contains('\t')) {
            val parts = line.split('\t')
            if (parts.size != 2) return null
            return correctionOrNull(parts[0], parts[1])
        }
        if (line.contains(',')) {
            val fields = parseCsv(line) ?: return null
            if (fields.size != 2) return null
            return correctionOrNull(fields[0], fields[1])
        }
        return null
    }

    private fun correctionOrNull(heardRaw: String, correctRaw: String): Correction? {
        val heard = heardRaw.trim()
        val correct = correctRaw.trim()
        if (heard.isEmpty() || correct.isEmpty()) return null
        if (LocalMusicMatcher.normalizeName(heard).isEmpty()) return null
        return Correction(heard, correct)
    }

    /**
     * Two-column CSV. A field may be quoted with `"`, and `""` inside quotes is
     * one quote. Returns null when the quotes are not closed.
     */
    private fun parseCsv(line: String): List<String>? {
        val fields = ArrayList<String>(2)
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        sb.append('"')
                        i += 2
                        continue
                    }
                    inQuotes = false
                    i++
                    continue
                }
                sb.append(c)
                i++
                continue
            }
            when (c) {
                '"' -> {
                    if (sb.isNotBlank()) return null
                    sb.clear()
                    inQuotes = true
                    i++
                }
                ',' -> {
                    fields.add(sb.toString())
                    sb.clear()
                    i++
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        if (inQuotes) return null
        fields.add(sb.toString())
        return fields
    }
}
