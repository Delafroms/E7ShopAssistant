package com.e7.shop.bot

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.e7.shop.ShopAccessibilityService.Stage
import com.e7.shop.data.AppConfig
import com.e7.shop.data.RecordStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 红队测试：**主动构造能骗过引擎的场景**，看它会不会误买 / 误判 / 乱点。
 *
 * 与 BotEngineFsmTest 的区别：那边验证「按设计工作」（钱路径、漏买回归）；
 * 这边验证「**搞不坏**」——攻击面包括：
 *  ① 识别层欺骗：行说书签、弹窗说奖牌（点错行）；假购买按钮放在左边；价格有歧义；售罄行
 *  ② 时序欺骗：确认点击被系统对话框吃掉 → 弹窗不消失（不得写出假成功）
 *
 * 每条用例要么证明防线成立（保留为回归护栏），要么暴露真实漏洞（本轮修）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RedTeamFsmTest {

    private val w = 1280
    private val h = 720
    private val rowY = 360f
    private val tol = 80f

    private fun ctx() = ApplicationProvider.getApplicationContext<android.content.Context>()

    /** 攻击场景：决定"商店行"和"弹窗"长什么样。 */
    private enum class Attack { MISMATCHED_DIALOG_KIND, FAKE_BUY_BUTTON_LEFT, AMBIGUOUS_PRICE, SOLD_OUT, DIALOG_NEVER_CLOSES, REFRESH_CONFIRM_MISSES }

    private inner class FakeHost(override val cfg: AppConfig) : BotEngine.Host {
        override val screenW = w
        override val screenH = h
        var taps = 0
        var shots = 0
        var stop = false
        var lastError = ""
        var lastSession: RecordStore.Session? = null
        val handled = mutableSetOf<Int>()
        val logs = mutableListOf<String>()
        val gateTags = mutableListOf<String>()

        override fun screenshot(): Bitmap? {
            shots++
            if (shots > 400) stop = true
            return Bitmap.createBitmap(screenW, screenH, Bitmap.Config.ARGB_8888)
        }
        override fun tapExact(x: Float, y: Float) { taps++ }
        override fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, baseMs: Long) {}
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
        override fun finish() { stop = true }
        override fun stopRequested() = stop
        override fun isPaused() = false
        /** 历史记录：handled 会在刷新时被引擎按设计清空（"刷新后重新给机会"），
         *  所以断言"是否曾标记过某行"必须看历史，不能看最终集合。 */
        val handledHistory = mutableListOf<Int>()
        override fun handledAdd(y: Int) { handled.add(y); handledHistory.add(y) }
        override fun handledContains(y: Int) = handled.contains(y)
        override fun handledClear() { handled.clear() }
        override fun errGoldCap(spent: Long) = "gold-cap"
        override fun errSkyBudget() = "sky-budget"
        override fun markCompleted() {}
        override fun str(resId: Int, vararg args: Any): String = "str(" + resId + ")"
        override fun log(tag: String, msg: String) {
            logs.add(tag + " " + msg)
            println("[RT] " + tag + " " + msg)
            if (msg.startsWith("ALLOW")) gateTags.add(msg.substringAfter("ALLOW ").substringBefore(" "))
        }
        override fun traceFrame(stage: String, r: DetectionResult) {}
        override fun pressBack() {}
        override fun framesFor(budgetMs: Long) = 3
    }

    /** 按攻击场景编排画面。 */
    private inner class Attacker(private val host: FakeHost, private val attack: Attack) : RecognitionEngine {
        override val id = "attacker"
        override var sleepMode = false
        override fun hasIconFast(bmp: Bitmap) = true

        /**
         * 编排依据「弹窗**此刻**是否还开着」（2026-09-19 修正）。
         *
         * 旧版用粘滞标志（"历史上点过 rowBuy 就永远返回弹窗"）——那会造出一个
         * 引擎永远走不出去的世界：它在 SCAN 阶段看到弹窗 → RECOVER → 又看到弹窗 ……
         * 三条红队用例因此全部误报。现在按最近一次动作推断：
         *  · 点过行内购买、且之后没被取消 → 弹窗开着；
         *  · RECOVER 点过取消 → 弹窗关上（引擎得以回到商店列表继续）；
         *  · 确认点过之后：正常场景关闭，"永不关闭"攻击场景保持打开。
         */
        private fun lastIndexOf(tag: String) = host.gateTags.indexOfLast { it == tag }

        private val dialogOpen: Boolean
            get() {
                val buy = lastIndexOf("rowBuy")
                if (buy < 0) return false
                if (lastIndexOf("recoverCancel") > buy) return false
                val fin = lastIndexOf("finalPurchase")
                return if (fin > buy) attack == Attack.DIALOG_NEVER_CLOSES else true
            }

        /** 刷新弹窗是否开着：点过「立即更新」、之后既没被取消也没被确认。 */
        private val refreshDlgOpen: Boolean
            get() {
                val r = lastIndexOf("refresh")
                if (r < 0) return false
                if (lastIndexOf("recoverCancel") > r) return false
                return lastIndexOf("refreshConfirm") < r
            }

        override fun analyze(bmp: Bitmap): DetectionResult =
            when {
                // 攻击⑥：整屏无目标 → 引擎只能走刷新；而刷新确认点下后列表**一帧都不变**，
                // 模拟"确认键点到了取消"（弹窗关了、天空石没花、列表也没换）。
                attack == Attack.REFRESH_CONFIRM_MISSES && refreshDlgOpen -> refreshDialog()
                attack == Attack.REFRESH_CONFIRM_MISSES -> emptyShop()
                dialogOpen -> dialog()
                else -> shopRow()
            }

        /** 无目标的商店列表：只留「立即更新」，逼引擎走刷新路径。 */
        private fun emptyShop(): DetectionResult =
            DetectionResult(
                Scene.SHOP_LIST,
                listOf(PpOcr.OcrLine("立即更新", 900f, 200f, 0.95f)),
                emptyList(), emptyList(), id, "redteam"
            )

        /** 刷新确认弹窗：含「更新+天空石」与「确认」，sceneOf 判为 REFRESH_DLG。 */
        private fun refreshDialog(): DetectionResult =
            DetectionResult(
                Scene.REFRESH_DLG,
                listOf(
                    PpOcr.OcrLine("要消耗天空石立即更新吗？", 300f, 300f, 0.95f),
                    PpOcr.OcrLine("取消", 500f, 500f, 0.95f),
                    PpOcr.OcrLine("确认", 900f, 500f, 0.95f)
                ),
                emptyList(), emptyList(), id, "redteam"
            )

        /** 商店列表：第 rowY 行一件誓约书签（可买），可带攻击变形。 */
        private fun shopRow(): DetectionResult {
            val buyCx = if (attack == Attack.FAKE_BUY_BUTTON_LEFT) 100f else 900f
            val name = if (attack == Attack.SOLD_OUT) "誓约书签 售罄" else "誓约书签"
            val lines = listOf(
                PpOcr.OcrLine(name, 300f, rowY, 0.95f),
                PpOcr.OcrLine("购买", buyCx, rowY, 0.95f)
            )
            val cand = listOf(Candidate("bookmark", 300f, rowY, 0.95f, tol, listOf("redteam")))
            val boxes = listOf(YoloDet.Box(0, 300f, rowY, 60f, 60f, 0.95f))
            return DetectionResult(Scene.SHOP_LIST, lines, cand, boxes, id, "redteam")
        }

        /** 购买弹窗：按攻击场景变形。 */
        private fun dialog(): DetectionResult {
            val itemName = if (attack == Attack.MISMATCHED_DIALOG_KIND) "神秘奖牌" else "誓约书签"
            val lines = mutableListOf(
                PpOcr.OcrLine(itemName, 300f, 300f, 0.95f),
                PpOcr.OcrLine("取消", 500f, 500f, 0.95f),
                PpOcr.OcrLine("确认购买", 900f, 500f, 0.95f)
            )
            if (attack == Attack.AMBIGUOUS_PRICE) {
                // 2026-09-20 修正：这里的注释一直写的是"184000 与 999999"（真歧义），
                // 实现塞的却是**两个 184000** —— 用例被调成"能过"的样子，反而掩盖了
                // 旧判据的语义倒置：同价重复被拒（→ 真机 2472 次活锁），
                // 而"价格根本对不上"这种真异常反被放行。
                // 现在按注释本意构造：弹窗价格与期望不符 → 价格项必须拒绝。
                lines.add(PpOcr.OcrLine("999999", 300f, 380f, 0.95f))
            } else {
                lines.add(PpOcr.OcrLine("184000", 300f, 380f, 0.95f))
            }
            // 图标旁证：书签的 YOLO 框（真机弹窗里商品图标会被检出）
            val boxes = if (attack == Attack.MISMATCHED_DIALOG_KIND)
                listOf(YoloDet.Box(1, 300f, 300f, 60f, 60f, 0.95f))   // cls=1 = medal
            else listOf(YoloDet.Box(0, 300f, 300f, 60f, 60f, 0.95f))
            return DetectionResult(Scene.BUY_DLG, lines, emptyList(), boxes, id, "redteam")
        }
    }

    private fun runAttack(attack: Attack): Pair<FakeHost, RecordStore.Session?> {
        val cfg = AppConfig(ctx())
        cfg.goldSpendCap = 0L
        cfg.maxSkystones = 0
        val host = FakeHost(cfg)
        BotEngine(host, 1, Attacker(host, attack)).run(0L, 0)
        return host to host.lastSession
    }

    /* ---------------- 攻击用例 ---------------- */

    /**
     * 攻击①：**点错行** —— 行里是书签，弹窗里却是奖牌。
     * 期望：引擎立即放弃该行（标记已处理，避免"点错→取消→再点错"活锁），且**不确认购买**。
     */
    @Test
    fun attack_mismatched_dialog_kind_is_abandoned() {
        val (host, session) = runAttack(Attack.MISMATCHED_DIALOG_KIND)
        assertFalse("不得点弹窗确认（点错行的确认 = 误买）", host.gateTags.contains("finalPurchase"))
        assertTrue(
            "必须标记该行已处理，否则会陷入「点错→取消→再点错」活锁；handled=" + host.handled +
                " taps=" + host.taps + "\n最近日志：\n" + host.logs.takeLast(20).joinToString("\n"),
            host.handledHistory.contains(rowY.toInt())
        )
        assertEquals("不得记账（没买到）", 0, session!!.bookmarksGot)
    }

    /**
     * 攻击②：**假购买按钮** —— OCR 读到的"购买"在商品图标**左侧**（真按钮在右边）。
     * 期望：不点（rowBuyPoint 要求购买键在候选右侧）。
     */
    @Test
    fun attack_fake_buy_button_on_the_left_is_ignored() {
        val (host, _) = runAttack(Attack.FAKE_BUY_BUTTON_LEFT)
        assertEquals("左侧的假购买键不得被点击", 0, host.taps)
        assertTrue("找不到真按钮属确定性失败，必须封顶", host.logs.any { it.contains("暂时放弃该行") })
    }

    /**
     * 攻击③：**价格对不上** —— 弹窗里是誓约书签，但价格显示 999999（不是 184000）。
     * 期望：三重验证里的价格项拒绝，不确认购买，并走封顶预算。
     */
    @Test
    fun attack_ambiguous_price_blocks_confirmation() {
        val (host, session) = runAttack(Attack.AMBIGUOUS_PRICE)
        assertFalse("价格对不上时不得确认购买", host.gateTags.contains("finalPurchase"))
        assertEquals("不得记账", 0, session!!.bookmarksGot)
        assertTrue(
            "确定性失败必须被封顶（出现「暂时放弃该行」而不是无限锤同一个按钮）",
            host.logs.any { it.contains("暂时放弃该行") }
        )
    }

    /**
     * 攻击④：**售罄行** —— 行文本带"售罄"。
     * 期望：一次都不点（售罄行没有购买按钮可点，且不应重试）。
     */
    @Test
    fun attack_sold_out_row_is_never_tapped() {
        val (host, _) = runAttack(Attack.SOLD_OUT)
        assertEquals("售罄行不得点击", 0, host.taps)
    }

    /**
     * 攻击⑤：**点击被吃掉** —— 确认点下后弹窗始终不消失（系统对话框拦截输入）。
     * 期望：**绝不写出假成功**（bookmarksGot 必须为 0），并留下明确记录。
     * 这条直接对应"假阳性比没有记录更危险"的举证原则。
     */
    @Test
    fun attack_dialog_never_closes_never_writes_fake_success() {
        val (host, session) = runAttack(Attack.DIALOG_NEVER_CLOSES)
        assertTrue("确认点击应当发生过", host.taps >= 2)
        // 设计行为（举证原则）：超时无法确认时**保守计数**（钱可能已花），但必须显式标注，
        // 且**绝不能写一条 PURCHASE COMMIT（假成功）** —— 那才是真正的假阳性。
        assertFalse(
            "弹窗没关时绝不能写 PURCHASE COMMIT（假成功记录）",
            host.logs.any { it.contains("PURCHASE COMMIT") }
        )
        assertTrue(
            "必须留下 UNCONFIRMED 记录（事后可复盘：这一件是「可能买到」而不是「确认买到」）",
            host.logs.any { it.contains("UNCONFIRMED") }
        )
    }

    /**
     * 攻击⑥（第二轮红队）：**刷新确认点到了「取消」** —— 弹窗关了，但列表一帧都没变。
     *
     * 引擎会记账（+3 天空石，保守方向对）、判未验证、退避、回到 SCAN，然后**又去刷新**：
     * 一圈一圈直到预算上限耗尽。这条用例钉的是"必须有止损"：连续点错会把整晚挂机时间
     * （情况 A：没花钱但记账虚高 → 提前停机）或整份天空石预算（情况 B：真花了钱）全部吃掉。
     */
    @Test
    fun attack_refresh_confirm_lands_on_cancel_must_not_burn_forever() {
        val (host, session) = runAttack(Attack.REFRESH_CONFIRM_MISSES)
        val refreshes = session?.refreshes ?: 0
        println("[RT] 刷新次数=" + refreshes + " 天空石=" + (session?.skystonesSpent ?: 0) +
            " 点击=" + host.taps + " 截图=" + host.shots)
        assertTrue("确认点击应当发生过", host.logs.any { it.contains("ALLOW refreshConfirm") } || host.taps >= 2)
        assertTrue(
            "刷新连续未验证必须有止损：不允许无限「刷新→记账→未验证→再刷新」，实际 refreshes=" + refreshes +
                "\n最近日志：\n" + host.logs.takeLast(25).joinToString("\n"),
            refreshes <= 6
        )
    }
}