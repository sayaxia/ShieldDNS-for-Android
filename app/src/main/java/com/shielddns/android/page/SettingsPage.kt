package com.shielddns.android.page

import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import com.shielddns.android.DnsVpnService
import com.shielddns.android.Diagnostics
import com.shielddns.android.MainActivity
import com.shielddns.android.Page
import com.shielddns.android.Prefs
import com.shielddns.android.Stats
import com.shielddns.android.Ui
import com.shielddns.android.UpdateChecker

/** 设置：上游、拦截响应、缓存、自动更新、关于。 */
class SettingsPage(private val a: MainActivity) : Page {

    companion object {
    }

    override val view: View
    private val customInput: EditText
    private val stateText: TextView
    private val updateStatus: TextView
    private val updateBtn: TextView
    private var pending: UpdateChecker.Latest? = null

    init {
        val scroll = ScrollView(a)
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(a, 14)
            setPadding(p, p, p, Ui.dp(a, 24))
        }
        scroll.addView(column)

        // ---- 上游 DNS ----
        column.addView(Ui.sectionTitle(a, "上游 DNS"))
        val upCard = Ui.card(a).apply { setPadding(Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14)) }
        val group = RadioGroup(a).apply { orientation = RadioGroup.VERTICAL }
        val sysRb = radio("跟随系统")
        val cusRb = radio("自定义上游")
        group.addView(sysRb)
        group.addView(cusRb)
        upCard.addView(group)

        customInput = EditText(a).apply {
            setText(Prefs.customUpstream)
            hint = "1.1.1.1, 8.8.8.8"
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 13f
            setTextColor(Ui.TEXT)
            setHintTextColor(0xFF5F7A6D.toInt())
            background = Ui.rounded(0xFF0C1411.toInt(), 10, 0xFF22322B.toInt(), 1, a)
            val p = Ui.dp(a, 10)
            setPadding(p, p, p, p)
            isEnabled = Prefs.upstreamMode == "custom"
        }
        upCard.addView(customInput, Ui.lpMatch().apply {
            topMargin = Ui.dp(a, 8)
        })

        if (Prefs.upstreamMode == "custom") cusRb.isChecked = true else sysRb.isChecked = true
        group.setOnCheckedChangeListener { _, id ->
            val custom = id == cusRb.id
            customInput.isEnabled = custom
            Prefs.upstreamMode = if (custom) "custom" else "system"
        }
        customInput.doAfterTextChanged { Prefs.customUpstream = it?.toString()?.trim() ?: "" }
        column.addView(upCard, Ui.lpMatch().apply { bottomMargin = Ui.dp(a, 14) })

        // ---- 拦截响应 ----
        column.addView(Ui.sectionTitle(a, "拦截响应"))
        val respCard = Ui.card(a).apply { setPadding(Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14)) }
        val respGroup = RadioGroup(a).apply { orientation = RadioGroup.VERTICAL }
        val nx = radio("NXDOMAIN（推荐，域名不存在）")
        val zero = radio("0.0.0.0 / ::（返回空地址）")
        respGroup.addView(nx)
        respGroup.addView(zero)
        if (Prefs.blockMode == "zero") zero.isChecked = true else nx.isChecked = true
        respGroup.setOnCheckedChangeListener { _, id ->
            Prefs.blockMode = if (id == zero.id) "zero" else "nxdomain"
        }
        respCard.addView(respGroup)
        column.addView(respCard, Ui.lpMatch().apply { bottomMargin = Ui.dp(a, 14) })

        // ---- SNI 增强拦截 ----
        column.addView(Ui.sectionTitle(a, "SNI 增强拦截"))
        val sniCard = Ui.card(a).apply { setPadding(Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14)) }
        sniCard.addView(switchRow(
            "启用增强拦截",
            "",
            Prefs.enhanceEnabled
        ) { on ->
            Prefs.enhanceEnabled = on
            if (Stats.running) {
                android.widget.Toast.makeText(
                    a, "已保存，重新开关防护后生效", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        })
        sniCard.addView(Ui.spacer(a, 12))
        sniCard.addView(switchRow(
            "命中广告域名时阻断",
            "",
            Prefs.sniBlockEnabled
        ) { on ->
            Prefs.sniBlockEnabled = on
            if (Stats.running) {
                android.widget.Toast.makeText(
                    a, "已保存，重新开关防护后生效", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        })
        sniCard.addView(Ui.spacer(a, 12))
        sniCard.addView(switchRow(
            "连带拦直播 CDN",
            "",
            Prefs.blockLiveCdn
        ) { on ->
            Prefs.blockLiveCdn = on
            if (Stats.running) {
                android.widget.Toast.makeText(
                    a, "已保存，重新开关防护后生效", android.widget.Toast.LENGTH_LONG
                ).show()
            }
        })
        sniCard.addView(Ui.spacer(a, 12))
        val pkgRow = Ui.row(a)
        pkgRow.addView(Ui.text(a, "应用包名", 12.5f, Ui.TEXT), Ui.lpWeight(1f))
        val pkgEdit = EditText(a).apply {
            setText(Prefs.enhancePackage)
            hint = "com.phoenix.read"
            textSize = 12f
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine()
        }
        pkgEdit.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) Prefs.enhancePackage = pkgEdit.text.toString()
        }
        pkgRow.addView(pkgEdit, Ui.lpWeight(1.3f))
        sniCard.addView(pkgRow, Ui.lpMatch().apply { topMargin = Ui.dp(a, 4) })
        column.addView(sniCard, Ui.lpMatch().apply { bottomMargin = Ui.dp(a, 14) })

        // ---- 缓存与自动更新 ----
        column.addView(Ui.sectionTitle(a, "缓存与更新"))
        val miscCard = Ui.card(a).apply { setPadding(Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14)) }
        miscCard.addView(switchRow("缓存查询结果", "", Prefs.cacheEnabled) {
            Prefs.cacheEnabled = it
        })
        miscCard.addView(Ui.spacer(a, 10))
        miscCard.addView(switchRow("订阅每 24 小时自动更新", "", Prefs.autoUpdate) {
            Prefs.autoUpdate = it
        })
        column.addView(miscCard, Ui.lpMatch().apply { bottomMargin = Ui.dp(a, 14) })

        // ---- 诊断导出 ----
        column.addView(Ui.sectionTitle(a, "诊断导出"))
        val diagCard = Ui.card(a).apply { setPadding(Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14)) }
        val diagRow = Ui.row(a)
        val windows = ArrayList<Pair<String, Long?>>().apply {
            add("2 分钟" to 120_000L)
            add("5 分钟" to 300_000L)
            add("10 分钟" to 600_000L)
            add("全部" to null)
        }
        val btnH = Ui.dp(a, 36)
        windows.forEachIndexed { idx, pair ->
            val b = Ui.button(a, pair.first, primary = false)
            b.textSize = 11.5f
            b.isSingleLine = true
            b.setPadding(0, 0, 0, 0)
            b.setOnClickListener {
                runCatching { Diagnostics.export(a, pair.second) }.onFailure {
                    android.widget.Toast.makeText(
                        a, "导出失败：${it.message}", android.widget.Toast.LENGTH_LONG
                    ).show()
                }
            }
            val lp = LinearLayout.LayoutParams(0, btnH, 1f)
            if (idx < windows.size - 1) lp.rightMargin = Ui.dp(a, 6)
            diagRow.addView(b, lp)
        }
        diagCard.addView(diagRow, Ui.lpMatch().apply { topMargin = Ui.dp(a, 10) })
        column.addView(diagCard, Ui.lpMatch().apply { bottomMargin = Ui.dp(a, 14) })

        // ---- 关于 ----
        column.addView(Ui.sectionTitle(a, "关于"))
        val about = Ui.card(a).apply { setPadding(Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14)) }
        val ver = Ui.row(a)
        ver.addView(Ui.text(a, "ShieldDNS for Android", 14f, Ui.TEXT, true), Ui.lpWeight(1f))
        ver.addView(Ui.text(a, "v${com.shielddns.android.BuildConfig.VERSION_NAME}", 12f, Ui.MUTED))
        about.addView(ver)
        about.addView(Ui.text(
            a,
            "私人 DNS 开启时拦截失效，需在系统设置里关闭",
            11.5f, Ui.MUTED
        ).apply { setPadding(0, Ui.dp(a, 6), 0, Ui.dp(a, 10)) })

        // ---- 检查更新（GitHub Release） ----
        updateStatus = Ui.text(a, "", 11.5f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(a, 10), 0, 0)
        }
        about.addView(updateStatus)
        updateBtn = Ui.button(a, "检查更新", primary = false)
        updateBtn.setOnClickListener {
            val ready = pending
            if (ready != null) startDownload(ready) else checkUpdate()
        }
        about.addView(updateBtn, Ui.lpMatch().apply { topMargin = Ui.dp(a, 8) })

        stateText = Ui.text(a, "", 11.5f, Ui.MUTED)
        about.addView(stateText)

        val quit = Ui.button(a, "退出应用", primary = false)
        quit.setOnClickListener {
            DnsVpnService.stop(a)
            a.finish()
        }
        about.addView(quit, Ui.lpMatch().apply { topMargin = Ui.dp(a, 12) })
        column.addView(about)

        view = scroll
    }

    private fun radio(text: String): RadioButton = RadioButton(a).apply {
        this.text = text
        setTextColor(Ui.TEXT)
        textSize = 13f
        buttonTintList = android.content.res.ColorStateList.valueOf(Ui.ACCENT)
        setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 4))
    }

    private fun switchRow(title: String, desc: String, initial: Boolean, onChange: (Boolean) -> Unit): View {
        val row = Ui.row(a)
        val left = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        left.addView(Ui.text(a, title, 13f, Ui.TEXT))
        if (desc.isNotEmpty()) {
            left.addView(Ui.text(a, desc, 11.5f, Ui.MUTED).apply { setPadding(0, Ui.dp(a, 4), 0, 0) })
        }
        row.addView(left, Ui.lpWeight(1f))
        val sw = Ui.switchWidget(a, initial)
        sw.setOnCheckedChangeListener { _, checked -> onChange(checked) }
        row.addView(sw)
        return row
    }

    private fun checkUpdate() {
        val current = com.shielddns.android.BuildConfig.VERSION_NAME
        updateBtn.isEnabled = false
        updateBtn.text = "检查中…"
        updateStatus.text = "正在检查…"
        val t = Thread {
            val res = runCatching { UpdateChecker.fetchLatest() }
            a.runOnUiThread {
                updateBtn.isEnabled = true
                res.onSuccess { latest ->
                    if (UpdateChecker.isNewer(latest.version, current)) {
                        pending = latest
                        updateStatus.text = "发现新版本 v${latest.version}（当前 v$current）"
                        updateBtn.text = "下载并安装 v${latest.version}"
                    } else {
                        pending = null
                        updateStatus.text = "已是最新版本（v$current）"
                        updateBtn.text = "重新检查"
                    }
                }
                res.onFailure {
                    pending = null
                    updateStatus.text = "检查失败：${it.message ?: "未知错误"}"
                    updateBtn.text = "重新检查"
                }
            }
        }
        t.isDaemon = true
        t.start()
    }

    private fun startDownload(latest: UpdateChecker.Latest) {
        updateBtn.isEnabled = false
        updateBtn.text = "准备下载…"
        updateStatus.text = "开始下载 v${latest.version}…"
        val t = Thread {
            val res = runCatching {
                UpdateChecker.download(a, latest) { pct ->
                    a.runOnUiThread {
                        updateBtn.text = "正在下载 $pct%"
                        updateStatus.text = "正在下载 v${latest.version} · $pct%"
                    }
                }
            }
            a.runOnUiThread {
                updateBtn.isEnabled = true
                res.onSuccess { apk ->
                    updateStatus.text = "下载完成，正在打开系统安装器…"
                    updateBtn.text = "重新检查"
                    pending = null
                    val opened = runCatching { UpdateChecker.install(a, apk) }
                    opened.onFailure {
                        updateStatus.text = "打不开安装器：${it.message}。请到系统设置里允许本应用「安装未知应用」。"
                    }
                }
                res.onFailure {
                    updateBtn.text = "下载并安装 v${latest.version}"
                    updateStatus.text = "下载失败：${it.message ?: "未知错误"}"
                }
            }
        }
        t.isDaemon = true
        t.start()
    }

    override fun refresh() {
        val mode = if (Prefs.upstreamMode == "custom") "自定义上游" else "跟随系统"
        stateText.text = buildString {
            append("当前网络：${Stats.networkText}\n")
            append("上游：$mode · ${Stats.upstreamText}\n")
            append("监听：${Stats.listenText}\n")
            append("缓存：${if (Prefs.cacheEnabled) "开" else "关"} · 生效规则 ${Stats.rules} 条")
        }
    }
}
