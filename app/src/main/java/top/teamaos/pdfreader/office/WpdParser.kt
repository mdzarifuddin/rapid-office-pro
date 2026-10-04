package top.teamaos.pdfreader.office

import java.io.File
import java.io.RandomAccessFile

/**
 * Reading WordPerfect 5.x and 6 and later documents (.wpd, .wp, .wp5, .wp6) as text.
 *
 * A WordPerfect file is a stream of characters with formatting codes woven in: single bytes for
 * the simplest ones, fixed-length groups that begin and end with the same byte, and
 * variable-length groups that carry their own size. Reading it as text is a matter of walking the
 * stream, keeping the characters and stepping over every code by its declared length. The
 * formatting those codes describe is not reproduced; the words and paragraphs are.
 *
 * Every length is checked against what is left of the file before it is trusted, so random or
 * damaged data ends the walk early rather than running past the end or never finishing.
 */
object WpdParser {

    fun parse(file: File, displayName: String): OfficeDocument? =
        runCatching { read(file, displayName) }.getOrNull()

    /** True for the WordPerfect signature: FF 'W' 'P' 'C' at the start of the file. */
    fun isWordPerfect(head: ByteArray): Boolean =
        head.size >= 16 && head[0] == 0xFF.toByte() && head[1] == 'W'.code.toByte() &&
            head[2] == 'P'.code.toByte() && head[3] == 'C'.code.toByte()

    /** True when [file] is a password-protected WordPerfect document. */
    fun isProtected(file: File): Boolean = runCatching {
        val head = readHead(file) ?: return@runCatching false
        isWordPerfect(head) && u16(head, 12) != 0
    }.getOrDefault(false)

    private fun readHead(file: File): ByteArray? = RandomAccessFile(file, "r").use { input ->
        if (input.length() < 16) return@use null
        ByteArray(16).also { input.readFully(it) }
    }

    private fun read(file: File, displayName: String): OfficeDocument? {
        val length = file.length()
        if (length < 16 || length > MAX_FILE_BYTES) return null
        val data = file.readBytes()
        if (!isWordPerfect(data)) return null
        // Bytes 12-13 hold the encryption key's checksum; non-zero means a password.
        if (u16(data, 12) != 0) return null
        val start = i32le(data, 4)
        if (start < 16 || start >= data.size) return null
        val major = data[10].toInt() and 0xFF
        val text = if (major >= 2) Walker(data, start).wp6() else Walker(data, start).wp5()
        val paragraphs = paragraphs(text)
        if (paragraphs.isEmpty()) return null
        return OfficeDocument(
            kind = OfficeKind.WORD,
            displayName = displayName,
            sections = listOf(Section.Flow(displayName, paragraphs.map { Block.Text(it) })),
            limitationNote = "This is a WordPerfect file. Its text is shown; the original fonts " +
                "and formatting are not.",
        )
    }

    /** The walked text in paragraphs, with runs of spaces collapsed and blank lines dropped. */
    private fun paragraphs(text: String): List<Paragraph> {
        val result = ArrayList<Paragraph>()
        for (line in text.splitToSequence('\n')) {
            val clean = line.replace(MULTIPLE_SPACES, " ").trim()
            if (clean.isEmpty()) continue
            if (result.size >= MAX_PARAGRAPHS) break
            result += Paragraph(listOf(TextSpan(clean.take(MAX_LINE_CHARS))), spaceAfterPt = 6f)
        }
        return result
    }

    private class Walker(private val data: ByteArray, private var position: Int) {
        private val out = StringBuilder()
        private val full: Boolean get() = out.length >= MAX_CHARS

        /**
         * WordPerfect 5.x.
         *
         * 0x20-0x7E are characters; 0x0A is a hard return and 0x0D a soft one; 0x80-0xBF are
         * one-byte codes; 0xC0-0xCF are fixed-length groups ending with their own first byte
         * (0xC0 is a character from another character set: code, character, set, code); and
         * 0xD0-0xFE are variable-length groups: code, subcode, a two-byte length, then that many
         * bytes, the last of which is the code again.
         */
        fun wp5(): String {
            while (position < data.size && !full) {
                val code = data[position].toInt() and 0xFF
                when {
                    code in 0x20..0x7E -> { out.append(code.toChar()); position++ }
                    code == 0x0A || code == 0x0C -> { out.append('\n'); position++ }
                    code == 0x0D || code == 0x0B -> { out.append(' '); position++ }
                    code == 0x09 -> { out.append('\t'); position++ }
                    code < 0x20 || code == 0x7F -> position++
                    code in 0x80..0xBF -> {
                        when (code) {
                            0x8C -> out.append('\n')
                            0xA0 -> out.append(' ')
                            0xA9, 0xAA, 0xAB -> out.append('-')
                        }
                        position++
                    }
                    code == 0xC0 -> {
                        if (position + 3 < data.size && (data[position + 3].toInt() and 0xFF) == 0xC0) {
                            out.append(character(data[position + 2].toInt() and 0xFF, data[position + 1].toInt() and 0xFF))
                            position += 4
                        } else {
                            position++
                        }
                    }
                    code in 0xC1..0xCF -> {
                        // A tab or indent leaves a gap in the text where it was.
                        if (code == 0xC1) out.append(' ')
                        skipFixed(code, WP5_FIXED[code - 0xC0])
                    }
                    code in 0xD0..0xFE -> {
                        // Length counts everything after the length field, closing code included.
                        val size = u16(data, position + 2)
                        val end = position + 4 + size
                        if (position + 4 <= data.size && size > 0 && end <= data.size &&
                            (data[end - 1].toInt() and 0xFF) == code
                        ) {
                            position = end
                        } else {
                            skipToClosing(code)
                        }
                    }
                    else -> position++
                }
            }
            return out.toString()
        }

