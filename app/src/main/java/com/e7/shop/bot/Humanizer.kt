package com.e7.shop.bot

import com.e7.shop.data.AppConfig
import kotlin.random.Random

/**
 * Human-like behavior library: random delays, tap offsets, curved swipe paths,
 * random rests and hesitation. Lowers automation detection risk.
 */
class Humanizer(private val cfg: AppConfig) {

    /**
     * FATIGUE DRIFT (the real anti-detection mechanism we were missing):
     * a human gets slower and sloppier the longer they play. The service
     * raises this 0 -> 0.4 over ~2 hours; every delay scales by (1+fatigue)
     * so the LONG-RUN rhythm drifts instead of staying machine-uniform.
     */
    @Volatile
    var fatigue = 0f

    /** Scale a delay by the speed multiplier (1-3x, learned from the Rem assistant). */
    private fun scale(ms: Long): Long = (ms.toDouble() / cfg.speedMult).toLong().coerceAtLeast(50)

    /** Apply speed + fatigue to a raw delay. */
    private fun effDelay(ms: Long): Long =
        (ms.toDouble() / cfg.speedMult * (1.0 + fatigue)).toLong().coerceAtLeast(50)

    fun randInt(min: Int, max: Int): Int =
        if (max <= min) min else Random.nextInt(min, max + 1)

    fun randFloat(min: Float, max: Float): Float =
        min + Random.nextFloat() * (max - min)

    /** Random wait between actions (human eye + reaction time). */
    fun delay() {
        Thread.sleep(effDelay(randInt(cfg.delayMinMs, cfg.delayMaxMs).toLong()))
    }

    /**
     * Hesitation pause before buying (human confirmation). Tuned FASTER:
     * every cycle buys up to 5 items + refreshes, so a 400-1200ms pause per
     * action added up to seconds of dead time per round.
     */
    fun hesitate() {
        Thread.sleep(effDelay(randInt(180, 520).toLong()))
    }

    /** Random rest every N operations, like a human taking a break. */
    fun maybeRest(opCount: Int) {
        if (!cfg.randomRest) return
        if (opCount > 0 && opCount % cfg.restEvery == 0) {
            // fatigue 让后期休息更长。
            // 旧版 `(fatigue * 2).toLong()` 在 fatigue < 0.5 时恒为 0（疲劳上限 0.4），
            // 这个加成从未生效过 —— 改为浮点缩放。
            val base = randInt(1200, 4200).toLong()
            Thread.sleep(effDelay((base * (1.0 + fatigue * 2.0)).toLong()))
        }
    }

    /** Small probability of a longer daze. */
    fun maybeDaze() {
        if (Random.nextFloat() < 0.05f + fatigue * 0.10f) {
            Thread.sleep(effDelay(randInt(900, 2600).toLong()))
        }
    }

    /**
     * Offset a tap point like a real finger: MOST taps are off by 1-3px,
     * some by 4-7px, rarely by the full allowed offset. A uniform ±offset
     * distribution has a telltale pattern; this near-Gaussian one does not.
     */
    fun offsetPoint(x: Float, y: Float): Pair<Float, Float> {
        // 硬上限 4px：E7 按钮高度普遍 80px 以上，4px 绝不会越出按钮边界。
        // 上限在此处统一夹取，调用方（tapExact）不必再夹一次。
        val amp = cfg.offsetPx.coerceIn(0, Tuning.TAP_OFFSET_MAX_PX)
        if (amp == 0) return Pair(x, y)
        val roll = Random.nextFloat()
        val max = when {
            roll < 0.70f -> (amp / 3).coerceAtLeast(1)
            roll < 0.95f -> (amp * 2 / 3).coerceAtLeast(2)
            else -> amp
        }
        val ox = x + randInt(-max, max)
        val oy = y + randInt(-max, max)
        return Pair(ox, oy)
    }
}
