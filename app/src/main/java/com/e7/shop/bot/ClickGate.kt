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
 * **职责边界（2026-09-22 校准 —— 此前文档写的「统一闸门含 scene/target 唯一/
 * button/row/price/未暂停」与实现不符，这里以实现为准并把分工写清楚）**：
 * 本闸门只做上面两件事；其余前置条件由**调用点**在决定点击之前完成：
 *  · rowBuyPoint 的「购买键必须在候选右侧且落在行容差内」（fail-closed）；
 *  · dialogBuy 的「必须先看到取消键、购买键在其右侧」；
 *  · dialogConfirmed 的三重验证（商品名 + 图标旁证 + 价格）；
 *  · 预算闸门 BudgetGate 在动作之前的检查。
 * 这样分工的原因：那些判据需要引擎的上下文状态（当前 target、会话记账），
 * 塞进闸门会让它反过来依赖引擎内部，更难维护。
 * **代价**：新增点击路径必须自行完成那些检查 —— 闸门不会替你兜底。
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
