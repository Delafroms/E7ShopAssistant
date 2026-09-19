package com.e7.shop.ui

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * **侧栏轨道户型**（Rail）—— 与经典"胶囊在上 + 面板在下"完全不同的布局架构。
 *
 * ## 户型差异（这正是主题化要达到的效果）
 *
 * | | 经典户型 | 本户型（Rail） |
 * |---|---|---|
 * | 收起态 | 横向胶囊，贴在顶部 | **竖向细轨**，贴右侧边缘 |
 * | 按钮位置 | 面板**底部** | 面板**顶部**（先动手再读信息） |
 * | 统计排布 | 3 列 × 2 行 | 2 列 × 3 行 |
 * | 信息顺序 | 状态 → 健康 → 统计 → 日志 → 控制 | 控制 → 状态 → 统计 → 日志 |
 *
 * ## 功能等价性（契约要求，一个都不能少）
 *
 * 开始/暂停/停止 · 阶段 · 时长 · 状态点 · 引擎 · 模型健康 · 截图健康 ·
 * 错误提示 · 6 项统计 · 事件日志 · 展开收起 · 拖拽移动。
 * 全部由 [FloatyState] 提供数据，本类不做任何业务判断。
 *
 * ## 为什么按钮放最上面
 *
 * 挂机时最常用的动作是"暂停一下看看"或"停下来"，而面板一旦展开，按钮在底部
 * 意味着每次都要先滑过一大段信息。放在顶部后，展开即可点，符合"更超前更方便"。
 */
internal class RailFloatySkin(private val ctx: Context) : FloatySkin {

    override val id: String = "rail"

    private var rail: LinearLayout? = null
    private var panel: LinearLayout? = null
    private var expandedFlag = false
    private var railWidthPx = 0

    // ---- 收起态（竖轨） ----
    private var railDot: View? = null
    private var railStage: TextView? = null
    private var railTime: TextView? = null

    // ---- 展开态（面板） ----
    private var panelDot: View? = null
    private var panelStage: TextView? = null
    private var panelTime: TextView? = null
    private var panelEngine: TextView? = null
    private var panelModel: TextView? = null
    private var panelShot: TextView? = null
    private var panelErr: TextView? = null
    private var panelLog: TextView? = null
    private val statLabels = ArrayList<TextView>()
    private val statValues = ArrayList<TextView>()
    private var startBtn: TextView? = null
    private var pauseBtn: TextView? = null
    private var stopBtn: TextView? = null
    private var chevron: TextView? = null

    override val expanded: Boolean get() = expandedFlag

    override fun collapsedWidthPx(): Int = railWidthPx

    /* ==================== 构建 ==================== */

