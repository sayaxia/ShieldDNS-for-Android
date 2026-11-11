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
        val sysRb = radio("跟随系统（读取当前网络下发的 DNS）")
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
        upCard.addView(Ui.text(a, "跟随系统时汇总当前所有活动网络（WLAN / 蜂窝）下发的 IPv4 DNS，默认网络优先，其余兜底；切网时自动刷新。自定义时用逗号分隔多个地址，前一个超时自动换下一个；UDP 与 TCP 查询都会走同一组上游。", 11.5f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(a, 8), 0, 0)
        })
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

        // ---- 缓存与自动更新 ----
        column.addView(Ui.sectionTitle(a, "缓存与更新"))
        val miscCard = Ui.card(a).apply { setPadding(Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14)) }
        miscCard.addView(switchRow("缓存允许的查询结果", "按应答里的 TTL 缓存，命中率越高越省流量", Prefs.cacheEnabled) {
            Prefs.cacheEnabled = it
        })
        miscCard.addView(Ui.spacer(a, 10))
        miscCard.addView(switchRow("订阅每 24 小时自动更新", "从没更新过、或距上次更新超过 24 小时，会在开启防护后自动拉一次", Prefs.autoUpdate) {
            Prefs.autoUpdate = it
        })
        column.addView(miscCard, Ui.lpMatch().apply { bottomMargin = Ui.dp(a, 14) })

        // ---- 关于 ----
        column.addView(Ui.sectionTitle(a, "关于"))
        val about = Ui.card(a).apply { setPadding(Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14), Ui.dp(a, 14)) }
        val ver = Ui.row(a)
        ver.addView(Ui.text(a, "ShieldDNS for Android", 14f, Ui.TEXT, true), Ui.lpWeight(1f))
        ver.addView(Ui.text(a, "v${com.shielddns.android.BuildConfig.VERSION_NAME}", 12f, Ui.MUTED))
        about.addView(ver)
        about.addView(Ui.text(a, "本地 VPN 形式接管 DNS · Kotlin 内核 · 无内置规则", 11.5f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(a, 6), 0, 0)
        })
        about.addView(Ui.text(
            a,
            "系统「私人 DNS（Private DNS）」开启时，解析会走 DoT 绕过本应用。拦截不生效时请在系统设置里把私人 DNS 设为「关闭」。也可以在系统「始终开启的 VPN」里选中 ShieldDNS，重启后自动生效。",
            11.5f, Ui.MUTED
        ).apply { setPadding(0, Ui.dp(a, 8), 0, Ui.dp(a, 12)) })

        // ---- 检查更新（GitHub Release） ----
        updateStatus = Ui.text(a, "检查 GitHub Release 上的最新版本，有新版可直接下载并安装。", 11.5f, Ui.MUTED).apply {
            setPadding(0, Ui.dp(a, 12), 0, 0)
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
        left.addView(Ui.text(a, desc, 11.5f, Ui.MUTED).apply { setPadding(0, Ui.dp(a, 4), 0, 0) })
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
