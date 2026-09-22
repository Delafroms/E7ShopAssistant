package com.e7.shop.bot

import android.graphics.Bitmap
import android.graphics.Color

/**
 * V1 识别体系：统一 Recognition Interface，两个平等地位的引擎 Provider。
 *
 *   Screenshot
 *      ↓
 *   RecognitionEngine（统一接口）
 *      ├── YoloEngine        神经图标检测 + 全帧 OCR 语义
 *      └── TraditionalEngine 经典 CV 行结构/颜色签名 + 同构 OCR 语义
 *      ↓
 *   DetectionResult（scene + candidates{kind,bbox,conf,evidence[]} + lines）
 *      ↓
 *   Decision Layer（BotEngine，与识别完全解耦，绝不直接控制购买）
 *      ↓
 *   Operation Layer
 *
 * 引擎差异仅在"目标候选来源"：YOLO=模型图标框；传统=投影行结构+颜色签名。
 * 场景语义/文本验证/按钮定位是两引擎共享的 OCR 基础设施（每帧 1 次 OCR）。
 * 每个候选携带 evidence 链，识别错误时可回答"为什么认为是这个商品"。
 */

/**
 * native 识别失败的限流日志（跨引擎共用）。
 *
 * 为什么必须记：旧版把 OCR/YOLO 的异常静默吞成 emptyList()，导致「native 崩了」
 * 和「画面本来就没有文字」在上层完全不可区分，日志里也没有任何痕迹
 * —— 而排查漏买时最想知道的恰恰是这一条。
 *
 * 为什么限流：失败往往持续发生（每帧一次），不限流会瞬间刷爆日志文件。
 * 只在第 1 次与每 50 次各记一条。
 */
private val ocrFailCount = java.util.concurrent.atomic.AtomicInteger(0)
private val yoloFailCount = java.util.concurrent.atomic.AtomicInteger(0)

private fun notePerceptionFailure(
    kind: String,
    counter: java.util.concurrent.atomic.AtomicInteger,
    e: Throwable
) {
    val n = counter.incrementAndGet()
    if (n == 1 || n % 50 == 0) {
        android.util.Log.e("E7SA.Percep", "$kind failed x$n: ${e.javaClass.simpleName}: ${e.message}")
    }
}

enum class Scene { SHOP_LIST, REFRESH_DLG, BUY_DLG, NET_ERROR, OTHER }

/** 一个目标候选（商品行），含可解释证据链。 */
data class Candidate(
    val kind: String,          // "bookmark" | "medal"
    val cx: Float,
    val cy: Float,             // bbox/行中心（原始像素）
    val conf: Float,           // 0..1 置信度
    val tol: Float,            // 行配对容差（行高推导）
    val evidence: List<String> // 证据链：来源、命中、拒绝原因
) {
    val rowY: Int get() = cy.toInt()
}

/** 统一识别输出（两引擎同构）。 */
data class DetectionResult(
    val scene: Scene,
    val lines: List<PpOcr.OcrLine>,
    val candidates: List<Candidate>,
    val yoloBoxes: List<YoloDet.Box>,   // 传统引擎为空
    val engine: String,
    val diag: String                    // 一句话诊断摘要
)

/** 统一识别接口：识别"看见什么"，绝不产生点击、绝不控制购买。 */
interface RecognitionEngine {
    val id: String
    fun analyze(bmp: Bitmap): DetectionResult

    /**
     * 睡眠模式开关（由引擎在会话启动时按配置设置）。
     *
     * 打开后 [hasIconFast] 一律返回 true，强制调用方走完整识别 —— 用速度换可靠性。
     * 挂机过夜时开启：不在乎慢，只怕漏看整屏。
     */
    var sleepMode: Boolean

    /**
     * 快速探测：**只回答"这一屏有没有书签/奖牌图标"**，跳过 OCR。
     *
     * 用途：滑动后确认"第 6 格有没有目标"这类只需知道"有没有"的场景。
     * 完整 analyze() 里 OCR 约占 390ms（总 450ms），而这个问题 YOLO 一个人就能答
     * —— 图标检测正是它的本职。省下 OCR 后单次探测约 45ms。
     *
     * 契约：
     *  · 返回 true 只表示"可能有目标"，**不保证**能买到（真正的购买仍走完整流程 + 三重验证）；
     *  · 返回 false 表示"没看见图标"，调用方可据此跳过本轮；
     *  · 默认实现退化为完整识别（传统引擎没有纯图标检测能力），保证行为不变。
     */
    fun hasIconFast(bmp: Bitmap): Boolean = analyze(bmp).candidates.isNotEmpty()
}

/* ================= 共享语义层（场景/按钮/验证，两引擎共用） ================= */

internal val CONFIRM_KW = listOf("确认", "确定", "確认", "確認", "ok", "confirm", "はい")
internal val CANCEL_KW = listOf("取消", "cancel", "キャンセル")

/**
 * 网络异常弹窗（2026-09-19 新增，依据用户提供的实机截图）。
 *
 * 实测样本：正文「网络连接异常，请重新连接。」+ 按钮「点击重试」。
 * **为什么用 OCR 而不是训练模型**：这是纯文本弹窗，OCR 一次就读到、且是精确匹配；
 * 训一个 YOLO 类需要几十张标注样本 + 重训 + 重导模型，收益完全不对等。
 * 项目里另外两个弹窗（购买 / 刷新）本来就是 OCR 关键词判定的，这里保持同一套机制。
 */
internal val NET_ERR_KW = listOf("网络连接异常", "请重新连接")

/**
 * 网络异常弹窗上的重试按钮文字。
 *
 * ⚠ **只能放完整短语**（2026-09-19 事故）：原先这里还有裸的 `重试`，
 * 而 App 自己的错误文案「网络异常弹窗 → 已点击重试」就显示在悬浮窗上、会被 OCR 读到
 * → 命中关键词 → 又判成网络弹窗 → 又点重试 → **自己触发自己、无限循环**，
 * 而且点在悬浮窗自己的文字上（实测乱点到了系统控制中心与桌面）。
 * 现在只认「点击重试」；读不到就退化为点弹窗中心。
 */
