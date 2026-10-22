package com.loadbalancer.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.VpnService
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.Formatter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var last = LongArray(4)
    private val density by lazy { resources.displayMetrics.density }

    private lateinit var status: TextView
    private lateinit var totalValue: TextView
    private lateinit var power: TextView
    private lateinit var wifiCard: NetCard
    private lateinit var cellCard: NetCard
    private lateinit var usageTotal: TextView
    private lateinit var sitesBox: LinearLayout
    private val chips = ArrayList<TextView>()
    private var period = 0
    private var visibleSites = 15
    private lateinit var chart: UsageChart
    private lateinit var moreBtn: TextView
    private val periodDays = intArrayOf(1, 7, 30)

    private class NetCard(
        val root: LinearLayout, val state: TextView, val down: TextView, val up: TextView,
        val conns: TextView, val bar: View,
    )

    private fun dp(v: Int) = (v * density).toInt()

    private fun round(color: Int, radius: Int, stroke: Int = 0, strokeColor: Int = 0) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radius).toFloat()
        if (stroke > 0) setStroke(dp(stroke), strokeColor)
    }

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG_TOP
        window.navigationBarColor = BG_BOTTOM

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(48), dp(20), dp(24))
        }
        content.addView(label(getString(R.string.app_name), 26f, Color.WHITE, true))

        power = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            setOnClickListener { toggle() }
        }
        content.addView(power, LinearLayout.LayoutParams(dp(170), dp(170)).apply { setMargins(0, dp(32), 0, dp(20)) })

        status = label("", 14f, TEXT_DIM).apply { gravity = Gravity.CENTER }
        content.addView(status)

        content.addView(label(getString(R.string.total_title), 13f, TEXT_DIM).apply { setPadding(0, dp(24), 0, 0) })
        totalValue = label("0 B/s", 34f, Color.WHITE, true)
        content.addView(totalValue)

        wifiCard = card(getString(R.string.wifi), CYAN)
        cellCard = card(getString(R.string.mobile), ORANGE)
        content.addView(wifiCard.root, cardParams())
        content.addView(cellCard.root, cardParams())

        content.addView(label(getString(R.string.usage_title), 18f, Color.WHITE, true).apply { setPadding(0, dp(26), 0, dp(8)) })
        val chipRow = LinearLayout(this)
        intArrayOf(R.string.today, R.string.week, R.string.month).forEachIndexed { i, res ->
            val chip = label(getString(res), 14f, Color.WHITE, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(8), dp(16), dp(8))
                setOnClickListener { period = i; visibleSites = 15; renderUsage() }
            }
            chips.add(chip)
            chipRow.addView(chip, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { setMargins(dp(3), 0, dp(3), 0) })
        }
        content.addView(chipRow)
        chart = UsageChart(this)
        content.addView(chart, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(170)).apply { topMargin = dp(12) })
        usageTotal = label("", 13f, TEXT_DIM).apply { setPadding(0, dp(12), 0, dp(4)) }
        content.addView(usageTotal)
        content.addView(label(getString(R.string.sites_title), 14f, Color.WHITE, true).apply { setPadding(0, dp(8), 0, dp(6)) })
        sitesBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(CARD, 20, 1, 0x22FFFFFF)
            setPadding(dp(16), dp(10), dp(16), dp(10))
        }
        content.addView(sitesBox, cardParams().apply { topMargin = 0 })
        moreBtn = label(getString(R.string.details), 14f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            background = round(ACCENT, 18)
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setOnClickListener { visibleSites += 10; renderUsage() }
        }
        content.addView(moreBtn, cardParams())

        content.addView(label(getString(R.string.hint), 12f, TEXT_DIM).apply {
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(16), dp(8), 0)
        })

        UsageStore.init(this)
        setContentView(ScrollView(this).apply {
            isFillViewport = true
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG_TOP, BG_BOTTOM))
            addView(content, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        })
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { setMargins(0, dp(14), 0, 0) }

    private fun card(title: String, accent: Int): NetCard {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = round(CARD, 20, 1, 0x22FFFFFF)
            setPadding(dp(18), dp(16), dp(18), dp(16))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(View(this).apply { background = round(accent, 6) }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) })
        header.addView(label(title, 17f, Color.WHITE, true), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val state = label("", 12f, TEXT_DIM)
        header.addView(state)
        root.addView(header)

        val speeds = LinearLayout(this).apply { setPadding(0, dp(12), 0, dp(8)) }
        val down = label("", 18f, accent, true)
        val up = label("", 18f, Color.WHITE, true)
        speeds.addView(down, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        speeds.addView(up, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(speeds)

        val track = FrameLayout(this).apply { background = round(0x22FFFFFF, 4) }
        val bar = View(this).apply { background = round(accent, 4) }
        track.addView(bar, FrameLayout.LayoutParams(0, dp(6)))
        root.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)))

        val conns = label("", 12f, TEXT_DIM).apply { setPadding(0, dp(8), 0, 0) }
        root.addView(conns)
        return NetCard(root, state, down, up, conns, bar)
    }

    override fun onResume() {
        super.onResume()
        tick.run()
    }

    override fun onPause() {
        handler.removeCallbacks(tick)
        super.onPause()
    }

    private fun toggle() {
        if (Stats.running) {
            startService(Intent(this, BalancerVpnService::class.java).setAction(BalancerVpnService.ACTION_STOP))
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) startActivityForResult(prepare, REQUEST_VPN) else startVpn()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VPN && resultCode == RESULT_OK) startVpn()
    }

    private fun startVpn() {
        startService(Intent(this, BalancerVpnService::class.java))
    }

    private fun renderUsage() {
        val sum = UsageStore.summary(periodDays[period])
        chips.forEachIndexed { i, c ->
            c.background = round(if (i == period) ACCENT else CARD, 18)
        }
        usageTotal.text = getString(
            R.string.usage_total,
            Formatter.formatFileSize(this, sum.wifi + sum.cell),
            Formatter.formatFileSize(this, sum.wifi),
            Formatter.formatFileSize(this, sum.cell),
        )
        val series = UsageStore.series(periodDays[period])
        chart.setData(series.first, series.second, series.third, getString(R.string.wifi), getString(R.string.mobile))
        sitesBox.removeAllViews()
        moreBtn.visibility = if (sum.sites.size > visibleSites) View.VISIBLE else View.GONE
        if (sum.sites.isEmpty()) {
            sitesBox.addView(label(getString(R.string.no_usage), 13f, TEXT_DIM).apply { setPadding(0, dp(6), 0, dp(6)) })
            return
        }
        val max = sum.sites.first().second.toFloat().coerceAtLeast(1f)
        for ((name, bytes) in sum.sites.take(visibleSites)) {
            val row = LinearLayout(this).apply { setPadding(0, dp(6), 0, dp(6)); orientation = LinearLayout.VERTICAL }
            val line = LinearLayout(this)
            line.addView(label(name, 14f, Color.WHITE), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            line.addView(label(Formatter.formatFileSize(this, bytes), 14f, CYAN, true))
            row.addView(line)
            val track = FrameLayout(this).apply { background = round(0x22FFFFFF, 3) }
            val fill = View(this).apply { background = round(ACCENT, 3) }
            track.addView(fill, FrameLayout.LayoutParams(0, dp(4)))
            track.post { fill.layoutParams = fill.layoutParams.apply { width = (track.width * bytes / max).toInt() } }
            row.addView(track, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(4) })
            sitesBox.addView(row)
        }
    }

    private fun size(bytes: Long) = Formatter.formatFileSize(this, maxOf(0L, bytes)) + "/s"

    private fun update(card: NetCard, available: Boolean, down: Long, up: Long, conns: Int, share: Float) {
        card.state.text = if (available) "●" else getString(R.string.network_missing)
        card.state.setTextColor(if (available) GREEN else TEXT_DIM)
        card.down.text = "↓ " + size(down)
        card.up.text = "↑ " + size(up)
        card.conns.text = getString(R.string.connections, conns)
        val track = card.bar.parent as View
        card.bar.layoutParams = (card.bar.layoutParams as FrameLayout.LayoutParams).apply {
            width = (track.width * share).toInt()
        }
        card.root.alpha = if (available) 1f else 0.5f
    }

    private val tick = object : Runnable {
        override fun run() {
            val now = longArrayOf(Stats.wifiDown.get(), Stats.wifiUp.get(), Stats.cellDown.get(), Stats.cellUp.get())
            val wd = now[0] - last[0]
            val cd = now[2] - last[2]
            val sum = (wd + cd).coerceAtLeast(1L)
            update(wifiCard, Stats.wifiAvailable, wd, now[1] - last[1], Stats.wifiConns.get(), wd.toFloat() / sum)
            update(cellCard, Stats.cellAvailable, cd, now[3] - last[3], Stats.cellConns.get(), cd.toFloat() / sum)
            totalValue.text = size(wd + cd)
            val running = Stats.running
            status.setText(if (running) R.string.status_running else R.string.status_stopped)
            power.setText(if (running) R.string.stop else R.string.start)
            power.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (running) GREEN else ACCENT)
                setStroke(dp(8), if (running) 0x5534D399 else 0x554C6FFF)
            }
            renderUsage()
            last = now
            handler.postDelayed(this, 1000)
        }
    }

    companion object {
        private const val REQUEST_VPN = 1
        private val BG_TOP = Color.parseColor("#141C4F")
        private val BG_BOTTOM = Color.parseColor("#070B22")
        private val CARD = Color.parseColor("#1A2260")
        private val TEXT_DIM = Color.parseColor("#9AA5D6")
        private val CYAN = Color.parseColor("#2EE6D6")
        private val ORANGE = Color.parseColor("#FFB02E")
        private val GREEN = Color.parseColor("#22C55E")
        private val ACCENT = Color.parseColor("#4C6FFF")
    }
}
