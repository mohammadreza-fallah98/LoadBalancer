package com.loadbalancer.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.util.concurrent.atomic.AtomicInteger

/** Keeps the Wi-Fi and cellular networks alive and picks the less busy one for every new flow. */
class NetPool(context: Context) {
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Volatile private var wifi: Network? = null
    @Volatile private var cell: Network? = null
    private val tieBreaker = AtomicInteger()

    private val wifiCallback = callback(
        onUp = { wifi = it; Stats.wifiAvailable = true },
        onDown = { n -> if (wifi == n) { wifi = null; Stats.wifiAvailable = false } },
    )
    private val cellCallback = callback(
        onUp = { cell = it; Stats.cellAvailable = true },
        onDown = { n -> if (cell == n) { cell = null; Stats.cellAvailable = false } },
    )

    private fun callback(onUp: (Network) -> Unit, onDown: (Network) -> Unit) =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onUp(network)
            override fun onLost(network: Network) = onDown(network)
        }

    private fun request(transport: Int) = NetworkRequest.Builder()
        .addTransportType(transport)
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

    fun start() {
        cm.requestNetwork(request(NetworkCapabilities.TRANSPORT_WIFI), wifiCallback)
        cm.requestNetwork(request(NetworkCapabilities.TRANSPORT_CELLULAR), cellCallback)
    }

    fun stop() {
        runCatching { cm.unregisterNetworkCallback(wifiCallback) }
        runCatching { cm.unregisterNetworkCallback(cellCallback) }
        wifi = null; cell = null
        Stats.wifiAvailable = false; Stats.cellAvailable = false
    }

    fun isWifi(n: Network) = n == wifi

    /** Candidates ordered by current load; the first one is tried first and the rest are fallbacks. */
    fun candidates(): List<Network> {
        val w = wifi
        val c = cell
        if (w == null && c == null) return emptyList()
        if (w == null) return listOf(c!!)
        if (c == null) return listOf(w)
        val wl = Stats.wifiConns.get()
        val cl = Stats.cellConns.get()
        val wifiFirst = if (wl != cl) wl < cl else tieBreaker.getAndIncrement() % 2 == 0
        return if (wifiFirst) listOf(w, c) else listOf(c, w)
    }

    fun acquire(n: Network) {
        if (isWifi(n)) Stats.wifiConns.incrementAndGet() else Stats.cellConns.incrementAndGet()
    }

    fun release(n: Network) {
        if (isWifi(n)) Stats.wifiConns.decrementAndGet() else Stats.cellConns.decrementAndGet()
    }

    fun addDown(n: Network, bytes: Int) {
        (if (isWifi(n)) Stats.wifiDown else Stats.cellDown).addAndGet(bytes.toLong())
    }

    fun addUp(n: Network, bytes: Int) {
        (if (isWifi(n)) Stats.wifiUp else Stats.cellUp).addAndGet(bytes.toLong())
    }
}
