package com.e7.shop.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 《冰与火之舞》(A Dance of Fire and Ice) 风格的非线性缓动函数集。
 *
 * 为什么单独做一套：Material 3 自带的 Standard / Emphasized 曲线偏"稳重克制"，
 * 匀速感强；而这款节奏游戏的动画语言是**打击感** —— 快速过冲、回弹、节拍顿挫。
 * 页面切换用它，界面才会有"节奏"而不是平顺滑过。
 *
 * 契约：全部满足 f(0)=0、f(1)=1（Compose Easing 的要求），
 * 中间允许过冲（>1）或回撤（<0）—— 这正是"非线性"的来源。
 *
 * 选用建议：
 *   · 入场（卡片/列表项）   → [DfibImpact]（过冲，有"啪"的打击感）
 *   · 页面切换入场         → [DfibBeat]（快-顿-收，带节拍）
 *   · 页面切换退场         → [DfibSnap]（急停，干脆）
 *   · 强调性弹入（徽章/提示）→ [DfibElastic]（衰减振荡）
 *   · 下落/归位（列表回落）  → [DfibBounce]（落地两次小跳）
 *   · 退场/收起            → [DfibAnticipate]（先回撤再走，蓄力感）
 */

/** 打击过冲：冲过目标再回弹，像音游判定成功那一下"啪"。 */
val DfibImpact: Easing = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1.0f)

/** 急停：起步极快、收尾干脆（easeOutQuint 手感），用于退场与快速切换。 */
val DfibSnap: Easing = CubicBezierEasing(0.22f, 1.0f, 0.36f, 1.0f)

/** 蓄力：先反向回撤一点再冲出去（预备动作），用于退场/收起。 */
val DfibAnticipate: Easing = CubicBezierEasing(0.68f, -0.55f, 0.27f, 1.55f)

/**
 * 节拍顿挫：把 0→1 切成"快—顿—收"两段，制造节拍感。
 * 前 60% 走完 85%（快），后 40% 补完剩余 15%（顿）。
 */
val DfibBeat: Easing = Easing { t ->
    when {
        t <= 0f -> 0f
        t >= 1f -> 1f
        t < 0.6f -> {
            val u = t / 0.6f
            0.85f * (1f - (1f - u).pow(3f))
        }
        else -> {
            val u = (t - 0.6f) / 0.4f
            0.85f + 0.15f * (1f - (1f - u).pow(2f))
        }
    }
}

/** 弹性回弹：衰减振荡收尾，用于强调性弹入。 */
val DfibElastic: Easing = Easing { t ->
    when {
        t <= 0f -> 0f
        t >= 1f -> 1f
        else -> {
            val p = 0.36
            val s = p / 4.0
            (2.0.pow(-9.0 * t) * sin((t - s) * (2.0 * PI / p)) + 1.0).toFloat()
        }
    }
}

/** 落地弹跳：模拟物体落地后的两次小跳，用于下落/归位。 */
val DfibBounce: Easing = Easing { t ->
    if (t >= 1f) {
        1f
    } else {
        val n1 = 7.5625f
        val d1 = 2.75f
        var x = t
        when {
            x < 1f / d1 -> n1 * x * x
            x < 2f / d1 -> {
                x -= 1.5f / d1
                n1 * x * x + 0.75f
            }
            x < 2.5f / d1 -> {
                x -= 2.25f / d1
                n1 * x * x + 0.9375f
            }
            else -> {
                x -= 2.625f / d1
                n1 * x * x + 0.984375f
            }
        }
    }
}

/* ============================================================================
 * 取自《冰与火之舞》的缓动族
 *
 * 该游戏使用 DOTween，其 Ease 枚举里的 quad / cubic / quart / expo / circ 五族
 * 在游戏动画里被大量使用（已从游戏目录 DOTween.dll 的 Ease 成员确认存在）。
 * 这里按 DOTween 内部 EaseManager.Evaluate 所用的标准 Penner 公式实现，
 * 每族给出 In / Out / InOut 三种变体；界面只暴露五族（用 InOut，最适合整页过渡）。
 * ========================================================================== */

/* ---- quad：二次方（温和起步）---- */
val EaseInQuad: Easing = Easing { t -> t * t }
val EaseOutQuad: Easing = Easing { t -> 1f - (1f - t) * (1f - t) }
val EaseInOutQuad: Easing = Easing { t ->
    if (t < 0.5f) 2f * t * t
    else 1f - (-2f * t + 2f).let { it * it } / 2f
}

/* ---- cubic：三次方（更明显的加速）---- */
val EaseInCubic: Easing = Easing { t -> t * t * t }
val EaseOutCubic: Easing = Easing { t -> 1f - (1f - t).let { it * it * it } }
val EaseInOutCubic: Easing = Easing { t ->
    if (t < 0.5f) 4f * t * t * t
    else 1f - (-2f * t + 2f).let { it * it * it } / 2f
}

