package dev.pairbridge.app

import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import org.json.JSONObject

data class FileEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
)

data class ShareRoot(val id: String, val name: String)

data class Roots(val serverId: String?, val roots: List<ShareRoot>)

/** A file or folder the server wrote, created or renamed, at its final path. */
data class SavedEntry(val path: String, val entry: FileEntry)

class LaptopClientException(val code: Int, message: String) : IOException(message)

private const val TAG = "Pairbridge"
private val OCTET_STREAM = "application/octet-stream".toMediaType()

/** HTTP client for the Pairbridge laptop server. Blocking; call it off the main thread. */
class LaptopClient(host: String, port: Int, private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val request = chain.request()
            val start = System.nanoTime()
            val response = chain.proceed(request)
            val ms = (System.nanoTime() - start) / 1_000_000
            Log.d(TAG, "${request.method} ${request.url.encodedPath} -> ${response.code} in ${ms}ms")
            response
        }
        .build()

    private val baseUrl = HttpUrl.Builder().scheme("http").host(host).port(port).build()

    private fun url(path: String, vararg params: Pair<String, String>): HttpUrl =
        baseUrl.newBuilder().addPathSegments(path).apply {
            for ((key, value) in params) addQueryParameter(key, value)
        }.build()

    private fun request(url: HttpUrl): Request.Builder =
        Request.Builder().url(url).header("Authorization", "Bearer $token")

    private fun execute(request: Request): Response {
        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val detail = response.body?.string()?.let { runCatching { JSONObject(it).getString("detail") }.getOrNull() }
            response.close()
            throw LaptopClientException(response.code, "${request.url.encodedPath}: HTTP ${response.code} ${detail ?: ""}".trim())
        }
        return response
    }

    private fun json(request: Request): JSONObject =
        execute(request).use { JSONObject(it.body?.string() ?: throw IOException("empty response")) }

    private fun parseEntry(json: JSONObject) = FileEntry(
        name = json.getString("name"),
        isDir = json.getBoolean("is_dir"),
        size = json.getLong("size"),
        mtime = json.getLong("mtime"),
    )

    private fun parseSaved(json: JSONObject) = SavedEntry(json.getString("path"), parseEntry(json))

    fun listRoots(): Roots {
        val json = json(request(url("roots")).build())
        val roots = json.getJSONArray("roots")
        return Roots(
            serverId = json.optString("server_id").ifEmpty { null },
            roots = (0 until roots.length()).map { i ->
                val root = roots.getJSONObject(i)
                ShareRoot(id = root.getString("id"), name = root.getString("name"))
            },
        )
    }

    fun listFiles(rootId: String, path: String): List<FileEntry> {
        val entries = json(request(url("files", "root" to rootId, "path" to path)).build()).getJSONArray("entries")
        return (0 until entries.length()).map { parseEntry(entries.getJSONObject(it)) }
    }

    fun stat(rootId: String, path: String): FileEntry =
        parseEntry(json(request(url("files/stat", "root" to rootId, "path" to path)).build()))

    /** The caller must close the returned stream. */
    fun download(rootId: String, path: String): InputStream {
        val response = execute(request(url("files/download", "root" to rootId, "path" to path)).build())
        return response.body?.byteStream() ?: throw IOException("empty download body")
    }

    /** A JPEG no larger than [size] on its longest side, rendered by the laptop. */
    fun thumbnail(rootId: String, path: String, size: Int): ByteArray =
        execute(request(url("files/thumb", "root" to rootId, "path" to path, "size" to size.toString())).build())
            .use { it.body?.bytes() ?: throw IOException("empty thumbnail") }

    /** Replaces the file's content with [input]. The laptop only swaps the file in once the upload completes. */
    fun write(rootId: String, path: String, input: InputStream): SavedEntry =
        parseSaved(json(request(url("files/content", "root" to rootId, "path" to path)).put(streamBody(input)).build()))

    fun create(rootId: String, parentPath: String, name: String, isDir: Boolean): SavedEntry {
        val kind = if (isDir) "dir" else "file"
        val url = url("files/create", "root" to rootId, "parent" to parentPath, "name" to name, "kind" to kind)
        return parseSaved(json(request(url).post(EMPTY_BODY).build()))
    }

    fun rename(rootId: String, path: String, newName: String): SavedEntry =
        parseSaved(json(request(url("files/rename", "root" to rootId, "path" to path, "name" to newName)).post(EMPTY_BODY).build()))

    fun delete(rootId: String, path: String) {
        execute(request(url("files", "root" to rootId, "path" to path)).delete().build()).close()
    }

    /** Uploads into the laptop's inbox folder; the laptop picks a free name if [name] is taken. */
    fun sendToInbox(name: String, input: InputStream, length: Long): SavedEntry =
        parseSaved(json(request(url("inbox", "name" to name)).post(streamBody(input, length)).build()))

    private fun streamBody(input: InputStream, length: Long = -1L) = object : RequestBody() {
        override fun contentType() = OCTET_STREAM
        override fun contentLength() = length
        // Not closed here: the caller owns the stream (and, for pipes, the file descriptor behind it).
        override fun writeTo(sink: BufferedSink) {
            sink.writeAll(input.source())
        }

        // The stream can be read only once, so OkHttp must not replay this body on a retry.
        override fun isOneShot() = true
    }

    private companion object {
        val EMPTY_BODY = ByteArray(0).toRequestBody()
    }
}
