package com.loadbalancer.app

import java.util.concurrent.ConcurrentHashMap

/** Maps server IPs to site names learned from DNS answers and TLS SNI, so usage can be shown per site. */
object DomainMap {
    private const val MAX = 6000
    private val names = ConcurrentHashMap<Int, String>()
    private val secondLevel = setOf("co", "com", "org", "net", "ac", "gov", "edu", "ir")

    fun name(ip: Int): String = names[ip] ?: ipText(ip)

    fun learn(ip: Int, host: String) {
        if (names.size > MAX) names.clear()
        names[ip] = baseDomain(host)
    }

    fun ipText(ip: Int) = "${ip ushr 24}.${(ip shr 16) and 255}.${(ip shr 8) and 255}.${ip and 255}"

    fun baseDomain(host: String): String {
        val parts = host.trim('.').lowercase().split('.')
        if (parts.size <= 2) return parts.joinToString(".")
        val take = if (parts.last().length == 2 && parts[parts.size - 2] in secondLevel) 3 else 2
        return parts.takeLast(take).joinToString(".")
    }

    /** Parses a DNS response and records every A record under the queried name. */
    fun learnFromDns(b: ByteArray, len: Int) {
        try {
            if (len < 12 || (b[2].toInt() and 0x80) == 0) return
            val qd = Packets.get16(b, 4)
            val an = Packets.get16(b, 6)
            var pos = 12
            var qname = ""
            repeat(qd) {
                val (n, next) = readName(b, pos, len)
                if (qname.isEmpty()) qname = n
                pos = next + 4
            }
            if (qname.isEmpty()) return
            repeat(an) {
                val (_, next) = readName(b, pos, len)
                pos = next
                val type = Packets.get16(b, pos)
                val rdLen = Packets.get16(b, pos + 8)
                pos += 10
                if (type == 1 && rdLen == 4) learn(Packets.get32(b, pos), qname)
                pos += rdLen
            }
        } catch (_: IndexOutOfBoundsException) {
        }
    }

    private fun readName(b: ByteArray, start: Int, len: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var pos = start
        var end = -1
        var hops = 0
        while (pos < len && hops < 20) {
            val l = b[pos].toInt() and 0xff
            if (l == 0) {
                if (end < 0) end = pos + 1
                break
            }
            if (l and 0xc0 == 0xc0) {
                if (end < 0) end = pos + 2
                pos = ((l and 0x3f) shl 8) or (b[pos + 1].toInt() and 0xff)
                hops++
                continue
            }
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(b, pos + 1, l, Charsets.US_ASCII))
            pos += l + 1
        }
        return sb.toString() to (if (end < 0) pos else end)
    }

    /** Extracts the SNI host name from the first TLS ClientHello bytes, or null. */
    fun parseSni(d: ByteArray): String? {
        try {
            if (d.size < 50 || d[0].toInt() != 0x16 || d[5].toInt() != 0x01) return null
            var p = 43
            p += 1 + (d[p].toInt() and 0xff)
            p += 2 + Packets.get16(d, p)
            p += 1 + (d[p].toInt() and 0xff)
            val extEnd = p + 2 + Packets.get16(d, p)
            p += 2
            while (p + 4 <= extEnd && p + 4 <= d.size) {
                val type = Packets.get16(d, p)
                val l = Packets.get16(d, p + 2)
                if (type == 0) {
                    val nameLen = Packets.get16(d, p + 7)
                    return String(d, p + 9, nameLen, Charsets.US_ASCII)
                }
                p += 4 + l
            }
        } catch (_: IndexOutOfBoundsException) {
        }
        return null
    }
}
