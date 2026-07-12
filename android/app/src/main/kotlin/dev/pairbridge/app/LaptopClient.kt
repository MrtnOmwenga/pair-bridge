package dev.pairbridge.app

import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject

data class FileEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val mtime: Long,
)

class LaptopClientException(message: String) : IOException(message)

/** Thin HTTP client for talking to the Pairbridge laptop server. */
class LaptopClient(private val host: String, private val port: Int, private val token: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val baseUrl = "http://$host:$port"

    private fun authorizedRequest(url: String): Request =
        Request.Builder().url(url).header("Authorization", "Bearer $token").build()

    fun checkHealth(): Boolean {
        val request = Request.Builder().url("$baseUrl/health").build()
        client.newCall(request).execute().use { response -> return response.isSuccessful }
    }

    fun listFiles(path: String): List<FileEntry> {
        val url = "$baseUrl/files?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
        client.newCall(authorizedRequest(url)).execute().use { response ->
            if (!response.isSuccessful) {
                throw LaptopClientException("list failed: HTTP ${response.code}")
            }
            val body = response.body?.string() ?: throw LaptopClientException("empty response")
            val json = JSONObject(body)
            val entries = json.getJSONArray("entries")
            return (0 until entries.length()).map { i ->
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

    /** Caller is responsible for closing the returned response (and its body stream). */
    fun openDownload(path: String): Response {
        val url = "$baseUrl/files/download?path=${java.net.URLEncoder.encode(path, "UTF-8")}"
        val response = client.newCall(authorizedRequest(url)).execute()
        if (!response.isSuccessful) {
            response.close()
            throw LaptopClientException("download failed: HTTP ${response.code}")
        }
        return response
    }

    fun downloadStream(path: String): InputStream = openDownload(path).body?.byteStream()
        ?: throw LaptopClientException("empty download body")
}
