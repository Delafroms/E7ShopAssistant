package com.e7.shop.bot

import kotlin.math.abs

/**
 * A3 店铺状态跟踪器 —— **观测层，不参与控制流**。
 *
 * 目的：把"两帧之间店铺列表到底变没变、哪一行变了"变成可读事件，供 BUG 回收期定位。
 * 群友报上来的「买完不下滑 / 卡住不动 / 反复点同一行 / 明明有货却不买」这类问题，
 * 最需要的正是这份状态变迁记录 —— 有它才能区分「识别没看见」与「看见了但决策没动」。
 *
 * 为什么先只做观测，不驱动恢复动作：
 * V1 的控制流（行级重试预算 + 刷新指纹验证 + A4 观测优先）已在设备上验证过；
 * 在冻结期直接拿状态差去改控制流，等于用"猜"替换"已验证"。等真实 BUG 样本回来、
 * 能证明「某类状态序列必然导致活锁」之后再接线，才有依据。
 *
 * 事件语义（每轮观测输出一行）：
 *   first    首次观测（建立基线）
 *   same     与上一轮完全一致（staticStreak 递增）
 *   changed  行集合或售罄状态发生变化（staticStreak 归零）
 */
class ShopStateTracker {

    /** 一行商品的可观测状态（不引用任何位图/识别对象，纯数据）。 */
    data class Row(val kind: String, val rowY: Int, val soldOut: Boolean, val tol: Float)

    data class Snap(val scene: String, val rows: List<Row>, val fp: String)

    private var last: Snap? = null

    /** 连续多少轮观测结果完全一致（不含首次）。用于回答"是不是卡住了"。 */
    var staticStreak = 0
        private set

    /** 本会话累计观测轮数。 */
    var rounds = 0
        private set

    fun reset() {
        last = null
        staticStreak = 0
        rounds = 0
    }

    /**
     * 观测一帧，返回可直接写日志的一行描述。
     *
     * 同一行两帧之间必须"kind 相同且 rowY 接近"才算同一行（容差取两帧 tol 的较大值），
     * 否则会把"行位置漂移"误读成"旧行消失 + 新行出现"。
     */
    fun observe(r: DetectionResult): String {
        rounds++
        val rows = r.candidates
            .map { Row(it.kind, it.rowY, rowSoldOut(r, it.cy, it.tol), it.tol) }
            .sortedWith(compareBy({ it.rowY }, { it.kind }))
        val now = Snap(r.scene.name, rows, frameFingerprint(r))
        val prev = last
        last = now

        if (prev == null) {
            staticStreak = 0
            return "type=first round=$rounds scene=${now.scene} ${describe(now)}"
        }
        if (prev.fp == now.fp && prev.rows == now.rows) {
            staticStreak++
            return "type=same round=$rounds static=$staticStreak scene=${now.scene} ${describe(now)}"
        }
        staticStreak = 0
        return "type=changed round=$rounds scene=${now.scene} ${describe(now)} ${delta(prev, now)}"
    }

    /** 紧凑状态：`rows=3 [bookmark@494:ok, medal@856:sold]`。 */
    private fun describe(s: Snap): String =
        "rows=${s.rows.size} [" + s.rows.joinToString(", ") { "${it.kind}@${it.rowY}:${if (it.soldOut) "sold" else "ok"}" } + "]"

    /** 行级差异：gone / added / flip（同一行售罄状态翻转）。 */
    private fun delta(prev: Snap, now: Snap): String {
        val gone = prev.rows.filter { p -> now.rows.none { same(it, p) } }
        val added = now.rows.filter { n -> prev.rows.none { same(n, it) } }
        val flip = now.rows.mapNotNull { n ->
            val p = prev.rows.firstOrNull { same(n, it) } ?: return@mapNotNull null
            if (p.soldOut == n.soldOut) null
            else "${n.kind}@${n.rowY}:${if (p.soldOut) "sold" else "ok"}->${if (n.soldOut) "sold" else "ok"}"
        }
        val parts = ArrayList<String>()
        if (gone.isNotEmpty()) parts.add("gone=[${gone.joinToString(",") { "${it.kind}@${it.rowY}" }}]")
        if (added.isNotEmpty()) parts.add("added=[${added.joinToString(",") { "${it.kind}@${it.rowY}" }}]")
        if (flip.isNotEmpty()) parts.add("flip=[${flip.joinToString(",")}]")
        if (parts.isEmpty()) parts.add("rows-identical-but-fingerprint-changed")
        return parts.joinToString(" ")
    }

    /**
     * 两行是否算"同一行"：kind 相同 + rowY 落在容差内。
     * 容差取两帧各自 tol 的较大值，并保证不低于 [MIN_ROW_TOL]（低分辨率下 tol 可能很小，
     * 太小会把"行位置抖动"误读成"旧行消失 + 新行出现"，日志就失去诊断价值了）。
     */
    private fun same(a: Row, b: Row): Boolean =
        a.kind == b.kind && abs(a.rowY - b.rowY) <= maxOf(a.tol, b.tol, MIN_ROW_TOL)

    private companion object {
        /** 行归属容差下限（像素）：E7 行高在 1080p 约 100px、2K 约 130px。 */
        const val MIN_ROW_TOL = 40f
    }
}
