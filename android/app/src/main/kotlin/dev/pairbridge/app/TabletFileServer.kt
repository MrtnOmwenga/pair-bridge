package dev.pairbridge.app

import android.os.Environment
import android.util.Log
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * The reverse of the laptop server: serves the tablet's public folders with the same endpoint
 * shapes, path-escape protection and pairing token, for the laptop's FUSE mount.
 *
 * Known issue: on HyperOS, connections to this server over WiFi hang at the TCP level while the
 * same requests over USB (adb forward) work. See the README; ShareActivity is the working
 * tablet -> laptop path.
 */
class TabletFileServer(port: Int, token: String) : NanoHTTPD("0.0.0.0", port) {

    private val expectedAuth = "Bearer $token".toByteArray()

    private val roots: Map<String, Pair<String, File>> = buildMap {
        fun add(id: String, name: String, dir: String) {
            val file = Environment.getExternalStoragePublicDirectory(dir)
            if (file.isDirectory) put(id, name to file)
        }
        add("dcim", "Camera", Environment.DIRECTORY_DCIM)
        add("download", "Download", Environment.DIRECTORY_DOWNLOADS)
        add("pictures", "Pictures", Environment.DIRECTORY_PICTURES)
        add("documents", "Documents", Environment.DIRECTORY_DOCUMENTS)
    }

    private class ApiException(val status: Response.Status, message: String) : Exception(message)

    override fun serve(session: IHTTPSession): Response {
        Log.d(TAG, "serve: ${session.method} ${session.uri}")
        if (session.uri == "/health") {
            return jsonResponse(Response.Status.OK, JSONObject().put("status", "ok"))
        }

        val presented = session.headers["authorization"].orEmpty().toByteArray()
        if (!MessageDigest.isEqual(presented, expectedAuth)) {
            return jsonResponse(Response.Status.UNAUTHORIZED, JSONObject().put("detail", "invalid or missing token"))
        }

        return try {
            when (session.uri) {
                "/roots" -> handleRoots()
                "/files" -> handleList(session)
                "/files/stat" -> handleStat(session)
                "/files/download" -> handleDownload(session)
                else -> jsonResponse(Response.Status.NOT_FOUND, JSONObject().put("detail", "not found"))
            }
        } catch (e: ApiException) {
            jsonResponse(e.status, JSONObject().put("detail", e.message))
        }
    }

    private fun resolvePath(rootId: String, relativePath: String): File {
        val base = roots[rootId]?.second ?: throw ApiException(Response.Status.NOT_FOUND, "unknown root")
        val baseCanonical = base.canonicalFile
        val candidate = File(base, relativePath).canonicalFile
        if (candidate != baseCanonical && !candidate.path.startsWith(baseCanonical.path + File.separator)) {
            throw ApiException(Response.Status.BAD_REQUEST, "path escapes shared root")
        }
        return candidate
    }

    private fun requireParam(session: IHTTPSession, name: String): String =
        session.parms[name] ?: throw ApiException(Response.Status.BAD_REQUEST, "$name is required")

    private fun handleRoots(): Response {
        val array = JSONArray()
        for ((id, pair) in roots) {
            array.put(JSONObject().put("id", id).put("name", pair.first))
        }
        return jsonResponse(Response.Status.OK, JSONObject().put("roots", array))
    }

    private fun handleList(session: IHTTPSession): Response {
        val root = requireParam(session, "root")
        val path = session.parms["path"] ?: ""
        val target = resolvePath(root, path)
        if (!target.exists()) throw ApiException(Response.Status.NOT_FOUND, "not found")
        if (!target.isDirectory) throw ApiException(Response.Status.BAD_REQUEST, "not a directory")

        val entries = JSONArray()
        target.listFiles()
            ?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
            ?.forEach { f -> entries.put(entryJson(f)) }
        return jsonResponse(Response.Status.OK, JSONObject().put("root", root).put("path", path).put("entries", entries))
    }

    private fun handleStat(session: IHTTPSession): Response {
        val root = requireParam(session, "root")
        val path = requireParam(session, "path")
        val target = resolvePath(root, path)
        if (!target.exists()) throw ApiException(Response.Status.NOT_FOUND, "not found")
        return jsonResponse(Response.Status.OK, entryJson(target))
    }

    private fun handleDownload(session: IHTTPSession): Response {
        val root = requireParam(session, "root")
        val path = requireParam(session, "path")
        val target = resolvePath(root, path)
        if (!target.isFile) throw ApiException(Response.Status.NOT_FOUND, "not found")
        val response = newFixedLengthResponse(Response.Status.OK, mimeTypeFor(target.name), FileInputStream(target), target.length())
        val quotedName = target.name.replace("\\", "_").replace("\"", "_")
        response.addHeader("Content-Disposition", "attachment; filename=\"$quotedName\"")
        return response
    }

    private fun entryJson(f: File): JSONObject = JSONObject()
        .put("name", f.name)
        .put("is_dir", f.isDirectory)
        .put("size", f.length())
        .put("mtime", f.lastModified() / 1000)

    private fun mimeTypeFor(name: String): String {
        val extension = name.substringAfterLast('.', "")
        if (extension.isEmpty()) return "application/octet-stream"
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lowercase()) ?: "application/octet-stream"
    }

    private fun jsonResponse(status: Response.Status, body: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", body.toString())

    companion object {
        private const val TAG = "Pairbridge"
        const val PORT = 8766
    }
}