    override fun build(root: FrameLayout, spec: FloatySpec, host: FloatyHost) {
        currentSpec = spec
        railWidthPx = dp(46)
        // 竖轨：窄条贴边，只回答"在跑吗 / 到哪一步了 / 多久了"
        rail = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(6), dp(10), dp(6), dp(10))
            background = railBg(spec)
            // 拖拽：竖轨与面板都支持，位移交回控制器夹取
            setOnTouchListener(dragListener(host))
            setOnClickListener { host.onToggleExpand() }
        }
        railDot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(spec.run)
            }
            rail?.addView(this, LinearLayout.LayoutParams(dp(12), dp(12)).apply { bottomMargin = dp(8) })
        }
        railStage = TextView(ctx).apply {
            setTextColor(spec.text)
            textSize = 11f
            gravity = Gravity.CENTER
            isSingleLine = true
            setTypeface(null, Typeface.BOLD)
            ellipsize = TextUtils.TruncateAt.END
            rail?.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        railTime = TextView(ctx).apply {
            setTextColor(spec.sub)
            textSize = 9.5f
            gravity = Gravity.CENTER
            isSingleLine = true
            if (spec.mono) typeface = Typeface.MONOSPACE
            rail?.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) })
        }
        root.addView(rail)

        // 面板：按钮在最上方
        panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            background = railBg(spec)
            visibility = View.GONE
        }
        buildControlBar(panel!!, spec, host)   // ← 顶部：控制优先
        buildStatusBlock(panel!!, spec)
        buildStatsBlock(panel!!, spec)
        buildLogBlock(panel!!, spec)
        buildErrorBlock(panel!!, spec)
        root.addView(panel)
    }

    /** 顶部控制条：开始 / 暂停 / 停止 + 收起箭头。 */
    private fun buildControlBar(parent: LinearLayout, spec: FloatySpec, host: FloatyHost) {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        startBtn = ctrlButton(spec, spec.startC1, spec.startC2, spec.startText).apply {
            setOnClickListener { host.onStart() }
            row.addView(this, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(5) })
        }
        pauseBtn = ctrlButton(spec, spec.pauseC1, spec.pauseC2, spec.pauseText).apply {
            setOnClickListener { host.onTogglePause() }
            row.addView(this, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(5) })
        }
        stopBtn = ctrlButton(spec, spec.stopC1, spec.stopC2, spec.stopText).apply {
            setOnClickListener { host.onStop() }
            row.addView(this, LinearLayout.LayoutParams(0, dp(34), 1f).apply { rightMargin = dp(5) })
        }
        chevron = TextView(ctx).apply {
            text = "▴"
            setTextColor(spec.sub)
            textSize = 13f
            gravity = Gravity.CENTER
            setOnClickListener { host.onToggleExpand() }
            row.addView(this, LinearLayout.LayoutParams(dp(24), dp(34)))
        }
        parent.addView(row, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(9) })
    }

    /** 状态块：状态点 + 阶段 + 时长 + 引擎 / 模型 / 截图健康。 */
    private fun buildStatusBlock(parent: LinearLayout, spec: FloatySpec) {
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        panelDot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(spec.run)
            }
            head.addView(this, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(7) })
        }
        panelStage = TextView(ctx).apply {
            setTextColor(spec.text)
            textSize = 13f
            isSingleLine = true
            setTypeface(null, Typeface.BOLD)
            ellipsize = TextUtils.TruncateAt.END
            head.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        panelTime = TextView(ctx).apply {
            setTextColor(spec.sub)
            textSize = 11f
            isSingleLine = true
            if (spec.mono) typeface = Typeface.MONOSPACE
            head.addView(this)
        }
        parent.addView(head)

        val health = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        panelEngine = chip(spec.edge).apply { health.addView(this) }
        panelModel = chip(spec.run).apply {
            health.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(6) })
        }
        panelShot = chip(spec.sub).apply {
            health.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { leftMargin = dp(6) })
        }
        parent.addView(health, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(7) })
    }

    /** 统计块：2 列 × 3 行（与经典的 3 列 × 2 行不同）。 */
    private fun buildStatsBlock(parent: LinearLayout, spec: FloatySpec) {
        val grid = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }
        statValues.clear()
        for (r in 0 until 3) {
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                if (r > 0) setPadding(0, dp(5), 0, 0)
            }
            for (c in 0 until 2) {
                val idx = r * 2 + c
                val cell = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(8), dp(6), dp(8), dp(6))
                    background = GradientDrawable().apply {
                        cornerRadius = dpx(8f)
                        setColor(withAlpha(spec.edge, 0x14))
                    }
                }
                val label = TextView(ctx).apply {
                    setTextColor(spec.sub)
                    textSize = 9f
                    isSingleLine = true
                }
                val value = TextView(ctx).apply {
                    setTextColor(spec.statColors.getOrElse(idx) { spec.text })
                    textSize = 14f
                    isSingleLine = true
                    setTypeface(null, Typeface.BOLD)
                    if (spec.mono) typeface = Typeface.MONOSPACE
                }
                cell.addView(label)
                cell.addView(value)
                statLabels.add(label)
                statValues.add(value)
                row.addView(cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    .apply { if (c == 0) rightMargin = dp(5) })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        parent.addView(grid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(9) })
    }

    private fun buildLogBlock(parent: LinearLayout, spec: FloatySpec) {
        panelLog = TextView(ctx).apply {
            setTextColor(spec.sub)
            textSize = 10f
            maxLines = 6
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, 0)
            if (spec.mono) typeface = Typeface.MONOSPACE
        }
        parent.addView(panelLog, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    private fun buildErrorBlock(parent: LinearLayout, spec: FloatySpec) {
        panelErr = TextView(ctx).apply {
            visibility = View.GONE
            setTextColor(spec.warn)
            textSize = 10f
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(8), 0, 0)
        }
        parent.addView(panelErr, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ))
    }

    /* ==================== 渲染 ==================== */

    override fun render(s: FloatyState) {
        val spec = currentSpec
        // 收起态：只回答"在跑吗 / 哪一步 / 多久"
        (railDot?.background as? GradientDrawable)?.setColor(if (s.hasError) spec.warn else spec.run)
        railStage?.text = shortStage(s.stageText)
        railStage?.setTextColor(if (s.hasError) spec.warn else spec.text)
        railTime?.text = s.timeText

        // 展开态
        (panelDot?.background as? GradientDrawable)?.setColor(if (s.hasError) spec.warn else spec.run)
        panelStage?.text = s.stageText
        panelStage?.setTextColor(if (s.hasError) spec.warn else spec.text)
        panelTime?.text = s.timeText
        panelEngine?.text = s.engineLongText
        panelModel?.let { tv ->
            if (s.modelVisible) {
                tv.visibility = View.VISIBLE
                tv.text = s.modelText
                tv.setTextColor(if (s.modelOk) spec.run else spec.warn)
            } else {
                tv.visibility = View.GONE
            }
        }
        panelShot?.let { tv ->
            tv.text = s.shotText
            tv.setTextColor(if (s.shotOk) spec.run else spec.warn)
        }
        // 统计：契约保证 stats 至少 6 项，缺失的槽位留空而不越界
        for (i in statValues.indices) {
            val st = s.stats.getOrNull(i)
            statLabels[i].text = st?.first ?: ""
            statValues[i].text = st?.second ?: "-"
        }
        panelLog?.text = s.events.joinToString("\n") { spec.logPrefix + " " + it }
        panelErr?.let { tv ->
            if (s.hasError) {
                tv.text = s.errorText
                tv.visibility = View.VISIBLE
            } else {
                tv.visibility = View.GONE
            }
        }
        startBtn?.text = s.startLabel
        pauseBtn?.text = s.pauseLabel
        stopBtn?.text = s.stopLabel
        chevron?.text = if (expandedFlag) "▴" else "▾"
    }

    override fun setExpanded(expanded: Boolean): Boolean {
        expandedFlag = expanded
        rail?.visibility = if (expanded) View.GONE else View.VISIBLE
        panel?.visibility = if (expanded) View.VISIBLE else View.GONE
        return true
    }

    override fun destroy() {
        rail?.setOnTouchListener(null)
        rail?.setOnClickListener(null)
        rail = null
        panel = null
        statValues.clear()
    }

    /* ==================== 工具 ==================== */

    /**
     * 竖轨上的阶段文字：只留最关键的 2 个字。
     * 竖轨宽度只有 46dp，整句会被截断成省略号 —— 截断反而更看不懂，
     * 不如主动取短名（"购买中" → "购买"）。
     */
    private fun shortStage(full: String): String = when {
        full.isEmpty() -> "—"
        full.length <= 3 -> full
        else -> full.take(2)
    }

    /** 供 render 取色：spec 在 build 时已固化，这里保留最后一次引用。 */
    private var currentSpec: FloatySpec = DEFAULT_SPEC

    private fun railBg(spec: FloatySpec): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dpx(spec.cornerDp)
        setColor(spec.body)
        setStroke(2, withAlpha(spec.edge, 0x66))
    }

    private fun ctrlButton(spec: FloatySpec, c1: Int, c2: Int, textColor: Int): TextView =
        TextView(ctx).apply {
            gravity = Gravity.CENTER
            textSize = 12.5f
            setTextColor(textColor)
            setTypeface(null, Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            background = GradientDrawable().apply {
                cornerRadius = dpx(9f)
                if (spec.flatButtons) {
                    setColor(c1)
                } else {
                    orientation = GradientDrawable.Orientation.TOP_BOTTOM
                    colors = intArrayOf(c1, c2)
                }
                setStroke(1, withAlpha(spec.edge, 0x44))
            }
        }

    private fun chip(color: Int): TextView = TextView(ctx).apply {
        setTextColor(color)
        textSize = 9.5f
        isSingleLine = true
        setTypeface(null, Typeface.BOLD)
        setPadding(dp(7), dp(2), dp(7), dp(2))
        background = GradientDrawable().apply {
            cornerRadius = dpx(7f)
            setColor(withAlpha(color, 0x16))
            setStroke(1, withAlpha(color, 0x55))
        }
    }

    /**
     * 拖拽：位移通过 [FloatyHost.onDragBy] 交回控制器。
     *
     * 为什么 skin 不自己移动窗口：窗口位置属于 WindowManager 参数，
     * 而夹取到屏幕内、持久化位置、与展开态尺寸联动都属于控制器的职责。
     * skin 只报告"用户拖了多少"，不碰窗口本身。
     */
    private fun dragListener(host: FloatyHost): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var moved = false
        return View.OnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) {
                        moved = true
                        host.onDragBy(dx.toInt(), dy.toInt())
                        downX = ev.rawX; downY = ev.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (moved) host.onDragEnd()
                    // 拖动结束不触发点击：交给 View 自身的 click 判定
                    false
                }
                else -> false
            }
        }
    }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()
    private fun dpx(v: Float): Float = v * ctx.resources.displayMetrics.density
    private fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

    private companion object {
        /** render 早于 build 时的兜底（正常情况下不会发生）。 */
        val DEFAULT_SPEC = FloatySpec(
            body = 0xF20D1330.toInt(), headerTop = 0, headerBottom = 0, edge = 0xFF66C0F4.toInt(),
            text = 0xFFE7ECFF.toInt(), sub = 0xFF8B96BB.toInt(),
            run = 0xFF7BE0A0.toInt(), warn = 0xFFE85C4A.toInt(),
            statColors = emptyList(),
            startC1 = 0xFF75B022.toInt(), startC2 = 0xFF4F7A10.toInt(), startText = -1,
            pauseC1 = 0xFFB8860B.toInt(), pauseC2 = 0xFF8A650F.toInt(), pauseText = -1,
            stopC1 = 0xFFB03A2E.toInt(), stopC2 = 0xFF7B241C.toInt(), stopText = -1,
            cornerDp = 12f, headerGradient = true, headerStripes = false, headerDots = false,
            tapeCorners = false, flatButtons = false, logPrefix = "›", mono = false
        )
    }
}
