package com.e7.shop.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Handler
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.e7.shop.R
import com.e7.shop.ShopAccessibilityService
import com.e7.shop.ShopAccessibilityService.Stage
import com.e7.shop.data.AppConfig
import com.e7.shop.data.RecordStore

/**
 * 悬浮窗「任务面板」控制器（从 ShopAccessibilityService 抽出）。
 *
 * 为什么单独成类：这块逻辑原先与无障碍服务生命周期、截图、手势、会话管理挤在同一个
 * 1600 行类里。悬浮窗是**纯 UI 关注点**——它只读 BotState、只画 View、只发控制指令，
 * 不碰截图也不碰手势。抽出来之后服务类只剩"机器人怎么跑"，这里只剩"面板怎么画"。
 *
 * 归属：悬浮窗由 **Service** 持有（不是 Activity）。OEM 的"游戏空间"回收后台 Activity 时，
 * 绝不能让正在运行的机器人把悬浮球一起弄丢。
 *
 * 线程约定：所有 View 操作都在主线程（[Handler] 绑定主 Looper）。状态来自机器线程，
 * 通过 [refreshContent] 在主线程读取。
 */
class FloatyController(
    private val ctx: ShopAccessibilityService,
    private val cfg: AppConfig,
    private val mainHandler: Handler,
    private val onStart: () -> String,
    private val onTogglePause: () -> Unit,
    private val onStop: () -> Unit
) {

    /* ---------- 视图引用 ---------- */
    private var floatyRoot: FrameLayout? = null
    private var floatyParams: WindowManager.LayoutParams? = null
    private var floatyCapW: Int = 0
    private var capsuleView: LinearLayout? = null
    private var deckView: LinearLayout? = null
    private var capStatus: TextView? = null
    private var capTime: TextView? = null
    private var capDot: View? = null
    private var capEngine: TextView? = null
    private var capErr: TextView? = null
    private var capChev: TextView? = null
    private var deckStage: TextView? = null
    private var deckTime: TextView? = null
    private var deckDot: View? = null
    private var deckEngine: TextView? = null
    private var deckModel: TextView? = null
    private var deckShot: TextView? = null
    private var deckErr: TextView? = null
    private var deckLog: TextView? = null
    private var deckPause: TextView? = null
    private val deckStatVals = ArrayList<TextView>()
    private var deckOpen = false
    private var deckAnim: ValueAnimator? = null
    private var lastDragLog = 0L

    /** 当前悬浮窗所渲染的主题（外观切换时检测变化 → 整体重建）。 */
    private var floatyAppearance = ""

    /** 面板展开宽度计算所需的刘海安全区（构建时记录，供 [toggle] 复用）。 */
    private var deckSafeLeft = 0
    private var deckSafeRight = 0

    /** 事件日志环形缓冲（时间戳 + 文案，最多 [MAX_EVENT_LOG] 条，最新在尾）。 */
    private val eventLog = ArrayDeque<String>()
    private var lastLoggedStage: Stage? = null
    private var lastLoggedError = ""
    private var lastLoggedBm = -1
    private var lastLoggedMedal = -1
    private var lastLoggedRefresh = -1

    /** 悬浮窗是否已建立。 */
    val isShown: Boolean get() = floatyRoot != null

    /* ---------- 事件日志 ---------- */

    fun logEvent(msg: String) {
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())
        if (eventLog.size >= MAX_EVENT_LOG) eventLog.removeFirst()
        eventLog.addLast("$stamp  $msg")
    }

    /** 每次 start = 全新会话，清空上一轮时间线并重置去重游标。 */
    fun resetEventLog(startedText: String) {
        eventLog.clear()
        lastLoggedStage = null
        lastLoggedError = ""
        lastLoggedBm = -1
        lastLoggedMedal = -1
        lastLoggedRefresh = -1
        logEvent(startedText)
    }

    /** 阶段变化 → 事件日志。相同阶段不重复记录（避免刷屏）。 */
    fun onStageChanged(stage: Stage, text: String) {
        if (lastLoggedStage == stage) return
        lastLoggedStage = stage
        logEvent(text)
    }

    /** 错误变化 → 事件日志。空串与重复值都跳过。 */
    fun onErrorChanged(msg: String, text: String) {
        if (msg.isEmpty() || msg == lastLoggedError) return
        lastLoggedError = msg
        logEvent(text)
    }

    /**
     * 计数变化 → 事件日志。
     *
     * 三个计数各自维护游标：只有真正变化的那一项才写日志，
     * 避免同一轮里"买了书签"顺带把"奖牌/刷新"也各写一条噪音。
     */
    fun onCountersChanged(
        bookmarks: Int, medals: Int, refreshes: Int,
        bookmarkText: String, medalText: String, refreshText: String
    ) {
        if (bookmarks != lastLoggedBm) {
            lastLoggedBm = bookmarks
            logEvent(bookmarkText)
        }
        if (medals != lastLoggedMedal) {
            lastLoggedMedal = medals
            logEvent(medalText)
        }
        if (refreshes != lastLoggedRefresh) {
            lastLoggedRefresh = refreshes
            logEvent(refreshText)
        }
    }

    /* ---------- 秒级时钟 ---------- */

    private val ticker = object : Runnable {
        override fun run() {
            if (floatyRoot == null) return
            val elapsed = if (ctx.state.startedAt > 0) System.currentTimeMillis() - ctx.state.startedAt else 0
            val timeTxt = RecordStore.fmtMs(elapsed)
            capTime?.text = timeTxt
            deckTime?.text = timeTxt
            mainHandler.postDelayed(this, 1000)
        }
    }

    private fun startTicker() {
        mainHandler.removeCallbacks(ticker)
        mainHandler.postDelayed(ticker, 1000)
    }

    /* ---------- 主题规格 ---------- */

    /**
     * 悬浮窗结构规格：颜色之外还有真正的"户型"差异 ——
     * Steam 控制台（渐变横幅 / 蓝边 / 绿色运行键）、OLED 极简（平面色块 / 细边 / 等宽字）、
     * BA 学园（条纹 + 网点横幅 / 胶带角 / 粉彩点缀）。
     */
    private data class FloatySpec(
        val body: Int, val headerTop: Int, val headerBottom: Int, val edge: Int,
        val text: Int, val sub: Int, val run: Int, val warn: Int,
        val statColors: List<Int>,
        val startC1: Int, val startC2: Int, val startText: Int,
        val pauseC1: Int, val pauseC2: Int, val pauseText: Int,
        val stopC1: Int, val stopC2: Int, val stopText: Int,
        val cornerDp: Float,
        val headerGradient: Boolean,
        val headerStripes: Boolean,
        val headerDots: Boolean,
        val tapeCorners: Boolean,
        val flatButtons: Boolean,
        val logPrefix: String,
        val mono: Boolean
    )

    private fun floatySpec(): FloatySpec = when (cfg.appearance) {
        "ba", "bluearchive" -> FloatySpec(
            body = 0xF2FFFFFF.toInt(), headerTop = 0xFF1E88E5.toInt(), headerBottom = 0xFF5BB2F2.toInt(),
            edge = 0xFF1E88E5.toInt(), text = 0xFF12263D.toInt(), sub = 0xFF4A5F75.toInt(),
            run = 0xFF1E88E5.toInt(), warn = 0xFFD93025.toInt(),
            statColors = listOf(0xFF1E88E5.toInt(), 0xFF00B0A3.toInt(), 0xFF7CB342.toInt(),
                0xFF039BE5.toInt(), 0xFFF0A020.toInt(), 0xFF4A5F75.toInt()),
            startC1 = 0xFF1E88E5.toInt(), startC2 = 0xFF1565C0.toInt(), startText = 0xFFFFFFFF.toInt(),
            pauseC1 = 0xFFF0A020.toInt(), pauseC2 = 0xFFB8860B.toInt(), pauseText = 0xFF2A2004.toInt(),
            stopC1 = 0xFFE85C4A.toInt(), stopC2 = 0xFF8B1A1A.toInt(), stopText = 0xFFFFFFFF.toInt(),
            cornerDp = 20f, headerGradient = true, headerStripes = true, headerDots = true,
            tapeCorners = true, flatButtons = false, logPrefix = "✎", mono = false
        )
        "oled" -> FloatySpec(
            body = 0xF5000000.toInt(), headerTop = 0xFF050505.toInt(), headerBottom = 0xFF050505.toInt(),
            edge = 0xFF3D4450.toInt(), text = 0xFFE7ECFF.toInt(), sub = 0xFF8B96BB.toInt(),
            run = 0xFF4ADE80.toInt(), warn = 0xFFE85C4A.toInt(),
            statColors = listOf(0xFF4D6BFE.toInt(), 0xFFB066E8.toInt(), 0xFF2E9E5B.toInt(),
                0xFF38BDF8.toInt(), 0xFFD4AF37.toInt(), 0xFF8B96BB.toInt()),
            startC1 = 0xFF2E9E5B.toInt(), startC2 = 0xFF2E9E5B.toInt(), startText = 0xFFFFFFFF.toInt(),
            pauseC1 = 0xFFB8860B.toInt(), pauseC2 = 0xFFB8860B.toInt(), pauseText = 0xFF2A2004.toInt(),
            stopC1 = 0xFF8B1A1A.toInt(), stopC2 = 0xFF8B1A1A.toInt(), stopText = 0xFFFFFFFF.toInt(),
            cornerDp = 10f, headerGradient = false, headerStripes = false, headerDots = false,
            tapeCorners = false, flatButtons = true, logPrefix = "·", mono = true
        )
        else -> FloatySpec(
            body = 0xF20D1330.toInt(), headerTop = 0xFF1B2838.toInt(), headerBottom = 0xFF0D1330.toInt(),
            edge = 0xFF66C0F4.toInt(), text = 0xFFE7ECFF.toInt(), sub = 0xFF8B96BB.toInt(),
            run = 0xFF7BE0A0.toInt(), warn = 0xFFE85C4A.toInt(),
            statColors = listOf(0xFF66C0F4.toInt(), 0xFFB066E8.toInt(), 0xFFA4D007.toInt(),
                0xFF38BDF8.toInt(), 0xFFD4AF37.toInt(), 0xFF8B96BB.toInt()),
            startC1 = 0xFF75B022.toInt(), startC2 = 0xFF4F7A10.toInt(), startText = 0xFFFFFFFF.toInt(),
            pauseC1 = 0xFFB8860B.toInt(), pauseC2 = 0xFF8A650F.toInt(), pauseText = 0xFF2A2004.toInt(),
            stopC1 = 0xFFB03A2E.toInt(), stopC2 = 0xFF7B241C.toInt(), stopText = 0xFFFFFFFF.toInt(),
            cornerDp = 12f, headerGradient = true, headerStripes = false, headerDots = false,
            tapeCorners = false, flatButtons = false, logPrefix = "›", mono = false
        )
    }

    /* ---------- 尺寸工具 ---------- */

    private fun wm(): WindowManager =
        ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    /** Convert dp to px (density-independent sizing). */
    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    /** Float 精度 dp → px（细尺寸用）。 */
    private fun dpx(v: Float): Float = v * ctx.resources.displayMetrics.density

    private fun screenW(): Int = ctx.resources.displayMetrics.widthPixels
    private fun screenH(): Int = ctx.resources.displayMetrics.heightPixels

    private fun withAlpha(c: Int, a: Int): Int = (c and 0x00FFFFFF) or (a shl 24)

    /** 悬浮窗安全区（刘海/挖孔 Insets），横竖屏通用。 */
    private fun safeInsets(): IntArray {
        return try {
            val insets = wm().currentWindowMetrics.windowInsets
            val cutout = insets.displayCutout
            intArrayOf(
                cutout?.safeInsetTop ?: 0,
                cutout?.safeInsetBottom ?: 0,
                cutout?.safeInsetLeft ?: 0,
                cutout?.safeInsetRight ?: 0
            )
        } catch (e: Exception) {
            // 刘海信息拿不到不是致命问题：退化为"无刘海"继续渲染
            Log.w(TAG, "safeInsets unavailable, assuming none: " + e.message)
            intArrayOf(0, 0, 0, 0)
        }
    }

    /** 当前阶段对应的文案资源（暂停态优先覆盖）。服务端记录事件日志时复用同一判据。 */
    fun stageTextRes(): Int = when {
        ctx.state.paused -> R.string.float_paused_txt
        else -> when (ctx.state.stage) {
            Stage.CHECKING -> R.string.checking
            Stage.BUYING -> R.string.buying
            Stage.REFRESHING -> R.string.refreshing
            Stage.RESTING -> R.string.resting
            Stage.WAITING -> R.string.waiting_game
            else -> R.string.float_running
        }
    }

    /* ---------- 生命周期 ---------- */

    /**
     * 按当前状态决定"该不该有悬浮窗"，并保证幂等。
     *
     * 三种情况必须销毁已有窗口：开关被关、悬浮权限被撤销、任务未运行。
     * 否则会残留一个既不受开关控制、也无法再被销毁的"孤儿窗口"。
     */
    fun sync() {
        if (!cfg.floatyEnabled || !Settings.canDrawOverlays(ctx)) {
            destroy()
            return
        }
        if (!ctx.state.running) {
            destroy()
            return
        }
        // 主题联动：外观切换 → 整体重建悬浮窗（不是换色补丁）
        if (floatyRoot == null || floatyAppearance != cfg.appearance) {
            destroy()
            floatyAppearance = cfg.appearance
            show()
        }
        refreshContent()
    }

    /** 把 BotState / 事件日志渲染进胶囊与任务面板。 */
    fun refreshContent() {
        val spec = floatySpec()
        val stageText = ctx.getString(stageTextRes())
        val elapsed = if (ctx.state.startedAt > 0) System.currentTimeMillis() - ctx.state.startedAt else 0
        val timeTxt = RecordStore.fmtMs(elapsed)
        val hasError = ctx.state.lastError.isNotEmpty()
        // 实际参与运行的引擎（YOLO 模型未加载时平等切换到传统识图）
        val yoloPicked = cfg.ocrEngine == "yolo"
        val yoloLoaded = com.e7.shop.bot.YoloDet.loaded
        val engineKey = if (yoloPicked && yoloLoaded) "yolo" else "cv"

        // ---- 状态胶囊（收起态） ----
        capStatus?.text = stageText
        capStatus?.setTextColor(if (hasError) spec.warn else spec.text)
        capTime?.text = timeTxt
        capEngine?.text = ctx.getString(if (engineKey == "yolo") R.string.float_engine_yolo else R.string.float_engine_trad)
        capEngine?.setTextColor(spec.edge)
        (capDot?.background as? GradientDrawable)?.setColor(if (hasError) spec.warn else spec.run)
        capErr?.let { tv ->
            if (hasError) {
                tv.text = ctx.state.lastError
                tv.visibility = View.VISIBLE
            } else {
                tv.visibility = View.GONE
            }
        }
        capChev?.text = if (deckOpen) "▴" else "▾"

        // ---- 任务面板（展开态） ----
        if (deckStatVals.size >= 6) {
            deckStatVals[0].text = ctx.state.bookmarksGot.toString()
            deckStatVals[1].text = ctx.state.medalsGot.toString()
            deckStatVals[2].text = ctx.state.refreshes.toString()
            deckStatVals[3].text = ctx.state.skystonesSpent.toString()
            deckStatVals[4].text = RecordStore.fmtNum(ctx.state.goldSpent)
            deckStatVals[5].text = timeTxt
        }
        deckStage?.text = stageText
        deckStage?.setTextColor(if (hasError) spec.warn else spec.text)
        deckTime?.text = timeTxt
        (deckDot?.background as? GradientDrawable)?.setColor(if (hasError) spec.warn else spec.run)
        deckEngine?.text = ctx.getString(if (engineKey == "yolo") R.string.ocr_engine_yolo else R.string.ocr_engine_traditional)
        deckEngine?.setTextColor(spec.edge)
        deckModel?.let { tv ->
            if (yoloPicked) {
                tv.visibility = View.VISIBLE
                tv.text = ctx.getString(if (yoloLoaded) R.string.float_model_ok else R.string.float_model_na)
                tv.setTextColor(if (yoloLoaded) spec.run else spec.warn)
            } else {
                tv.visibility = View.GONE
            }
        }
        deckShot?.let { tv ->
            tv.text = if (ctx.state.shotOk) ctx.getString(R.string.float_shot_ok, ctx.state.shotCount)
            else ctx.getString(R.string.float_shot_fail)
            tv.setTextColor(if (ctx.state.shotOk) spec.run else spec.warn)
        }
        deckPause?.text = if (ctx.state.paused) ctx.getString(R.string.resume_btn) else ctx.getString(R.string.float_pause)
        deckErr?.let { tv ->
            if (hasError) {
                tv.text = ctx.state.lastError
                tv.visibility = View.VISIBLE
            } else {
                tv.visibility = View.GONE
            }
        }
        deckLog?.text = eventLog.takeLast(6).joinToString("\n") { spec.logPrefix + " " + it }
    }

    /* ---------- 背景绘制 ---------- */

    /** 主体玻璃背景 + 主题色描边（OLED 用平面细边，Steam/BA 带外发光）。 */
    private fun floatyBg(spec: FloatySpec, cornerPx: Int): Drawable {
        val inner = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = cornerPx.toFloat()
            setColor(spec.body)
            setStroke(2, withAlpha(spec.edge, 0x66))
        }
        if (spec.flatButtons) return inner
        val outer = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = (cornerPx + dp(4)).toFloat()
            setColor(Color.TRANSPARENT)
            setStroke(4, withAlpha(spec.edge, 0x28))
            setPadding(3, 3, 3, 3)
        }
        return LayerDrawable(arrayOf(outer, inner))
    }

    /** 任务横幅背景：渐变/纯色 + 可选条纹 + 可选网点，只圆上角。 */
    private fun headerBg(spec: FloatySpec, cornerPx: Int): Drawable {
        val layers = ArrayList<Drawable>()
        val base = if (spec.headerGradient) {
            GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(spec.headerTop, spec.headerBottom)
            )
        } else {
            GradientDrawable().apply { setColor(spec.headerTop) }
        }
        base.cornerRadii = floatArrayOf(
            cornerPx.toFloat(), cornerPx.toFloat(), cornerPx.toFloat(), cornerPx.toFloat(),
            0f, 0f, 0f, 0f
        )
        layers.add(base)
        if (spec.headerStripes) layers.add(StripeDrawable(0xFFFFFFFF.toInt(), 0x24))
        if (spec.headerDots) layers.add(DotDrawable(0xFFFFFFFF.toInt(), 0x2A))
        return LayerDrawable(layers.toTypedArray())
    }

    /** 横幅斜向条纹（BA）。 */
    private inner class StripeDrawable(private val color: Int, private val alpha: Int) : Drawable() {
        private val paint = Paint().apply {
            this.color = color
            this.alpha = alpha
            strokeWidth = dpx(6.5f)
            style = Paint.Style.STROKE
        }
        override fun draw(canvas: Canvas) {
            var x = -bounds.height().toFloat()
            while (x < bounds.width() + bounds.height()) {
                canvas.drawLine(x, bounds.height().toFloat(), x + bounds.height(), 0f, paint)
                x += dpx(26f)
            }
        }
        override fun setAlpha(a: Int) { /* 透明度由 paint.alpha 固定 */ }
        override fun setColorFilter(f: ColorFilter?) { /* 颜色由主题固定 */ }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /** 横幅网点半调（BA）。 */
    private inner class DotDrawable(private val color: Int, private val alpha: Int) : Drawable() {
        private val paint = Paint().apply {
            this.color = color
            this.alpha = alpha
        }
        override fun draw(canvas: Canvas) {
            val step = dpx(13f)
            var row = 0
            var y = step / 2f
            while (y < bounds.height()) {
                val off = if (row % 2 == 0) 0f else step / 2f
                var x = off + step / 2f
                while (x < bounds.width()) {
                    canvas.drawCircle(x, y, dpx(1.4f), paint)
                    x += step
                }
                y += step
                row++
            }
        }
        override fun setAlpha(a: Int) { /* 透明度由 paint.alpha 固定 */ }
        override fun setColorFilter(f: ColorFilter?) { /* 颜色由主题固定 */ }
        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    }

    /* ---------- 构建 ---------- */

    private fun show() {
        if (floatyRoot != null) return   // 重复悬浮窗防护（幂等）
        try {
            val spec = floatySpec()
            val ins = safeInsets()   // top,bottom,left,right
            val safeTop = ins[0]
            val sw = screenW()

            // 尺寸：胶囊宽度随屏幕自适应；面板宽度预留刘海/圆角安全区
            val capW = minOf(dp(290), (sw * 0.56f).toInt().coerceAtLeast(dp(190)))
            val cornerPx = dpx(spec.cornerDp).toInt()
            floatyCapW = capW

            val root = FrameLayout(ctx)
            val pp = WindowManager.LayoutParams(
                capW, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                x = dp(12)
                y = maxOf(dp(120), safeTop + dp(8))
            }
            wm().addView(root, pp)
            floatyRoot = root
            floatyParams = pp

            buildCapsule(root, spec, cornerPx)
            buildDeck(root, spec, cornerPx, pp, capW, ins[2], ins[3])

            startTicker()
            refreshContent()
        } catch (e: Exception) {
            // 悬浮窗失败不能影响机器人本体：记录后放弃显示
            Log.e(TAG, "floating panel failed to build", e)
        }
    }

    /** 状态胶囊（收起态）：状态点 · 阶段 · 时长 · 引擎徽标 · 箭头 + 错误行。 */
    private fun buildCapsule(root: FrameLayout, spec: FloatySpec, cornerPx: Int) {
        val cap = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(9), dp(6), dp(6), dp(6))
            background = floatyBg(spec, cornerPx)
        }
        val capRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        capDot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(spec.run)
            }
            capRow.addView(this, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(7) })
        }
        capStatus = TextView(ctx).apply {
            setTextColor(spec.text)
            textSize = 12f
            isSingleLine = true
            setTypeface(null, Typeface.BOLD)
            ellipsize = TextUtils.TruncateAt.END
            capRow.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        capTime = TextView(ctx).apply {
            setTextColor(spec.sub)
            textSize = 10f
            isSingleLine = true
            if (spec.mono) typeface = Typeface.MONOSPACE
            capRow.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { rightMargin = dp(4) })
        }
        capEngine = TextView(ctx).apply {
            setTextColor(spec.edge)
            textSize = 9f
            isSingleLine = true
            gravity = Gravity.CENTER
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpx(7f)
                setColor(withAlpha(spec.edge, 0x16))
                setStroke(1, withAlpha(spec.edge, 0x55))
            }
            capRow.addView(this, LinearLayout.LayoutParams(dp(36), dp(17)).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            })
        }
        capChev = TextView(ctx).apply {
            text = "▾"
            setTextColor(spec.sub)
            textSize = 11.5f
            gravity = Gravity.CENTER
            capRow.addView(this, LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        cap.addView(capRow)
        capErr = TextView(ctx).apply {
            visibility = View.GONE
            setTextColor(spec.warn)
            textSize = 9.5f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(16), 0, 0, 0)
            cap.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        root.addView(cap)
        capsuleView = cap
    }

    /** 任务面板（展开态）：横幅 / 状态行 / 引擎健康 / 统计 / 事件日志 / 控制。 */
    private fun buildDeck(
        root: FrameLayout, spec: FloatySpec, cornerPx: Int,
        pp: WindowManager.LayoutParams, capW: Int, safeLeft: Int, safeRight: Int
    ) {
        val deck = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = floatyBg(spec, cornerPx)
        }
        buildDeckHeader(deck, spec, cornerPx)

        val body = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        buildStatusRow(body, spec)
        buildHealthRow(body, spec)
        buildErrorBanner(body, spec)
        buildStatsGrid(body, spec)
        buildEventLog(body, spec)
        buildControls(body, spec)
        deck.addView(body)
        root.addView(deck)
        deckView = deck

        deckSafeLeft = safeLeft
        deckSafeRight = safeRight
        attachInteractions(root, deck, pp, capW)
    }

    private fun buildDeckHeader(deck: LinearLayout, spec: FloatySpec, cornerPx: Int) {
        val head = FrameLayout(ctx)
        head.background = headerBg(spec, cornerPx)
        val headRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(7), dp(7), dp(7))
        }
        headRow.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.float_deck_title)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 11.5f
            setTypeface(null, Typeface.BOLD)
            isSingleLine = true
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        headRow.addView(TextView(ctx).apply {
            text = "▴"
            setTextColor(0xCCFFFFFF.toInt())
            textSize = 12f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.MATCH_PARENT))
        head.addView(headRow)
        if (spec.tapeCorners) {
            head.addView(tapeView(0x73FF7FB6.toInt(), -12f), FrameLayout.LayoutParams(dp(34), dp(13)).apply {
                gravity = Gravity.TOP or Gravity.START
                leftMargin = dp(10); topMargin = dp(5)
            })
            head.addView(tapeView(0x4D1E88E5.toInt(), 12f), FrameLayout.LayoutParams(dp(34), dp(13)).apply {
                gravity = Gravity.TOP or Gravity.END
                rightMargin = dp(10); topMargin = dp(5)
            })
        }
        deck.addView(head)
    }

    /** BA 主题横幅的斜贴"胶带角"。 */
    private fun tapeView(color: Int, rotationDeg: Float): View = View(ctx).apply {
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dpx(3f)
            setColor(color)
        }
        rotation = rotationDeg
    }

    /** 状态行：状态点 + 阶段 + 运行时长。 */
    private fun buildStatusRow(body: LinearLayout, spec: FloatySpec) {
        val statRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        deckDot = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(spec.run)
            }
            statRow.addView(this, LinearLayout.LayoutParams(dp(9), dp(9)).apply { rightMargin = dp(6) })
        }
        deckStage = TextView(ctx).apply {
            setTextColor(spec.text)
            textSize = 12.5f
            setTypeface(null, Typeface.BOLD)
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            statRow.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        deckTime = TextView(ctx).apply {
            setTextColor(spec.sub)
            textSize = 10f
            isSingleLine = true
            if (spec.mono) typeface = Typeface.MONOSPACE
            statRow.addView(this)
        }
        body.addView(statRow)
    }

    /** 引擎 + 模型 + 截图健康行。 */
    private fun buildHealthRow(body: LinearLayout, spec: FloatySpec) {
        val healthRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        deckEngine = TextView(ctx).apply {
            setTextColor(spec.edge)
            textSize = 9.5f
            setTypeface(null, Typeface.BOLD)
            isSingleLine = true
            healthRow.addView(this)
        }
        deckModel = TextView(ctx).apply {
            setTextColor(spec.sub)
            textSize = 9f
            isSingleLine = true
            setPadding(dp(8), 0, 0, 0)
            healthRow.addView(this)
        }
        deckShot = TextView(ctx).apply {
            setTextColor(spec.run)
            textSize = 9f
            isSingleLine = true
            healthRow.addView(this, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = dp(8)
            })
        }
        body.addView(healthRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
    }

    private fun buildErrorBanner(body: LinearLayout, spec: FloatySpec) {
        deckErr = TextView(ctx).apply {
            visibility = View.GONE
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 10f
            setPadding(dp(6), dp(4), dp(6), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpx(7f)
                setColor(spec.warn)
            }
            body.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }
    }

    /** 统计区：2×3 网格（值 + 短标签）。 */
    private fun buildStatsGrid(body: LinearLayout, spec: FloatySpec) {
        val statsGrid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val statDefs = listOf(
            spec.statColors[0] to R.string.float_lbl_bookmark,
            spec.statColors[1] to R.string.float_lbl_medal,
            spec.statColors[2] to R.string.float_lbl_refresh,
            spec.statColors[3] to R.string.float_lbl_sky,
            spec.statColors[4] to R.string.float_lbl_gold,
            spec.statColors[5] to R.string.float_lbl_time
        )
        deckStatVals.clear()
        statDefs.chunked(3).forEach { rowDefs ->
            val r = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
            }
            for ((colorHex, labelRes) in rowDefs) {
                r.addView(statCell(colorHex, labelRes, spec), LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
                ).apply { leftMargin = dp(2); rightMargin = dp(2) })
            }
            statsGrid.addView(r)
        }
        body.addView(statsGrid, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) })
    }

    private fun statCell(colorHex: Int, labelRes: Int, spec: FloatySpec): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(2), dp(4), dp(2), dp(4))
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpx(8f)
                setColor(withAlpha(spec.edge, 0x12))
                setStroke(1, withAlpha(spec.edge, 0x2E))
            }
            addView(TextView(ctx).apply {
                text = "0"
                setTextColor(colorHex)
                textSize = 12.5f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                isSingleLine = true
                if (spec.mono) typeface = Typeface.MONOSPACE
                deckStatVals.add(this)
            })
            addView(TextView(ctx).apply {
                text = ctx.getString(labelRes)
                setTextColor(spec.sub)
                textSize = 8.5f
                gravity = Gravity.CENTER
                isSingleLine = true
            })
        }

    /** 事件日志（滚动条带，最新在底部）。 */
    private fun buildEventLog(body: LinearLayout, spec: FloatySpec) {
        body.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.float_log_title)
            setTextColor(spec.sub)
            textSize = 8.5f
            setTypeface(null, Typeface.BOLD)
            isSingleLine = true
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
        deckLog = TextView(ctx).apply {
            setTextColor(spec.text)
            textSize = 9f
            if (spec.mono) typeface = Typeface.MONOSPACE
            setPadding(dp(6), dp(4), dp(6), dp(4))
            maxLines = 6
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpx(8f)
                setColor(withAlpha(0xFF000000.toInt(), if (spec.body == 0xF2FFFFFF.toInt()) 0x0C else 0x30))
            }
            body.addView(this, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(4) })
        }
    }

    /** 控制区：主按钮（开始 = 重启语义）+ 暂停/停止。 */
    private fun buildControls(body: LinearLayout, spec: FloatySpec) {
        body.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.float_start)
            setTextColor(spec.startText)
            textSize = 12.5f
            gravity = Gravity.CENTER
            isSingleLine = true
            setTypeface(null, Typeface.BOLD)
            background = ctrlBg(spec, spec.startC1, spec.startC2)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener {
                mainHandler.post {
                    val msg = onStart()
                    if (msg != "ok") toast(msg)
                }
                collapse()
            }
        }, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(32)
        ).apply { topMargin = dp(9) })

        val secRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        deckPause = TextView(ctx).apply {
            text = ctx.getString(R.string.float_pause)
            setTextColor(spec.pauseText)
            textSize = 11f
            gravity = Gravity.CENTER
            isSingleLine = true
            setTypeface(null, Typeface.BOLD)
            background = ctrlBg(spec, spec.pauseC1, spec.pauseC2)
            setOnClickListener {
                onTogglePause()
                collapse()
            }
            secRow.addView(this, LinearLayout.LayoutParams(0, dp(28), 1f).apply { rightMargin = dp(4) })
        }
        secRow.addView(TextView(ctx).apply {
            text = ctx.getString(R.string.float_stop)
            setTextColor(spec.stopText)
            textSize = 11f
            gravity = Gravity.CENTER
            isSingleLine = true
            setTypeface(null, Typeface.BOLD)
            background = ctrlBg(spec, spec.stopC1, spec.stopC2)
            setOnClickListener {
                onStop()
                collapse()
            }
        }, LinearLayout.LayoutParams(0, dp(28), 1f).apply { leftMargin = dp(4) })
        body.addView(secRow, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(8) })
    }

    private fun ctrlBg(spec: FloatySpec, c1: Int, c2: Int): GradientDrawable =
        if (spec.flatButtons) {
            GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpx(9f)
                setColor(c1)
                setStroke(1, withAlpha(spec.edge, 0x55))
            }
        } else {
            GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(c1, c2)).apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dpx(9f)
            }
        }

    /* ---------- 交互（拖拽 / 展开收起） ---------- */

    private fun attachInteractions(
        root: FrameLayout, deck: LinearLayout, pp: WindowManager.LayoutParams, capW: Int
    ) {
        // 拖拽（胶囊任意处 / 面板横幅）+ 点按展开收起
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var dragged = false
        fun attachDrag(v: View, tap: () -> Unit) {
            v.setOnTouchListener { _, ev ->
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = ev.rawX; downY = ev.rawY; startX = pp.x; startY = pp.y; dragged = false; true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = ev.rawX - downX; val dy = ev.rawY - downY
                        if (kotlin.math.abs(dx) > DRAG_SLOP_PX || kotlin.math.abs(dy) > DRAG_SLOP_PX) dragged = true
                        if (dragged) {
                            val vw = root.width.coerceAtLeast(capW)
                            val vh = root.height.coerceAtLeast(dp(40))
                            // 完全自由拖动：不做边界限制，窗口可拖到任意位置（含完全移出屏幕）。
                            // 若拖出屏幕后想找回，可长按胶囊重置位置。
                            pp.x = startX - dx.toInt()
                            pp.y = startY + dy.toInt()
                            if (System.currentTimeMillis() - lastDragLog > 1000) {
                                lastDragLog = System.currentTimeMillis()
                                Log.i(TAG, "drag x=${pp.x} y=${pp.y} vw=$vw vh=$vh")
                            }
                            applyLayout(root, pp)
                        }
                        true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!dragged) tap()
                        true
                    }
                    else -> false
                }
            }
        }
        // 长按胶囊 = 重置悬浮窗位置到屏幕内。
        // 完全自由拖动后窗口可能被拖出屏幕再也点不到，这是唯一的"救回"方式；
        // 另外 App 内「我的 → 悬浮窗」开关一次也会重建窗口并回到默认位置。
        capsuleView?.setOnLongClickListener {
            try {
                pp.x = (screenW() - floatyCapW) / 2
                pp.y = (screenH() * 0.72f).toInt()
                applyLayout(root, pp)
                Log.i(TAG, "reset position x=${pp.x} y=${pp.y}")
                toast(ctx.getString(R.string.floaty_reset_pos))
            } catch (e: Exception) {
                Log.w(TAG, "reset position failed: " + e.message)
            }
            true
        }
        capsuleView?.let { attachDrag(it) { toggle() } }
        // 面板横幅点击 = 收起
        deck.getChildAt(0)?.let { attachDrag(it) { collapse() } }
    }

    /** 展开/收起任务面板：窗口宽度在胶囊宽与面板宽之间切换（右缘锚定）。 */
    private fun toggle() {
        val deck = deckView ?: return
        val root = floatyRoot ?: return
        val pp = floatyParams ?: return
        val show = !deckOpen
        deckOpen = show
        deckAnim?.cancel()
        val deckW = minOf(dp(300), (screenW() - deckSafeLeft - deckSafeRight - dp(20)).coerceAtLeast(dp(210)))
        pp.width = if (show) deckW else floatyCapW
        if (show) {
            deck.scaleX = 0.92f; deck.scaleY = 0.92f; deck.alpha = 0f
            deck.visibility = View.VISIBLE
        }
        applyLayout(root, pp)
        // 完全自由拖动：展开/收起不再强制把窗口拉回屏幕内（玩家要求任意位置）。
        deckAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 170
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                if (show) {
                    deck.scaleX = 0.92f + 0.08f * f; deck.scaleY = 0.92f + 0.08f * f; deck.alpha = f
                } else {
                    deck.scaleX = 1f - 0.08f * f; deck.scaleY = 1f - 0.08f * f; deck.alpha = 1f - f
                }
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!show) {
                        deck.visibility = View.GONE
                        applyLayout(root, pp)
                    }
                }
            })
            start()
        }
        refreshContent()
    }

    private fun collapse() {
        if (deckOpen && deckView != null) toggle()
    }

    /**
     * 应用窗口布局参数。
     *
     * 窗口可能在拖动过程中被系统移除（权限撤销 / 进程回收），
     * 此时 updateViewLayout 会抛异常——记录后忽略，不影响机器人运行。
     */
    private fun applyLayout(root: FrameLayout, pp: WindowManager.LayoutParams) {
        try {
            wm().updateViewLayout(root, pp)
        } catch (e: Exception) {
            Log.w(TAG, "updateViewLayout failed: " + e.message)
        }
    }

    /* ---------- 销毁 ---------- */

    fun destroy() {
        mainHandler.removeCallbacks(ticker)
        try {
            deckAnim?.cancel()
            floatyRoot?.let { wm().removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "removeView failed: " + e.message)
        }
        floatyRoot = null; floatyParams = null; floatyCapW = 0
        capsuleView = null; deckView = null
        capStatus = null; capTime = null; capDot = null; capEngine = null; capErr = null; capChev = null
        deckStage = null; deckTime = null; deckDot = null; deckEngine = null; deckModel = null
        deckShot = null; deckErr = null; deckLog = null; deckPause = null
        deckStatVals.clear()
        deckOpen = false
        deckAnim = null
        floatyAppearance = ""
    }

    private fun toast(msg: String) {
        Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val TAG = "E7SA.Floaty"

        /** 事件日志最多保留多少条（超出丢最旧的）。 */
        const val MAX_EVENT_LOG = 24

        /** 判定"这是拖拽而不是点按"的位移阈值（px）。 */
        const val DRAG_SLOP_PX = 14f
    }
}