internal val RETRY_KW = listOf("点击重试")

/**
 * 商人对话面板的特征台词（2026-09-22 真机事故驱动）。
 *
 * 面板打开时里面会显示商品名（实测出现「圣约书签」），OCR 把它当成商品行候选 →
 * 生成假候选（cy≈200，实际是面板文字，不是列表行）；配合当时的容差放大就点到了
 * 非目标商品（熔岩陆龟）的购买按钮。面板是覆盖层，检测到台词时本帧**只信 YOLO 图标框**。
 */
internal val MERCHANT_TALK_KW = listOf("这里应有尽有", "说说看", "你想买什么", "欢迎光临")
internal val BUY_KW = listOf("购买", "購買", "buy", "purchase", "購入")
private val BUY_TITLE_KW = listOf("购买商品", "購買商品", "确定要购买", "確定要購買", "是否购买", "是否購買", "confirm purchase")

/**
 * 「可购买 N 次」这类**商品信息**文本（含"购买"二字，但不是按钮）。
 *
 * 2026-09-22 修复：按钮定位的语义锚点原先只按 [BUY_KW] 过滤，会命中它 ——
 * 于是色块窗口锚在商品名区域，质心落到非按钮位置（真机表现：点击落空 + 12 秒 DIALOG TIMEOUT）。
 */
internal val BUY_COUNT_KW = listOf("可购买", "可購買", "剩余可购买")
internal val REFRESH_BTN_KW = listOf("立即更新", "refresh", "更新")
internal val SOLD_KW = listOf(
    "售罄", "缺货", "缺貨", "sold", "已售", "售空", "售完", "已售完", "soldout", "品切れ"
)
private val ITEM_BOOKMARK_KW = listOf("书签", "书簽", "書籤")
private val ITEM_MEDAL_KW = listOf("奖牌", "獎牌")

internal fun norm(s: String): String = s.replace(" ", "").lowercase()
internal fun hasAny(text: String, kws: Collection<String>): Boolean {
    val t = norm(text)
    return kws.any { t.contains(norm(it)) }
}
internal fun hasAll(text: String, kws: Collection<String>): Boolean {
    val t = norm(text)
    return kws.all { t.contains(norm(it)) }
}
internal fun itemKind(text: String): String? {
    val t = norm(text)
    return when {
        ITEM_BOOKMARK_KW.any { t.contains(norm(it)) } -> "bookmark"
        ITEM_MEDAL_KW.any { t.contains(norm(it)) } -> "medal"
        else -> null
    }
}
/**
 * 图标类映射：只认 bookmark / medal（商品候选）。
 *
 * 多分类模型里还有 5 个按钮类，它们**不是**商品候选 —— 返回 null 让调用方跳过。
 * 旧版写法 `if (cls == 0) "bookmark" else "medal"` 会把 cls=2..6 的按钮全部
 * 误判成奖牌，等于凭空造出假候选。
 */
internal fun yoloKind(b: YoloDet.Box): String? = when (b.clsName) {
    "bookmark" -> "bookmark"
    "medal" -> "medal"
    else -> null
}

private fun flagsOf(lines: List<PpOcr.OcrLine>): BooleanArray = booleanArrayOf(
    lines.any { hasAny(it.text, CONFIRM_KW) },
    lines.any { hasAny(it.text, CANCEL_KW) },
    lines.any { hasAny(it.text, BUY_KW) },
    lines.any { hasAny(it.text, BUY_TITLE_KW) },
    lines.any { hasAll(it.text, listOf("更新", "天空石")) },
    lines.any { hasAny(it.text, REFRESH_BTN_KW) }
)

/** 场景判定（纯 OCR 文本语义，模型输出推导，无固定坐标）。 */
fun sceneOf(lines: List<PpOcr.OcrLine>): Scene {
    val f = flagsOf(lines)
    return when {
        // ⚠ 网络异常弹窗**必须最先判定**（2026-09-19）：它是盖在商店列表上的模态窗，
        // 列表的「立即更新」等文字仍会被 OCR 读到 —— 若不先判，整屏会被当成正常
        // SHOP_LIST，然后卡在「「立即更新」按钮无法视觉确认（彩色占比 0.00）」
        // 反复 undecided（实测日志 01:32:52 就是这么卡的）。
        lines.any { hasAny(it.text, NET_ERR_KW) } -> Scene.NET_ERROR
        f[4] && f[0] -> Scene.REFRESH_DLG
        f[3] || (f[1] && f[2]) -> Scene.BUY_DLG
        f[5] -> Scene.SHOP_LIST
        // ⚠ 漏买修复（2026-09-18，89 张真实截图基准暴露的真实漏买路径）：
        //
        // 商店列表原先**只认「立即更新」按钮文本**。一旦该按钮被遮挡、滚出画面
        // 或 OCR 漏读，整屏就被判成 OTHER → 决策层直接 WAIT →
        // **识别层明明检出了奖牌，也一个都不会买**（漏买且无任何日志痕迹）。
        // 实测样本：某张图 gt=2 且检出 2 个正确候选，却因 scene=OTHER 全程不动。
        //
        // 现在补一条**并列证据**：读到商品名（奖牌/书签）**且**存在「购买」按钮。
        // 后者是关键约束——App 自身界面的统计区也写着"神秘奖牌"，但那里没有购买键，
        // 因此不会被误判成商店列表而触发误买。
        lines.any { itemKind(it.text) != null } && f[2] -> Scene.SHOP_LIST
        else -> Scene.OTHER
    }
}

/* ---- 按钮定位（点击点 = OCR bbox 中心，决策层使用） ---- */

