package com.e7.shop.bot

import android.graphics.Bitmap
import com.e7.shop.ShopAccessibilityService
import com.e7.shop.data.AppConfig
import com.e7.shop.data.RecordStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 最终点击闸门单测（2026-09-18 新增）。
 *
 * 为什么最该测这里：全代码库**所有**决策性点击都要过 guardedTap，它是"绝不盲点"的
 * 最后一道防线，而此前它是零测试。四个 DENY 条件里任何一个失效，机器人都可能点到
 * 非法坐标、或在会话已经停止之后继续点屏幕（与玩家的手抢操作）。
 */
class ClickGateTest {

    /**
     * 只实现闸门用得到的能力，其余一律 no-op。
     *
     * 刻意不依赖任何 Android 框架行为（不碰 cfg / 不截图），因此能在 JVM 上直接跑 ——
     * 这也是 Host 被设计成接口的价值所在。
     */
    private class FakeHost(
        override val screenW: Int = 1080,
        override val screenH: Int = 2400,
        private val stopped: Boolean = false
    ) : BotEngine.Host {

        var tappedAt: Pair<Float, Float>? = null
        var lastError: String = ""
        val logs = mutableListOf<String>()

        override val cfg: AppConfig get() = error("闸门测试不读配置")
        override fun screenshot(): Bitmap? = null
        override fun tapExact(x: Float, y: Float) { tappedAt = x to y }
        override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, baseMs: Long) {}
        override fun sleepMs(ms: Long) {}
        override fun hesitate() {}
        override fun delayRandom() {}
        override fun daze() {}
        override fun rest(opCount: Int) {}
        override fun randInt(min: Int, max: Int): Int = min
        override fun setFatigue(f: Float) {}
        override fun setStage(stage: ShopAccessibilityService.Stage) {}
        override fun setError(msg: String) { lastError = msg }
        override fun counters(session: RecordStore.Session) {}
        override fun commitSession(session: RecordStore.Session) {}
        override fun finish() {}
        override fun stopRequested(): Boolean = stopped
        override fun isPaused(): Boolean = false
        override fun handledAdd(y: Int) {}
        override fun handledContains(y: Int): Boolean = false
        override fun handledClear() {}
        override fun errGoldCap(spent: Long): String = ""
        override fun errSkyBudget(): String = ""
        override fun markCompleted() {}
        override fun str(resId: Int, vararg args: Any): String = "str#" + resId
        override fun log(tag: String, msg: String) { logs.add(tag + " " + msg) }
        override fun traceFrame(stage: String, r: DetectionResult) {}
        override fun pressBack() {}
        override fun framesFor(budgetMs: Long): Int = 1
    }

    /* ================= ALLOW ================= */

    @Test
    fun allow_legal_point_taps_exactly_once() {
        val h = FakeHost()
        assertTrue(h.guardedTap(100f, 200f, "test"))
        assertEquals(100f to 200f, h.tappedAt)
        assertTrue(h.logs.any { it.startsWith("E7SA.Gate ALLOW") })
    }

    @Test
    fun allow_screen_edge_is_legal() {
        // 边界值 x==w / y==h 属合法（闸门判据是 x > w 才拒绝）
        val h = FakeHost(screenW = 1080, screenH = 2400)
        assertTrue(h.guardedTap(1080f, 2400f, "edge"))
    }

    /* ================= DENY ================= */

    @Test
    fun deny_when_session_stopped_and_never_taps() {
        val h = FakeHost(stopped = true)
        assertFalse(h.guardedTap(100f, 200f, "test"))
        assertNull(h.tappedAt)
        assertTrue(h.logs.any { it.contains("DENY test: session stopped") })
    }

    @Test
    fun deny_nan_point() {
        val h = FakeHost()
        assertFalse(h.guardedTap(Float.NaN, 200f, "nan"))
        assertNull(h.tappedAt)
    }

    @Test
    fun deny_negative_point() {
        val h = FakeHost()
        assertFalse(h.guardedTap(-1f, 200f, "neg"))
        assertNull(h.tappedAt)
    }

    @Test
    fun deny_point_beyond_screen_and_reports_error() {
        val h = FakeHost(screenW = 1080, screenH = 2400)
        assertFalse(h.guardedTap(1081f, 100f, "oob"))
        assertNull(h.tappedAt)
        assertTrue(h.lastError.isNotEmpty())
    }
}
