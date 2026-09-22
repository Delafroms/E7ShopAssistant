package com.e7.shop.bot

import android.graphics.Bitmap
import android.graphics.Color

/**
 * AI 点击视觉工具箱（仅 AI 点击模式使用，与传统点击函数零依赖、零回退）。
 *
 * 所有按钮定位与状态判断都由「当前帧」的颜色、形状与 OCR 文本语义推导：
 *  - 高饱和彩色色块 = 可点击按钮（金色购买/确认键、蓝色立即更新键等）
 *  - 低饱和灰色色块 = 售空/不可点击按钮
 *  - 无法视觉确认 = 返回 NONE / null，由 AI 决策层明确报告，绝不猜测、
 *    绝不调用传统点击函数、绝不使用固定或录制坐标。
 */
class ClickPlanner {

    enum class Button { CLICKABLE, GRAY, NONE }

    data class Loc(val state: Button, val pt: Pair<Float, Float>?)

    /**
     * E1 模型按钮框的采信阈值。
     *
     * 实测（7 类模型，proposed GT）：售罄行被误判成 row_buy_button 的置信度集中在
     * 0.27~0.38，0.5 能干净滤掉；低于阈值一律回退色块法 —— 绝不采信"可能看错"的模型输出。
     */
    private val modelBtnConf = Tuning.MODEL_BUTTON_CONF

    /**
     * E1：从模型检测结果里取按钮框中心（置信度达标才返回）。
     *
     * 多分类模型直接给出按钮框，比色块质心更稳，也能区分可买/售罄。
     * 现有 2 类图标模型下 yoloBoxes 里没有按钮类 → 恒返回 null → 调用方自然回退色块法，
     * 所以这个改动对当前线上行为零影响。
     *
     * @param cy  限定在某一商品行附近（null = 全屏找最可信的一个）
     */
    fun modelButton(r: DetectionResult, clsName: String, cy: Float? = null, tol: Float = 0f): Pair<Float, Float>? {
        val b = r.yoloBoxes
            .filter { it.clsName == clsName && it.prob >= modelBtnConf }
            .let { list -> if (cy == null) list else list.filter { kotlin.math.abs(it.cy - cy) < tol } }
            .maxByOrNull { it.prob } ?: return null
        return b.cx to b.cy
    }

    /** 金色按钮填充色（E7 购买/确认键）。 */
    fun gold(p: Int): Boolean {
        val r = Color.red(p)
        val g = Color.green(p)
        val b = Color.blue(p)
        return r > 175 && g > 110 && b < 140 && (r - b) > 70
    }

    /** 蓝色按钮填充色（E7「立即更新」等蓝色系按键）。 */
    fun blue(p: Int): Boolean {
        val r = Color.red(p)
        val g = Color.green(p)
        val b = Color.blue(p)
        return b > 150 && (b - r) > 60 && g in 80..200
    }

    /** 任意高饱和彩色按钮填充（金/蓝/绿等，覆盖 UI 配色差异）。 */
    fun buttonFill(p: Int): Boolean {
        val r = Color.red(p)
        val g = Color.green(p)
        val b = Color.blue(p)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        return max - min > 45 && max > 95
    }

    /** 灰色售空按钮（低饱和浅灰）。 */
    fun gray(p: Int): Boolean {
        val r = Color.red(p)
        val g = Color.green(p)
        val b = Color.blue(p)
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        return max - min < 30 && r in 90..220 && g in 90..220 && b in 90..220
    }

    /** 区域内匹配像素占比（0..1）。 */
    private fun ratio(bmp: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int, match: (Int) -> Boolean): Float {
        if (x2 <= x1 || y2 <= y1) return 0f
        var hit = 0
        var total = 0
        var y = y1
        while (y < y2) {
            var x = x1
            while (x < x2) {
                if (match(bmp.getPixel(x, y))) hit++
                total++
                x += Tuning.SAMPLE_STEP
            }
            y += Tuning.SAMPLE_STEP
        }
        return if (total == 0) 0f else hit.toFloat() / total
    }

