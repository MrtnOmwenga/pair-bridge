package dev.pairbridge.app

import java.io.File
import java.security.MessageDigest

/**
 * On-disk cache of downloaded file bytes, keyed by (rootId, path, size, mtime). Because the
 * key encodes the exact version, a hit is always known-fresh with zero network calls —
 * staleness is only ever checked when a folder is browsed again (which refreshes the known
 * size/mtime), not at the moment a file is opened. A file that changed on the laptop between
 * browses will serve stale content until the next browse of that folder; that's the deliberate
 * trade for never blocking on the network at tap time.
 */
class PreloadCache(private val dir: File) {

    init {
        dir.mkdirs()
    }

    fun fileFor(rootId: String, path: String, size: Long, mtime: Long): File =
        File(dir, "${key(rootId, path)}_${size}_$mtime")

    fun find(rootId: String, path: String, size: Long, mtime: Long): File? {
        val file = fileFor(rootId, path, size, mtime)
        return if (file.exists()) file else null
    }

    /** Removes older cached versions of the same document once a fresh one has landed. */
    fun purgeOtherVersions(rootId: String, path: String, keep: File) {
        val prefix = "${key(rootId, path)}_"
        dir.listFiles { f -> f.name.startsWith(prefix) && f.name != keep.name }?.forEach { it.delete() }
    }

    private fun key(rootId: String, path: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$rootId::$path".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(20)
    }
}