fun refreshButton(lines: List<PpOcr.OcrLine>): Pair<Float, Float>? =
    lines.filter { hasAny(it.text, REFRESH_BTN_KW) && !hasAll(it.text, listOf("更新", "天空石")) }
        .maxByOrNull { it.cy }?.let { it.cx to it.cy }

fun refreshConfirm(lines: List<PpOcr.OcrLine>): Pair<Float, Float>? =
    lines.filter { hasAny(it.text, CONFIRM_KW) }
        .maxByOrNull { it.cx }?.let { it.cx to it.cy }

fun dialogCancel(lines: List<PpOcr.OcrLine>): Pair<Float, Float>? =
    lines.filter { hasAny(it.text, CANCEL_KW) }
        .minByOrNull { it.cx }?.let { it.cx to it.cy }

fun dialogBuy(lines: List<PpOcr.OcrLine>, imgH: Int): Pair<Float, Float>? {
    val cancel = dialogCancel(lines) ?: return null
    val buys = lines.filter {
        hasAny(it.text, BUY_KW) && !hasAny(it.text, BUY_TITLE_KW) && it.cx > cancel.first
    }
    val best = buys.minByOrNull { kotlin.math.abs(it.cy - cancel.second) } ?: return null
    if (kotlin.math.abs(best.cy - cancel.second) > imgH * Tuning.DIALOG_CANCEL_MAX_OFFSET) return null
    return best.cx to best.cy
}

/**
 * 从「购买」按钮文本的 y 间距推导**行距**（商店最可靠的结构骨架：每行一个购买键、垂直等距）。
 *
 * 为什么必须用行距而不是图标高度：行容差只要 ≥ 行距，相邻行就会互相污染 ——
 * 实测踩过的活锁 BUG：YOLO 候选的 tol 取"图标高度×2"（≈300px），而行距只有 ≈260px，
 * 于是当目标行自己的"购买"文本没被 OCR 读到（刚刷出的商品按钮还在渐显）时，
 * `rowBuyPoint` 会跨行点到**相邻行**的购买键 —— 表现为"奖牌在第 2 栏却点第 1 栏，
 * 弹窗验证失败后取消、再重试，反复循环"。
 */
internal fun rowPitch(lines: List<PpOcr.OcrLine>, imgH: Int): Float {
    val ys = lines
        .filter { hasAny(it.text, BUY_KW) && !hasAny(it.text, BUY_TITLE_KW) }
        .map { it.cy }
        .sorted()
    // 过滤噪声间距（同一按钮被 OCR 拆成两段时会出现很小的 gap）
    val gaps = ys.zipWithNext { a, b -> b - a }.filter { it > imgH * Tuning.ROW_GAP_MIN }
    // 取**最小**间距而不是中位数：OCR 漏读一行时中位数会翻倍（260 → 520），
    // 而最小值仍是真实行距 —— 容差宁可偏小（够不着就不点），绝不能大到跨行。
    val raw = if (gaps.isNotEmpty()) gaps.min() else imgH * Tuning.ROW_PITCH_FALLBACK
    // 夹在合理区间：下限保证"找得到按钮"（太小会漏买），上限保证"不会跨行"（太大会点错行）
    return raw.coerceIn(imgH * Tuning.ROW_PITCH_MIN, imgH * Tuning.ROW_PITCH_MAX)
}

/** 行容差 = 行距的一半（略收一点，避免边界行互相干扰）。 */
internal fun rowTol(lines: List<PpOcr.OcrLine>, imgH: Int): Float =
    rowPitch(lines, imgH) * Tuning.ROW_TOL_FROM_PITCH

/**
 * 弹窗里的商品名类型（bookmark / medal），读不到返回 null。
 *
 * 用途：区分「点错了行」与「OCR 暂时读不到」——
 * 前者重试毫无意义（要立即放弃该行止损），后者是暂时性失败（该走重试预算）。
 * 只凭 dialogConfirmed=false 就一律放弃，会把"价格 OCR 抖动"误判成点错行而漏买。
 */
fun dialogItemKind(lines: List<PpOcr.OcrLine>): String? =
    lines.mapNotNull { itemKind(it.text) }.firstOrNull()

fun rowBuyPoint(lines: List<PpOcr.OcrLine>, cy: Float, tol: Float, cx: Float): Pair<Float, Float>? {
    val buys = lines.filter {
        hasAny(it.text, BUY_KW) && !hasAny(it.text, BUY_TITLE_KW) && it.cx > cx
    }
    // 取**全局最近**的购买键，再检查它是否落在本行容差内。
    // 旧版先按 tol 过滤再取最近：tol 过大时（见 rowPitch 的说明）会把相邻行的键
    // 也算进来，于是点错行。这里改成"够不着就不点"（fail-closed），绝不跨行。
    val best = buys.minByOrNull { kotlin.math.abs(it.cy - cy) } ?: return null
    if (kotlin.math.abs(best.cy - cy) >= tol) return null
    return best.cx to best.cy
}

/* ---- 验证门控（防误买：宁可漏买，不可误买；识别失败 → 不操作） ---- */

const val ICON_RATIO_MIN = Tuning.ICON_RATIO_MIN

