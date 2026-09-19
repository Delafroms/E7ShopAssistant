package com.e7.shop.bot

/**
 * 帧级感知追踪：把"这一帧到底看到了什么"格式化成一行可检索的日志。
 *
 * ## 为什么必须记录**原始数据**，而不是只记决策结果
 *
 * 漏买的定义是「玩家眼睛 100% 确定有书签/奖牌，但系统没买」。其中最难受的一种是
 * **无视型漏买** —— 系统根本没把它当成候选，于是连"我没买"都不会记录。
 * 只记决策结果永远查不出这种漏买，必须把识别层的原始输出留下来，
 * 事后才能定位是**哪一层**吃掉了目标：
 *
 * | 日志表现 | 结论 |
 * |---|---|
 * | OCR 原始文本里没有「神秘奖牌」 | 识别没读到（图像/模型问题） |
 * | OCR 有「神秘奖牌」但候选为空 | 候选生成逻辑有问题 |
 * | 候选有但没买 | 决策/点击/验证环节有问题 |
 * | 这一轮完全没有帧记录 | 那一轮根本没跑（漏刷） |
 *
 * ## 为什么是纯函数
 *
 * 格式稳定性直接决定日志能不能用来复盘，因此不依赖 Android、可在 JVM 单测里锁住。
 * 同时限制单帧体积：一次挂机 8 小时可能产生上万帧，无节制输出会撑爆日志文件。
 */
fun formatPerceptionTrace(
    stage: String,
    r: DetectionResult,
    maxOcrChars: Int = 260,
    maxYoloBoxes: Int = 12
): String {
    val sb = StringBuilder(320)
    sb.append("stage=").append(stage)
    sb.append(" scene=").append(r.scene.name)
    sb.append(" eng=").append(r.engine)

    // ---- OCR 原始文本：判断"识别到底有没有读到"的唯一依据，不能省 ----
    sb.append(" | ocr(").append(r.lines.size).append("): ")
    if (r.lines.isEmpty()) {
        sb.append('-')
    } else {
        var used = 0
        val parts = ArrayList<String>(r.lines.size)
        for (l in r.lines) {
            val t = l.text.replace('\n', ' ').trim()
            if (t.isEmpty()) continue
            if (used + t.length > maxOcrChars) { parts.add("…"); break }
            parts.add(t)
            used += t.length
        }
        sb.append(if (parts.isEmpty()) "-" else parts.joinToString("│"))
    }

    // ---- YOLO 原始框：模型看到、但没进候选的框，是"候选生成漏了"的直接证据 ----
    sb.append(" | yolo(").append(r.yoloBoxes.size).append("): ")
    if (r.yoloBoxes.isEmpty()) {
        sb.append('-')
    } else {
        sb.append(r.yoloBoxes.take(maxYoloBoxes).joinToString(" ") { b ->
            "${b.clsName}(${b.cx.toInt()},${b.cy.toInt()},${fmt2(b.prob)})"
        })
    }

    // ---- 候选生成结果 ----
    sb.append(" | cand(").append(r.candidates.size).append("): ")
    if (r.candidates.isEmpty()) {
        sb.append('-')
    } else {
        sb.append(r.candidates.joinToString(" ") { c ->
            "${c.kind}@${c.rowY}[tol${c.tol.toInt()}][${c.evidence.joinToString("+")}]"
        })
    }
    return sb.toString()
}

/** 固定两位小数、不依赖 Locale（避免不同区域设置下小数点变成逗号）。 */
private fun fmt2(v: Float): String {
    val n = (v * 100f + 0.5f).toInt()
    return "${n / 100}.${(n % 100).toString().padStart(2, '0')}"
}
