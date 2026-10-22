package com.loadbalancer.app

import android.net.Network
import java.io.FileOutputStream
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import kotlin.random.Random

/** Userspace TCP/UDP relay: reads raw IPv4 packets from the TUN and forwards each flow over a chosen network. */
class Forwarder(private val tunOut: FileOutputStream, val pool: NetPool) {
    private val exec = Executors.newCachedThreadPool()
    private val tcpSessions = ConcurrentHashMap<Long, TcpSession>()
    private val udpFlows = ConcurrentHashMap<Long, UdpFlow>()

    fun write(packet: ByteArray) {
        try {
            synchronized(tunOut) { tunOut.write(packet) }
        } catch (_: IOException) {
        }
    }

    private fun account(n: Network, ip: Int, bytes: Int, down: Boolean) {
        if (down) pool.addDown(n, bytes) else pool.addUp(n, bytes)
        UsageStore.add(DomainMap.name(ip), bytes.toLong(), pool.isWifi(n))
    }

    fun shutdown() {
        tcpSessions.values.forEach { it.close(false) }
        udpFlows.values.forEach { it.close() }
        exec.shutdownNow()
    }

    fun onPacket(b: ByteArray, len: Int) {
        if (len < 20 || (b[0].toInt() shr 4) != 4) return
        val ihl = (b[0].toInt() and 0x0f) * 4
        val total = minOf(Packets.get16(b, 2), len)
        if (total < ihl + 8) return
        val fragment = Packets.get16(b, 6) and 0x3fff
        if (fragment != 0) return
        when (b[9].toInt()) {
            6 -> onTcp(b, ihl, total)
            17 -> onUdp(b, ihl, total)
        }
    }

    private fun key(srcPort: Int, dstIp: Int, dstPort: Int) =
        (srcPort.toLong() shl 48) or ((dstIp.toLong() and 0xffffffffL) shl 16) or dstPort.toLong()

    private fun onTcp(b: ByteArray, ihl: Int, total: Int) {
        if (total < ihl + 20) return
        val srcIp = Packets.get32(b, 12)
        val dstIp = Packets.get32(b, 16)
        val srcPort = Packets.get16(b, ihl)
        val dstPort = Packets.get16(b, ihl + 2)
        val flags = b[ihl + 13].toInt() and 0xff
        val k = key(srcPort, dstIp, dstPort)
        val existing = tcpSessions[k]
        if (existing != null) {
            if (flags and Packets.SYN == 0) existing.onSegment(b, ihl, total, flags)
            return
        }
        if (flags and Packets.SYN != 0 && flags and Packets.ACK == 0) {
            val session = TcpSession(srcIp, srcPort, dstIp, dstPort, k, Packets.get32(b, ihl + 4))
            tcpSessions[k] = session
            exec.execute { session.run() }
        } else if (flags and Packets.RST == 0) {
            val ack = Packets.get32(b, ihl + 8)
            write(Packets.tcp(dstIp, srcIp, dstPort, srcPort, ack, 0, Packets.RST, null, 0, 0, false))
        }
    }

    private fun onUdp(b: ByteArray, ihl: Int, total: Int) {
        val srcIp = Packets.get32(b, 12)
        val dstIp = Packets.get32(b, 16)
        val srcPort = Packets.get16(b, ihl)
        val dstPort = Packets.get16(b, ihl + 2)
        val pLen = total - ihl - 8
        if (pLen < 0) return
        val k = key(srcPort, dstIp, dstPort)
        var flow = udpFlows[k]
        if (flow == null) {
            val created = UdpFlow(srcIp, srcPort, dstIp, dstPort, k)
            if (!created.open()) return
            udpFlows[k] = created
            exec.execute { created.receiveLoop() }
            flow = created
        }
        flow.send(b, ihl + 8, pLen)
    }

    private inner class UdpFlow(
        val clientIp: Int, val clientPort: Int, val serverIp: Int, val serverPort: Int, val key: Long,
    ) {
        private var socket: DatagramSocket? = null
        private var net: Network? = null
        @Volatile private var closed = false

        fun open(): Boolean {
            for (n in pool.candidates()) {
                try {
                    val s = DatagramSocket()
                    n.bindSocket(s)
                    s.soTimeout = 30_000
                    socket = s
                    net = n
                    pool.acquire(n)
                    return true
                } catch (_: IOException) {
                }
            }
            return false
        }

        fun send(b: ByteArray, off: Int, len: Int) {
            val n = net ?: return
            try {
                val addr = InetSocketAddress(InetAddress.getByAddress(Packets.ipBytes(serverIp)), serverPort)
                socket?.send(DatagramPacket(b.copyOfRange(off, off + len), len, addr))
                account(n, serverIp, len, false)
            } catch (_: IOException) {
                close()
            }
        }

        fun receiveLoop() {
            val n = net ?: return
            val buf = ByteArray(2048)
            try {
                while (!closed) {
                    val p = DatagramPacket(buf, buf.size)
                    socket!!.receive(p)
                    account(n, serverIp, p.length, true)
                    if (serverPort == 53) DomainMap.learnFromDns(buf, p.length)
                    write(Packets.udp(serverIp, clientIp, serverPort, clientPort, buf, p.length))
                }
            } catch (_: SocketTimeoutException) {
            } catch (_: IOException) {
            } finally {
                close()
            }
        }

        fun close() {
            if (closed) return
            closed = true
            udpFlows.remove(key)
            runCatching { socket?.close() }
            net?.let { pool.release(it) }
        }
    }

