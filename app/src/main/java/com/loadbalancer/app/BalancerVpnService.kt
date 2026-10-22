package com.loadbalancer.app

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

class BalancerVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var pool: NetPool? = null
    private var forwarder: Forwarder? = null
    private var worker: Thread? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopBalancer()
            stopSelf()
            return START_NOT_STICKY
        }
        if (tun == null) startBalancer()
        return START_STICKY
    }

    private fun startBalancer() {
        UsageStore.init(this)
        val netPool = NetPool(this).also { it.start() }
        val fd = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(1500)
            .addAddress("10.8.0.2", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
            .addDnsServer("8.8.8.8")
            .addDisallowedApplication(packageName)
            .establish()
        if (fd == null) {
            netPool.stop()
            stopSelf()
            return
        }
        tun = fd
        pool = netPool
        Stats.reset()
        Stats.running = true
        val fwd = Forwarder(FileOutputStream(fd.fileDescriptor), netPool)
        forwarder = fwd
        worker = Thread {
            val input = FileInputStream(fd.fileDescriptor)
            val buf = ByteArray(32768)
            var lastSave = System.currentTimeMillis()
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) fwd.onPacket(buf, n)
                    if (System.currentTimeMillis() - lastSave > 30_000) {
                        UsageStore.save()
                        lastSave = System.currentTimeMillis()
                    }
                }
            } catch (_: IOException) {
            }
        }.also { it.start() }
    }

    private fun stopBalancer() {
        Stats.running = false
        UsageStore.save()
        worker?.interrupt()
        forwarder?.shutdown()
        pool?.stop()
        runCatching { tun?.close() }
        tun = null
        pool = null
        forwarder = null
        worker = null
    }

    override fun onRevoke() {
        stopBalancer()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopBalancer()
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.loadbalancer.app.STOP"
    }
}
