package com.shielddns.android.page

import android.app.AlertDialog
import android.graphics.Typeface
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import com.shielddns.android.BuiltinRules
import com.shielddns.android.MainActivity
import com.shielddns.android.Page
import com.shielddns.android.Prefs
import com.shielddns.android.SubEntry
import com.shielddns.android.SubStore
import com.shielddns.android.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

/**
 * 过滤订阅：内置策略置顶，其余是用户订阅。
 *
 * 交互约定：
 *  - 订阅只能更新和删除，不能编辑；
 *  - 每行右侧一个开关，决定这条订阅的规则是否参与拦截；
 *  - 长按某行进入多选模式，可以逐条勾、全选，再点删除；
 *  - 按住右侧手柄上下拖动调整顺序，列表从上到下就是优先级从高到低。
 */
class SubsPage(private val a: MainActivity) : Page {

    override val view: View

    private val listBox: LinearLayout
    private val summary: TextView
    private val toolbar: LinearLayout
    private val toolbarCount: TextView

    private val fmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

    private var lastKey = ""
    private var selectMode = false
    private val selected = LinkedHashSet<String>()

    /** id -> 行容器 / 勾选框，用于切换选择态时原地更新，避免整页重绘 */
    private val rowViews = HashMap<String, View>()
    private val rowChecks = HashMap<String, CircleCheck>()
    private val rowSwitches = HashMap<String, Switch>()
    private val rowHandles = HashMap<String, TextView>()

    /** 拖动排序的临时状态 */
    private var draggingIndex = -1
    private var draggingStartY = 0f
    private var draggingTarget = -1
    private var draggingSubCount = 0

    init {
        val scroll = ScrollView(a)
        val column = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(a, 14)
            setPadding(p, p, p, Ui.dp(a, 24))
        }
        scroll.addView(column)

        val head = Ui.row(a).apply { gravity = Gravity.CENTER_VERTICAL }
        head.addView(Ui.sectionTitle(a, "过滤订阅"), Ui.lpWeight(1f))
        val add = Ui.button(a, "添加订阅")
        add.setOnClickListener { showAddDialog() }
        head.addView(add)
        column.addView(head, Ui.lpMatch().apply { bottomMargin = Ui.dp(a, 8) })

        summary = Ui.text(a, "", 12f, Ui.MUTED)
        column.addView(summary)
        column.addView(
            Ui.text(a, "从上到下优先级由高到低", 11f, Ui.MUTED).apply {
                setPadding(0, Ui.dp(a, 4), 0, Ui.dp(a, 10))
            }
        )

        // 选择模式工具栏，默认隐藏
        toolbar = Ui.row(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            background = Ui.rounded(0xFF16241D.toInt(), 12, 0xFF2B3D34.toInt(), 1, a)
            val hp = Ui.dp(a, 12)
            val vp = Ui.dp(a, 8)
            setPadding(hp, vp, hp, vp)
        }
        toolbarCount = Ui.text(a, "", 12f, Ui.TEXT, true)
        toolbar.addView(toolbarCount, Ui.lpWeight(1f))
        toolbar.addView(textButton("全选", Ui.ACCENT) { toggleSelectAll() })
        toolbar.addView(textButton("删除", Ui.DANGER) { confirmDeleteSelected() })
        toolbar.addView(textButton("取消", Ui.MUTED) { setSelectMode(false) })
        column.addView(toolbar, Ui.lpMatch().apply { bottomMargin = Ui.dp(a, 10) })

        listBox = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        column.addView(listBox)

