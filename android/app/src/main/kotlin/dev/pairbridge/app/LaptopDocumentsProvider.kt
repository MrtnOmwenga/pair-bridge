package dev.pairbridge.app

import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.os.StrictMode
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.webkit.MimeTypeMap
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Exposes the laptop's shared folder as a Storage Access Framework root, so it shows up
 * in any app's file picker (Slack, WhatsApp, Files) the same way Dropbox or Drive would.
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
    // opening a fresh TCP connection (queryDocument + openDocument per tap otherwise pay for
    // two handshakes back to back, which is the "moment of delay" before a file attaches).
    private var cachedClient: LaptopClient? = null
    private var cachedCredentials: Triple<String, Int, String>? = null

    private fun client(): LaptopClient {
        val store = CredentialStore(context ?: throw IllegalStateException("no context"))
        val host = store.host ?: throw IllegalStateException("not paired")
        val token = store.token ?: throw IllegalStateException("not paired")
        val credentials = Triple(host, store.port, token)
        if (credentials != cachedCredentials) {
            cachedClient = LaptopClient(host, store.port, token)
            cachedCredentials = credentials
        }
        return cachedClient!!
    }

    override fun queryRoots(projection: Array<out String>?): Cursor {
        allowNetworkOnThisThread()
        val cursor = MatrixCursor(projection ?: rootProjection)
        val store = CredentialStore(context ?: return cursor)
        if (!store.isPaired) return cursor

        cursor.newRow().apply {
            add(Root.COLUMN_ROOT_ID, ROOT_ID)
            add(Root.COLUMN_ICON, R.drawable.ic_launcher_foreground)
            add(Root.COLUMN_TITLE, "Laptop")
            add(Root.COLUMN_FLAGS, 0)
            add(Root.COLUMN_DOCUMENT_ID, docIdForPath(""))
        }
        return cursor
    }

    override fun queryDocument(documentId: String, projection: Array<out String>?): Cursor {
        allowNetworkOnThisThread()
        val cursor = MatrixCursor(projection ?: docProjection)
        val path = pathForDocId(documentId)
        if (path.isEmpty()) {
            addDirRow(cursor, documentId, "Laptop")
            return cursor
        }

        val parentPath = path.substringBeforeLast('/', "")
        val name = path.substringAfterLast('/')
        val entry = try {
            client().listFiles(parentPath).firstOrNull { it.name == name }
        } catch (e: IOException) {
            throw FileNotFoundException("could not stat $path: ${e.message}")
        } ?: throw FileNotFoundException(path)

        if (entry.isDir) {
            addDirRow(cursor, documentId, entry.name)
        } else {
            addFileRow(cursor, documentId, entry)
        }
        return cursor
    }

    override fun queryChildDocuments(
        parentDocumentId: String,
        projection: Array<out String>?,
        sortOrder: String?,
    ): Cursor {
        allowNetworkOnThisThread()
        val cursor = MatrixCursor(projection ?: docProjection)
        val parentPath = pathForDocId(parentDocumentId)
        val entries = try {
            client().listFiles(parentPath)
        } catch (e: IOException) {
            throw FileNotFoundException("could not list $parentPath: ${e.message}")
        }

        for (entry in entries) {
            val childPath = if (parentPath.isEmpty()) entry.name else "$parentPath/${entry.name}"
            val childDocId = docIdForPath(childPath)
            if (entry.isDir) {
                addDirRow(cursor, childDocId, entry.name)
            } else {
                addFileRow(cursor, childDocId, entry)
            }
        }
        return cursor
    }

    override fun openDocument(
        documentId: String,
        mode: String,
        signal: CancellationSignal?,
    ): ParcelFileDescriptor {
        if (mode != "r") {
            throw UnsupportedOperationException("only read access is supported")
        }
        val path = pathForDocId(documentId)
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val readSide = pipe[0]
        val writeSide = pipe[1]

        thread(name = "pairbridge-download-$documentId") {
            allowNetworkOnThisThread()
            try {
                client().downloadStream(path).use { input ->
                    ParcelFileDescriptor.AutoCloseOutputStream(writeSide).use { output ->
                        input.copyTo(output)
                    }
                }
            } catch (e: IOException) {
                try {
                    writeSide.closeWithError(e.message ?: "download failed")
                } catch (_: IOException) {
                    // pipe already closed; nothing more to do
                }
            }
        }
        return readSide
    }

    override fun openDocumentThumbnail(
        documentId: String,
        sizeHint: android.graphics.Point?,
        signal: CancellationSignal?,
    ): AssetFileDescriptor? = null

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
        cursor.newRow().apply {
            add(Document.COLUMN_DOCUMENT_ID, docId)
            add(Document.COLUMN_DISPLAY_NAME, entry.name)
            add(Document.COLUMN_SIZE, entry.size)
            add(Document.COLUMN_MIME_TYPE, mimeTypeFor(entry.name))
            add(Document.COLUMN_LAST_MODIFIED, entry.mtime * 1000L)
            add(Document.COLUMN_FLAGS, 0)
        }
    }

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "")
        if (extension.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase())
            ?: "application/octet-stream"
    }

    private fun docIdForPath(path: String): String = if (path.isEmpty()) ROOT_DOC_ID else path

    private fun pathForDocId(docId: String): String = if (docId == ROOT_DOC_ID) "" else docId

    companion object {
        private const val ROOT_ID = "pairbridge-laptop"
        private const val ROOT_DOC_ID = "root"
    }
}
