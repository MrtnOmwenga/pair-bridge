package dev.pairbridge.app

import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

class NotPairedException : IOException("not paired with a laptop")

/**
 * Process-wide access to the paired laptop. Holds one [LaptopClient] so requests share pooled
 * connections, and follows the laptop to a new LAN address (DHCP) by finding it over mDNS.
 */
class LaptopConnection private constructor(context: Context) {

    // Opening the Keystore-backed credential store is slow after the process has been idle
    // (it once accounted for most of a 3-second queryDocument), so it's built once.
    val credentials = CredentialStore(context)

    private val discovery = LaptopDiscovery(context)
    private var client: LaptopClient? = null
    private var clientKey: Triple<String, Int, String>? = null
    private var lastRelocation = 0L

    @Synchronized
    fun client(): LaptopClient {
        val host = credentials.host ?: throw NotPairedException()
        val token = credentials.token ?: throw NotPairedException()
        val key = Triple(host, credentials.port, token)
        if (key != clientKey) {
            client = LaptopClient(host, credentials.port, token)
            clientKey = key
        }
        return client!!
    }

    /**
     * Runs [block]; if the laptop couldn't be reached, looks for it on the network and retries once
     * at its new address. Only for requests that are safe to send twice: a connect failure means
     * nothing was sent, but a one-shot upload body can't be replayed anyway.
     */
    fun <T> call(block: (LaptopClient) -> T): T =
        try {
            block(client())
        } catch (e: IOException) {
            if (!isUnreachable(e) || !relocate()) throw e
            block(client())
        }

    // OkHttp reports a connect timeout as SocketTimeoutException("failed to connect to ..."),
    // the same type as a read timeout, which must not trigger a relocation.
    private fun isUnreachable(e: IOException) =
        e is ConnectException || e is NoRouteToHostException ||
            (e is SocketTimeoutException && e.message.orEmpty().contains("connect"))

    @Synchronized
    private fun relocate(): Boolean {
        val serverId = credentials.serverId ?: return false
        val now = SystemClock.elapsedRealtime()
        if (now - lastRelocation < RELOCATION_INTERVAL_MS) return false
        lastRelocation = now

        val (host, port) = discovery.find(serverId) ?: return false
        if (host == credentials.host && port == credentials.port) return false
        Log.i(TAG, "laptop found at a new address: $host:$port")
        credentials.host = host
        credentials.port = port
        return true
    }

    companion object {
        private const val TAG = "Pairbridge"
        private const val RELOCATION_INTERVAL_MS = 30_000L

        @Volatile
        private var instance: LaptopConnection? = null

        fun get(context: Context): LaptopConnection =
            instance ?: synchronized(this) {
                instance ?: LaptopConnection(context.applicationContext).also { instance = it }
            }
    }
}
