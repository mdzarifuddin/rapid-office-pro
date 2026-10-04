package top.teamaos.pdfreader.office

import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.Charset

/**
 * Reading Kindle and Mobipocket books: .mobi, .azw, .azw3 and .prc, and plain PalmDOC text.
 *
 * All of them are a Palm database: a 78-byte header, a table of record offsets, and the records.
 * Record 0 describes the book — how the text is compressed, how many records hold it, whether it is
 * encrypted — and the records after it are the text, a few kilobytes each, compressed one by one.
 * The text is HTML (Mobipocket's own dialect for .mobi, XHTML for KF8 / .azw3), and goes through
 * [EbookHtml] like an EPUB chapter does.
 *
 * Two things trip readers up. Each text record can carry trailing bytes that are not text — sizes
 * and indexes appended after the compressed data, flagged in the header — and they have to be cut
 * off before decompressing or every record ends in junk. And a modern .azw3 or a "combo" .mobi
 * holds the book twice, old format and KF8; the KF8 half is the better one and is preferred.
 *
 * Every offset in the file is checked before it is used, and every back-reference in the
 * decompression stays inside what has been written, so a damaged file stops early instead of
 * reading out of bounds or looping.
 */
object MobiParser {

    fun parse(file: File, displayName: String): OfficeDocument? =
        runCatching { Database.open(file)?.use { read(it, displayName) } }.getOrNull()

    /** True when [file] is a Mobipocket / Kindle book whose text is encrypted (DRM). */
    fun isProtected(file: File): Boolean = runCatching {
        Database.open(file)?.use { database ->
            val book = book(database) ?: return@use false
            book.isMobi && book.encryption != 0
        } ?: false
    }.getOrDefault(false)

    /** True for a Palm database holding a Mobipocket book ("BOOKMOBI") or PalmDOC text ("TEXtREAd"). */
    fun isMobi(head: ByteArray): Boolean {
        if (head.size < 68) return false
        val type = String(head, 60, 8, Charsets.ISO_8859_1)
        return type == "BOOKMOBI" || type == "TEXtREAd"
    }

    // ------------------------------------------------------------------ the database

    private class Database(private val file: RandomAccessFile, val offsets: LongArray, val type: String) : AutoCloseable {
        val count: Int get() = offsets.size

        fun size(index: Int): Long {
            if (index !in 0 until count) return 0
            val end = if (index + 1 < count) offsets[index + 1] else file.length()
            return (end - offsets[index]).coerceAtLeast(0)
        }

        /** Record [index], or null when it is out of range, empty or bigger than [limit]. */
        fun record(index: Int, limit: Int = MAX_RECORD_BYTES): ByteArray? {
            if (index !in 0 until count) return null
            val size = size(index)
            if (size <= 0 || size > limit) return null
            val data = ByteArray(size.toInt())
            file.seek(offsets[index])
            file.readFully(data)
            return data
        }

        override fun close() {
            runCatching { file.close() }
        }

        companion object {
            fun open(path: File): Database? {
                val file = RandomAccessFile(path, "r")
                try {
                    val length = file.length()
                    if (length < 78 + 8) return null.also { file.close() }
                    val header = ByteArray(78)
                    file.readFully(header)
                    val type = String(header, 60, 8, Charsets.ISO_8859_1)
                    if (type != "BOOKMOBI" && type != "TEXtREAd") return null.also { file.close() }
                    val count = u16(header, 76)
                    if (count <= 0 || 78L + count * 8L > length) return null.also { file.close() }
                    val table = ByteArray(count * 8)
                    file.readFully(table)
                    val offsets = LongArray(count) { u32(table, it * 8) }
                    // Offsets must rise and stay in the file; a table that does not is cut where it goes wrong.
                    var valid = 0
                    while (valid < count && offsets[valid] in 78L..length &&
                        (valid == 0 || offsets[valid] >= offsets[valid - 1])
                    ) {
                        valid++
                    }
                    if (valid == 0) return null.also { file.close() }
                    return Database(file, offsets.copyOf(valid), type)
                } catch (error: Throwable) {
                    file.close()
                    throw error
                }
            }
        }
    }

    // ------------------------------------------------------------------ the book header

