package dev.pairbridge.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * "Send to laptop" in Android's share sheet: uploads the shared files (or text) into the laptop's
 * inbox folder.
 *
 * This is the tablet -> laptop path that works on HyperOS, because the tablet only opens outbound
 * connections. Uploads run inside this activity rather than a background worker because the read
 * grant for shared content URIs is tied to the activity that received them.
 */
class ShareActivity : AppCompatActivity() {

    private class Item(val name: String, val size: Long, val open: () -> InputStream)

    @Volatile
    private var cancelled = false

    private lateinit var titleText: TextView
    private lateinit var detailText: TextView
    private lateinit var progress: LinearProgressIndicator

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)
        setFinishOnTouchOutside(false)
        titleText = findViewById(R.id.shareTitle)
        detailText = findViewById(R.id.shareDetail)
        progress = findViewById(R.id.shareProgress)
        findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener {
            cancelled = true
            finish()
        }

        val laptop = LaptopConnection.get(this)
        if (!laptop.credentials.isPaired) {
            toast(getString(R.string.share_not_paired))
            startActivity(Intent(this, PairingActivity::class.java))
            finish()
            return
        }
        val items = itemsFrom(intent)
        if (items.isEmpty()) {
            toast(getString(R.string.share_nothing))
            finish()
            return
        }
        lifecycleScope.launch { send(laptop, items) }
    }

    private suspend fun send(laptop: LaptopConnection, items: List<Item>) {
        var folder = ""
        try {
            // A cheap authenticated call first: fails fast with a clear error, and gives the
            // connection a chance to find the laptop at a new address before any upload starts.
            withContext(Dispatchers.IO) { laptop.call { it.listRoots() } }
            for ((index, item) in items.withIndex()) {
                titleText.text = getString(R.string.share_sending, index + 1, items.size)
                detailText.text = item.name
                progress.isIndeterminate = item.size <= 0
                progress.progress = 0
                val saved = withContext(Dispatchers.IO) {
                    item.open().use { input ->
                        val tracked = ProgressInputStream(input) { read ->
                            if (item.size > 0) runOnUiThread { progress.progress = (read * 100 / item.size).toInt() }
                        }
                        laptop.client().sendToInbox(item.name, tracked, item.size)
                    }
                }
                folder = saved.path.substringBeforeLast('/', "")
            }
            toast(resources.getQuantityString(R.plurals.share_done, items.size, items.size, folder))
        } catch (e: IOException) {
            if (!cancelled) toast(getString(R.string.share_failed, e.message))
        }
        finish()
    }

    private fun itemsFrom(intent: Intent): List<Item> {
        val uris = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))
            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()
            else -> emptyList()
        }
        if (uris.isNotEmpty()) return uris.map(::itemFor)

        // Shared links and text arrive as EXTRA_TEXT with no stream; they're saved as a .txt file.
        val text = intent.getStringExtra(Intent.EXTRA_TEXT) ?: return emptyList()
        val bytes = text.toByteArray()
        val stamp = SimpleDateFormat("yyyy-MM-dd HH.mm.ss", Locale.US).format(Date())
        return listOf(Item("Shared text $stamp.txt", bytes.size.toLong()) { bytes.inputStream() })
    }

    private fun itemFor(uri: Uri): Item {
        var name: String? = null
        var size = -1L
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0)
                    if (!cursor.isNull(1)) size = cursor.getLong(1)
                }
            }
        val safeName = (name ?: uri.lastPathSegment ?: "shared file").replace('/', '_')
        return Item(safeName, size) {
            contentResolver.openInputStream(uri) ?: throw IOException("could not open $safeName")
        }
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    /** Reports bytes read, and aborts the upload once the user cancels. */
    private inner class ProgressInputStream(input: InputStream, private val onRead: (Long) -> Unit) :
        FilterInputStream(input) {
        private var total = 0L
        private var lastReported = 0L

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (cancelled) throw IOException("cancelled")
            val n = super.read(b, off, len)
            if (n > 0) {
                total += n
                if (total - lastReported >= 256 * 1024) {
                    lastReported = total
                    onRead(total)
                }
            }
            return n
        }
    }
}