/** 图标列颜色签名占比（0..1）。区域锚定商品名文本中心左侧，分辨率无关。 */
fun iconColorRatio(bmp: Bitmap, cx: Float, cy: Float, kind: String, wide: Boolean = false): Float {
    return try {
        val w = bmp.width
        val h = bmp.height
        // 主窗：锚定文本左侧（等比缩放下自适应）。
        // 宽窗（wide）：验证失败时的二次观测（P5「先增加观测再放弃」）——
        // 非等比布局（平板 / 超宽屏 / 折叠屏）下图标可能偏出主窗，
        // 此时放宽搜索范围再试一次，而不是直接判为"行验证失败 → 漏买"。
        val x1f = if (wide) Tuning.ICON_WIN_WIDE_X1 else Tuning.ICON_WIN_NARROW_X1
        val x2f = if (wide) Tuning.ICON_WIN_WIDE_X2 else Tuning.ICON_WIN_NARROW_X2
        val yf = if (wide) Tuning.ICON_WIN_WIDE_Y else Tuning.ICON_WIN_NARROW_Y
        val x1 = (cx - w * x1f).toInt().coerceAtLeast(0)
        val x2 = (cx - w * x2f).toInt().coerceAtMost(w)
        val y1 = (cy - h * yf).toInt().coerceAtLeast(0)
        val y2 = (cy + h * yf).toInt().coerceAtMost(h)
        if (x2 <= x1 || y2 <= y1) return 0f
        var hit = 0
        var total = 0
        var y = y1
        while (y < y2) {
            var x = x1
            while (x < x2) {
                val p = bmp.getPixel(x, y)
                val r = Color.red(p)
                val g = Color.green(p)
                val b = Color.blue(p)
                val ok = if (kind == "bookmark") {
                    (b - r > 40 && b - g > 15) || (r > 170 && g > 130 && b < 120)
                } else {
                    (r > 120 && b - r > 15 && g < 110) || (r > 160 && g < 90 && b < 90)
                }
                if (ok) hit++
                total++
                x += 3
            }
            y += 3
        }
        if (total == 0) 0f else hit.toFloat() / total
    } catch (e: Exception) { 0f }
}

/** 商品行验证（S1）：该行文本含目标商品名且无"售罄"；图标旁证=引擎图标候选或颜色签名。 */
fun rowConfirmed(r: DetectionResult, bmp: Bitmap, c: Candidate): Boolean {
    val near = r.lines.filter { kotlin.math.abs(it.cy - c.cy) < c.tol }
    val textOk = near.any { itemKind(it.text) == c.kind }
    val soldOut = near.any { hasAny(it.text, SOLD_KW) }
    if (!textOk || soldOut) return false
    // 容差只用候选自带的行容差（由行距推导）。旧版是 max(b.h*2, c.tol)，
    // 而 b.h*2 ≈ 300px 已超过商店行距（≈260px），会让**相邻行**的图标把本行"确认"掉。
    val yoloOk = r.yoloBoxes.any { b ->
        yoloKind(b) == c.kind && kotlin.math.abs(b.cy - c.cy) < c.tol
    }
    // 性能：有 YOLO 框即可确认，**直接返回、跳过昂贵的颜色扫描**。
    // 旧版把 colorOk 先算成 val 再 `yoloOk || colorOk`，短路根本没生效——
    // 每行都白跑一遍逐像素扫描（YOLO 引擎路径下尤其浪费，占单帧耗时很大一块）。
    if (yoloOk) return true
    // 无 YOLO 框（传统引擎）才走颜色签名：窄窗失败再用宽窗二次观测（P5）
    return near.filter { itemKind(it.text) == c.kind }
        .any {
            iconColorRatio(bmp, it.cx, it.cy, c.kind) >= ICON_RATIO_MIN ||
                iconColorRatio(bmp, it.cx, it.cy, c.kind, wide = true) >= ICON_RATIO_MIN
        }
}

/** 目标行是否已售空（售空/售罄等关键词）。用于「已售空 → 放弃该行」判断。 */
internal fun rowSoldOut(r: DetectionResult, cy: Float, tol: Float): Boolean =
    r.lines.any { kotlin.math.abs(it.cy - cy) < tol && hasAny(it.text, SOLD_KW) }

/**
 * 画面语义指纹（V1.1 刷新验证的核心）：由「候选（种类+行位）+ 文本行」推导，
 * 用于判断**列表内容是否真的变了**。
 *
 * 为什么需要它：旧版只看"连续两帧像素稳定"，无法区分「刷新完成」与「旧列表静止」——
 * 网络慢时旧列表原封不动也会被判为"已稳定"，于是提前下滑、跳过第一格。
 * 正确顺序是：先证明 fingerprint 变了，再证明它稳定了。
 */
fun frameFingerprint(r: DetectionResult): String {
    // C1 埋点：每帧都要遍历全部文本行做哈希，是被怀疑的 CPU 小头之一
    val t0 = System.currentTimeMillis()
    val sb = StringBuilder()
    for (c in r.candidates.sortedBy { it.cy }) {
        sb.append(c.kind).append('@').append(c.rowY / 12).append(';')
    }
    sb.append('|')
    for (l in r.lines.sortedBy { it.cy }) {
        sb.append(norm(l.text)).append(',')
    }
    com.e7.shop.device.Profiler.record("fingerprint", System.currentTimeMillis() - t0)
    return sb.toString().hashCode().toString()
}

/**
 * didScroll 的**复用缓冲**（2026-09-18 修复）。
 *
 * 旧版每次调用都分配 2×IntArray(regW×regH)：2800×1272 上采样窗约 1820×1195，
 * 单个数组 2.17M int ≈ 8.7MB，一次调用 17.4MB。而刷新等待循环每帧调一次
 * （framesFor(18000) 最多 40 帧）→ 单次等待就有数百 MB 的分配压力，
 * 且 `catch (Exception)` 捕不到 OutOfMemoryError（它是 Error）→ 进程被杀。
 *
 * 按线程持有：bot 线程每轮新建、结束后随之释放，既不跨会话泄漏，也不需要锁。
 */
private class ScrollBuffers {
    private var w = -1
    private var h = -1
    var a: IntArray = IntArray(0)
        private set
    var b: IntArray = IntArray(0)
        private set

    fun ensure(width: Int, height: Int) {
        if (width == w && height == h) return
        w = width
        h = height
        a = IntArray(width * height)
        b = IntArray(width * height)
    }
}