    private class Book(
        /** The record the header was read from: 0, or the KF8 half's first record in a combo file. */
        val base: Int,
        val isMobi: Boolean,
        val compression: Int,
        val textLength: Long,
        val textRecords: Int,
        val encryption: Int,
        val encoding: Charset,
        val version: Int,
        val title: String?,
        val firstImage: Long,
        val huffRecord: Long,
        val huffCount: Long,
        val extraFlags: Int,
        val fdst: Long,
        val exth: Map<Int, ByteArray>,
    )

    private fun header(database: Database, index: Int): Book? {
        val record = database.record(index) ?: return null
        if (record.size < 16) return null
        val isMobi = database.type == "BOOKMOBI" && record.size >= 24 &&
            String(record, 16, 4, Charsets.ISO_8859_1) == "MOBI"
        if (!isMobi) {
            return Book(
                index, false, u16(record, 0), u32(record, 4), u16(record, 8), 0,
                WINDOWS_1252, 0, null, NONE, NONE, 0, 0, NONE, emptyMap(),
            )
        }
        val headerLength = u32(record, 20)
        fun field(offset: Int): Long = if (offset + 4 <= record.size && offset + 4 <= 16 + headerLength) u32(record, offset) else NONE
        val encoding = if (field(28) == 65001L) Charsets.UTF_8 else WINDOWS_1252
        val version = field(0x24).takeIf { it != NONE }?.toInt() ?: 0
        val exth = if ((field(0x80).takeIf { it != NONE } ?: 0L) and 0x40L != 0L) {
            exth(record, (16 + headerLength).toInt())
        } else {
            emptyMap()
        }
        val nameOffset = field(0x54)
        val nameLength = field(0x58)
        val title = exth[503]?.let { String(it, encoding) }
            ?: if (nameOffset != NONE && nameLength in 1..1024 && nameOffset + nameLength <= record.size) {
                String(record, nameOffset.toInt(), nameLength.toInt(), encoding)
            } else {
                null
            }
        val extraFlags = if (version >= 5 && headerLength >= 0xE4 && record.size >= 0xF4) u16(record, 0xF2) else 0
        return Book(
            base = index,
            isMobi = true,
            compression = u16(record, 0),
            textLength = u32(record, 4),
            textRecords = u16(record, 8),
            encryption = u16(record, 12),
            encoding = encoding,
            version = version,
            title = title?.trim()?.takeIf { it.isNotEmpty() },
            firstImage = field(0x6C),
            huffRecord = field(0x70),
            huffCount = field(0x74),
            extraFlags = extraFlags,
            fdst = if (headerLength >= 0xB4) field(0xC0) else NONE,
            exth = exth,
        )
    }

    /** The EXTH block's records, by type. Only the few small ones this reader uses are kept. */
    private fun exth(record: ByteArray, start: Int): Map<Int, ByteArray> {
        if (start < 0 || start + 12 > record.size || String(record, start, 4, Charsets.ISO_8859_1) != "EXTH") {
            return emptyMap()
        }
        val count = u32(record, start + 8)
        val result = HashMap<Int, ByteArray>()
        var position = start + 12
        var seen = 0L
        while (seen < count && seen < MAX_EXTH && position + 8 <= record.size) {
            val type = u32(record, position).toInt()
            val length = u32(record, position + 4)
            if (length < 8 || position + length > record.size) break
            if (type in WANTED_EXTH) result[type] = record.copyOfRange(position + 8, (position + length).toInt())
            position += length.toInt()
            seen++
        }
        return result
    }

    /** The header to read the book from: the KF8 half when there is one, record 0 otherwise. */
    private fun book(database: Database): Book? {
        val first = header(database, 0) ?: return null
        if (!first.isMobi || first.version == 8) return first
        val boundary = first.exth[121]?.takeIf { it.size >= 4 }?.let { u32(it, 0) } ?: return first
        if (boundary <= 0 || boundary >= database.count) return first
        val kf8 = header(database, boundary.toInt()) ?: return first
        if (!kf8.isMobi || kf8.textRecords <= 0 || kf8.encryption != 0 && first.encryption == 0) return first
        return kf8
    }

    // ------------------------------------------------------------------ reading

