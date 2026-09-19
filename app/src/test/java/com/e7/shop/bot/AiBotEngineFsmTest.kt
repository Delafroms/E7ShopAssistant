package com.e7.shop.bot

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.e7.shop.ShopAccessibilityService.Stage
import com.e7.shop.data.AppConfig
import com.e7.shop.data.RecordStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **AI 管线（AiBotEngine）的 FSM 整机测试** —— 2026-09-19 补齐。
 *
 * 为什么必须也测这条管线：本项目踩过最贵的坑就是「两条管线各自演化」——
 * 同一条规则（记账时序、闸门返回值、失败分类）在传统引擎修了、AI 侧没同步。
 * 现在两条管线用**同一套假 Host** 约束，AI 侧至少有：
 *  ① 模型没加载时**明确报错并退出，绝不降级成传统点击**（AI 管线的立身之本）；
 *  ② 零感知输入下**一次都不点**（宁可不动，也不猜）；
 *  ③ 收到停止请求后立即退出，不做多余动作。
 *
 * 需要两处可测性改造（已做，生产行为不变）：AiBotEngine 构造函数可注入视觉引擎；
 * YoloDet.loaded 改 internal set（让测试能模拟"模型已加载"）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AiBotEngineFsmTest {

    private val w = 1280
    private val h = 720

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** 假视觉引擎：按场景返回固定结果，不依赖画面内容。 */
    private class FakeVision(private val scene: Scene) : RecognitionEngine {
        override val id = "fake-vision"
        override var sleepMode = false
        override fun hasIconFast(bmp: Bitmap) = scene == Scene.SHOP_LIST
        override fun analyze(bmp: Bitmap) =
            DetectionResult(scene, emptyList(), emptyList(), emptyList(), id, "fake")
    }

    private inner class FakeHost(override val cfg: AppConfig) : BotEngine.Host {
        override val screenW = w
        override val screenH = h
        var taps = 0
        var shots = 0
        var swipes = 0
        var stop = false
        var finished = false
        var lastError = ""
        var lastSession: RecordStore.Session? = null
        val logs = mutableListOf<String>()

        override fun screenshot(): Bitmap? {
            shots++
            if (shots > 200) stop = true      // 安全阀：编排失误也不让测试挂死
            return Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
        }
        override fun tapExact(x: Float, y: Float) { taps++ }
        override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, baseMs: Long) { swipes++ }
        override fun sleepMs(ms: Long) {}
        override fun hesitate() {}
        override fun delayRandom() {}
        override fun daze() {}
        override fun rest(opCount: Int) {}
        override fun randInt(min: Int, max: Int) = min
        override fun setFatigue(f: Float) {}
        override fun setStage(stage: Stage) {}
        override fun setError(msg: String) { lastError = msg }
        override fun counters(session: RecordStore.Session) { lastSession = session }
        override fun commitSession(session: RecordStore.Session) { lastSession = session }
        override fun finish() { finished = true; stop = true }
        override fun stopRequested() = stop
        override fun isPaused() = false
        override fun handledAdd(y: Int) {}
        override fun handledContains(y: Int) = false
        override fun handledClear() {}
        override fun errGoldCap(spent: Long) = "gold-cap"
        override fun errSkyBudget() = "sky-budget"
        override fun markCompleted() {}
        override fun str(resId: Int, vararg args: Any): String = "str(" + resId + ")"
        override fun log(tag: String, msg: String) { logs.add(tag + " " + msg) }
        override fun traceFrame(stage: String, r: DetectionResult) {}
        override fun pressBack() {}
        override fun framesFor(budgetMs: Long) = 3
    }

    @After
    fun resetModelFlag() {
        // 全局状态：不清理会污染同进程里的其它用例
        YoloDet.loaded = false
    }

    private fun cfg() = AppConfig(ctx()).apply { goldSpendCap = 0L; maxSkystones = 0 }

    /** ① 模型没加载：必须明确报错并退出，**绝不降级成传统点击**（AI 管线的立身之本）。 */
    @Test
    fun ai_refuses_to_run_without_model_and_never_falls_back() {
        YoloDet.loaded = false
        val host = FakeHost(cfg())
        AiBotEngine(host, 1, FakeVision(Scene.SHOP_LIST)).run(0L, 0)

        assertEquals("模型不可用时一次都不该点（更不能回退传统点击）", 0, host.taps)
        assertTrue("必须调用 finish() 结束会话", host.finished)
        assertTrue("必须留下明确错误，而不是静默退出", host.lastError.isNotEmpty())
    }

    /** ② 零感知：宁可不动也不猜 —— 一次都不点、不滑动。 */
    @Test
    fun ai_never_taps_blindly_without_perception() {
        YoloDet.loaded = true
        val host = FakeHost(cfg())
        AiBotEngine(host, 1, FakeVision(Scene.OTHER)).run(0L, 0)

        assertEquals("没有任何可确认目标时不得点击", 0, host.taps)
        assertEquals("更不得滑动（滑动会改变画面，属主动操作）", 0, host.swipes)
    }

    /** ③ 停止请求：立即退出，不做多余动作。 */
    @Test
    fun ai_stops_immediately_when_asked() {
        YoloDet.loaded = true
        val host = FakeHost(cfg())
        host.stop = true      // 一进循环就该退出
        AiBotEngine(host, 1, FakeVision(Scene.SHOP_LIST)).run(0L, 0)

        assertEquals("已请求停止时不得点击", 0, host.taps)
        assertTrue("会话必须收尾（commitSession/finish）", host.finished)
    }
}