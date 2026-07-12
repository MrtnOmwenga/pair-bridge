package dev.pairbridge.app

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Point
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

/**
 * Exposes a single "Laptop" root in the Storage Access Framework, so it shows up in any
 * app's file picker (Slack, WhatsApp, Files) the same way Dropbox or Drive would. Inside
 * that root, each of the laptop's configured shared folders (Downloads, Documents, ...)
 * appears as a subfolder.
 */
class LaptopDocumentsProvider : DocumentsProvider() {

    private val rootProjection = arrayOf(
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_FLAGS,
        Root.COLUMN_DOCUMENT_ID,
    )

    private val docProjection = arrayOf(
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_SIZE,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
    )

    override fun onCreate(): Boolean = true

    /**
     * StrictMode's thread policy propagates across Binder calls, so a caller that enforces
     * "no network on this thread" (Slack does) trips NetworkOnMainThreadException here even
     * though this isn't the UI thread. Each entry point needs to relax it locally.
     */
    private fun allowNetworkOnThisThread() {
        StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.Builder().permitAll().build())
    }

    // Reused across calls so requests share one pooled/keep-alive connection instead of each
    // opening a fresh TCP connection.
    private var cachedClient: LaptopClient? = null
    private var cachedCredentials: Triple<String, Int, String>? = null

    // Root id -> display name, populated when the "Laptop" root's children are listed; lets
    // queryDocument() label a root folder's own row without an extra round trip.
    private var rootNames: Map<String, String> = emptyMap()

    // CredentialStore sets up Android's hardware-backed Keystore encryption on construction
    // (MasterKey + EncryptedSharedPreferences), which is measurably slow after the process has
    // been idle. Building a fresh one on every call was a hidden cost behind "queryDocument
    // took 3 seconds" even though the actual network request was fast — reuse a single instance.
    private val credentialStore: CredentialStore by lazy { CredentialStore(context!!) }

    private val preloadCache: PreloadCache by lazy { PreloadCache(File(context!!.cacheDir, "preload")) }

    // documentId -> last-known metadata, populated whenever a folder is listed. Lets
    // queryDocument()/openDocument() answer from memory + local disk with zero network calls
    // for anything already seen this session, which is what actually dodges the WiFi radio's
    // wake-from-idle latency (a stat call is cheap once you're on the network, but the first
    // packet after a couple seconds of silence can cost 1-3s regardless of payload size).
    private val knownEntries = ConcurrentHashMap<String, FileEntry>()

    // queryChildDocuments() fires repeatedly for the same folder (the picker re-lists on
    // every scroll/focus change); without tracking in-flight downloads, a second listing that
    // arrives before the first preload finishes writing sees no cache file yet and starts a
    // duplicate download of the same file.
    private val preloadsInFlight = ConcurrentHashMap.newKeySet<String>()

    private fun client(): LaptopClient {
        val host = credentialStore.host ?: throw IllegalStateException("not paired")
        val token = credentialStore.token ?: throw IllegalStateException("not paired")
        val credentials = Triple(host, credentialStore.port, token)
        if (credentials != cachedCredentials) {
            cachedClient = LaptopClient(host, credentialStore.port, token)
            cachedCredentials = credentials
        }
        return cachedClient!!
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val cursor = MatrixCursor(projection ?: rootProjection)
        if (context == null) return cursor
        if (!credentialStore.isPaired) return cursor

        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, "pairbridge-laptop")
            add(Root.COLUMN_ICON, R.drawable.ic_launcher_foreground)
            add(Root.COLUMN_TITLE, "Laptop")
            add(Root.COLUMN_FLAGS, 0)
            add(Root.COLUMN_DOCUMENT_ID, TOP_DOC_ID)
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor =
        logged("queryDocument($documentId)") {
            val cursor = MatrixCursor(projection ?: docProjection)
            if (documentId == TOP_DOC_ID) {
                addDirRow(cursor, documentId, "Laptop")
                return@logged cursor
            }

            val (rootId, path) = parseDocId(documentId)
            if (path.isEmpty()) {
                addDirRow(cursor, documentId, rootNames[rootId] ?: rootId)
                return@logged cursor
            }

            val known = knownEntries[documentId]
            if (known != null) {
                if (known.isDir) addDirRow(cursor, documentId, known.name) else addFileRow(cursor, documentId, known)
                return@logged cursor
            }

            allowNetworkOnThisThread()
            val entry = try {
                client().statFile(rootId, path)
            } catch (e: IOException) {
                throw FileNotFoundException("could not stat $path: ${e.message}")
            }
            knownEntries[documentId] = entry

            if (entry.isDir) {
                addDirRow(cursor, documentId, entry.name)
            } else {
                addFileRow(cursor, documentId, entry)
            }
            cursor
        }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor = logged("queryChildDocuments($parentDocumentId)") {
        allowNetworkOnThisThread()
        val cursor = MatrixCursor(projection ?: docProjection)

        if (parentDocumentId == TOP_DOC_ID) {
            val roots = try {
                client().listRoots()
            } catch (e: IOException) {
                throw FileNotFoundException("could not list shared folders: ${e.message}")
            }
            rootNames = roots.associate { it.id to it.name }
            for (root in roots) {
                addDirRow(cursor, docId(root.id, ""), root.name)
            }
            return@logged cursor
        }

        val (rootId, parentPath) = parseDocId(parentDocumentId)
        val entries = try {
            client().listFiles(rootId, parentPath)
        } catch (e: IOException) {
            throw FileNotFoundException("could not list $parentPath: ${e.message}")
        }

        for (entry in entries) {
            val childPath = if (parentPath.isEmpty()) entry.name else "$parentPath/${entry.name}"
            val childDocId = docId(rootId, childPath)
            knownEntries[childDocId] = entry
            if (entry.isDir) {
                addDirRow(cursor, childDocId, entry.name)
            } else {
                addFileRow(cursor, childDocId, entry)
                if (entry.size in 1..MAX_PRELOAD_BYTES) {
                    preload(rootId, childPath, entry)
                }
            }
        }
        cursor
    }

    /** Downloads a file into [preloadCache] in the background, skipping it if already cached. */
    private fun preload(rootId: String, path: String, entry: FileEntry) {
        if (preloadCache.find(rootId, path, entry.size, entry.mtime) != null) return
        val inFlightKey = "$rootId::$path::${entry.size}::${entry.mtime}"
        if (!preloadsInFlight.add(inFlightKey)) return

        thread(name = "pairbridge-preload-${rootId}-${path.hashCode()}") {
            allowNetworkOnThisThread()
            try {
                val target = preloadCache.fileFor(rootId, path, entry.size, entry.mtime)
                val tmp = File(target.parentFile, "${target.name}.tmp")
                client().downloadForPreload(rootId, path).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                }
                tmp.renameTo(target)
                preloadCache.purgeOtherVersions(rootId, path, target)
                Log.d(TAG, "preloaded $rootId/$path (${entry.size} bytes)")
            } catch (e: IOException) {
                Log.w(TAG, "preload failed for $rootId/$path: ${e.message}")
            } finally {
                preloadsInFlight.remove(inFlightKey)
            }
        }
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor = logged("openDocument($documentId)") {
        if (mode != "r") {
            throw UnsupportedOperationException("only read access is supported")
        }
        val (rootId, path) = parseDocId(documentId)
        allowNetworkOnThisThread()

        val known = knownEntries[documentId]
        if (known != null) {
            val cached = preloadCache.find(rootId, path, known.size, known.mtime)
            if (cached != null) {
                Log.d(TAG, "openDocument($documentId): serving from local cache, no network used")
                return@logged ParcelFileDescriptor.open(cached, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }

        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]
        val start = System.nanoTime()

        thread(name = "pairbridge-download-$documentId") {
            allowNetworkOnThisThread()
            try {
                client().downloadStream(rootId, path).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                        val bytes = input.copyTo(output)
                        val ms = (System.nanoTime() - start) / 1_000_000
                        Log.d(TAG, "openDocument($documentId): streamed $bytes bytes in ${ms}ms")
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "openDocument($documentId): failed: ${e.message}")
                try {
                    writeSide.closeWithError(e.message ?: "download failed")
                } catch (_: IOException) {
                    // pipe already closed; nothing more to do
                }
            }
        }
        readSide
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: Point?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor = logged("openDocumentThumbnail($documentId)") {
        allowNetworkOnThisThread()
        val (rootId, path) = parseDocId(documentId)
        val thumbFile = thumbCacheFile(documentId)

        if (!thumbFile.exists()) {
            val bytes = client().downloadForThumbnail(rootId, path).use { it.readBytes() }

            // Warms the same cache the background preloader and openDocument() use, so a
            // thumbnail fetch (which happens while scrolling, ahead of any tap) also covers
            // the real attach that usually follows shortly after.
            val known = knownEntries[documentId]
            if (known != null && known.size <= MAX_PRELOAD_BYTES) {
                val target = preloadCache.fileFor(rootId, path, known.size, known.mtime)
                if (!target.exists()) {
                    target.writeBytes(bytes)
                    preloadCache.purgeOtherVersions(rootId, path, target)
                }
            }

            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            val targetW = sizeHint?.x ?: 256
            val targetH = sizeHint?.y ?: 256
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetW, targetH)
            }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: throw FileNotFoundException("could not decode image: $path")
            FileOutputStream(thumbFile).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 82, out) }
            bitmap.recycle()
        }

        AssetFileDescriptor(
            ParcelFileDescriptor.open(thumbFile, ParcelFileDescriptor.MODE_READ_ONLY),
            0,
            AssetFileDescriptor.UNKNOWN_LENGTH,
        )
    }

    private fun sampleSizeFor(width: Int, height: Int, targetW: Int, targetH: Int): Int {
        var sampleSize = 1
        while (width / (sampleSize * 2) >= targetW && height / (sampleSize * 2) >= targetH) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun thumbCacheFile(documentId: String): File =
        File(context!!.cacheDir, "pairbridge_thumb_${documentId.hashCode()}.jpg")

    private fun <T> logged(label: String, block: () -> T): T {
        val start = System.nanoTime()
        Log.d(TAG, "-> $label")
        try {
            val result = block()
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.d(TAG, "<- $label (${ms}ms)")
            return result
        } catch (e: Exception) {
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.w(TAG, "x  $label failed after ${ms}ms: ${e.message}")
            throw e
        }
    }

    private fun addDirRow(cursor: MatrixCursor, docId: String, name: String) {
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, name)
            add(Document.COLUMN_SIZE, 0)
            add(Document.COLUMN_MIME_TYPE, Document.MIME_TYPE_DIR)
            add(Document.COLUMN_LAST_MODIFIED, 0)
            add(Document.COLUMN_FLAGS, Document.FLAG_DIR_SUPPORTS_CREATE)
        }
    }

    private fun addFileRow(cursor: MatrixCursor, docId: String, entry: FileEntry) {
        val mimeType = mimeTypeFor(entry.name)
        val flags = if (mimeType.startsWith("image/")) Document.FLAG_SUPPORTS_THUMBNAIL else 0
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, entry.name)
            add(Document.COLUMN_SIZE, entry.size)
            add(Document.COLUMN_MIME_TYPE, mimeType)
            add(Document.COLUMN_LAST_MODIFIED, entry.mtime * 1000L)
            add(Document.COLUMN_FLAGS, flags)
        }
    }

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "")
        if (extension.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            ?: "application/octet-stream"
    }

    /** Document IDs encode which root they belong to: "<rootId>::<relativePath>". */
    private fun docId(rootId: String, path: String): String = "$rootId$DOC_ID_SEPARATOR$path"

    private fun parseDocId(documentId: String): Pair<String, String> {
        val index = documentId.indexOf(DOC_ID_SEPARATOR)
        return if (index == -1) {
            documentId to ""
        } else {
            documentId.substring(0, index) to documentId.substring(index + DOC_ID_SEPARATOR.length)
        }
    }

    companion object {
        private const val TAG = "Pairbridge"
        private const val DOC_ID_SEPARATOR = "::"
        private const val TOP_DOC_ID = "top"

        // Files at or under this size get preloaded to local storage in the background when
        // their folder is browsed. No settings UI yet, so tune by editing this constant.
        private const val MAX_PRELOAD_BYTES = 10L * 1024 * 1024
    }
}
