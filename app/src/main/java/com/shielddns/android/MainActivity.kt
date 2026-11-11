package com.shielddns.android

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.shielddns.android.page.CustomRulesPage
import com.shielddns.android.page.LogPage
import com.shielddns.android.page.OverviewPage
import com.shielddns.android.page.SettingsPage
import com.shielddns.android.page.SubsPage

interface Page {
    val view: View
    fun refresh() {}
    fun onShow() {}
}

class MainActivity : AppCompatActivity() {

    private lateinit var content: FrameLayout
    private lateinit var statusPill: TextView
    private lateinit var tabs: MutableList<TextView>
    private lateinit var pages: MutableList<Page>
    private var index = 0
    private val ticker = Handler(Looper.getMainLooper())

    private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == RESULT_OK) {
            launchService()
        } else {
            (pages[0] as OverviewPage).syncToggle()
        }
    }

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        Stats.running = DnsVpnService.isRunning()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            fitsSystemWindows = true
        }
        root.addView(buildHeader())
        root.addView(buildTabs())

        content = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
        }
        root.addView(content)
        setContentView(root)

        pages = mutableListOf(
            OverviewPage(this),
            SubsPage(this),
            CustomRulesPage(this),
            LogPage(this),
            SettingsPage(this)
        )
        pages.forEach { if (it.view.parent == null) content.addView(it.view) }
        selectTab(0)

        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun buildHeader(): View {
        val row = Ui.row(this).apply {
            setPadding(Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 16), Ui.dp(this@MainActivity, 18), Ui.dp(this@MainActivity, 8))
        }
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = Ui.lpWeight(1f)
        }
        left.addView(Ui.text(this, "ShieldDNS", 22f, Ui.TEXT, true))
        left.addView(Ui.text(this, "本地 DNS 防护", 12f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(this@MainActivity, 4), 0, 0)
        })
        row.addView(left)

        statusPill = Ui.pill(this, "保护已暂停", Ui.MUTED)
        row.addView(statusPill)
        return row
    }

    private fun buildTabs(): View {
        val scroll = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
        }
        val bar = Ui.row(this).apply {
            setPadding(Ui.dp(this@MainActivity, 12), 0, Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 10))
        }
        tabs = mutableListOf()
        listOf("概览", "过滤订阅", "自定义规则", "查询日志", "设置").forEachIndexed { i, name ->
            val t = Ui.text(this, name, 14f, Ui.MUTED, false).apply {
                gravity = Gravity.CENTER
                setPadding(Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 8), Ui.dp(this@MainActivity, 12), Ui.dp(this@MainActivity, 8))
                setOnClickListener { selectTab(i) }
            }
            tabs.add(t)
            bar.addView(t)
        }
        scroll.addView(bar)
        return scroll
    }

    private fun selectTab(i: Int) {
        index = i
        tabs.forEachIndexed { idx, t ->
            if (idx == i) {
                t.setTextColor(Ui.TEXT)
                t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
                t.background = Ui.rounded(Ui.ACCENT_DIM, 10, Ui.ACCENT, 1, this)
            } else {
                t.setTextColor(Ui.MUTED)
                t.setTypeface(t.typeface, android.graphics.Typeface.NORMAL)
                t.background = null
            }
        }
        pages.forEachIndexed { idx, p -> p.view.visibility = if (idx == i) View.VISIBLE else View.GONE }
        pages[i].onShow()
        pages[i].refresh()
    }

    override fun onResume() {
        super.onResume()
        // 刚点过「关闭」的 3 秒内不要用 isRunning() 把开关拨回去；
        // 服务真正停下来后 isRunning() 自然为 false，不必抢这一拍。
        if (System.currentTimeMillis() - DnsVpnService.lastStopAt > 3000) {
            Stats.running = DnsVpnService.isRunning()
        }
        ticker.post(object : Runnable {
            override fun run() {
                updateHeader()
                if (index in pages.indices) pages[index].refresh()
                ticker.postDelayed(this, 1000)
            }
        })
    }

    override fun onPause() {
        super.onPause()
        ticker.removeCallbacksAndMessages(null)
    }

    private fun updateHeader() {
        when {
            Stats.running && Stats.vpnUp -> {
                statusPill.text = "防护运行中"
                statusPill.setTextColor(Ui.ACCENT)
                statusPill.background = Ui.rounded(Ui.adjustAlpha(Ui.ACCENT, 0.14f), 10, Ui.adjustAlpha(Ui.ACCENT, 0.45f), 1, this)
            }
            Stats.running -> {
                // 开关已开但 tun 还没建立：明确显示中间态，不要让人以为已经在防护
                statusPill.text = "正在建立 VPN…"
                statusPill.setTextColor(Ui.WARN)
                statusPill.background = Ui.rounded(Ui.adjustAlpha(Ui.WARN, 0.14f), 10, Ui.adjustAlpha(Ui.WARN, 0.45f), 1, this)
            }
            else -> {
                statusPill.text = "保护已暂停"
                statusPill.setTextColor(Ui.MUTED)
                statusPill.background = Ui.rounded(0x00000000, 10, 0xFF2B3D34.toInt(), 1, this)
            }
        }
    }

    /** App 里所有开关最终都走这里：开=申请 VPN 权限并起前台服务，关=停服务。 */
    fun setProtection(on: Boolean) {
        if (!on) {
            // 先让界面和后端状态立刻变成「已暂停」，再停服务，避免被 1 秒一次的刷新拨回去
            Stats.running = false
            DnsVpnService.stop(this)
            return
        }
        val prepare = VpnService.prepare(this)
        if (prepare != null) {
            vpnPermission.launch(prepare)
        } else {
            launchService()
        }
    }

    private fun launchService() {
        ContextCompat.startForegroundService(this, DnsVpnService.startIntent(this))
        // 1.8 秒后若还没真正跑起来，把失败原因直接告诉用户，而不是让开关默默弹回去
        ticker.postDelayed({
            if (!DnsVpnService.isRunning() && Stats.lastError.isNotEmpty()) {
                android.widget.Toast.makeText(this, Stats.lastError, android.widget.Toast.LENGTH_LONG).show()
            }
        }, 1800)
    }

    fun reloadService() {
        if (DnsVpnService.isRunning()) DnsVpnService.reload(this)
    }
}
