package com.e7.shop.bot

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.e7.shop.ShopAccessibilityService.Stage
import com.e7.shop.data.AppConfig
import com.e7.shop.data.RecordStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 决策状态机（FSM）整机测试 —— 2026-09-19 引入 Robolectric 后新增。
 *
 * 为什么必须跑整机：烧钱的那个 P0（记账被验证门控）里，每一个函数单独看都是对的 ——
 * 错的是顺序（钱花了却没记账）。纯函数单测抓不到这类问题，只有把状态机推着走一遍、
 * 在点下确认之后立刻检查账本，才能发现。
 *
 * 怎么跑起来：Robolectric 提供真 Bitmap + 真 Context，于是引擎能真的走
 * OBSERVE → BUYING → CONFIRM → VERIFY 全流程；画面内容由 Director 按已点击次数编排
 * （比固定帧队列稳健：不依赖内部调用次数）。
 *
 * 时间被压平：FakeHost.framesFor 一律返回 3 帧，否则一轮 12000ms 的等待会让测试跑几分钟。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotEngineFsmTest {

    private val w = 1280
    private val h = 720
    private val rowY = 360f
    private val tol = 80f

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /* ---------------- 假 Host：记录一切动作 ---------------- */

    private inner class FakeHost(override val cfg: AppConfig) : BotEngine.Host {
        override val screenW = w
        override val screenH = h
        var taps = 0
        var shots = 0
        var swipes = 0
        var stop = false
        var completed = false
        var finished = false
        var lastError = ""
        var lastSession: RecordStore.Session? = null
        val handled = mutableSetOf<Int>()

        override fun screenshot(): Bitmap? {
            shots++
            // 安全阀：任何编排失误都不该让测试挂死（真机上由 stopRequested 兜底）
            if (shots > 300) stop = true
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
        override fun handledAdd(y: Int) { handled.add(y) }
        override fun handledContains(y: Int) = handled.contains(y)
        override fun handledClear() { handled.clear() }
        override fun errGoldCap(spent: Long) = "gold-cap"
        override fun errSkyBudget() = "sky-budget"
        override fun markCompleted() { completed = true }
        override fun str(resId: Int, vararg args: Any): String = "str(" + resId + ")"
        val logs = mutableListOf<String>()
        override fun log(tag: String, msg: String) {
            logs.add(tag + " " + msg)
            println("[HOST] " + tag + " " + msg)
        }
        override fun traceFrame(stage: String, r: DetectionResult) {}
        override fun pressBack() {}
        /** 把最坏等待压成 3 帧，测试才跑得动。 */
        override fun framesFor(budgetMs: Long) = 3
    }

    /* ---------------- 编排：按已点击次数决定画面 ---------------- */

    private inner class Director(private val host: FakeHost, private val dialogEverAppears: Boolean) :
        RecognitionEngine {
        override val id = "director"
        override var sleepMode = false
        override fun hasIconFast(bmp: Bitmap) = true

        // 编排依据"闸门放行了哪一种点击"，而不是"点了几次" ——
        // 前者与真实语义一一对应（rowBuy=点了行内购买 / finalPurchase=点了弹窗确认），
        // 后者会被任何一次额外的点击打乱（实测：计数错位导致弹窗永远给不出来，
        // 引擎于是按设计反复重试同一行 45 次）。
        private val rowBuyTapped get() = host.logs.any { it.startsWith("E7SA.Gate ALLOW rowBuy") }
        private val finalTapped get() = host.logs.any { it.startsWith("E7SA.Gate ALLOW finalPurchase") }

        override fun analyze(bmp: Bitmap): DetectionResult = when {
            finalTapped -> shopStillBuyable()          // 确认已点下 → 回到商店列表（正向证据）
            rowBuyTapped && dialogEverAppears -> buyDialog()
            rowBuyTapped -> shopStillBuyable()          // 弹窗"不出现"的场景
            else -> shopWithBookmark()
        }

        /** 商店列表：第 rowY 行有可买的誓约书签（带 YOLO 图标框 → 行验证直接通过）。 */
        private fun shopWithBookmark(): DetectionResult {
            val lines = listOf(
                PpOcr.OcrLine("誓约书签", 300f, rowY, 0.95f),
                PpOcr.OcrLine("购买", 900f, rowY, 0.95f)
            )
            val cand = listOf(Candidate("bookmark", 300f, rowY, 0.95f, tol, listOf("test")))
            val boxes = listOf(YoloDet.Box(0, 300f, rowY, 60f, 60f, 0.95f))
            return DetectionResult(Scene.SHOP_LIST, lines, cand, boxes, id, "test")
        }

        /** 购买弹窗：商品名 + 「购买」按钮（dialogBuy 需要）。 */
        private fun buyDialog(): DetectionResult {
            // 注意：dialogBuy 要求**先看到「取消」**，且购买按钮必须在它右边
            // （防误点设计）——假弹窗少了取消按钮，确认点击会被正确拦下。
            val lines = listOf(
                PpOcr.OcrLine("誓约书签", 300f, 300f, 0.95f),
                PpOcr.OcrLine("184000", 300f, 380f, 0.95f),
                PpOcr.OcrLine("取消", 500f, 500f, 0.95f),
                PpOcr.OcrLine("确认购买", 900f, 500f, 0.95f)
            )
            // 三重验证（dialogConfirmed）= 商品名 + **图标旁证** + 价格唯一匹配。
            // 图标旁证必须有：弹窗里的商品图标会被 YOLO 检出（真机如此）。
            val boxes = listOf(YoloDet.Box(0, 300f, 300f, 60f, 60f, 0.95f))
            return DetectionResult(Scene.BUY_DLG, lines, emptyList(), boxes, id, "test")
        }

        /** 点击没生效：弹窗始终不出现，而这一行仍然可购买。 */
        private fun shopStillBuyable(): DetectionResult = shopWithBookmark()
    }
    /* ---------------- 用例 ---------------- */

    /**
     * 钱路径（P0 回归）：买到一件 → 立刻记账 → 金币闸门在下一轮生效并正常收工。
     *
     * 旧版把 countPurchase 放在验证通过之后：验证不过 → 钱花了、账没记 →
     * goldSpendCap 永不触发，可以整夜买/刷。这条用例钉死这个顺序。
     */
    @Test
    fun purchase_commits_accounting_then_budget_gate_stops() {
        val cfg = AppConfig(ctx())
        cfg.goldSpendCap = 1L
        cfg.maxSkystones = 0
        val host = FakeHost(cfg)
        val engine = BotEngine(host, 1, Director(host, dialogEverAppears = true))

        engine.run(0L, 0)

        assertTrue(
            "点击次数不足：taps=" + host.taps + " shots=" + host.shots + " swipes=" + host.swipes +
                "\n最近日志：\n" + host.logs.takeLast(18).joinToString("\n"),
            host.taps >= 2
        )
        val s = host.lastSession
        assertTrue("会话必须被记录", s != null)
        assertEquals(
            "买到一件书签就应立刻记账（ACTION→COMMIT）；taps=" + host.taps +
                " bookmarks=" + s!!.bookmarksGot + " refreshes=" + s.refreshes +
                "\n最近日志：\n" + host.logs.takeLast(26).joinToString("\n"),
            // 注意：BOOKMARK_PRICE 是"一次购买 5 个"的**整价**（184000），不是单价
            Tuning.BOOKMARK_PRICE, s.goldSpent
        )
        assertEquals("一次购买应记 5 个书签", Tuning.BOOKMARK_PER_BUY, s.bookmarksGot)
        assertTrue("已花 ≥ 上限 → 闸门必须触发（并标记为正常完成）", host.completed)
        assertTrue("该行应被标记已处理，避免重复点击", host.handled.contains(rowY.toInt()))
    }

    /**
     * 漏买回归：点了购买但弹窗没出现、且这一行仍然可购买 —— 说明这次点击没生效。
     * 该行不能被标记已处理（旧版会标记 → 该行被永久跳过 = 整屏漏买）。
     */
    @Test
    fun click_without_dialog_does_not_mark_row_handled() {
        val cfg = AppConfig(ctx())
        cfg.goldSpendCap = 0L
        cfg.maxSkystones = 0
        val host = FakeHost(cfg)
        val engine = BotEngine(host, 1, Director(host, dialogEverAppears = false))

        engine.run(0L, 0)

        assertTrue("点击发生过", host.taps >= 1)
        assertFalse(
            "点击没生效时不得标记该行已处理（否则整屏漏买）",
            host.handled.contains(rowY.toInt())
        )
    }

    /** fail-closed：完全没有任何感知结果时绝不能盲点；循环必须自己收敛。 */
    @Test
    fun no_perception_never_taps_blindly() {
        val cfg = AppConfig(ctx())
        cfg.goldSpendCap = 0L
        cfg.maxSkystones = 0
        val host = FakeHost(cfg)
        val blind = object : RecognitionEngine {
            override val id = "blind"
            override var sleepMode = false
            override fun hasIconFast(bmp: Bitmap) = false
            override fun analyze(bmp: Bitmap) =
                DetectionResult(Scene.OTHER, emptyList(), emptyList(), emptyList(), id, "empty")
        }
        val engine = BotEngine(host, 1, blind)

        engine.run(0L, 0)

        assertEquals("没有任何可确认的目标时，一次都不该点", 0, host.taps)
        // 注意：**它不会自己停** —— 这是 2026-09-17 玩家的明确要求：
        // 深夜"停一次"等于把剩下几小时全变成漏买，所以非商店画面一律一直等下去。
        // 这里只断言"等过、且一次都没点"，收敛由外部停止负责（安全阀只是防止测试挂死）。
        assertTrue("应当尝试过取帧（说明它进入等待而不是崩溃退出）", host.shots > 0)
        assertTrue("零感知下不应发生任何滑动", host.swipes == 0)
    }
}