    private fun read(database: Database, displayName: String): OfficeDocument? {
        val book = book(database) ?: return null
        if (book.isMobi && book.encryption != 0) return null
        if (book.textRecords <= 0) return null

        var truncated = false
        val huff = if (book.compression == HUFF_CDIC) Huffman.load(database, book) ?: return null else null
        val output = java.io.ByteArrayOutputStream()
        val lastRecord = minOf(book.textRecords.toLong(), database.count - 1L - book.base).toInt()
        for (number in 1..lastRecord) {
            val raw = database.record(book.base + number) ?: continue
            val size = raw.size - trailingSize(raw, raw.size, book.extraFlags)
            if (size <= 0) continue
            val text = when (book.compression) {
                1 -> raw.copyOf(size)
                2 -> palmDoc(raw, size)
                HUFF_CDIC -> huff?.decompress(raw, size)
                else -> return null
            } ?: continue
            if (output.size() + text.size > MAX_TEXT_BYTES) {
                output.write(text, 0, MAX_TEXT_BYTES - output.size())
                truncated = true
                break
            }
            output.write(text)
        }
        var bytes = output.toByteArray()
        if (book.textLength in 1 until bytes.size) bytes = bytes.copyOf(book.textLength.toInt())
        if (book.fdst != NONE) bytes = firstFlow(database, book, bytes)
        if (bytes.isEmpty()) return null
        val text = String(bytes, book.encoding)
        val title = book.title

        if (!book.isMobi) return plainText(text, displayName, title, truncated)

        val budget = EbookHtml.Budget()
        val images = Images(database, book)
        val result = EbookHtml.read(text, budget) { source, tag -> images.picture(source, tag) }
        val (sections, headings) = EbookHtml.splitAtPageBreaks(result, { index ->
            if (index == 0 && title != null) title else "Part ${index + 1}"
        })
        if (sections.isEmpty()) return null
        val outline = headings.ifEmpty {
            if (sections.size > 1) sections.mapIndexed { index, section -> OutlineEntry(section.title, index) } else emptyList()
        }.take(MAX_OUTLINE)
        return OfficeDocument(
            kind = OfficeKind.WORD,
            displayName = displayName,
            sections = sections,
            outline = outline,
            limitationNote = if (truncated || budget.truncated) EbookHtml.TRUNCATED_NOTE else null,
        )
    }

    /** PalmDOC without a Mobipocket header is plain text, one paragraph per line. */
    private fun plainText(text: String, displayName: String, title: String?, truncatedBytes: Boolean): OfficeDocument? {
        val budget = EbookHtml.Budget()
        val blocks = mutableListOf<Block>()
        for (line in text.splitToSequence('\n')) {
            val clean = line.trimEnd('\r').replace("\u0000", "")
            if (clean.isBlank()) continue
            if (budget.full) {
                budget.truncated = true
                break
            }
            budget.paragraphs++
            budget.chars += clean.length
            blocks += Block.Text(Paragraph(listOf(TextSpan(clean.take(MAX_LINE_CHARS))), spaceAfterPt = 6f))
        }
        if (blocks.isEmpty()) return null
        return OfficeDocument(
            kind = OfficeKind.TEXT,
            displayName = displayName,
            sections = listOf(Section.Flow(title ?: displayName, blocks)),
            limitationNote = if (truncatedBytes || budget.truncated) EbookHtml.TRUNCATED_NOTE else null,
        )
    }

    /**
     * KF8 text is several "flows" end to end: the book's XHTML first, then its stylesheets and
     * SVGs. The FDST record says where each one starts and ends; only the first is the book.
     */
    private fun firstFlow(database: Database, book: Book, text: ByteArray): ByteArray {
        val index = book.base + book.fdst
        if (index >= database.count) return text
        val record = database.record(index.toInt()) ?: return text
        if (record.size < 12 || String(record, 0, 4, Charsets.ISO_8859_1) != "FDST") return text
        val start = u32(record, 4)
        val count = u32(record, 8)
        if (count < 2 || start + 8 > record.size) return text
        val from = u32(record, start.toInt())
        val to = u32(record, start.toInt() + 4)
        if (from < 0 || to <= from || to > text.size) return text
        return text.copyOfRange(from.toInt(), to.toInt())
    }

    // ------------------------------------------------------------------ trailing entries

