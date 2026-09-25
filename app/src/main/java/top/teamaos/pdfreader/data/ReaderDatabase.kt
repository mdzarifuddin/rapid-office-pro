package top.teamaos.pdfreader.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One remembered document: where it is, where you were in it, and how you like reading it. */
data class DocumentRecord(
    val id: Long,
    val uri: String,
    val displayName: String,
    val folder: String?,
    val sizeBytes: Long,
    val modifiedAt: Long,
    val pageCount: Int,
    val lastPage: Int,
    val lastFractionX: Float,
    val lastFractionY: Float,
    val lastZoom: Float,
    val viewMode: Int,
    val colorMode: Int,
    val isFavorite: Boolean,
    val lastOpenedAt: Long,
)

/** One brush stroke as it is stored: points are pairs of floats in the page's own coordinates. */
data class MarkRecord(
    val id: Long,
    val page: Int,
    val colour: Int,
    val widthPts: Float,
    val points: ByteArray,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

data class BookmarkRecord(
    val id: Long,
    val documentId: Long,
    val page: Int,
    val label: String?,
    val createdAt: Long,
)

/**
 * Reading history, bookmarks, favourites and cached page geometry.
 *
 * Plain SQLite rather than Room: the schema is small, it keeps an annotation processor out of the
 * build, and it makes the pruning below explicit.
 *
 * **Everything here is capped.** A reader used daily for years would otherwise accumulate tens of
 * thousands of history rows and a page-size blob per document, and gradually become the sluggish
 * app this one exists to replace. [prune] runs after every open and enforces those caps, so the
 * database stays roughly the same size in month sixty as in month one.
 */
class ReaderDatabase private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        // Incremental vacuum lets pruning actually give disk back instead of leaving free pages.
        // PRAGMAs that return a row have to go through rawQuery; execSQL rejects them outright.
        db.rawQuery("PRAGMA auto_vacuum = INCREMENTAL", null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE documents (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uri TEXT NOT NULL UNIQUE,
                display_name TEXT NOT NULL,
                folder TEXT,
                size_bytes INTEGER NOT NULL DEFAULT 0,
                modified_at INTEGER NOT NULL DEFAULT 0,
                page_count INTEGER NOT NULL DEFAULT 0,
                last_page INTEGER NOT NULL DEFAULT 0,
                last_fraction_x REAL NOT NULL DEFAULT 0,
                last_fraction_y REAL NOT NULL DEFAULT 0,
                last_zoom REAL NOT NULL DEFAULT 1,
                view_mode INTEGER NOT NULL DEFAULT 0,
                color_mode INTEGER NOT NULL DEFAULT 0,
                is_favorite INTEGER NOT NULL DEFAULT 0,
                last_opened_at INTEGER NOT NULL DEFAULT 0,
                page_sizes BLOB
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_documents_last_opened ON documents(last_opened_at DESC)")
        db.execSQL("CREATE INDEX idx_documents_favorite ON documents(is_favorite, last_opened_at DESC)")
        db.execSQL(
            """
            CREATE TABLE bookmarks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                document_id INTEGER NOT NULL,
                page INTEGER NOT NULL,
                label TEXT,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_bookmarks_document ON bookmarks(document_id, page)")
        createMarks(db)
    }

    /**
     * Brush strokes drawn over a document's pages.
     *
     * Kept beside the document rather than inside it. A stroke is a decision the reader can undo,
     * change its mind about, or wipe, and none of that is possible once it has been written into
     * the PDF — so the file is only touched when they ask for it explicitly. Points are stored as
     * a blob of floats in page coordinates, which is the only form that survives zooming,
     * rotating, cropping and being reopened on a different-sized screen.
     */
    private fun createMarks(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS marks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                document_id INTEGER NOT NULL,
                page INTEGER NOT NULL,
                colour INTEGER NOT NULL,
                width REAL NOT NULL,
                points BLOB NOT NULL,
                created_at INTEGER NOT NULL,
                FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_marks_document ON marks(document_id, page)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Reading history and bookmarks are cheap to lose and expensive to migrate wrongly, so
        // they are rebuilt. Marks are not: they are the reader's own work, so the table is only
        // ever added, never dropped.
        if (oldVersion < 2) {
            db.execSQL("DROP TABLE IF EXISTS bookmarks")
            db.execSQL("DROP TABLE IF EXISTS documents")
            onCreate(db)
            return
        }
        createMarks(db)
    }

    // ---------------------------------------------------------------- documents

    /** Insert or refresh the row for [uri] and return its id. */
    suspend fun upsertDocument(
        uri: String,
        displayName: String,
        folder: String?,
        sizeBytes: Long,
        modifiedAt: Long,
        pageCount: Int,
        openedAt: Long,
    ): Long = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            val values = ContentValues().apply {
                put("uri", uri)
                put("display_name", displayName)
                put("folder", folder)
                put("size_bytes", sizeBytes)
                put("modified_at", modifiedAt)
                put("page_count", pageCount)
                put("last_opened_at", openedAt)
            }
            val updated = db.update("documents", values, "uri = ?", arrayOf(uri))
            val id = if (updated > 0) {
                idForUri(db, uri)
            } else {
                db.insert("documents", null, values)
            }
            db.setTransactionSuccessful()
            id
        } finally {
            db.endTransaction()
        }
    }

    suspend fun findDocument(uri: String): DocumentRecord? = withContext(Dispatchers.IO) {
        readableDatabase.query(
            "documents", null, "uri = ?", arrayOf(uri), null, null, null, "1",
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toDocument() else null }
    }

    /** Most recently opened first, capped so the list screen never has to page through history. */
    suspend fun recentDocuments(limit: Int = 200): List<DocumentRecord> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            "documents", null, "last_opened_at > 0", null, null, null,
            "last_opened_at DESC", limit.toString(),
        ).use { it.toDocumentList() }
    }

    suspend fun favouriteDocuments(): List<DocumentRecord> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            "documents", null, "is_favorite = 1", null, null, null, "last_opened_at DESC",
        ).use { it.toDocumentList() }
    }

    suspend fun saveReadingPosition(
        documentId: Long,
        page: Int,
        fractionX: Float,
        fractionY: Float,
        zoom: Float,
        viewMode: Int,
        colorMode: Int,
    ) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put("last_page", page)
            put("last_fraction_x", fractionX)
            put("last_fraction_y", fractionY)
            put("last_zoom", zoom)
            put("view_mode", viewMode)
            put("color_mode", colorMode)
        }
        writableDatabase.update("documents", values, "id = ?", arrayOf(documentId.toString()))
        Unit
    }

    suspend fun setFavourite(documentId: Long, favourite: Boolean) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put("is_favorite", if (favourite) 1 else 0) }
        writableDatabase.update("documents", values, "id = ?", arrayOf(documentId.toString()))
        Unit
    }

    suspend fun removeDocument(documentId: Long) = withContext(Dispatchers.IO) {
        writableDatabase.delete("documents", "id = ?", arrayOf(documentId.toString()))
        Unit
    }

    /**
     * Point a history entry at where its file really is, keeping its reading position, bookmarks
     * and marks. If that file already has its own entry, the stale one is dropped instead.
     */
    suspend fun relocateDocument(documentId: Long, newUri: String) = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            if (idForUri(db, newUri) > 0) {
                db.delete("documents", "id = ?", arrayOf(documentId.toString()))
            } else {
                val values = ContentValues().apply { put("uri", newUri) }
                db.update("documents", values, "id = ?", arrayOf(documentId.toString()))
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    suspend fun clearHistory() = withContext(Dispatchers.IO) {
        writableDatabase.delete("documents", "is_favorite = 0", null)
        Unit
    }

    // ------------------------------------------------------------- page geometry

    /** Cached page sizes, so reopening a long document skips measuring entirely. */
    suspend fun pageSizes(documentId: Long, expectedPageCount: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            readableDatabase.query(
                "documents", arrayOf("page_sizes", "page_count"),
                "id = ?", arrayOf(documentId.toString()), null, null, null, "1",
            ).use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                if (cursor.getInt(1) != expectedPageCount) return@use null
                if (cursor.isNull(0)) null else cursor.getBlob(0)
            }
        }

    suspend fun savePageSizes(documentId: Long, blob: ByteArray) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply { put("page_sizes", blob) }
        writableDatabase.update("documents", values, "id = ?", arrayOf(documentId.toString()))
        Unit
    }

    // ---------------------------------------------------------------- bookmarks

    suspend fun bookmarks(documentId: Long): List<BookmarkRecord> = withContext(Dispatchers.IO) {
        readableDatabase.query(
            "bookmarks", null, "document_id = ?", arrayOf(documentId.toString()),
            null, null, "page ASC",
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        BookmarkRecord(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            documentId = cursor.getLong(cursor.getColumnIndexOrThrow("document_id")),
                            page = cursor.getInt(cursor.getColumnIndexOrThrow("page")),
                            label = cursor.getStringOrNull("label"),
                            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                        ),
                    )
                }
            }
        }
    }

    suspend fun addBookmark(documentId: Long, page: Int, label: String?, createdAt: Long): Long =
        withContext(Dispatchers.IO) {
            val values = ContentValues().apply {
                put("document_id", documentId)
                put("page", page)
                put("label", label)
                put("created_at", createdAt)
            }
            writableDatabase.insert("bookmarks", null, values)
        }

    suspend fun removeBookmark(documentId: Long, page: Int) = withContext(Dispatchers.IO) {
        writableDatabase.delete(
            "bookmarks", "document_id = ? AND page = ?",
            arrayOf(documentId.toString(), page.toString()),
        )
        Unit
    }

    // ------------------------------------------------------------------- pruning

    /**
     * Enforce the size caps. Cheap enough to run on every document open.
     *
     * Three passes, cheapest first: drop page-size blobs beyond the most recent few dozen (they are
     * by far the biggest column), then delete history rows past the cap, keeping anything
     * favourited or bookmarked, then hand the freed pages back to the filesystem.
     */
    // ---------------------------------------------------------------- marks

    /** Every stroke on a document, page by page. */
    suspend fun marks(documentId: Long): List<MarkRecord> = withContext(Dispatchers.IO) {
        if (documentId <= 0) return@withContext emptyList()
        readableDatabase.query(
            "marks", null, "document_id = ?", arrayOf(documentId.toString()),
            null, null, "page ASC, id ASC", MAX_MARKS.toString(),
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        MarkRecord(
                            id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                            page = cursor.getInt(cursor.getColumnIndexOrThrow("page")),
                            colour = cursor.getInt(cursor.getColumnIndexOrThrow("colour")),
                            widthPts = cursor.getFloat(cursor.getColumnIndexOrThrow("width")),
                            points = cursor.getBlob(cursor.getColumnIndexOrThrow("points")),
                        ),
                    )
                }
            }
        }
    }

    /** Add one stroke and return the id it was given. */
    suspend fun addMark(
        documentId: Long,
        page: Int,
        colour: Int,
        widthPts: Float,
        points: ByteArray,
    ): Long = withContext(Dispatchers.IO) {
        if (documentId <= 0) return@withContext -1L
        val values = ContentValues().apply {
            put("document_id", documentId)
            put("page", page)
            put("colour", colour)
            put("width", widthPts)
            put("points", points)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insert("marks", null, values)
    }

    /** Put a stroke back with the id it had, so undo and redo do not renumber anything. */
    suspend fun restoreMark(
        documentId: Long,
        id: Long,
        page: Int,
        colour: Int,
        widthPts: Float,
        points: ByteArray,
    ) = withContext(Dispatchers.IO) {
        if (documentId <= 0 || id <= 0) return@withContext
        val values = ContentValues().apply {
            put("id", id)
            put("document_id", documentId)
            put("page", page)
            put("colour", colour)
            put("width", widthPts)
            put("points", points)
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict(
            "marks", null, values, SQLiteDatabase.CONFLICT_REPLACE,
        )
        Unit
    }

    suspend fun removeMarks(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext
        val places = ids.joinToString(",") { "?" }
        writableDatabase.delete("marks", "id IN ($places)", ids.map { it.toString() }.toTypedArray())
        Unit
    }

    suspend fun clearMarks(documentId: Long) = withContext(Dispatchers.IO) {
        if (documentId <= 0) return@withContext
        writableDatabase.delete("marks", "document_id = ?", arrayOf(documentId.toString()))
        Unit
    }

    suspend fun prune() = withContext(Dispatchers.IO) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.execSQL(
                """
                UPDATE documents SET page_sizes = NULL
                WHERE page_sizes IS NOT NULL AND id NOT IN (
                    SELECT id FROM documents ORDER BY last_opened_at DESC LIMIT ?
                )
                """.trimIndent(),
                arrayOf(MAX_CACHED_GEOMETRIES),
            )
            db.execSQL(
                """
                DELETE FROM documents
                WHERE is_favorite = 0
                  AND id NOT IN (SELECT DISTINCT document_id FROM bookmarks)
                  AND id NOT IN (
                      SELECT id FROM documents ORDER BY last_opened_at DESC LIMIT ?
                  )
                """.trimIndent(),
                arrayOf(MAX_HISTORY_ROWS),
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        db.rawQuery("PRAGMA incremental_vacuum", null).use { it.moveToFirst() }
        Unit
    }

    // ------------------------------------------------------------------- helpers

    private fun idForUri(db: SQLiteDatabase, uri: String): Long =
        db.query("documents", arrayOf("id"), "uri = ?", arrayOf(uri), null, null, null, "1")
            .use { if (it.moveToFirst()) it.getLong(0) else -1L }

    private fun Cursor.toDocumentList(): List<DocumentRecord> = buildList {
        while (moveToNext()) add(toDocument())
    }

    private fun Cursor.toDocument(): DocumentRecord = DocumentRecord(
        id = getLong(getColumnIndexOrThrow("id")),
        uri = getString(getColumnIndexOrThrow("uri")),
        displayName = getString(getColumnIndexOrThrow("display_name")),
        folder = getStringOrNull("folder"),
        sizeBytes = getLong(getColumnIndexOrThrow("size_bytes")),
        modifiedAt = getLong(getColumnIndexOrThrow("modified_at")),
        pageCount = getInt(getColumnIndexOrThrow("page_count")),
        lastPage = getInt(getColumnIndexOrThrow("last_page")),
        lastFractionX = getFloat(getColumnIndexOrThrow("last_fraction_x")),
        lastFractionY = getFloat(getColumnIndexOrThrow("last_fraction_y")),
        lastZoom = getFloat(getColumnIndexOrThrow("last_zoom")),
        viewMode = getInt(getColumnIndexOrThrow("view_mode")),
        colorMode = getInt(getColumnIndexOrThrow("color_mode")),
        isFavorite = getInt(getColumnIndexOrThrow("is_favorite")) != 0,
        lastOpenedAt = getLong(getColumnIndexOrThrow("last_opened_at")),
    )

    private fun Cursor.getStringOrNull(column: String): String? {
        val index = getColumnIndexOrThrow(column)
        return if (isNull(index)) null else getString(index)
    }

    companion object {
        private const val DATABASE_NAME = "reader.db"
        /** Enough to draw over every page of a long document several times over. */
        private const val MAX_MARKS = 40000

        private const val DATABASE_VERSION = 2

        /** Reading history beyond this many documents is dropped, oldest first. */
        private const val MAX_HISTORY_ROWS = "400"

        /** Page-size blobs are the largest thing stored, so only recent documents keep theirs. */
        private const val MAX_CACHED_GEOMETRIES = "40"

        @Volatile
        private var instance: ReaderDatabase? = null

        /**
         * A scope for writes that have to finish even though whatever asked for them is going away.
         *
         * Saving where the reader got to is the case that matters: it happens as the screen closes,
         * and a write started on the screen's own scope is cancelled a moment later by the screen
         * being destroyed — which is why the last page read was so often not there on the way back
         * in. Process-scoped, never cancelled, and only ever used for small writes.
         */
        private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** Run a small write that must not be tied to any screen's lifetime. */
        fun writeInBackground(block: suspend ReaderDatabase.() -> Unit) {
            val database = instance ?: return
            writeScope.launch { database.block() }
        }

        fun get(context: Context): ReaderDatabase =
            instance ?: synchronized(this) {
                instance ?: ReaderDatabase(context).also { instance = it }
            }
    }
}