// 匿名子类提供 initialValue()，get() 因此永远不会返回 null；
// 后面仍写 ?: 兜底，是为了消掉 Kotlin 对 ThreadLocal.get() 的可空推断警告。
private val scrollBuffers = object : ThreadLocal<ScrollBuffers>() {
    override fun initialValue(): ScrollBuffers = ScrollBuffers()
}

/** 列表是否真的滚动了（截图对比，仅验证手势，与点击无关；两套点击逻辑共用）。 */
fun didScroll(a: Bitmap, b: Bitmap): Boolean {
    return try {
        val w = a.width
        val h = a.height
        if (b.width != w || b.height != h) return false
        val x1 = (w * Tuning.SHOP_SCAN_X1).toInt()
        val x2 = (w * Tuning.SHOP_SCAN_X2).toInt().coerceAtMost(w - 1)
        val y1 = (h * Tuning.SHOP_SCAN_Y1).toInt()
        val y2 = (h * Tuning.SHOP_SCAN_Y2).toInt().coerceAtMost(h - 1)
        if (x2 <= x1 || y2 <= y1) return false
        val regW = x2 - x1
        val regH = y2 - y1
        val bufs = scrollBuffers.get() ?: ScrollBuffers()
        bufs.ensure(regW, regH)
        val pa = bufs.a
        val pb = bufs.b
        a.getPixels(pa, 0, regW, x1, y1, regW, regH)
        b.getPixels(pb, 0, regW, x1, y1, regW, regH)
        var diff = 0
        var total = 0
        for (y in 0 until regH step 6) {
            val rowOff = y * regW
            for (x in 0 until regW step 6) {
                val i = rowOff + x
                val p1 = pa[i]
                val p2 = pb[i]
                val d = kotlin.math.abs((p1 shr 16 and 0xFF) - (p2 shr 16 and 0xFF)) +
                    kotlin.math.abs((p1 shr 8 and 0xFF) - (p2 shr 8 and 0xFF)) +
                    kotlin.math.abs((p1 and 0xFF) - (p2 and 0xFF))
                if (d > 60) diff++
                total++
            }
        }
        total > 0 && diff.toDouble() / total > Tuning.SCROLL_DIFF_RATIO
    } catch (e: Throwable) {
        // 捕 Throwable 而不只是 Exception：OOM 是 Error，旧版捕不到 —— 一旦发生就直接
        // 把进程带走。这里退化为"判定为没动"，让上层走自己的重试/退避逻辑。
        android.util.Log.w("E7SA.Percep", "didScroll failed: " + e.javaClass.simpleName)
        false
    }
}

/**
 * 两帧是否几乎完全相同 —— 用于判断"上一次的识别结果能否直接复用"。
 *
 * 与 [didScroll] 的分工：那个回答"画面动没动"（阈值 6%，服务滚动判定）；
 * 这个回答"画面是否**完全相同**"（阈值极小），只有几乎相同才敢复用 OCR/YOLO 结果。
 *
 * **保守是刻意的**：判"不同"只是多花一次识别（行为与优化前完全一致）；
 * 判"相同"却判错，就会拿旧画面的结果做决策 —— 那是漏买/误买的来源。
 * 所以尺寸不符、采样异常等任何拿不准的情况一律返回 false。
 *
 * 采样方式与 [didScroll] 同思路（按行取像素 + 隔点比较），避免 getPixel 的逐点 JNI 开销。
 */
fun frameNearlyIdentical(a: Bitmap, b: Bitmap): Boolean {
    return try {
        val w = a.width
        val h = a.height
        if (b.width != w || b.height != h) return false
        val rowA = IntArray(w)
        val rowB = IntArray(w)
        var diff = 0
        var total = 0
        var y = 0
        while (y < h) {
            a.getPixels(rowA, 0, w, 0, y, w, 1)
            b.getPixels(rowB, 0, w, 0, y, w, 1)
            var x = 0
            while (x < w) {
                val p1 = rowA[x]
                val p2 = rowB[x]
                if (p1 != p2) {
                    val d = kotlin.math.abs((p1 shr 16 and 0xFF) - (p2 shr 16 and 0xFF)) +
                        kotlin.math.abs((p1 shr 8 and 0xFF) - (p2 shr 8 and 0xFF)) +
                        kotlin.math.abs((p1 and 0xFF) - (p2 and 0xFF))
                    if (d > Tuning.FRAME_IDENTICAL_PIXEL_DIFF) diff++
                }
                total++
                x += Tuning.FRAME_IDENTICAL_STEP
            }
            y += Tuning.FRAME_IDENTICAL_ROW_STEP
        }
        total > 0 && diff.toFloat() / total <= Tuning.FRAME_IDENTICAL_RATIO
    } catch (e: Throwable) {
        // 拿不准就不复用：退回"重新识别"，行为与优化前一致
        android.util.Log.w("E7SA.Percep", "frameNearlyIdentical failed: " + e.javaClass.simpleName)
        false
    }
}

/* ---- 弹窗内容区（2026-09-20 活锁修复） ----
 *
 * 购买弹窗是**盖在商店列表上的对话框**：弹窗外的列表行仍会被 OCR 完整读到。
 * 三重验证若直接在整帧文本上做，弹窗外那一行就会污染判据。2026-09-20 生产日志
 * 实测：玩家要点的那件誓约书签就在弹窗下方仍可见 → 整帧里 184000 出现两次
 * （弹窗内一次 + 列表行一次）→「价格唯一匹配」100% 失败 → 一夜 2472 次
 * 「买 → 取消 → 再买」活锁（只买到 8 件、4.4 小时满速空转、手机严重发烫）。
 */

/**
 * 弹窗锚点 y：优先取模型的取消/确认按钮框（弹窗独有，实测置信度稳定在 0.98），
 * 回退到 OCR 读到的「取消」行（列表行里没有"取消"二字）。
 *
 * 两者都读不到时返回 null —— 调用方必须按"无法验证"处理，**绝不退回整帧**。
 */