    /**
     * How many bytes at the end of a text record are not compressed text.
     *
     * Bit 0 of the extra-data flags means a multibyte-character overlap: its size is in the low two
     * bits of the last byte, plus one. Every higher bit set means one more trailing entry, whose size
     * is a variable-length number written backwards at the very end, seven bits a byte, the first
     * byte read (the last one in the record) carrying the high bit. They are removed from the end in
     * the order of the bits from 1 up, and the multibyte overlap after all of them.
     */
    internal fun trailingSize(data: ByteArray, size: Int, flags: Int): Int {
        var total = 0
        var bits = flags ushr 1
        while (bits != 0) {
            if (bits and 1 != 0) {
                total += backwardVarint(data, size - total)
                if (total >= size) return size
            }
            bits = bits ushr 1
        }
        if (flags and 1 != 0 && size - total - 1 >= 0) {
            total += (data[size - total - 1].toInt() and 0x3) + 1
        }
        return total.coerceIn(0, size)
    }

    private fun backwardVarint(data: ByteArray, end: Int): Int {
        if (end <= 0 || end > data.size) return 0
        var result = 0
        var shift = 0
        var position = end
        while (true) {
            val value = data[position - 1].toInt() and 0xFF
            result = result or ((value and 0x7F) shl shift)
            shift += 7
            position--
            if (value and 0x80 != 0 || shift >= 28 || position == 0) return result
        }
    }

    // ------------------------------------------------------------------ PalmDOC

    /**
     * PalmDOC's LZ77: literal bytes, runs of literals, "space plus character" bytes, and two-byte
     * back-references into the last 2047 bytes written.
     */
    internal fun palmDoc(data: ByteArray, size: Int): ByteArray {
        val out = ByteArray(MAX_RECORD_TEXT)
        var length = 0
        var index = 0
        val end = minOf(size, data.size)
        while (index < end && length < out.size) {
            val code = data[index++].toInt() and 0xFF
            when {
                code == 0 || code in 0x09..0x7F -> out[length++] = code.toByte()
                code in 0x01..0x08 -> {
                    var count = code
                    while (count-- > 0 && index < end && length < out.size) out[length++] = data[index++]
                }
                code in 0x80..0xBF -> {
                    if (index >= end) break
                    val pair = (code shl 8) or (data[index++].toInt() and 0xFF)
                    val distance = (pair ushr 3) and 0x7FF
                    var count = (pair and 0x7) + 3
                    if (distance == 0 || distance > length) continue
                    while (count-- > 0 && length < out.size) {
                        out[length] = out[length - distance]
                        length++
                    }
                }
                else -> {
                    out[length++] = ' '.code.toByte()
                    if (length < out.size) out[length++] = (code xor 0x80).toByte()
                }
            }
        }
        return out.copyOf(length)
    }

    // ------------------------------------------------------------------ HUFF/CDIC

