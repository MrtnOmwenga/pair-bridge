package dev.pairbridge.app

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

data class FileEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
)

data class ShareRoot(val id: String, val name: String)

class LaptopClientException(message: String) : IOException(message)

private const val TAG = "Pairbridge"

/** Thin HTTP client for talking to the Pairbridge laptop server. */
class LaptopClient(private val host: String, private val port: Int, private val token: String) {

    // Shared so both clients below reuse the same pooled TCP connections.
    private val connectionPool = ConnectionPool()

    private val client = OkHttpClient.Builder()
        .connectionPool(connectionPool)
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // The picker fires a burst of thumbnail fetches while scrolling a folder (one full
    // download per visible image). Those must never queue in front of an interactive
    // stat/list/download triggered by the user actually tapping a file, so thumbnails get
    // their own capped Dispatcher instead of sharing OkHttp's default one with everything else.
    private val thumbnailClient = client.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequests = 3; maxRequestsPerHost = 3 })
        .build()

    // Background folder preloading is the lowest priority. Kept to a single connection: a
    // handful of parallel multi-MB downloads on a local dev server can saturate the LAN link
    // badly enough that a concurrent interactive fetch (a tap on an uncached file) slows to a
    // crawl even though it's on its own dispatcher and never queues behind preload requests —
    // separate dispatchers avoid queueing contention, not bandwidth contention.
    private val preloadClient = client.newBuilder()
        .dispatcher(Dispatcher().apply { maxRequests = 1; maxRequestsPerHost = 1 })
        .build()

    private val baseUrl = "http://$host:$port"

    private fun authorizedRequest(url: String): Request =
        Request.Builder().url(url).header("Authorization", "Bearer $token").build()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun <T> timed(label: String, block: () -> T): T {
        val start = System.nanoTime()
        Log.d(TAG, "-> $label")
        try {
            val result = block()
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.d(TAG, "<- $label (${ms}ms)")
            return result
        } catch (e: Exception) {
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.d(TAG, "x  $label failed after ${ms}ms: ${e.message}")
            throw e
        }
    }

    fun checkHealth(): Boolean = timed("GET /health") {
        val request = Request.Builder().url("$baseUrl/health").build()
        client.newCall(request).execute().use { response -> response.isSuccessful }
    }

    fun listRoots(): List<ShareRoot> = timed("GET /roots") {
        val request = authorizedRequest("$baseUrl/roots")
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw LaptopClientException("roots failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw LaptopClientException("empty response")
            val roots = JSONObject(body).getJSONArray("roots")
            (0 until roots.length()).map { i ->
                val root = roots.getJSONObject(i)
                ShareRoot(id = root.getString("id"), name = root.getString("name"))
            }
        }
    }

    fun statFile(rootId: String, path: String): FileEntry = timed("GET /files/stat?root=$rootId&path=$path") {
        val url = "$baseUrl/files/stat?root=${encode(rootId)}&path=${encode(path)}"
        client.newCall(authorizedRequest(url)).execute().use { response ->
            if (!response.isSuccessful) {
                throw LaptopClientException("stat failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw LaptopClientException("empty response")
            val entry = JSONObject(body)
            FileEntry(
                name = entry.getString("name"),
                isDir = entry.getBoolean("is_dir"),
                size = entry.getLong("size"),
                mtime = entry.getLong("mtime"),
            )
        }
    }

    fun listFiles(rootId: String, path: String): List<FileEntry> =
        timed("GET /files?root=$rootId&path=$path") {
            val url = "$baseUrl/files?root=${encode(rootId)}&path=${encode(path)}"
            client.newCall(authorizedRequest(url)).execute().use { response ->
                if (!response.isSuccessful) {
                    throw LaptopClientException("list failed: HTTP ${response.code}")
                }
                val body = response.body?.string() ?: throw LaptopClientException("empty response")
                val entries = JSONObject(body).getJSONArray("entries")
                (0 until entries.length()).map { i ->
                    val entry = entries.getJSONObject(i)
                    FileEntry(
                        name = entry.getString("name"),
                        isDir = entry.getBoolean("is_dir"),
                        size = entry.getLong("size"),
                        mtime = entry.getLong("mtime"),
                    )
                }
            }
        }

    private fun download(httpClient: OkHttpClient, label: String, rootId: String, path: String): InputStream =
        timed(label) {
            val url = "$baseUrl/files/download?root=${encode(rootId)}&path=${encode(path)}"
            val response = httpClient.newCall(authorizedRequest(url)).execute()
            if (!response.isSuccessful) {
                response.close()
                throw LaptopClientException("download failed: HTTP ${response.code}")
            }
            response.body?.byteStream() ?: throw LaptopClientException("empty download body")
        }

    fun downloadStream(rootId: String, path: String): InputStream =
        download(client, "GET /files/download?root=$rootId&path=$path", rootId, path)

    /** Same as [downloadStream] but queued on the low-concurrency thumbnail dispatcher. */
    fun downloadForThumbnail(rootId: String, path: String): InputStream =
        download(thumbnailClient, "GET(thumb) /files/download?root=$rootId&path=$path", rootId, path)

    /** Same as [downloadStream] but queued on the lowest-priority preload dispatcher. */
    fun downloadForPreload(rootId: String, path: String): InputStream =
        download(preloadClient, "GET(preload) /files/download?root=$rootId&path=$path", rootId, path)
}