internal fun dialogAnchorY(r: DetectionResult): Float? {
    val btn = r.yoloBoxes.filter {
        (it.clsName == "cancel_button" || it.clsName == "confirm_button") &&
            it.prob >= Tuning.MODEL_BUTTON_CONF
    }
    if (btn.isNotEmpty()) return btn.map { it.cy }.average().toFloat()
    return r.lines.filter { hasAny(it.text, CANCEL_KW) }.minByOrNull { it.cx }?.cy
}

/** 弹窗内容区的 y 范围；锚点读不到返回 null。 */
private fun dialogWindow(r: DetectionResult, imgH: Int): ClosedFloatingPointRange<Float>? {
    val anchor = dialogAnchorY(r) ?: return null
    return (anchor - imgH * Tuning.DIALOG_WIN_UP)..(anchor + imgH * Tuning.DIALOG_WIN_DOWN)
}

/**
 * 弹窗内容区内的文本行；锚点读不到时返回 null（= 无法验证，fail-closed）。
 *
 * **绝不退回整帧**：整帧扫描既能造出 2026-09-20 那种活锁（弹窗外同价行让判据失败），
 * 也能在点错行时把弹窗外的列表行当成"弹窗证据"而**误买**（不可逆的真金白银损失）。
 *
 * 同时供"弹窗里到底是什么商品"（[dialogItemKind]）判定使用 —— 那个判定若看整帧，
 * 弹窗外的另一种商品行会让引擎误判"点错行"而放弃一个本来能买到的目标（漏买）。
 */
fun dialogLines(r: DetectionResult, imgH: Int): List<PpOcr.OcrLine>? =
    dialogWindow(r, imgH)?.let { w -> r.lines.filter { it.cy in w } }

/** 购买弹窗三重验证（S4，全 AND）：商品名 + 图标旁证 + 价格匹配，**全部限定在弹窗内容区内**。 */
fun dialogConfirmed(r: DetectionResult, bmp: Bitmap, kind: String): Boolean {
    val win = dialogWindow(r, bmp.height) ?: return false
    val lines = r.lines.filter { it.cy in win }
    val boxes = r.yoloBoxes.filter { it.cy in win }
    val textOk = lines.any { it.prob >= Tuning.OCR_TEXT_CONF && itemKind(it.text) == kind }
    val yoloOk = boxes.any { yoloKind(it) == kind }
    val nameLine = lines.firstOrNull { itemKind(it.text) == kind }
    val iconOk = yoloOk || (nameLine != null && (
        iconColorRatio(bmp, nameLine.cx, nameLine.cy, kind) >= ICON_RATIO_MIN ||
            iconColorRatio(bmp, nameLine.cx, nameLine.cy, kind, wide = true) >= ICON_RATIO_MIN
        ))
    return textOk && iconOk && priceMatches(lines, kind)
}

/**
 * 价格匹配：弹窗内容区内出现期望价格即通过。
 *
 * **判据从「严格唯一」改为「出现即可」**（2026-09-20 生产日志驱动）。
 * 旧判据要求全帧恰好一条 6 位数字等于期望价格；但弹窗盖在列表上，玩家要点的
 * 那一行往往仍在弹窗下方可见 —— 同一个价格被读到两次，判据必然失败。
 * 实测那一夜：2472 次「买 → 取消 → 再买」、只买到 8 件、4.4 小时满速空转。
 *
 * 语义纠正：**同一价格的重复不是歧义**（两处 184000 都指向誓约书签）；
 * 真正的歧义是"弹窗里其实是另一种商品"，那由 [dialogConfirmed] 的 textOk
 * （弹窗内必须读到目标商品名）挡住。旧判据恰好两头都错：
 *  · 同价重复被拒 → 活锁（本次事故）；
 *  · 184000 与 280000 同屏这种真歧义反被放行 —— 旧实现只把**等于期望价**的计入
 *    候选，280000 根本不进统计，于是"唯一一条"照样成立。
 *
 * 调用方必须传入**弹窗内容区**内的行（见 [dialogLines]）；传整帧会重新引入污染。
 */
fun priceMatches(lines: List<PpOcr.OcrLine>, kind: String): Boolean {
    val expect = if (kind == "bookmark") Tuning.BOOKMARK_PRICE.toString()
    else Tuning.MEDAL_PRICE.toString()
    return lines.any { l ->
        val d = l.text.filter { it.isDigit() }
        d.length == 6 && d == expect
    }
}

/* ================= YOLO Engine ================= */

class YoloEngine : RecognitionEngine {
    override val id = "yolo"

    /** 上次亮度增强重试的时间（A5 限频：每 5 秒最多一次，避免拖慢正常帧）。 */
    private var lastEnhanceAt = 0L

    /**
     * 亮度增强（Gamma 0.6，查表法，一次像素遍历）。
     *
     * 用途（A5，2026-09-22）：售空/暗色图标在原始帧上的检测置信度只有 0.23
     * （实测 140720：原始帧 conf < 0.05 完全漏检，增强后可检出）——
     * 因为售空后图标失去蓝金特征色、整体变暗。只在「零图标候选」的帧调用。
     */
    private fun enhanceBrightness(src: Bitmap): Bitmap? {
        return try {
            val w = src.width
            val h = src.height
            val px = IntArray(w * h)
            src.getPixels(px, 0, w, 0, 0, w, h)
            val lut = IntArray(256) { i ->
                (255.0 * Math.pow(i / 255.0, 0.6)).toInt().coerceIn(0, 255)
            }
            for (i in px.indices) {
                val c = px[i]
                val r = lut[(c shr 16) and 0xFF]
                val g = lut[(c shr 8) and 0xFF]
                val b = lut[c and 0xFF]
                px[i] = (c and -0x1000000) or (r shl 16) or (g shl 8) or b
            }
            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            out.setPixels(px, 0, w, 0, 0, w, h)
            out
        } catch (e: Throwable) {
            null
        }
    }