    /**
     * Mobipocket's Huffman compression: a HUFF record with two code tables, and CDIC records with
     * the phrase dictionary. A phrase can itself be compressed, so it is expanded on first use;
     * one that turns out to refer back to itself is a damaged file, and decoding stops there.
     */
    private class Huffman(
        private val codeTable: IntArray,
        private val minCodes: LongArray,
        private val maxCodes: LongArray,
        private val phrases: Array<ByteArray?>,
        private val expanded: BooleanArray,
    ) {
        private val busy = BooleanArray(phrases.size)

        fun decompress(data: ByteArray, size: Int): ByteArray? {
            val out = java.io.ByteArrayOutputStream()
            return if (unpack(data, minOf(size, data.size), out, 0)) out.toByteArray() else out.toByteArray().takeIf { it.isNotEmpty() }
        }

        private fun unpack(data: ByteArray, size: Int, out: java.io.ByteArrayOutputStream, depth: Int): Boolean {
            if (depth > MAX_DEPTH) return false
            var bitsLeft = size.toLong() * 8
            var position = 0
            var window = window(data, size, 0)
            var available = 32
            while (true) {
                if (available <= 0) {
                    position += 4
                    window = window(data, size, position)
                    available += 32
                }
                val code = (window ushr available) and 0xFFFFFFFFL
                val entry = codeTable[(code ushr 24).toInt()]
                var codeLength = entry and 0x1F
                val terminal = entry and 0x80 != 0
                var maxCode = (((entry.toLong() and 0xFFFFFFFFL) ushr 8) + 1 shl (32 - codeLength)) - 1
                if (codeLength == 0) return false
                if (!terminal) {
                    while (codeLength < 32 && code < minCodes[codeLength]) codeLength++
                    maxCode = maxCodes[codeLength]
                }
                available -= codeLength
                bitsLeft -= codeLength
                if (bitsLeft < 0) return true
                if (maxCode < code) return false
                val index = ((maxCode - code) ushr (32 - codeLength)).toInt()
                if (index !in phrases.indices) return false
                val phrase = phrases[index] ?: return false
                if (!expanded[index]) {
                    if (busy[index]) return false
                    busy[index] = true
                    val inner = java.io.ByteArrayOutputStream()
                    val ok = unpack(phrase, phrase.size, inner, depth + 1)
                    busy[index] = false
                    if (!ok) return false
                    phrases[index] = inner.toByteArray()
                    expanded[index] = true
                }
                out.write(phrases[index]!!)
                if (out.size() > MAX_RECORD_TEXT) return true
            }
        }

        /** Eight bytes big-endian from [position], zero past the end. */
        private fun window(data: ByteArray, size: Int, position: Int): Long {
            var value = 0L
            for (offset in 0 until 8) {
                val at = position + offset
                value = (value shl 8) or (if (at < size) data[at].toLong() and 0xFF else 0L)
            }
            return value
        }

        companion object {
            fun load(database: Database, book: Book): Huffman? {
                if (book.huffRecord == NONE || book.huffCount < 1 || book.huffCount > MAX_CDIC) return null
                val first = book.base + book.huffRecord
                if (first >= database.count) return null
                val huff = database.record(first.toInt()) ?: return null
                if (huff.size < 24 || String(huff, 0, 4, Charsets.ISO_8859_1) != "HUFF") return null
                val tableOffset = u32(huff, 8).toInt()
                val limitsOffset = u32(huff, 12).toInt()
                if (tableOffset < 0 || tableOffset + 256 * 4 > huff.size) return null
                if (limitsOffset < 0 || limitsOffset + 64 * 4 > huff.size) return null
                val codeTable = IntArray(256) { u32(huff, tableOffset + it * 4).toInt() }
                val minCodes = LongArray(33)
                val maxCodes = LongArray(33)
                for (length in 1..32) {
                    val low = u32(huff, limitsOffset + (length - 1) * 8)
                    val high = u32(huff, limitsOffset + (length - 1) * 8 + 4)
                    minCodes[length] = low shl (32 - length)
                    maxCodes[length] = ((high + 1) shl (32 - length)) - 1
                }

                val phrases = ArrayList<ByteArray?>()
                val expanded = ArrayList<Boolean>()
                for (number in 1 until book.huffCount) {
                    val index = first + number
                    if (index >= database.count) break
                    val cdic = database.record(index.toInt()) ?: break
                    if (cdic.size < 16 || String(cdic, 0, 4, Charsets.ISO_8859_1) != "CDIC") break
                    val total = u32(cdic, 8)
                    val bits = u32(cdic, 12).toInt()
                    if (bits !in 0..31) break
                    val count = minOf(1L shl bits, total - phrases.size).toInt()
                    if (count <= 0) continue
                    for (entry in 0 until count) {
                        if (16 + entry * 2 + 2 > cdic.size) break
                        val offset = u16(cdic, 16 + entry * 2)
                        if (16 + offset + 2 > cdic.size) {
                            phrases += null
                            expanded += true
                            continue
                        }
                        val header = u16(cdic, 16 + offset)
                        val length = header and 0x7FFF
                        val start = 18 + offset
                        val end = minOf(cdic.size, start + length)
                        phrases += cdic.copyOfRange(start, end)
                        expanded += header and 0x8000 != 0
                        if (phrases.size > MAX_PHRASES) return null
                    }
                }
                if (phrases.isEmpty()) return null
                return Huffman(codeTable, minCodes, maxCodes, phrases.toTypedArray(), expanded.toBooleanArray())
            }

            private const val MAX_DEPTH = 32
            private const val MAX_CDIC = 1024
            private const val MAX_PHRASES = 1 shl 20
        }
    }

    // ------------------------------------------------------------------ pictures