    private inner class TcpSession(
        val clientIp: Int, val clientPort: Int, val serverIp: Int, val serverPort: Int, val key: Long, clientIsn: Int,
    ) {
        private val lock = Object()
        private val outQueue = LinkedBlockingQueue<ByteArray>()
        private val finMark = ByteArray(0)
        private val ourIsn = Random.nextInt()
        private var ourSeq = ourIsn
        private var acked = ourIsn
        @Volatile private var window = 65535
        @Volatile private var rcvNext = clientIsn + 1
        @Volatile private var established = false
        @Volatile private var closed = false
        private var finSent = false
        private var appFin = false
        private var outShutdown = false
        private var sniChecked = false
        private var socket: Socket? = null
        private var net: Network? = null

        fun run() {
            if (!connect()) {
                write(Packets.tcp(serverIp, clientIp, serverPort, clientPort, 0, rcvNext, Packets.RST or Packets.ACK, null, 0, 0, false))
                tcpSessions.remove(key)
                return
            }
            synchronized(lock) {
                write(Packets.tcp(serverIp, clientIp, serverPort, clientPort, ourIsn, rcvNext, Packets.SYN or Packets.ACK, null, 0, 0, true))
                ourSeq = ourIsn + 1
                acked = ourSeq
                established = true
            }
            exec.execute { writerLoop() }
            readerLoop()
        }

        private fun connect(): Boolean {
            val addr = InetSocketAddress(InetAddress.getByAddress(Packets.ipBytes(serverIp)), serverPort)
            for (n in pool.candidates()) {
                val s = Socket()
                try {
                    n.bindSocket(s)
                    s.tcpNoDelay = true
                    s.connect(addr, 8000)
                    socket = s
                    net = n
                    pool.acquire(n)
                    return true
                } catch (_: IOException) {
                    runCatching { s.close() }
                }
            }
            return false
        }

        fun onSegment(b: ByteArray, ihl: Int, total: Int, flags: Int) {
            if (flags and Packets.RST != 0) {
                close(false)
                return
            }
            if (!established) return
            val seq = Packets.get32(b, ihl + 4)
            synchronized(lock) {
                if (flags and Packets.ACK != 0) {
                    val ack = Packets.get32(b, ihl + 8)
                    if (ack - acked > 0 && ourSeq - ack >= 0) acked = ack
                    window = Packets.get16(b, ihl + 14)
                    lock.notifyAll()
                }
            }
            val dataOff = ihl + ((b[ihl + 12].toInt() and 0xf0) shr 2)
            val pLen = total - dataOff
            if (pLen > 0) {
                if (seq == rcvNext) {
                    rcvNext += pLen
                    if (!sniChecked) {
                        sniChecked = true
                        DomainMap.parseSni(b.copyOfRange(dataOff, total))?.let { DomainMap.learn(serverIp, it) }
                    }
                    outQueue.add(b.copyOfRange(dataOff, total))
                }
                sendAck()
            }
            if (flags and Packets.FIN != 0 && seq + maxOf(pLen, 0) == rcvNext) {
                synchronized(lock) { appFin = true }
                rcvNext += 1
                outQueue.add(finMark)
                sendAck()
            }
        }

        private fun sendAck() {
            val seq = synchronized(lock) { ourSeq }
            write(Packets.tcp(serverIp, clientIp, serverPort, clientPort, seq, rcvNext, Packets.ACK, null, 0, 0, false))
        }

        private fun readerLoop() {
            val n = net!!
            val buf = ByteArray(Packets.MSS * 8)
            try {
                val input = socket!!.getInputStream()
                while (!closed) {
                    val read = input.read(buf)
                    if (read < 0) break
                    account(n, serverIp, read, true)
                    var off = 0
                    while (off < read && !closed) {
                        val chunk = minOf(Packets.MSS, read - off)
                        synchronized(lock) {
                            while (!closed && ourSeq - acked > 0 && ourSeq - acked + chunk > minOf(window, 65535)) lock.wait(200)
                            if (closed) return
                            write(Packets.tcp(serverIp, clientIp, serverPort, clientPort, ourSeq, rcvNext, Packets.PSH or Packets.ACK, buf, off, chunk, false))
                            ourSeq += chunk
                        }
                        off += chunk
                    }
                }
                synchronized(lock) {
                    write(Packets.tcp(serverIp, clientIp, serverPort, clientPort, ourSeq, rcvNext, Packets.FIN or Packets.ACK, null, 0, 0, false))
                    ourSeq += 1
                    finSent = true
                }
                checkDone()
            } catch (_: IOException) {
                close(true)
            } catch (_: InterruptedException) {
                close(true)
            }
        }

        private fun writerLoop() {
            val n = net!!
            try {
                val output = socket!!.getOutputStream()
                while (true) {
                    val data = outQueue.take()
                    if (data === finMark) break
                    output.write(data)
                    account(n, serverIp, data.size, false)
                }
                if (!closed) runCatching { socket?.shutdownOutput() }
                synchronized(lock) { outShutdown = true }
                checkDone()
            } catch (_: IOException) {
                close(true)
            } catch (_: InterruptedException) {
            }
        }

        private fun checkDone() {
            val done = synchronized(lock) { finSent && appFin && outShutdown }
            if (done) close(false)
        }

        fun close(sendRst: Boolean) {
            synchronized(lock) {
                if (closed) return
                closed = true
                lock.notifyAll()
            }
            tcpSessions.remove(key)
            if (sendRst && established) {
                write(Packets.tcp(serverIp, clientIp, serverPort, clientPort, ourSeq, rcvNext, Packets.RST or Packets.ACK, null, 0, 0, false))
            }
            runCatching { socket?.close() }
            net?.let { pool.release(it) }
            outQueue.add(finMark)
        }
    }
}
