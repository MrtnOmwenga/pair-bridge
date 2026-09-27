package dev.pairbridge.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Finds a paired laptop on the LAN by the server id it advertises over mDNS. */
class LaptopDiscovery(context: Context) {

    private val nsd = context.getSystemService(NsdManager::class.java)

    /** Blocks for up to [timeoutMs]; returns the laptop's current host and port, or null. */
    @Synchronized
    fun find(serverId: String, timeoutMs: Long = 3_000): Pair<String, Int>? {
        val found = LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(info: NsdServiceInfo) {
                found.add(info)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.w(TAG, "mDNS discovery failed to start: $errorCode")
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(info: NsdServiceInfo) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        }

        nsd.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
        try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
            while (true) {
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remainingMs <= 0) return null
                val candidate = found.poll(remainingMs, TimeUnit.MILLISECONDS) ?: return null
                val resolved = resolve(candidate, remainingMs) ?: continue
                if (resolved.attributes["id"]?.decodeToString() == serverId) {
                    @Suppress("DEPRECATION")
                    val host = resolved.host?.hostAddress ?: continue
                    return host to resolved.port
                }
            }
        } finally {
            runCatching { nsd.stopServiceDiscovery(listener) }
        }
    }

    // resolveService() is deprecated from API 34, but its replacement (registerServiceInfoCallback)
    // doesn't exist below 34. Before 34 only one resolve may run at a time, hence the sequential loop.
    @Suppress("DEPRECATION")
    private fun resolve(info: NsdServiceInfo, timeoutMs: Long): NsdServiceInfo? {
        val result = CompletableFuture<NsdServiceInfo?>()
        nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolved: NsdServiceInfo) {
                result.complete(resolved)
            }

            override fun onResolveFailed(failed: NsdServiceInfo, errorCode: Int) {
                result.complete(null)
            }
        })
        return try {
            result.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            null
        }
    }

    private companion object {
        const val TAG = "Pairbridge"
        const val SERVICE_TYPE = "_pairbridge._tcp"
    }
}
