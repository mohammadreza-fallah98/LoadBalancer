package com.loadbalancer.app

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/** Persistent per-day traffic totals (per network) and per-site byte counters. */
object UsageStore {
    private const val FILE = "usage.json"
    private const val KEEP_DAYS = 35L

    class Day(
        var wifi: Long = 0, var cell: Long = 0, val sites: MutableMap<String, Long> = HashMap(),
        val hourWifi: LongArray = LongArray(24), val hourCell: LongArray = LongArray(24),
    )

    class Summary(val wifi: Long, val cell: Long, val sites: List<Pair<String, Long>>)

    private val days = HashMap<String, Day>()
    private var file: File? = null
    private var keyStamp = 0L
    private var todayKey = ""

    @Synchronized
    fun init(context: Context) {
        if (file != null) return
        val f = File(context.filesDir, FILE)
        file = f
        if (!f.exists()) return
        runCatching {
            val root = JSONObject(f.readText())
            for (k in root.keys()) {
                val o = root.getJSONObject(k)
                val day = Day(o.optLong("w"), o.optLong("c"))
                o.optJSONArray("hw")?.let { a -> for (i in 0 until minOf(24, a.length())) day.hourWifi[i] = a.getLong(i) }
                o.optJSONArray("hc")?.let { a -> for (i in 0 until minOf(24, a.length())) day.hourCell[i] = a.getLong(i) }
                val s = o.optJSONObject("s")
                if (s != null) for (name in s.keys()) day.sites[name] = s.getLong(name)
                days[k] = day
            }
        }
    }

    private fun today(): String {
        val now = System.currentTimeMillis()
        if (now - keyStamp > 30_000 || todayKey.isEmpty()) {
            todayKey = LocalDate.now().toString()
            keyStamp = now
        }
        return todayKey
    }

    @Synchronized
    fun add(site: String, bytes: Long, isWifi: Boolean) {
        val day = days.getOrPut(today()) { Day() }
        val now = System.currentTimeMillis()
        val hour = (((now + java.util.TimeZone.getDefault().getOffset(now)) / 3_600_000L) % 24).toInt()
        if (isWifi) { day.wifi += bytes; day.hourWifi[hour] += bytes } else { day.cell += bytes; day.hourCell[hour] += bytes }
        day.sites[site] = (day.sites[site] ?: 0L) + bytes
    }

    @Synchronized
    fun summary(periodDays: Int): Summary {
        val start = LocalDate.now().minusDays(periodDays - 1L)
        var wifi = 0L
        var cell = 0L
        val sites = HashMap<String, Long>()
        for ((k, d) in days) {
            val date = runCatching { LocalDate.parse(k) }.getOrNull() ?: continue
            if (date.isBefore(start)) continue
            wifi += d.wifi
            cell += d.cell
            for ((s, v) in d.sites) sites[s] = (sites[s] ?: 0L) + v
        }
        return Summary(wifi, cell, sites.entries.sortedByDescending { it.value }.map { it.key to it.value })
    }

    /** Chart series for the period: 24 hourly buckets for one day, otherwise one bucket per day. */
    @Synchronized
    fun series(periodDays: Int): Triple<LongArray, LongArray, List<String>> {
        if (periodDays == 1) {
            val d = days[today()]
            return Triple(d?.hourWifi?.copyOf() ?: LongArray(24), d?.hourCell?.copyOf() ?: LongArray(24), listOf("00:00", "24:00"))
        }
        val now = LocalDate.now()
        val w = LongArray(periodDays)
        val c = LongArray(periodDays)
        for (i in 0 until periodDays) {
            val d = days[now.minusDays(periodDays - 1L - i).toString()] ?: continue
            w[i] = d.wifi
            c[i] = d.cell
        }
        val first = now.minusDays(periodDays - 1L)
        return Triple(w, c, listOf("${first.monthValue}/${first.dayOfMonth}", "${now.monthValue}/${now.dayOfMonth}"))
    }

    @Synchronized
    fun save() {
        val f = file ?: return
        val limit = LocalDate.now().minusDays(KEEP_DAYS)
        days.keys.removeAll { k -> runCatching { LocalDate.parse(k).isBefore(limit) }.getOrDefault(true) }
        val root = JSONObject()
        for ((k, d) in days) {
            val s = JSONObject()
            d.sites.entries.sortedByDescending { it.value }.take(300).forEach { s.put(it.key, it.value) }
            root.put(
                k,
                JSONObject().put("w", d.wifi).put("c", d.cell).put("s", s)
                    .put("hw", org.json.JSONArray(d.hourWifi.toList())).put("hc", org.json.JSONArray(d.hourCell.toList())),
            )
        }
        runCatching { f.writeText(root.toString()) }
    }
}
