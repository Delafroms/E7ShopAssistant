package com.e7.shop.bot

import com.e7.shop.R

/**
 * 最终点击闸门（P1 BeforeClickGate）—— 传统点击与 AI 点击**共用同一道门**。
 *
 * 架构意义：无论上游是"文本锚点"还是"彩色按钮色块"，也无论识别算法怎么演进，
 * 真正触摸屏幕之前都必须经过这里。安全语义共享、只有点击决策独立（P6）。
 *
 * 拒绝条件（任一命中即 DENY，绝不盲点）：
 *  ① 会话已失效（generation 变化 / 已停止）
 *  ② 坐标非法或越出屏幕（NaN / 负数 / 超界）
 *
 * 设计取舍：这里**不检查暂停**——暂停语义是"延迟到安全边界生效"，
 * 不能在购买事务中途打断（否则会出现"买了但没验证完"的中间态）。
 */
fun BotEngine.Host.guardedTap(x: Float, y: Float, tag: String): Boolean {
    if (stopRequested()) {
        log("E7SA.Gate", "DENY $tag: session stopped")
        return false
    }
    val w = screenW.toFloat()
    val h = screenH.toFloat()
    if (x.isNaN() || y.isNaN() || x < 0f || y < 0f || x > w || y > h) {
        setError(str(R.string.err_gate_bad_point, x.toInt(), y.toInt()))
        log("E7SA.Gate", "DENY $tag: point out of screen (${x.toInt()},${y.toInt()}) screen=${w.toInt()}x${h.toInt()}")
        return false
    }
    log("E7SA.Gate", "ALLOW $tag tap=(${x.toInt()},${y.toInt()})")
    tapExact(x, y)
    return true
}
