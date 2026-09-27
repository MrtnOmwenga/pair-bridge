package dev.pairbridge.app

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.util.LruCache
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import kotlin.concurrent.thread

/**
 * Exposes a single "Laptop" root in the Storage Access Framework, so it shows up in any app's
 * file picker (Slack, WhatsApp, Files) the way Dropbox or Drive would. Each of the laptop's
 * shared folders appears as a subfolder of that root.
 *
 * Document ids are "<rootId>::<path relative to that root>".
 */
class LaptopDocumentsProvider : DocumentsProvider() {

    private val laptop by lazy { LaptopConnection.get(context!!) }
    private val preloadCache by lazy { DocumentCache(File(context!!.cacheDir, "preload")) }
    private val thumbnailCache by lazy { DocumentCache(File(context!!.cacheDir, "thumbnails")) }

    // Metadata from the latest folder listings. Answering queryDocument() and cache lookups from
    // memory skips a round trip, which matters because the first packet after the tablet's WiFi
    // radio idles costs 1-3 s regardless of size.
    private val knownEntries = LruCache<String, FileEntry>(5_000)

    // Root id -> display name, filled when the top level is listed.
    @Volatile
    private var rootNames: Map<String, String> = emptyMap()

    // Preloads run one at a time: parallel multi-MB downloads saturate the LAN link and slow down
    // the download the user is actually waiting for.
    private val preloadExecutor = Executors.newSingleThreadExecutor()
    private val preloadsQueued = ConcurrentHashMap.newKeySet<String>()

    // Pickers request a thumbnail for every visible image at once; the cap keeps a scroll burst
    // from crowding out a file the user tapped.
    private val thumbnailPermits = Semaphore(3)

    override fun onCreate(): Boolean = true