    /**
     * The book's pictures. Mobipocket HTML says `<img recindex="00004">`, KF8 says
     * `src="kindle:embed:0004"` (base 32); both count from 1 at the first image record.
     */
    private class Images(private val database: Database, book: Book) {
        private val first: Long = firstImageRecord(database, book)
        private val cache = HashMap<Long, Block.Picture?>()
        private var total = 0L

        fun picture(source: String?, tag: String): Block.Picture? {
            if (first == NONE) return null
            val number = (EbookHtml.attribute(tag, "recindex") ?: EbookHtml.attribute(tag, "hirecindex"))
                ?.trim()?.toLongOrNull()
                ?: source?.let(::kindleEmbed)
                ?: return null
            if (number < 1) return null
            val index = first + number - 1
            if (index >= database.count) return null
            if (cache.containsKey(index)) {
                // The same picture again: a new block over the same bytes, so sizes stay its own.
                val known = cache[index] ?: return null
                return EbookHtml.picture(known.data, EbookHtml.attribute(tag, "width"), EbookHtml.attribute(tag, "height"))
            }
            if (total >= OoxmlPackage.MAX_PICTURE_BYTES) return null
            val data = database.record(index.toInt(), MAX_IMAGE_BYTES)?.takeIf { isImage(it) }
            val block = data?.let { EbookHtml.picture(it, EbookHtml.attribute(tag, "width"), EbookHtml.attribute(tag, "height")) }
            if (block != null) total += block.data.size
            cache[index] = block
            return block
        }

        private fun kindleEmbed(source: String): Long? {
            val match = Regex("""(?i)kindle:embed:([0-9A-V]+)""").find(source) ?: return null
            var value = 0L
            for (character in match.groupValues[1].uppercase()) {
                val digit = if (character.isDigit()) character - '0' else character - 'A' + 10
                value = value * 32 + digit
                if (value > Int.MAX_VALUE) return null
            }
            return value
        }

        companion object {
            /**
             * Where image records start. In a combo file the KF8 header's number may count from its
             * own first record or from the file's, and the old header's is absolute; the first
             * candidate that actually has an image near it wins.
             */
            fun firstImageRecord(database: Database, book: Book): Long {
                val candidates = mutableListOf<Long>()
                if (book.firstImage != NONE) {
                    if (book.base > 0) candidates += book.base + book.firstImage
                    candidates += book.firstImage
                }
                if (book.base > 0) header(database, 0)?.firstImage?.takeIf { it != NONE }?.let { candidates += it }
                return candidates.firstOrNull { start ->
                    start >= 1 && start < database.count &&
                        (start until minOf(database.count.toLong(), start + 8)).any { index ->
                            database.record(index.toInt(), MAX_IMAGE_BYTES)?.let(::isImage) == true
                        }
                } ?: NONE
            }

            fun isImage(data: ByteArray): Boolean = data.size > 8 && (
                (data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte()) ||
                    (data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() && data[2] == 'N'.code.toByte()) ||
                    (data[0] == 'G'.code.toByte() && data[1] == 'I'.code.toByte() && data[2] == 'F'.code.toByte()) ||
                    (data[0] == 'B'.code.toByte() && data[1] == 'M'.code.toByte())
                )
        }
    }

    // ------------------------------------------------------------------ numbers

    internal fun u16(data: ByteArray, offset: Int): Int =
        if (offset < 0 || offset + 2 > data.size) 0
        else ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    internal fun u32(data: ByteArray, offset: Int): Long =
        if (offset < 0 || offset + 4 > data.size) 0
        else ((data[offset].toLong() and 0xFF) shl 24) or ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or (data[offset + 3].toLong() and 0xFF)

    private val WINDOWS_1252: Charset = runCatching { Charset.forName("windows-1252") }.getOrDefault(Charsets.ISO_8859_1)

    /** "No such record" in a Mobipocket header. */
    private const val NONE = 0xFFFFFFFFL
    private const val HUFF_CDIC = 17480
    private val WANTED_EXTH = setOf(121, 503)
    private const val MAX_EXTH = 4096
    private const val MAX_RECORD_BYTES = 1 * 1024 * 1024
    private const val MAX_IMAGE_BYTES = 16 * 1024 * 1024
    private const val MAX_RECORD_TEXT = 64 * 1024
    private const val MAX_TEXT_BYTES = 24 * 1024 * 1024
    private const val MAX_LINE_CHARS = 4000
    private const val MAX_OUTLINE = 5000
}