        /**
         * WordPerfect 6 and later.
         *
         * 0x20-0x7E are characters; 0x80-0xCF are one-byte codes, 0xCC a hard return and 0xCF a
         * soft one; 0xD0-0xEF are variable-length groups: code, subgroup, a two-byte length
         * counting the whole group, ending with the code; 0xF0 is a character from another set
         * (code, character, set, code); 0xF1-0xFE are fixed-length groups ending with their code.
         */
        fun wp6(): String {
            while (position < data.size && !full) {
                val code = data[position].toInt() and 0xFF
                when {
                    code in 0x20..0x7E -> { out.append(code.toChar()); position++ }
                    code == 0x0A -> { out.append('\n'); position++ }
                    code == 0x09 -> { out.append('\t'); position++ }
                    code < 0x20 || code == 0x7F -> position++
                    code in 0x80..0xCF -> {
                        when (code) {
                            0x80, 0x81 -> out.append(' ')
                            0x84 -> out.append('-')
                            0xC7, 0xCC -> out.append('\n')
                            in 0xC8..0xCB, 0xCD, 0xCE, 0xCF -> out.append(' ')
                        }
                        position++
                    }
                    code in 0xD0..0xEF -> {
                        val size = u16le(data, position + 2)
                        val end = position + size
                        if (size >= 4 && end <= data.size && (data[end - 1].toInt() and 0xFF) == code) {
                            // A tab, indent or hard space group still separates the words around it.
                            if (code == 0xE0 || code == 0xE1) out.append(' ')
                            position = end
                        } else {
                            skipToClosing(code)
                        }
                    }
                    code == 0xF0 -> {
                        if (position + 3 < data.size && (data[position + 3].toInt() and 0xFF) == 0xF0) {
                            out.append(character(data[position + 2].toInt() and 0xFF, data[position + 1].toInt() and 0xFF))
                            position += 4
                        } else {
                            position++
                        }
                    }
                    code in 0xF1..0xFE -> skipFixed(code, WP6_FIXED[code - 0xF0])
                    else -> position++
                }
            }
            return out.toString()
        }

        /** Step over a fixed-length group; when its closing byte is not where expected, look for it. */
        private fun skipFixed(code: Int, size: Int) {
            val end = position + size
            if (size >= 2 && end <= data.size && (data[end - 1].toInt() and 0xFF) == code) {
                position = end
            } else {
                skipToClosing(code)
            }
        }

        /** The next occurrence of [code] within a short distance closes the group; otherwise skip the one byte. */
        private fun skipToClosing(code: Int) {
            val limit = minOf(data.size, position + MAX_SEARCH)
            var index = position + 1
            while (index < limit) {
                if ((data[index].toInt() and 0xFF) == code) {
                    position = index + 1
                    return
                }
                index++
            }
            position++
        }
    }

    /**
     * A character from one of WordPerfect's character sets: set 1 (multinational) and set 4
     * (typographic symbols) cover nearly everything Western documents use. Others become '?'.
     */
    private fun character(set: Int, number: Int): String = when (set) {
        0 -> if (number in 0x20..0x7E) number.toChar().toString() else "?"
        1 -> MULTINATIONAL.getOrNull(number - MULTINATIONAL_FIRST)?.toString() ?: "?"
        4 -> TYPOGRAPHIC.getOrNull(number)?.toString() ?: "?"
        else -> "?"
    }

    private fun u16(data: ByteArray, offset: Int): Int = u16le(data, offset)

    private fun u16le(data: ByteArray, offset: Int): Int =
        if (offset < 0 || offset + 2 > data.size) 0
        else (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun i32le(data: ByteArray, offset: Int): Int =
        if (offset < 0 || offset + 4 > data.size) -1
        else (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8) or
            ((data[offset + 2].toInt() and 0xFF) shl 16) or ((data[offset + 3].toInt() and 0xFF) shl 24)

    /** Total sizes of WP5 fixed-length groups 0xC0-0xCF, both code bytes included. */
    private val WP5_FIXED = intArrayOf(4, 9, 11, 3, 3, 5, 6, 7, 4, 4, 4, 4, 4, 4, 4, 4)

    /** Total sizes of WP6 fixed-length groups 0xF0-0xFE. */
    private val WP6_FIXED = intArrayOf(4, 5, 3, 3, 3, 3, 4, 4, 4, 5, 5, 6, 6, 8, 8)

    /** Set 1 from character 23: the accented Latin letters, capital then small. */
    private const val MULTINATIONAL_FIRST = 23
    private const val MULTINATIONAL =
        "ßÁáÂâÄäÀàÅåÆæÇçÉéÊêËëÈèÍíÎîÏïÌìÑñÓóÔôÖöÒòÚúÛûÜüÙùŸÿÃãĐđØøÕõÝýÐðÞþ"

    private const val TYPOGRAPHIC =
        "•◦▪·*¶§¡¿«»£¥₧ƒªº½¼¢²ⁿ®©¤¾³‛’‘‟”“–—‹›○□†‡™℠℞●○■□"

    private val MULTIPLE_SPACES = Regex("[ \\t]{2,}")
    private const val MAX_FILE_BYTES = 64L * 1024 * 1024
    private const val MAX_CHARS = 4 * 1024 * 1024
    private const val MAX_PARAGRAPHS = 50_000
    private const val MAX_LINE_CHARS = 4000
    private const val MAX_SEARCH = 1024
}
