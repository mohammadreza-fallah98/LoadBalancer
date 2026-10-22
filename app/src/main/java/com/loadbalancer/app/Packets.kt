package com.loadbalancer.app

import java.util.concurrent.atomic.AtomicInteger

object Packets {
    const val MSS = 1400
    const val FIN = 0x01
    const val SYN = 0x02
    const val RST = 0x04
    const val PSH = 0x08
    const val ACK = 0x10

    private val ipId = AtomicInteger(1)

    fun get16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xff) shl 8) or (b[o + 1].toInt() and 0xff)

    fun get32(b: ByteArray, o: Int) =
        ((b[o].toInt() and 0xff) shl 24) or ((b[o + 1].toInt() and 0xff) shl 16) or
            ((b[o + 2].toInt() and 0xff) shl 8) or (b[o + 3].toInt() and 0xff)

    fun put16(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 8).toByte()
        b[o + 1] = v.toByte()
    }

    fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = (v ushr 24).toByte()
        b[o + 1] = (v ushr 16).toByte()
        b[o + 2] = (v ushr 8).toByte()
        b[o + 3] = v.toByte()
    }

    fun ipBytes(ip: Int) = byteArrayOf((ip ushr 24).toByte(), (ip ushr 16).toByte(), (ip ushr 8).toByte(), ip.toByte())

    fun checksum(b: ByteArray, off: Int, len: Int, initial: Long): Int {
        var sum = initial
        var i = off
        val end = off + len
        while (i + 1 < end) {
            sum += get16(b, i)
            i += 2
        }
        if (i < end) sum += (b[i].toInt() and 0xff) shl 8
        while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
        return sum.inv().toInt() and 0xffff
    }

    private fun pseudoSum(src: Int, dst: Int, proto: Int, len: Int): Long =
        ((src ushr 16) + (src and 0xffff) + (dst ushr 16) + (dst and 0xffff) + proto + len).toLong()

    private fun ipHeader(b: ByteArray, total: Int, proto: Int, src: Int, dst: Int) {
        b[0] = 0x45
        put16(b, 2, total)
        put16(b, 4, ipId.getAndIncrement() and 0xffff)
        put16(b, 6, 0x4000)
        b[8] = 64
        b[9] = proto.toByte()
        put32(b, 12, src)
        put32(b, 16, dst)
        put16(b, 10, checksum(b, 0, 20, 0))
    }

    fun tcp(
        src: Int, dst: Int, srcPort: Int, dstPort: Int, seq: Int, ack: Int, flags: Int,
        payload: ByteArray?, pOff: Int, pLen: Int, mssOption: Boolean,
    ): ByteArray {
        val hdr = if (mssOption) 24 else 20
        val total = 20 + hdr + pLen
        val b = ByteArray(total)
        ipHeader(b, total, 6, src, dst)
        val t = 20
        put16(b, t, srcPort)
        put16(b, t + 2, dstPort)
        put32(b, t + 4, seq)
        put32(b, t + 8, ack)
        b[t + 12] = ((hdr / 4) shl 4).toByte()
        b[t + 13] = flags.toByte()
        put16(b, t + 14, 65535)
        if (mssOption) {
            b[t + 20] = 2
            b[t + 21] = 4
            put16(b, t + 22, MSS)
        }
        if (payload != null && pLen > 0) System.arraycopy(payload, pOff, b, t + hdr, pLen)
        put16(b, t + 16, checksum(b, t, hdr + pLen, pseudoSum(src, dst, 6, hdr + pLen)))
        return b
    }

    fun udp(src: Int, dst: Int, srcPort: Int, dstPort: Int, payload: ByteArray, pLen: Int): ByteArray {
        val total = 28 + pLen
        val b = ByteArray(total)
        ipHeader(b, total, 17, src, dst)
        put16(b, 20, srcPort)
        put16(b, 22, dstPort)
        put16(b, 24, 8 + pLen)
        System.arraycopy(payload, 0, b, 28, pLen)
        var c = checksum(b, 20, 8 + pLen, pseudoSum(src, dst, 17, 8 + pLen))
        if (c == 0) c = 0xffff
        put16(b, 26, c)
        return b
    }
}