    /** 睡眠模式：见 RecognitionEngine.sleepMode。打开后 hasIconFast 强制返回 true。 */
    override var sleepMode: Boolean = false

    /**
     * 快速探测：只跑 YOLO，不跑 OCR（省约 390ms）。
     *
     * 阈值取 0.25，**比完整分析的 0.3 更宽松**——这是刻意的：
     * 快速探测决定"要不要继续看这一屏"，一旦它漏报，整屏就被跳过、**没有补救机会**，
     * 直接变成漏买。所以它必须"宁可多报"：宽松阈值只会让它偶尔多触发一次完整识别
     * （多花约 400ms），而不会漏掉任何一屏。
     *
     * （先前误设为 0.4 —— 比完整识别更严，会在 0.3~0.4 置信度区间漏报，
     *   那是"漏看整屏"的风险，已修正。）
     *
     * 睡眠模式下直接返回 true：调用方会走完整识别，彻底避开"轻量探测漏报"这条路。
     */
    override fun hasIconFast(bmp: Bitmap): Boolean {
        if (sleepMode) return true
        val boxes = YoloDet.detect(bmp)
        // 快速探测失败 → 返回 false → 整屏会被跳过（漏买方向），必须留痕。
        // 失败在 YoloDet 内部已被吞成 emptyList，所以这里用 takeFailure() 取走留痕 ——
        // 原来的 try/catch 是死代码（2026-09-19 修复）。
        YoloDet.takeFailure()?.let { notePerceptionFailure("yolo-fast", yoloFailCount, it) }
        return boxes.any { it.isIcon && it.prob >= Tuning.ICON_CONF_FAST }
    }

    override fun analyze(bmp: Bitmap): DetectionResult {
        val t0 = System.currentTimeMillis()
        val lines = PpOcr.recognize(bmp)
        PpOcr.takeFailure()?.let { notePerceptionFailure("ocr", ocrFailCount, it) }
        val t1 = System.currentTimeMillis()
        val boxes = YoloDet.detect(bmp)
        YoloDet.takeFailure()?.let { notePerceptionFailure("yolo", yoloFailCount, it) }
        val t2 = System.currentTimeMillis()
        val scene = sceneOf(lines)
        // 候选 = YOLO 图标框 ∪ OCR 商品名行（按行合并，冲突剔除）
        val raw = ArrayList<Candidate>()
        for (b in boxes) {
            if (b.prob < Tuning.ICON_CONF_FULL) continue
            // 只有图标类进候选；按钮类留在 yoloBoxes 里，供 AI 点击管线直接使用
            val kind = yoloKind(b) ?: continue
            // 行容差由**行距**推导，不再用 b.h*2（那个值约 300px，超过商店行距，
            // 会让相邻行互相污染：点错行 / 售罄误判 / 图标误确认）
            raw.add(Candidate(kind, b.cx, b.cy, b.prob, rowTol(lines, bmp.height),
                listOf("yolo-box ${b.clsName} prob=${"%.2f".format(b.prob)}")))
        }
        // ⚠ 2026-09-22 回退（有真机证据）：这里曾改成 rowTol（103px），但它引入了误点 ——
        // 商店顶部是商人 NPC 区域，那里的「圣约书签」文字会生成假候选 cy≈200；
        // tol=103 让它够到了第 1 行（熔岩陆龟等非目标商品）的购买按钮 y=277（差 77px），
        // 于是机器人点了非目标商品、弹出怪物的购买界面（真机 21:10:28 实测）。
        // 回到 0.045h（1272 高屏上 57px）：假候选够不着任何真实按钮，宁可 fail-closed 不点。
        // 教训：当初「OCR 候选够不着按钮」的结论建立在误判上 —— y=199 本身就是假候选。
        // 商人对话面板打开时，面板里的商品名不是列表行 —— 本帧丢弃全部 OCR 候选，
        // 只信 YOLO 图标框（2026-09-22 真机事故：面板里的「圣约书签」文字生成了假候选）。
        val merchantPanel = lines.any { hasAny(it.text, MERCHANT_TALK_KW) }
        val ocrTol = bmp.height * Tuning.ROW_TOL_FALLBACK
        for (l in lines) {
            val kind = itemKind(l.text) ?: continue
            if (hasAny(l.text, SOLD_KW)) continue
            if (merchantPanel) continue
            raw.add(Candidate(kind, l.cx, l.cy, l.prob * Tuning.ICON_CONF_WEIGHT, ocrTol,
                listOf("ocr-name ${l.text}@${(l.prob * 100).toInt()}")))
        }
        var candidates = mergeCandidates(raw)
        // ⚠ 2026-09-22 新增（A5，召回优先）：零图标候选时做一次亮度增强重试。
        // 依据：售空书签在原始帧 conf < 0.05 完全漏检，Gamma 增强后能到 0.23。
        // 限频 5 秒一次 —— 增强约 30~80ms 加一次 YOLO 约 250ms，不加限频会拖慢正常帧。
        if (candidates.none { it.kind == "bookmark" || it.kind == "medal" }) {
            val now = System.currentTimeMillis()
            if (now - lastEnhanceAt > 5000L) {
                lastEnhanceAt = now
                val bright = enhanceBrightness(bmp)
                if (bright != null) {
                    val boxes2 = YoloDet.detect(bright)
                    bright.recycle()
                    val raw2 = ArrayList<Candidate>(raw)
                    for (b in boxes2) {
                        if (b.prob < Tuning.ICON_CONF_FULL) continue
                        val kind = yoloKind(b) ?: continue
                        raw2.add(
                            Candidate(kind, b.cx, b.cy, b.prob, rowTol(lines, bmp.height),
                                listOf("yolo-box-bright " + b.clsName + " prob=" + "%.2f".format(b.prob)))
                        )
                    }
                    candidates = mergeCandidates(raw2)
                }
            }
        }
        // 耗时分解（性能优化前必须先知道钱花在哪）：ocr = PP-OCR det+rec，yolo = ncnn 推理
        // 另加候选漏斗 raw(合并前) → cands(合并后)：实机出现"识别到 60 个文本框却 0 候选"时，
        // 靠它区分是「YOLO 没出框」还是「OCR 商品名没匹配上」还是「合并阶段被剔除」。
        val diag = "yolo boxes=${boxes.size}(${t2 - t1}ms) ocr=${lines.size}(${t1 - t0}ms) " +
            "raw=${raw.size} cands=${candidates.size} total=${System.currentTimeMillis() - t0}ms"
        return DetectionResult(scene, lines, candidates, boxes, id, diag)
    }
}