    /**
     * StrictMode's thread policy propagates across Binder calls, so a caller that forbids network
     * on its own thread (Slack does) trips NetworkOnMainThreadException here even though this
     * isn't the UI thread.
     */
    private fun allowNetworkOnThisThread() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: ROOT_PROJECTION)
        if (context == null || !laptop.credentials.isPaired) return cursor

        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_ICON, R.drawable.ic_launcher_foreground)
            add(Root.COLUMN_TITLE, "Laptop")
            add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE)
            add(Root.COLUMN_DOCUMENT_ID, TOP_DOC_ID)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        logged("queryDocument($documentId)") {
            val cursor = MatrixCursor(projection ?: DOC_PROJECTION)
            if (documentId == TOP_DOC_ID) {
                addTopRow(cursor)
                return@logged cursor
            }
            val (rootId, path) = parseDocId(documentId)
            if (path.isEmpty()) {
                addRootFolderRow(cursor, documentId, rootNames[rootId] ?: rootId)
                return@logged cursor
            }
            addEntryRow(cursor, documentId, entryFor(documentId))
            cursor
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = logged("queryChildDocuments($parentDocumentId)") {
        allowNetworkOnThisThread()
        val cursor = MatrixCursor(projection ?: DOC_PROJECTION)
        cursor.setNotificationUri(context!!.contentResolver, childrenUri(parentDocumentId))

        if (parentDocumentId == TOP_DOC_ID) {
            val roots = network("list shared folders") { laptop.call { it.listRoots() }.roots }
            rootNames = roots.associate { it.id to it.name }
            roots.forEach { addRootFolderRow(cursor, docId(it.id, ""), it.name) }
            return@logged cursor
        }

        val (rootId, parentPath) = parseDocId(parentDocumentId)
        val entries = network("list $parentPath") { laptop.call { it.listFiles(rootId, parentPath) } }
        for (entry in entries) {
            val childPath = childPath(parentPath, entry.name)
            val childDocId = docId(rootId, childPath)
            knownEntries.put(childDocId, entry)
            addEntryRow(cursor, childDocId, entry)
            if (!entry.isDir && entry.size in 1..MAX_PRELOAD_BYTES) preload(rootId, childPath, entry)
        }
        cursor
    }

    private fun preload(rootId: String, path: String, entry: FileEntry) {
        if (preloadCache.find(rootId, path, entry) != null) return
        val key = "$rootId::$path::${entry.size}::${entry.mtime}"
        if (!preloadsQueued.add(key)) return

        preloadExecutor.execute {
            try {
                laptop.call { it.download(rootId, path) }.use { preloadCache.store(rootId, path, entry, it) }
                Log.d(TAG, "preloaded $rootId/$path (${entry.size} bytes)")
            } catch (e: IOException) {
                Log.w(TAG, "preload failed for $rootId/$path: ${e.message}")
            } finally {
                preloadsQueued.remove(key)
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = logged("openDocument($documentId, $mode)") {
        allowNetworkOnThisThread()
        when (mode) {
            "r" -> openForRead(documentId)
            "w", "wt" -> openForWrite(documentId)
            // "rw" and "wa" need a seekable file; this provider streams through pipes instead of
            // staging a local copy, so only sequential modes work.
            else -> throw UnsupportedOperationException("mode $mode is not supported")
        }
    }

    private fun openForRead(documentId: String): ParcelFileDescriptor {
        val (rootId, path) = parseDocId(documentId)
        knownEntries.get(documentId)?.let { known ->
            preloadCache.find(rootId, path, known)?.let { cached ->
                return ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }

        val (readSide, writeSide) = ParcelFileDescriptor.createReliablePipe()
        thread(name = "pairbridge-download") {
            try {
                laptop.call { it.download(rootId, path) }.use { input ->
                    FileOutputStream(writeSide.fileDescriptor).let { input.copyTo(it) }
                }
                writeSide.close()
            } catch (e: IOException) {
                Log.w(TAG, "download of $documentId failed: ${e.message}")
                // Without this the reader would see a clean EOF and treat a truncated file as complete.
                runCatching { writeSide.closeWithError(e.message ?: "download failed") }
            }
        }
        return readSide
    }

    private fun openForWrite(documentId: String): ParcelFileDescriptor {
        val (rootId, path) = parseDocId(documentId)
        val (readSide, writeSide) = ParcelFileDescriptor.createReliablePipe()
        thread(name = "pairbridge-upload") {
            try {
                val saved = laptop.client().write(rootId, path, FileInputStream(readSide.fileDescriptor))
                readSide.close()
                knownEntries.put(documentId, saved.entry)
                preloadCache.evict(rootId, path)
                thumbnailCache.evict(rootId, path)
                notifyChildrenChanged(parentDocId(documentId))
            } catch (e: IOException) {
                Log.w(TAG, "upload of $documentId failed: ${e.message}")
                // The writing app sees this error when it closes its end of the pipe.
                runCatching { readSide.closeWithError(e.message ?: "upload failed") }
            }
        }
        return writeSide
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor = logged("openDocumentThumbnail($documentId)") {
        allowNetworkOnThisThread()
        val (rootId, path) = parseDocId(documentId)
        val entry = entryFor(documentId)
        val file = thumbnailCache.find(rootId, path, entry) ?: run {
            val size = maxOf(sizeHint?.x ?: 256, sizeHint?.y ?: 256).coerceIn(64, 1024)
            thumbnailPermits.acquire()
            val bytes = try {
                network("thumbnail $path") { laptop.call { it.thumbnail(rootId, path, size) } }
            } finally {
                thumbnailPermits.release()
            }
            thumbnailCache.store(rootId, path, entry, bytes.inputStream())
        }
        AssetFileDescriptor(
            ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    override fun createDocument(parentDocumentId: String, mimeType: String, displayName: String): String =
        logged("createDocument($parentDocumentId, $displayName)") {
            allowNetworkOnThisThread()
            if (parentDocumentId == TOP_DOC_ID) throw UnsupportedOperationException("pick a shared folder first")
            val (rootId, parentPath) = parseDocId(parentDocumentId)
            val isDir = mimeType == Document.MIME_TYPE_DIR
            val name = if (isDir) displayName else withExtensionFor(displayName, mimeType)
            val saved = network("create $name") { laptop.call { it.create(rootId, parentPath, name, isDir) } }
            val documentId = docId(rootId, saved.path)
            knownEntries.put(documentId, saved.entry)
            notifyChildrenChanged(parentDocumentId)
            documentId
        }

    override fun renameDocument(documentId: String, displayName: String): String =
        logged("renameDocument($documentId, $displayName)") {
            allowNetworkOnThisThread()
            val (rootId, path) = parseDocId(documentId)
            val saved = network("rename $path") { laptop.call { it.rename(rootId, path, displayName) } }
            forget(documentId)
            val newDocumentId = docId(rootId, saved.path)
            knownEntries.put(newDocumentId, saved.entry)
            notifyChildrenChanged(parentDocId(documentId))
            newDocumentId
        }

    override fun deleteDocument(documentId: String) = logged("deleteDocument($documentId)") {
        allowNetworkOnThisThread()
        val (rootId, path) = parseDocId(documentId)
        network("delete $path") { laptop.call { it.delete(rootId, path) } }
        forget(documentId)
        notifyChildrenChanged(parentDocId(documentId))
    }

    private fun entryFor(documentId: String): FileEntry {
        knownEntries.get(documentId)?.let { return it }
        allowNetworkOnThisThread()
        val (rootId, path) = parseDocId(documentId)
        return network("stat $path") { laptop.call { it.stat(rootId, path) } }
            .also { knownEntries.put(documentId, it) }
    }

    private fun forget(documentId: String) {
        val (rootId, path) = parseDocId(documentId)
        knownEntries.remove(documentId)
        preloadCache.evict(rootId, path)
        thumbnailCache.evict(rootId, path)
    }

    /** SAF expects FileNotFoundException for anything that makes a document unavailable. */
    private fun <T> network(what: String, block: () -> T): T =
        try {
            block()
        } catch (e: IOException) {
            throw FileNotFoundException("could not $what: ${e.message}")
        }

    private fun <T> logged(label: String, block: () -> T): T {
        val start = System.nanoTime()
        try {
            return block().also { Log.d(TAG, "$label took ${(System.nanoTime() - start) / 1_000_000}ms") }
        } catch (e: Exception) {
            Log.w(TAG, "$label failed after ${(System.nanoTime() - start) / 1_000_000}ms: ${e.message}")
            throw e
        }
    }

    private fun notifyChildrenChanged(parentDocumentId: String) {
        context?.contentResolver?.notifyChange(childrenUri(parentDocumentId), null)
    }

    private fun childrenUri(parentDocumentId: String) =
        DocumentsContract.buildChildDocumentsUri(AUTHORITY, parentDocumentId)

    private fun addTopRow(cursor: MatrixCursor) =
        addRow(cursor, TOP_DOC_ID, "Laptop", Document.MIME_TYPE_DIR, 0, 0, flags = 0)

    // Shared folders are fixed by the laptop's config, so they can hold new files but can't be
    // renamed or deleted from the tablet.
    private fun addRootFolderRow(cursor: MatrixCursor, documentId: String, name: String) =
        addRow(cursor, documentId, name, Document.MIME_TYPE_DIR, 0, 0, Document.FLAG_DIR_SUPPORTS_CREATE)

    private fun addEntryRow(cursor: MatrixCursor, documentId: String, entry: FileEntry) {
        val editable = Document.FLAG_SUPPORTS_DELETE or Document.FLAG_SUPPORTS_RENAME
        if (entry.isDir) {
            addRow(cursor, documentId, entry.name, Document.MIME_TYPE_DIR, 0, entry.mtime,
                editable or Document.FLAG_DIR_SUPPORTS_CREATE)
        } else {
            val mimeType = mimeTypeFor(entry.name)
            val thumbnail = if (mimeType.startsWith("image/")) Document.FLAG_SUPPORTS_THUMBNAIL else 0
            addRow(cursor, documentId, entry.name, mimeType, entry.size, entry.mtime,
                editable or Document.FLAG_SUPPORTS_WRITE or thumbnail)
        }
    }

    private fun addRow(
        cursor: MatrixCursor,
        documentId: String,
        name: String,
        mimeType: String,
        size: Long,
        mtime: Long,
        flags: Int,
    ) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, documentId)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_SIZE, size)
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_LAST_MODIFIED, mtime * 1000L)
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    // Apps creating a document pass a MIME type and often a bare name ("Scan 12"); the laptop
    // only sees the file name, so the extension has to come from the MIME type.
    private fun withExtensionFor(name: String, mimeType: String): String {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: return name
        return if (name.endsWith(".$extension", ignoreCase = true)) name else "$name.$extension"
    }

    private fun docId(rootId: String, path: String) = "$rootId$SEPARATOR$path"

    private fun parseDocId(documentId: String): Pair<String, String> {
        val index = documentId.indexOf(SEPARATOR)
        if (index == -1) return documentId to ""
        return documentId.substring(0, index) to documentId.substring(index + SEPARATOR.length)
    }

    private fun parentDocId(documentId: String): String {
        val (rootId, path) = parseDocId(documentId)
        return docId(rootId, path.substringBeforeLast('/', ""))
    }

    private fun childPath(parentPath: String, name: String) =
        if (parentPath.isEmpty()) name else "$parentPath/$name"

    companion object {
        private const val TAG = "Pairbridge"
        const val AUTHORITY = "dev.pairbridge.app.documents"
        const val ROOT_ID = "pairbridge-laptop"
        private const val SEPARATOR = "::"
        private const val TOP_DOC_ID = "top"

        // Files up to this size are downloaded in the background when their folder is listed.
        private const val MAX_PRELOAD_BYTES = 10L * 1024 * 1024

        private val ROOT_PROJECTION = arrayOf(
            Root.COLUMN_ROOT_ID,
            Root.COLUMN_ICON,
            Root.COLUMN_TITLE,
            Root.COLUMN_FLAGS,
            Root.COLUMN_DOCUMENT_ID,
        )

        private val DOC_PROJECTION = arrayOf(
            Document.COLUMN_DOCUMENT_ID,
            Document.COLUMN_DISPLAY_NAME,
            Document.COLUMN_SIZE,
            Document.COLUMN_MIME_TYPE,
            Document.COLUMN_LAST_MODIFIED,
            Document.COLUMN_FLAGS,
        )
    }
}
