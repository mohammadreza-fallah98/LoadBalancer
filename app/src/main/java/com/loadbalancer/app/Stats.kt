package com.loadbalancer.app

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

object Stats {
    @Volatile var running = false
    val wifiDown = AtomicLong()
    val wifiUp = AtomicLong()
    val cellDown = AtomicLong()
    val cellUp = AtomicLong()
    val wifiConns = AtomicInteger()
    val cellConns = AtomicInteger()
    @Volatile var wifiAvailable = false
    @Volatile var cellAvailable = false

    fun reset() {
        wifiDown.set(0); wifiUp.set(0); cellDown.set(0); cellUp.set(0)
        wifiConns.set(0); cellConns.set(0)
    }
}