    /** 区域内匹配像素质心（不足阈值返回 null）。 */
    private fun centroid(
        bmp: Bitmap, x1: Int, y1: Int, x2: Int, y2: Int, match: (Int) -> Boolean
    ): Pair<Float, Float>? {
        if (x2 <= x1 || y2 <= y1) return null
        var sx = 0L
        var sy = 0L
        var n = 0L
        var y = y1
        while (y < y2) {
            var x = x1
            while (x < x2) {
                if (match(bmp.getPixel(x, y))) {
                    sx += x
                    sy += y
                    n++
                }
                x += Tuning.SAMPLE_STEP
            }
            y += Tuning.SAMPLE_STEP
        }
        if (n < Tuning.CENTROID_MIN_PIXELS) return null
        return (sx.toFloat() / n) to (sy.toFloat() / n)
    }

    /**
     * 商品行按钮状态判断（AI 决策核心）：
     * 以行内「购买/售空」文本为语义锚点，对按钮区域做彩色/灰色色块分类。
     * 无文本锚点时扫描行右侧按钮带。所有坐标由当前帧推导。
     */
    fun rowButtonState(bmp: Bitmap, r: DetectionResult, cy: Float, tol: Float): Loc {
        // C1 埋点（2026-09-22）：色块扫描是纯逐像素计算，属 bot 线程未归属时间的头号嫌疑。
        // 用包装层而不是在 3 个调用点各埋一次 —— 调用点会变，包一层不会漏。
        val t0 = System.currentTimeMillis()
        try {
            return rowButtonStateInner(bmp, r, cy, tol)
        } finally {
            com.e7.shop.device.Profiler.record("rowButton", System.currentTimeMillis() - t0)
        }
    }

    private fun rowButtonStateInner(bmp: Bitmap, r: DetectionResult, cy: Float, tol: Float): Loc {
        val w = bmp.width
        val h = bmp.height

        // E1 优先路径：模型直接给出这一行的按钮框时，不再靠色块猜。
        // 可购买（金色）→ CLICKABLE + 框中心；售罄（灰色）→ GRAY（无点击点）。
        // 现有 2 类模型没有按钮类 → 两个查询都返回 null → 落到下面的色块法，行为不变。
        modelButton(r, "row_buy_button", cy, tol)?.let { return Loc(Button.CLICKABLE, it) }
        if (modelButton(r, "row_soldout_button", cy, tol) != null) return Loc(Button.GRAY, null)

        val ry = (tol * Tuning.BTN_WIN_RY_FROM_TOL).toInt()
            .coerceAtLeast((h * Tuning.BTN_WIN_RY_MIN).toInt())
        // 语义锚点：只认「购买 / 售空」按钮文本。
        //  · 排除「可购买1次」这类商品信息（含"购买"二字却不是按钮）——
        //    2026-09-22 修复：它会把色块窗口锚在商品名区域，质心偏到非按钮位置，
        //    真机表现为点击落空 + 12 秒 DIALOG TIMEOUT。
        //  · 同 y 命中多条时取最右：真按钮在列表行右侧。
        val txtLine = r.lines
            .filter {
                kotlin.math.abs(it.cy - cy) < tol &&
                    (hasAny(it.text, BUY_KW) || hasAny(it.text, SOLD_KW)) &&
                    !hasAny(it.text, BUY_COUNT_KW)
            }
            .maxByOrNull { it.cx }
        val x1: Int
        val x2: Int
        val y1: Int
        val y2: Int
        if (txtLine != null) {
            x1 = (txtLine.cx - w * Tuning.BTN_WIN_TEXT_HALF_W).toInt().coerceAtLeast(0)
            x2 = (txtLine.cx + w * Tuning.BTN_WIN_TEXT_HALF_W).toInt().coerceAtMost(w)
            y1 = (txtLine.cy - ry).toInt().coerceAtLeast(0)
            y2 = (txtLine.cy + ry).toInt().coerceAtMost(h)
        } else {
            // 无文本锚点：扫描行右侧整条按钮带
            x1 = (w * Tuning.BTN_WIN_BAND_X1).toInt()
            x2 = (w * Tuning.BTN_WIN_BAND_X2).toInt().coerceAtMost(w)
            y1 = (cy - ry).toInt().coerceAtLeast(0)
            y2 = (cy + ry).toInt().coerceAtMost(h)
        }
        val fillRatio = ratio(bmp, x1, y1, x2, y2, ::buttonFill)
        val grayRatio = ratio(bmp, x1, y1, x2, y2, ::gray)
        if (fillRatio >= Tuning.BTN_FILL_RATIO) {
            val pt = centroid(bmp, x1, y1, x2, y2, ::buttonFill)
            if (pt != null) return Loc(Button.CLICKABLE, pt)
        }
        if (grayRatio >= Tuning.BTN_GRAY_RATIO && fillRatio < Tuning.BTN_FILL_RATIO_LOW) return Loc(Button.GRAY, null)
        return Loc(Button.NONE, null)
    }