        view = scroll
    }

    private fun textButton(label: String, color: Int, onClick: () -> Unit): TextView =
        Ui.text(a, label, 12f, color, true).apply {
            setPadding(Ui.dp(a, 10), Ui.dp(a, 4), 0, Ui.dp(a, 4))
            setOnClickListener { onClick() }
        }

    private fun currentKey(): String {
        val subs = Prefs.loadSubs()
        return buildString {
            append(subs.joinToString("|") { "${it.id}:${it.enabled}:${it.count}:${it.updatedAt}:${it.name}" })
            append("|b=").append(Prefs.builtinEnabled)
            append(':').append(Prefs.builtinName)
            append(':').append(Prefs.builtinCount)
            append(':').append(Prefs.builtinUpdatedAt)
        }
    }

    override fun refresh() {
        val key = currentKey()
        if (key != lastKey) {
            lastKey = key
            render()
        }
        updateSummary()
    }

    private fun updateSummary() {
        val subs = Prefs.loadSubs()
        val total = subs.size + 1          // 含置顶的内置策略
        val on = subs.count { it.enabled } + if (Prefs.builtinEnabled) 1 else 0
        summary.text = "共 $total 条 · 启用 $on 条"
    }

    // ---------------- 渲染 ----------------

    private fun render() {
        listBox.removeAllViews()
        rowViews.clear()
        rowChecks.clear()
        rowSwitches.clear()
        rowHandles.clear()

        addBuiltinRow()

        val subs = Prefs.loadSubs()
        if (subs.isEmpty()) {
            listBox.addView(
                Ui.text(a, "还没有订阅，点右上角添加", 12f, Ui.MUTED).apply {
                    setPadding(0, Ui.dp(a, 12), 0, 0)
                }
            )
        }
        subs.forEachIndexed { i, s -> addSubRow(s, i) }
    }

    /** 置顶的内置策略：一条固定订阅，能更新、能开关、能改名，不能删除和排序。 */
    private fun addBuiltinRow() {
        val count = if (Prefs.builtinCount > 0) Prefs.builtinCount else BuiltinRules.items(a).size
        val card = Ui.card(a).apply {
            setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8))
        }

        val top = Ui.row(a).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = Ui.text(a, Prefs.builtinName, 14f, Ui.TEXT, true).apply {
            setOnClickListener {
                if (!selectMode) {
                    renameDialog(Prefs.builtinName) { name ->
                        Prefs.builtinName = name
                        lastKey = ""
                        render()
                    }
                }
            }
        }
        top.addView(title)
        top.addView(Ui.spacer(a, 8))
        top.addView(Ui.pill(a, "内置", Ui.ACCENT))
        top.addView(View(a), Ui.lpWeight(1f))
        val sw = Ui.switchWidget(a, Prefs.builtinEnabled)
        sw.setOnCheckedChangeListener { _, on ->
            Prefs.builtinEnabled = on
            a.reloadService()
            updateSummary()
        }
        top.addView(sw)
        card.addView(top)

        val whens = if (Prefs.builtinUpdatedAt > 0) {
            "更新于 ${fmt.format(Date(Prefs.builtinUpdatedAt))}"
        } else {
            "内置副本"
        }
        val info = Ui.row(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(a, 2), 0, 0)
        }
        info.addView(Ui.text(a, "规则 $count 条 · $whens", 11f, Ui.MUTED), Ui.lpWeight(1f))
        info.addView(textButton("更新", Ui.ACCENT) { updateBuiltin() })
        card.addView(info)

        listBox.addView(card, Ui.lpMatch().apply { bottomMargin = Ui.dp(a, 8) })
    }

    private fun updateBuiltin() {
        BuiltinRules.update(a) { ok, n, changed, err ->
            a.runOnUiThread {
                lastKey = ""
                a.reloadService()
                refresh()
                val msg = when {
                    !ok -> "「${Prefs.builtinName}」更新失败：${err ?: "网络不可用"}，继续使用本地副本"
                    changed -> "「${Prefs.builtinName}」共 $n 条规则 · 已更新"
                    else -> "「${Prefs.builtinName}」共 $n 条规则 · 已是最新"
                }
                toast(msg, !ok)
            }
        }
    }

    private fun addSubRow(s: SubEntry, index: Int) {
        val card = Ui.card(a).apply {
            setPadding(Ui.dp(a, 12), Ui.dp(a, 8), Ui.dp(a, 12), Ui.dp(a, 8))
        }

        val top = Ui.row(a).apply { gravity = Gravity.CENTER_VERTICAL }

        val check = CircleCheck(a).apply {
            visibility = if (selectMode) View.VISIBLE else View.GONE
            checked = s.id in selected
            onToggle = { on ->
                if (on) selected.add(s.id) else selected.remove(s.id)
                updateToolbar()
            }
        }
        rowChecks[s.id] = check
        top.addView(
            check,
            LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22)).apply {
                rightMargin = Ui.dp(a, 8)
                gravity = Gravity.CENTER_VERTICAL
            }
        )

        val title = Ui.text(a, s.name.ifBlank { "未命名" }, 14f, Ui.TEXT, true).apply {
            setOnClickListener { if (!selectMode) renameDialog(s.name) { renameSub(s.id, it) } }
        }
        top.addView(title, Ui.lpWeight(1f))

        val handle = Ui.text(a, "≡", 16f, Ui.MUTED, true).apply {
            setPadding(Ui.dp(a, 8), 0, Ui.dp(a, 8), 0)
            visibility = if (selectMode) View.VISIBLE else View.GONE
            tag = s.id
            setOnTouchListener(dragTouchListener())
        }
        rowHandles[s.id] = handle
        top.addView(handle)

        val sw = Ui.switchWidget(a, s.enabled)
        sw.setOnCheckedChangeListener { _, on -> setEnabled(s.id, on) }
        rowSwitches[s.id] = sw
        top.addView(sw)

        card.addView(top)

        val whens = if (s.updatedAt > 0) fmt.format(Date(s.updatedAt)) else "尚未下载"
        val info = Ui.row(a).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(a, 2), 0, 0)
        }
        info.addView(Ui.text(a, "规则 ${s.count} 条 · $whens", 11f, Ui.MUTED), Ui.lpWeight(1f))
        info.addView(textButton("更新", Ui.ACCENT) { downloadOne(s) })
        card.addView(info)

        // 长按整行进多选；多选模式下点行切换勾选
        card.setOnLongClickListener {
            if (!selectMode) {
                selected.clear()
                selected.add(s.id)
                setSelectMode(true)
            }
            true
        }
        card.setOnClickListener {
            if (selectMode) {
                val on = s.id !in selected
                if (on) selected.add(s.id) else selected.remove(s.id)
                rowChecks[s.id]?.checked = on
                updateToolbar()
            }
        }

        rowViews[s.id] = card
        listBox.addView(card, Ui.lpMatch().apply { bottomMargin = Ui.dp(a, 8) })
    }

    // ---------------- 选择模式 ----------------

    private fun setSelectMode(on: Boolean) {
        selectMode = on
        if (!on) selected.clear()
        rowChecks.values.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
        rowChecks.forEach { (id, cb) -> cb.checked = id in selected }
        toolbar.visibility = if (on) View.VISIBLE else View.GONE
        // 常规状态不显示拖动手柄；长按进多选后手柄出现，用来拖动排序
        rowHandles.values.forEach { it.visibility = if (on) View.VISIBLE else View.GONE }
        updateToolbar()
    }

    private fun updateToolbar() {
        toolbarCount.text = "已选 ${selected.size}"
    }

    private fun toggleSelectAll() {
        val subs = Prefs.loadSubs()
        if (selected.size == subs.size) {
            selected.clear()
        } else {
            selected.clear()
            subs.forEach { selected.add(it.id) }
        }
        rowChecks.forEach { (id, cb) -> cb.checked = id in selected }
        updateToolbar()
    }

    private fun confirmDeleteSelected() {
        if (selected.isEmpty()) {
            android.widget.Toast.makeText(a, "先勾选要删除的订阅", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val subs = Prefs.loadSubs()
        val names = subs.filter { it.id in selected }.joinToString("、") { it.name.ifBlank { "未命名" } }
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("删除 ${selected.size} 条订阅")
            .setMessage("$names\n\n已下载的规则文件会一起清掉。")
            .setPositiveButton("删除") { _, _ ->
                val keep = subs.filter { it.id !in selected }
                subs.filter { it.id in selected }.forEach { runCatching { SubStore.fileFor(a, it).delete() } }
                Prefs.saveSubs(keep)
                setSelectMode(false)
                lastKey = ""
                a.reloadService()
                refresh()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setEnabled(id: String, on: Boolean) {
        val all = Prefs.loadSubs()
        all.firstOrNull { it.id == id }?.enabled = on
        Prefs.saveSubs(all)
        // 不重建列表，保持当前位置：把 lastKey 同步成新状态，让定时 refresh 跳过重绘
        lastKey = currentKey()
        a.reloadService()
        updateSummary()
    }


    // ---------------- 改名 ----------------

    private fun renameSub(id: String, name: String) {
        val all = Prefs.loadSubs().map { if (it.id == id) it.copy(name = name) else it }
        Prefs.saveSubs(all)
        lastKey = ""
        refresh()
    }

    private fun renameDialog(current: String, onSave: (String) -> Unit) {
        val input = EditText(a).apply {
            setText(current)
            setSelection(text.length)
            setTextColor(Ui.TEXT)
            setHintTextColor(0xFF5F7A6D.toInt())
        }
        val box = LinearLayout(a).apply {
            val p = Ui.dp(a, 20)
            setPadding(p, p, p, 0)
            addView(input)
        }
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("重命名订阅")
            .setView(box)
            .setPositiveButton("保存") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && name != current) onSave(name)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------------- 拖动排序 ----------------

    private fun dragTouchListener() = View.OnTouchListener { v, e ->
        val id = v.tag as? String ?: return@OnTouchListener false
        val holder = rowViews[id] ?: return@OnTouchListener false

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val subs = Prefs.loadSubs()
                val index = subs.indexOfFirst { it.id == id }
                if (index < 0) return@OnTouchListener false
                draggingIndex = index
                draggingTarget = index
                draggingStartY = e.rawY
                draggingSubCount = subs.size
                holder.alpha = 0.92f
                holder.translationZ = Ui.dp(a, 6).toFloat()
                v.parent?.requestDisallowInterceptTouchEvent(true)
                true
            }
            MotionEvent.ACTION_MOVE -> {
                if (draggingIndex < 0) return@OnTouchListener true
                val dy = e.rawY - draggingStartY
                holder.translationY = dy
                // 落点直接由「拖过了多少个行高」决定：越过半行就换一格
                val step = rowStep()
                val shift = if (step <= 0f) 0 else Math.round(dy / step)
                val target = (draggingIndex + shift).coerceIn(0, (draggingSubCount - 1).coerceAtLeast(0))
                if (target != draggingTarget) {
                    draggingTarget = target
                    shiftOthers(draggingIndex, draggingTarget, draggingSubCount)
                }
                true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val from = draggingIndex
                val to = draggingTarget
                rowViews.values.forEach {
                    it.animate().translationY(0f).setDuration(90).start()
                    it.alpha = 1f
                    it.translationZ = 0f
                }
                draggingIndex = -1
                draggingTarget = -1
                draggingSubCount = 0
                v.parent?.requestDisallowInterceptTouchEvent(false)
                if (from >= 0 && to >= 0 && from != to) commitMove(from, to)
                true
            }
            else -> true
        }
    }

    /** 拖动中让其它卡片腾位：目标在下方则中间那些上移一格，在上方则下移一格。 */
    private fun shiftOthers(from: Int, target: Int, count: Int) {
        val step = rowStep()
        val subs = Prefs.loadSubs()
        subs.forEachIndexed { i, s ->
            if (i == from) return@forEachIndexed
            val v = rowViews[s.id] ?: return@forEachIndexed
            val y = when {
                target > from && i in (from + 1)..target -> -step
                target < from && i in target until from -> step
                else -> 0f
            }
            if (v.translationY != y) {
                v.animate().translationY(y).setDuration(110).start()
            }
        }
    }

    /** 一格的纵向步长（卡片高度 + 行间距）。 */
    private fun rowStep(): Float {
        val v = rowViews.values.firstOrNull() ?: return Ui.dp(a, 70).toFloat()
        val lp = v.layoutParams as? LinearLayout.LayoutParams
        return (v.height + (lp?.bottomMargin ?: 0)).toFloat()
    }

    private fun commitMove(from: Int, to: Int) {
        val all = Prefs.loadSubs().toMutableList()
        val moved = all.removeAt(from)
        all.add(to.coerceIn(0, all.size), moved)
        Prefs.saveSubs(all)
        a.reloadService()
        render()
        lastKey = currentKey()
        updateSummary()
    }

    // ---------------- 下载 / 添加 ----------------

    private fun downloadOne(s: SubEntry) {
        var error: String? = null
        SubStore.refreshAll(a, listOf(s), { u -> if (!u.ok) error = u.error }, { changed, _, fail ->
            val all = Prefs.loadSubs().map { if (it.id == s.id) s else it }
            Prefs.saveSubs(all)
            a.runOnUiThread {
                lastKey = ""
                a.reloadService()
                refresh()
                // 三条路径统一成「名称 + 结果 + 条数」，失败时直接给原因
                val count = s.count
                val msg = when {
                    fail > 0 ->
                        "「${s.name}」更新失败：${error ?: "网络不可用"}，保留原有 $count 条"
                    changed > 0 ->
                        "「${s.name}」共 $count 条规则 · 已更新"
                    else ->
                        "「${s.name}」共 $count 条规则 · 已是最新"
                }
                toast(msg, fail > 0)
            }
        })
    }

    private fun toast(msg: String, long: Boolean = false) {
        android.widget.Toast.makeText(
            a, msg,
            if (long) android.widget.Toast.LENGTH_LONG else android.widget.Toast.LENGTH_SHORT
        ).show()
    }

    private fun showAddDialog() {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            val p = Ui.dp(a, 18)
            setPadding(p, p, p, 0)
        }
        val nameInput = EditText(a).apply {
            hint = "名称（可留空）"
            setTextColor(Ui.TEXT)
            setHintTextColor(0xFF5F7A6D.toInt())
            typeface = Typeface.DEFAULT
        }
        val urlInput = EditText(a).apply {
            hint = "https://…/rules.txt"
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setTextColor(Ui.TEXT)
            setHintTextColor(0xFF5F7A6D.toInt())
        }
        box.addView(nameInput)
        box.addView(urlInput)
        AlertDialog.Builder(a, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("添加过滤订阅")
            .setView(box)
            .setPositiveButton("添加") { _, _ ->
                val url = urlInput.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                val name = nameInput.text.toString().trim().ifBlank {
                    runCatching { java.net.URI(url).host ?: "订阅" }.getOrDefault("订阅")
                }
                val entry = SubEntry(
                    id = UUID.randomUUID().toString().take(8),
                    name = name,
                    url = url
                )
                val all = Prefs.loadSubs().apply { add(entry) }
                Prefs.saveSubs(all)
                lastKey = ""
                a.reloadService()
                refresh()
                downloadOne(entry)
            }
            .setNegativeButton("取消", null)
            .show()
    }
}