/* ---- quart：四次方（起步更慢、后段更冲）---- */
val EaseInQuart: Easing = Easing { t -> t * t * t * t }
val EaseOutQuart: Easing = Easing { t -> 1f - (1f - t).let { it * it * it * it } }
val EaseInOutQuart: Easing = Easing { t ->
    if (t < 0.5f) 8f * t * t * t * t
    else 1f - (-2f * t + 2f).let { it * it * it * it } / 2f
}

/* ---- expo：指数（极端起步/收尾，冲击力最强）---- */
val EaseInExpo: Easing = Easing { t ->
    if (t <= 0f) 0f else 2f.pow(10f * t - 10f)
}
val EaseOutExpo: Easing = Easing { t ->
    if (t >= 1f) 1f else 1f - 2f.pow(-10f * t)
}
val EaseInOutExpo: Easing = Easing { t ->
    when {
        t <= 0f -> 0f
        t >= 1f -> 1f
        t < 0.5f -> 2f.pow(20f * t - 10f) / 2f
        else -> (2f - 2f.pow(-20f * t + 10f)) / 2f
    }
}

/* ---- circ：圆弧（起步像圆规画弧，收尾平滑贴住）---- */
val EaseInCirc: Easing = Easing { t ->
    1f - sqrt((1f - t * t).coerceAtLeast(0f))
}
val EaseOutCirc: Easing = Easing { t ->
    sqrt((1f - (t - 1f) * (t - 1f)).coerceAtLeast(0f))
}
val EaseInOutCirc: Easing = Easing { t ->
    if (t < 0.5f) {
        (1f - sqrt((1f - 4f * t * t).coerceAtLeast(0f))) / 2f
    } else {
        (sqrt((1f - (-2f * t + 2f).let { it * it }).coerceAtLeast(0f)) + 1f) / 2f
    }
}

/**
 * 可选的缓动族（id → 默认 InOut 函数）。
 *
 * 设置页用它渲染族列表；MainActivity 的 NavHost 用 [animEasingOf] 取实际函数 ——
 * 两边共用同一份定义，避免"设置里能选、实际不生效"的假开关。
 * 第一项是默认值（冰火节拍）。
 */
val ANIM_EASINGS: List<Pair<String, Easing>> = listOf(
    "beat" to DfibBeat,
    "quad" to EaseInOutQuad,
    "cubic" to EaseInOutCubic,
    "quart" to EaseInOutQuart,
    "expo" to EaseInOutExpo,
    "circ" to EaseInOutCirc
)

/** 每个族的三个变体：In / Out / InOut。 */
private val EASING_VARIANTS: Map<String, Triple<Easing, Easing, Easing>> = mapOf(
    "quad" to Triple(EaseInQuad, EaseOutQuad, EaseInOutQuad),
    "cubic" to Triple(EaseInCubic, EaseOutCubic, EaseInOutCubic),
    "quart" to Triple(EaseInQuart, EaseOutQuart, EaseInOutQuart),
    "expo" to Triple(EaseInExpo, EaseOutExpo, EaseInOutExpo),
    "circ" to Triple(EaseInCirc, EaseOutCirc, EaseInOutCirc)
)

/** 方向选项 id（与设置页、配置字段共用）。 */
const val EASING_VARIANT_IN = "in"
const val EASING_VARIANT_OUT = "out"
const val EASING_VARIANT_INOUT = "inout"

/**
 * 组合出实际使用的缓动函数。
 *
 * @param family   缓动族：beat / quad / cubic / quart / expo / circ（未知值回退冰火节拍）
 * @param variant  方向：in / out / inout
 * @param strength 强度 0..100 —— 与**线性**插值：0 = 完全线性（看不出缓动），
 *                 100 = 完整缓动。用于"缓动太夸张/太弱"时的微调。
 *
 * 冰火节拍没有严格意义的 In/Out，用现有三个函数近似映射：
 * in → DfibAnticipate（先回撤，蓄力感）、out → DfibSnap（急停）、inout → DfibBeat（节拍顿挫）。
 */
fun animEasingOf(family: String, variant: String, strength: Int): Easing {
    val base: Easing = if (family == "beat") {
        when (variant) {
            EASING_VARIANT_IN -> DfibAnticipate
            EASING_VARIANT_OUT -> DfibSnap
            else -> DfibBeat
        }
    } else {
        val triple = EASING_VARIANTS[family] ?: return DfibBeat
        when (variant) {
            EASING_VARIANT_IN -> triple.first
            EASING_VARIANT_OUT -> triple.second
            else -> triple.third
        }
    }
    val s = strength.coerceIn(0, 100) / 100f
    if (s >= 1f) return base
    // 强度混合：x + (ease(x) - x) * s。s=0 时退化为线性，动画依然走完，只是没有加减速。
    return Easing { x -> x + (base.transform(x) - x) * s }
}