    /**
     * 按文本语义定位彩色按钮（弹窗「购买/确认」、商店「立即更新」）：
     * 取语义行中最右的一条为锚点，其附近必须有足够彩色色块才会给出点击点；
     * 视觉上无法确认 → null（AI 决策层据此明确报告，不猜测）。
     */
    fun goldButtonForText(lines: List<PpOcr.OcrLine>, bmp: Bitmap, kws: List<String>): Pair<Float, Float>? {
        val line = lines.filter { hasAny(it.text, kws) }.maxByOrNull { it.cx } ?: return null
        val ry = (bmp.height * Tuning.GOLD_WIN_RY).toInt().coerceAtLeast(Tuning.GOLD_WIN_RY_MIN_PX)
        val x1 = (line.cx - bmp.width * Tuning.GOLD_WIN_HALF_W).toInt().coerceAtLeast(0)
        val x2 = (line.cx + bmp.width * Tuning.GOLD_WIN_HALF_W).toInt().coerceAtMost(bmp.width)
        val y1 = (line.cy - ry).toInt().coerceAtLeast(0)
        val y2 = (line.cy + ry).toInt().coerceAtMost(bmp.height)
        if (ratio(bmp, x1, y1, x2, y2, ::buttonFill) >= Tuning.GOLD_FILL_RATIO) {
            return centroid(bmp, x1, y1, x2, y2, ::buttonFill)
        }
        return null
    }

    /** 调试：文本锚点附近的彩色按钮填充占比（AI 无法确认时用于报告具体原因）。 */
    fun textButtonFillRatio(lines: List<PpOcr.OcrLine>, bmp: Bitmap, kws: List<String>): Float {
        val line = lines.filter { hasAny(it.text, kws) }.maxByOrNull { it.cx } ?: return 0f
        val ry = (bmp.height * Tuning.GOLD_WIN_RY).toInt().coerceAtLeast(Tuning.GOLD_WIN_RY_MIN_PX)
        val x1 = (line.cx - bmp.width * Tuning.GOLD_WIN_HALF_W).toInt().coerceAtLeast(0)
        val x2 = (line.cx + bmp.width * Tuning.GOLD_WIN_HALF_W).toInt().coerceAtMost(bmp.width)
        val y1 = (line.cy - ry).toInt().coerceAtLeast(0)
        val y2 = (line.cy + ry).toInt().coerceAtMost(bmp.height)
        return ratio(bmp, x1, y1, x2, y2, ::buttonFill)
    }

    /**
     * 弹窗「取消」按钮（AI 自己的定位）：
     * 最左「取消」文本为语义锚点，在其附近寻找暗色按钮色块质心；
     * 找不到色块时点击文本中心（两者都来自当前帧识别，无固定坐标）。
     */
    fun cancelButton(lines: List<PpOcr.OcrLine>, bmp: Bitmap): Pair<Float, Float>? {
        val line = lines.filter { hasAny(it.text, CANCEL_KW) }.minByOrNull { it.cx } ?: return null
        val ry = (bmp.height * Tuning.CANCEL_WIN_RY).toInt().coerceAtLeast(Tuning.GOLD_WIN_RY_MIN_PX)
        val x1 = (line.cx - bmp.width * Tuning.CANCEL_WIN_HALF_W).toInt().coerceAtLeast(0)
        val x2 = (line.cx + bmp.width * Tuning.CANCEL_WIN_HALF_W).toInt().coerceAtMost(bmp.width)
        val y1 = (line.cy - ry).toInt().coerceAtLeast(0)
        val y2 = (line.cy + ry).toInt().coerceAtMost(bmp.height)
        fun dark(p: Int): Boolean {
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            return r < 130 && g < 130 && b < 150
        }
        return centroid(bmp, x1, y1, x2, y2, ::dark) ?: (line.cx to line.cy)
    }
}
