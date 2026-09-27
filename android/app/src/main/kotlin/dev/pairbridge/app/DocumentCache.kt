package dev.pairbridge.app

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * On-disk cache of laptop files, keyed by (rootId, path, size, mtime). Because the key encodes
 * the version, a hit is known to be fresh without asking the laptop. The trade-off: a file that
 * changed on the laptop is only noticed when its folder is listed again, never when it's
 * opened, so opening a cached file never waits on the network.
 */
class DocumentCache(private val dir: File) {

    init {
        dir.mkdirs()
        dir.listFiles { f -> f.name.startsWith(TEMP_PREFIX) }?.forEach { it.delete() }
    }

    fun find(rootId: String, path: String, entry: FileEntry): File? =
        fileFor(rootId, path, entry).takeIf { it.exists() }

    /** Stores [input] atomically, so a failed download never leaves a partial file behind a valid key. */
    fun store(rootId: String, path: String, entry: FileEntry, input: InputStream): File {
        val target = fileFor(rootId, path, entry)
        val tmp = File.createTempFile(TEMP_PREFIX, null, dir)
        try {
            tmp.outputStream().use { input.copyTo(it) }
            if (!tmp.renameTo(target)) throw IOException("could not move ${tmp.name} into the cache")
        } finally {
            tmp.delete()
        }
        evict(rootId, path, keep = target)
        return target
    }

    fun evict(rootId: String, path: String, keep: File? = null) {
        val prefix = "${key(rootId, path)}_"
        dir.listFiles { f -> f.name.startsWith(prefix) && f != keep }?.forEach { it.delete() }
    }

    private fun fileFor(rootId: String, path: String, entry: FileEntry) =
        File(dir, "${key(rootId, path)}_${entry.size}_${entry.mtime}")

    private fun key(rootId: String, path: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest("$rootId::$path".toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(20)
    }

    private companion object {
        const val TEMP_PREFIX = "tmp-"
    }
}
