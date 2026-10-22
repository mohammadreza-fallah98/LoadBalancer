package com.loadbalancer.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
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
    private val handler = Handler(Looper.getMainLooper())
    private var lastDown = longArrayOf(0, 0)

    private val notifier = object : Runnable {
        override fun run() {
            val now = longArrayOf(Stats.wifiDown.get(), Stats.cellDown.get())
            val text = getString(
                R.string.notification_text,
                Formatter.formatFileSize(this@BalancerVpnService, maxOf(0L, now[0] - lastDown[0])),
                Formatter.formatFileSize(this@BalancerVpnService, maxOf(0L, now[1] - lastDown[1])),
            )
            lastDown = now
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
            handler.postDelayed(this, 2000)
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BalancerVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(getString(R.string.status_running_short))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    private fun showForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW))
        val n = buildNotification(getString(R.string.notification_text, "0 B", "0 B"))
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
        lastDown = longArrayOf(Stats.wifiDown.get(), Stats.cellDown.get())
        handler.postDelayed(notifier, 2000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopBalancer()
            stopSelf()
            return START_NOT_STICKY
        }
        if (tun == null) {
            showForeground()
            startBalancer()
        }
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
        handler.removeCallbacks(notifier)
        stopForeground(STOP_FOREGROUND_REMOVE)
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
        private const val CHANNEL_ID = "balancer"
        private const val NOTIFICATION_ID = 1
    }
}
