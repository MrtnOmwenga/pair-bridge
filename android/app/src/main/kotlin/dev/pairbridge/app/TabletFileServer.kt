package dev.pairbridge.app

import android.os.Environment
import android.util.Log
import android.webkit.MimeTypeMap
import fi.iki.elonen.NanoHTTPD
import java.io.File
import java.io.FileInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mirrors the laptop's FastAPI server (same endpoint shapes, same shared-root scoping and
 * path-escape protection) so the laptop can browse/download from the tablet's public folders
 * the same way the tablet browses the laptop's. Auth reuses the token issued during pairing —
 * the laptop already knows it, since it generated it.
 *
 * Known issue: on this HyperOS build, connections to the bound WLAN IP hang indefinitely at
 * the TCP level (works fine over loopback/USB) even with WLAN + background-data permissions
 * granted and battery restrictions disabled. Root cause not yet found — likely an
 * undocumented HyperOS restriction on inbound connections to a third-party app's listening
 * socket. Binding explicitly to "0.0.0.0" here (rather than the wildcard default) was one
 * hypothesis tried and ruled out (kernel dual-stack was already fine); left in as reasonable
 * hygiene, not a fix.
 */
class TabletFileServer(port: Int, private val token: String) : NanoHTTPD("0.0.0.0", port) {

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

        if (session.headers["authorization"] != "Bearer $token") {
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
        response.addHeader("Content-Disposition", "attachment; filename=\"${target.name}\"")
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