/* ================= Traditional Engine（经典 CV，全新设计） =================
 *
 * 每帧成本硬约束：1 次 OCR + 1 次行投影扫描 + 0 个额外 Bitmap 副本。
 * 流程（每步可解释）：
 *   1. 全帧 OCR（场景语义 + 文本行，与 YOLO 引擎共用同一基础设施）
 *   2. 行结构：以每行"购买"按钮文字 y 聚类推导行中心与行高（纯图像内容推导）
 *   3. 候选一：OCR 商品名行（与 YOLO 路径同构）
 *   4. 候选二：每行图标区颜色签名（蓝+金=书签 / 紫+红=奖牌）+ 行高几何约束
 *   5. 合并/冲突剔除 → 每个候选携带完整 evidence 链
 */

class TraditionalEngine : RecognitionEngine {
    override val id = "traditional"

    /** 传统引擎没有纯图标检测能力，hasIconFast 本就退化为完整识别，此开关无实际作用。 */
    override var sleepMode: Boolean = false

    override fun analyze(bmp: Bitmap): DetectionResult {
        val t0 = System.currentTimeMillis()
        val lines = PpOcr.recognize(bmp)
        PpOcr.takeFailure()?.let { notePerceptionFailure("ocr", ocrFailCount, it) }
        val scene = sceneOf(lines)
        val raw = ArrayList<Candidate>()

        // ---- 行结构：购买按钮 y 聚类 -> 行中心 + 行高 ----
        val buyYs = lines.filter { hasAny(it.text, BUY_KW) && !hasAny(it.text, BUY_TITLE_KW) }
            .map { it.cy }.sorted()
        val gaps = buyYs.zipWithNext { a, b -> b - a }.filter { it > 4f }
        val rowGap = if (gaps.isEmpty()) bmp.height * Tuning.ROW_GAP_MIN else median(gaps)
        val halfBand = (rowGap * Tuning.ROW_HALF_BAND).coerceAtLeast(bmp.height * Tuning.ROW_HALF_BAND_MIN)
        val rowCenters = buyYs

        // ---- 候选一：OCR 商品名行 ----
        for (l in lines) {
            val kind = itemKind(l.text) ?: continue
            if (hasAny(l.text, SOLD_KW)) continue
            raw.add(Candidate(kind, l.cx, l.cy, l.prob * Tuning.ICON_CONF_WEIGHT, halfBand,
                listOf("ocr-name ${l.text}@${(l.prob * 100).toInt()}")))
        }

        // ---- 候选二：每行图标区颜色签名（零额外 OCR） ----
        for (by in rowCenters) {
            val nameLine = lines.firstOrNull {
                itemKind(it.text) != null && kotlin.math.abs(it.cy - by) < halfBand && !hasAny(it.text, SOLD_KW)
            } ?: continue
            for (kind in listOf("bookmark", "medal")) {
                val ratio = iconColorRatio(bmp, nameLine.cx, nameLine.cy, kind)
                if (ratio >= ICON_RATIO_MIN) {
                    val conf = (ratio / Tuning.COLOR_CONF_BASE).coerceIn(Tuning.COLOR_CONF_MIN, Tuning.COLOR_CONF_MAX)
                    raw.add(Candidate(kind, nameLine.cx, nameLine.cy, conf, halfBand,
                        listOf("cv-row@y=${by.toInt()} band=±${halfBand.toInt()} iconColor($kind) ratio=${"%.3f".format(ratio)}")))
                }
            }
        }

        val candidates = mergeCandidates(raw)
        val diag = "trad rows=${rowCenters.size} rowGap=${rowGap.toInt()} ocr=${lines.size} cands=${candidates.size} ms=${System.currentTimeMillis() - t0}"
        return DetectionResult(scene, lines, candidates, emptyList(), id, diag)
    }

    private fun median(v: List<Float>): Float = v.sorted()[v.size / 2]
}

/** 候选按行合并：同行同 kind 取置信度最高；同行不同 kind 冲突 → 整行剔除（fail-closed）。 */
internal fun mergeCandidates(raw: List<Candidate>): List<Candidate> {
    val out = ArrayList<Candidate>()
    for (t in raw.sortedBy { it.cy }) {
        val idx = out.indexOfFirst { kotlin.math.abs(it.cy - t.cy) < kotlin.math.max(it.tol, t.tol) }
        when {
            idx < 0 -> out.add(t)
            out[idx].kind != t.kind -> out.removeAt(idx)
            out[idx].conf < t.conf -> out[idx] = t
        }
    }
    return out
}

/* ================= 引擎工厂 ================= */

object RecognitionEngines {
    /** 引擎 id：yolo / traditional（旧值 builtin/gpu/opencv 归入 traditional）。 */
    fun create(id: String): RecognitionEngine = when (id) {
        "traditional", "builtin", "gpu", "opencv" -> TraditionalEngine()
        else -> YoloEngine()
    }

    /** 推荐引擎（模型已加载时）与回退引擎。 */
    fun pick(preferred: String, yoloLoaded: Boolean): RecognitionEngine =
        when {
            preferred == "traditional" -> TraditionalEngine()
            !yoloLoaded -> TraditionalEngine()
            else -> YoloEngine()
        }
}
