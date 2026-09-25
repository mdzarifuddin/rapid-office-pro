package top.teamaos.pdfreader.office

import java.io.File

/**
 * A reader for the compound file that .doc, .xls and .ppt are stored in.
 *
 * Before Office moved to zipped XML it used a little filesystem inside a single file: sectors, an
 * allocation table chaining them together, and a directory of named streams. Reading one is not
 * hard, it is just fiddly, and every one of the three old formats needs exactly this and nothing
 * more — pull out a named stream as bytes and hand it to whichever parser understands it.
 *
 * Two allocation tables exist because small streams are packed together: anything under the mini
 * cutoff lives inside the root entry's stream and is chained by its own table. Missing that is the
 * usual reason a hand-written reader works on large documents and returns nothing on small ones.
 */
class Ole2File private constructor(
    private val data: ByteArray,
    private val sectorSize: Int,
    private val miniSectorSize: Int,
    private val fat: IntArray,
    private val miniFat: IntArray,
    private val directory: List<Entry>,
    private val miniStream: ByteArray,
) {

    class Entry(val name: String, val type: Int, val startSector: Int, val size: Long)

    fun names(): List<String> = directory.map { it.name }

    /** The bytes of a named stream, or null if this file has no such stream. */
    fun stream(name: String): ByteArray? {
        val entry = directory.firstOrNull { it.type == TYPE_STREAM && it.name.equals(name, true) }
            ?: return null
        return readStream(entry)
    }

    /** The first stream whose name matches any of [candidates], in the order given. */
    fun firstStream(vararg candidates: String): ByteArray? {
        candidates.forEach { name -> stream(name)?.let { return it } }
        return null
    }

    private fun readStream(entry: Entry): ByteArray? {
        if (entry.size <= 0) return ByteArray(0)
        if (entry.size > MAX_STREAM_BYTES) return null
        val size = entry.size.toInt()
        return if (entry.size < MINI_CUTOFF) {
            readChain(miniStream, miniFat, entry.startSector, miniSectorSize, size, mini = true)
        } else {
            readChain(data, fat, entry.startSector, sectorSize, size, mini = false)
        }
    }

    private fun readChain(
        source: ByteArray,
        table: IntArray,
        start: Int,
        unit: Int,
        size: Int,
        mini: Boolean,
    ): ByteArray? {
        val out = ByteArray(size)
        var written = 0
        var sector = start
        var guard = 0
        while (sector >= 0 && written < size && guard++ < MAX_CHAIN) {
            val offset = if (mini) sector * unit else (sector + 1) * unit
            if (offset < 0 || offset + unit > source.size) break
            val count = minOf(unit, size - written)
            System.arraycopy(source, offset, out, written, count)
            written += count
            sector = table.getOrElse(sector) { END_OF_CHAIN }
            if (sector == END_OF_CHAIN || sector == FREE_SECTOR) break
        }
        return if (written == 0) null else out
    }

    companion object {
        private const val TYPE_STREAM = 2
        private const val TYPE_ROOT = 5
        private const val END_OF_CHAIN = -2
        private const val FREE_SECTOR = -1
        private const val MINI_CUTOFF = 4096
        private const val MAX_CHAIN = 1 shl 22
        private const val MAX_STREAM_BYTES = 96L * 1024 * 1024
        private const val MAX_FILE_BYTES = 192L * 1024 * 1024

        private val SIGNATURE = byteArrayOf(
            0xD0.toByte(), 0xCF.toByte(), 0x11, 0xE0.toByte(),
            0xA1.toByte(), 0xB1.toByte(), 0x1A, 0xE1.toByte(),
        )

        fun looksLikeOle(file: File): Boolean = runCatching {
            file.inputStream().use { stream ->
                val header = ByteArray(8)
                stream.read(header) == 8 && header.contentEquals(SIGNATURE)
            }
        }.getOrDefault(false)

        fun open(file: File): Ole2File? {
            if (file.length() > MAX_FILE_BYTES) return null
            val data = runCatching { file.readBytes() }.getOrNull() ?: return null
            return open(data)
        }

        fun open(data: ByteArray): Ole2File? {
            if (data.size < 512) return null
            for (index in SIGNATURE.indices) if (data[index] != SIGNATURE[index]) return null

            val sectorSize = 1 shl u16(data, 30)
            val miniSectorSize = 1 shl u16(data, 32)
            if (sectorSize < 128 || sectorSize > 65536 || miniSectorSize !in 8..4096) return null

            val fatSectorCount = i32(data, 44)
            val firstDirectory = i32(data, 48)
            val firstMiniFat = i32(data, 60)
            val miniFatCount = i32(data, 64)
            val firstDifat = i32(data, 68)
            val difatCount = i32(data, 72)

            // Where the allocation table's own sectors live: the first 109 in the header, the rest
            // chained through further sectors.
            val fatSectors = mutableListOf<Int>()
            for (index in 0 until minOf(109, maxOf(0, fatSectorCount))) {
                val sector = i32(data, 76 + index * 4)
                if (sector >= 0) fatSectors += sector
            }
            var difat = firstDifat
            var guard = 0
            while (difat >= 0 && guard++ < 4096 && fatSectors.size < fatSectorCount) {
                val base = (difat + 1) * sectorSize
                if (base + sectorSize > data.size) break
                val perSector = sectorSize / 4 - 1
                for (index in 0 until perSector) {
                    val sector = i32(data, base + index * 4)
                    if (sector >= 0) fatSectors += sector
                }
                difat = i32(data, base + perSector * 4)
            }

            val entriesPerSector = sectorSize / 4
            val fat = IntArray(fatSectors.size * entriesPerSector) { END_OF_CHAIN }
            fatSectors.forEachIndexed { sectorIndex, sector ->
                val base = (sector + 1) * sectorSize
                if (base + sectorSize > data.size) return@forEachIndexed
                for (index in 0 until entriesPerSector) {
                    fat[sectorIndex * entriesPerSector + index] = i32(data, base + index * 4)
                }
            }

            val miniFat = readTable(data, fat, firstMiniFat, sectorSize, miniFatCount)

            // The directory is itself a chained stream of 128-byte entries.
            val directoryBytes = readRaw(data, fat, firstDirectory, sectorSize) ?: return null
            val directory = mutableListOf<Entry>()
            var offset = 0
            while (offset + 128 <= directoryBytes.size) {
                val nameLength = u16(directoryBytes, offset + 64)
                val name = if (nameLength <= 2) {
                    ""
                } else {
                    String(
                        directoryBytes,
                        offset,
                        minOf(nameLength - 2, 64),
                        Charsets.UTF_16LE,
                    )
                }
                directory += Entry(
                    name = name,
                    type = directoryBytes[offset + 66].toInt() and 0xFF,
                    startSector = i32(directoryBytes, offset + 116),
                    size = i32(directoryBytes, offset + 120).toLong() and 0xFFFFFFFFL,
                )
                offset += 128
            }

            val root = directory.firstOrNull { it.type == TYPE_ROOT }
            val miniStream = if (root == null || root.size <= 0) {
                ByteArray(0)
            } else {
                readRaw(data, fat, root.startSector, sectorSize) ?: ByteArray(0)
            }

            return Ole2File(data, sectorSize, miniSectorSize, fat, miniFat, directory, miniStream)
        }

        /** Follow a chain and return everything in it, without a length to trim to. */
        private fun readRaw(data: ByteArray, fat: IntArray, start: Int, sectorSize: Int): ByteArray? {
            if (start < 0) return null
            val out = java.io.ByteArrayOutputStream()
            var sector = start
            var guard = 0
            while (sector >= 0 && guard++ < MAX_CHAIN) {
                val offset = (sector + 1) * sectorSize
                if (offset + sectorSize > data.size) break
                out.write(data, offset, sectorSize)
                if (out.size() > MAX_STREAM_BYTES) break
                sector = fat.getOrElse(sector) { END_OF_CHAIN }
                if (sector == END_OF_CHAIN || sector == FREE_SECTOR) break
            }
            return if (out.size() == 0) null else out.toByteArray()
        }

        private fun readTable(
            data: ByteArray,
            fat: IntArray,
            start: Int,
            sectorSize: Int,
            sectorCount: Int,
        ): IntArray {
            if (start < 0 || sectorCount <= 0) return IntArray(0)
            val bytes = readRaw(data, fat, start, sectorSize) ?: return IntArray(0)
            val count = bytes.size / 4
            return IntArray(count) { i32(bytes, it * 4) }
        }

        fun u16(data: ByteArray, offset: Int): Int =
            if (offset + 1 >= data.size) 0
            else (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

        fun i32(data: ByteArray, offset: Int): Int =
            if (offset + 3 >= data.size) 0
            else (data[offset].toInt() and 0xFF) or
                ((data[offset + 1].toInt() and 0xFF) shl 8) or
                ((data[offset + 2].toInt() and 0xFF) shl 16) or
                ((data[offset + 3].toInt() and 0xFF) shl 24)

        fun u32(data: ByteArray, offset: Int): Long = i32(data, offset).toLong() and 0xFFFFFFFFL
    }
}
