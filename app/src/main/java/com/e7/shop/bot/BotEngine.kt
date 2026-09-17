package com.e7.shop.bot

import android.graphics.Bitmap
import com.e7.shop.R
import com.e7.shop.ShopAccessibilityService.Stage
import com.e7.shop.data.AppConfig
import com.e7.shop.data.RecordStore

/**
 * 状态机（新架构第三层）：显式 FSM，负责流程与恢复。
 *
 * 每个阶段都是一小段确定性逻辑；任何异常画面（弹窗残留 / 非商店 / 截图失败）
 * 都有明确的下一阶段，绝不落入"WAITING 死循环"。中断后重新开始 =
 * 服务端无条件重建会话（generation 令牌 + 清空残留状态），引擎第一次动作
 * 永远是 SCAN —— 从"当前屏幕是什么"开始恢复。
 *
 * 关键安全语义（不可变）：
 *  - 宁可漏买，不可误买：S1 行验证 + S4 弹窗三重验证全 AND 才购买；
 *  - 全代码库最终购买 tap 唯一入口（S5）；失败绝不二次点击；
 *  - 弹窗残留 -> RECOVER 只点"取消"（关闭），绝不猜测购买；
 *  - 截图失败 -> 不刷新、不购买（fail-closed）。
 */
class BotEngine(
    private val host: Host,
    private val generation: Int,
    private val engine: RecognitionEngine
) {

    /** 服务需要提供给引擎的能力（截图/手势/状态/配置）。 */
    interface Host {
        val cfg: AppConfig
        val screenW: Int
        val screenH: Int
        fun screenshot(): Bitmap?
        fun tapExact(x: Float, y: Float)
        fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, baseMs: Long)
        fun sleepMs(ms: Long)
        fun hesitate()
        /** 拟人化随机等待（用设置里的操作间隔上下限）——低频路径使用，不拖慢主循环。 */
        fun delayRandom()
        fun daze()
        fun rest(opCount: Int)
        fun randInt(min: Int, max: Int): Int
        fun setFatigue(f: Float)
        fun setStage(stage: Stage)
        fun setError(msg: String)
        fun counters(session: RecordStore.Session)
        fun commitSession(session: RecordStore.Session)
        fun finish()
        fun stopRequested(): Boolean
        fun isPaused(): Boolean
        fun handledAdd(y: Int)
        fun handledContains(y: Int): Boolean
        fun handledClear()
        fun errGoldCap(spent: Long): String
        fun errSkyBudget(): String

        /**
         * 标记"本次停止是任务正常完成"（预算/持有量达上限），而不是出错。
         *
         * 用途：任务完成时可按设置自动熄屏省电；出错停止则**不熄屏**，
         * 因为玩家需要看屏幕排查问题。
         */
        fun markCompleted()
        fun str(resId: Int, vararg args: Any): String
        fun log(tag: String, msg: String)

        /**
         * 时序自适应：把"时间预算(ms)"换算成"该等多少帧"。
         *
         * 为什么需要：所有等待循环原先都写死帧数（如"最多 24 帧"），那是按开发机
         * （旗舰机，单帧约 0.85s）定的。换到中低端机（单帧 3.5s）同样的 24 帧就变成
         * **84 秒**——玩家会以为卡死，进而手动点屏幕，与机器人抢操作。
         * 改成按时间预算换算后，无论设备快慢，"最坏等待"都稳定在同一个量级。
         */
        fun framesFor(budgetMs: Long): Int
    }

    private enum class Phase { SCAN, SHOP_SCAN, BUYING, REFRESHING, RECOVER, WAIT, DONE }
    private enum class BuyResult { OK, FAIL, INERT }

    private var currentTarget: Candidate? = null
    private var waitStreak = 0
    private var recoverStreak = 0
    private var opCount = 0
    private var dbgCount = 0

    /** A3 店铺状态跟踪器（观测层）：只记录状态变迁供诊断，不参与任何决策。 */
    private val stateTracker = ShopStateTracker()

    /** 引擎入口：每个 start 只调用一次。结束时无条件提交会话并收尾。 */
    fun run(startGold: Long, startSkystones: Int) {
        val session = RecordStore.Session(
            startTime = System.currentTimeMillis(),
            startGold = startGold,
            startSkystones = startSkystones
        )
        stateTracker.reset()
        var phase = Phase.SCAN
        try {
            while (!host.stopRequested()) {
                // fatigue drift: 0 -> 0.4 over ~2 hours (anti-detection)
                val runMin = (System.currentTimeMillis() - session.startTime) / 60000.0
                host.setFatigue(
                    (runMin / Tuning.FATIGUE_RAMP_MINUTES * Tuning.FATIGUE_MAX)
                        .toFloat().coerceAtMost(Tuning.FATIGUE_MAX)
                )
                if (host.isPaused()) {
                    host.setStage(Stage.PAUSED)
                    host.sleepMs(500)
                    continue
                }
                host.log("E7SA.State", "phase=${phase.name}")
                phase = when (phase) {
                    Phase.SCAN -> doScan()
                    Phase.SHOP_SCAN -> doShopScan(session)
                    Phase.BUYING -> {
                        doBuy(session, currentTarget)
                        Phase.SCAN
                    }
                    Phase.REFRESHING -> doRefresh(session)
                    Phase.RECOVER -> doRecover()
                    Phase.WAIT -> doWait()
                    Phase.DONE -> return
                }
            }
        } catch (e: Exception) {
            host.setError(host.str(R.string.err_exception, e.message ?: ""))
        } finally {
            session.endTime = System.currentTimeMillis()
            host.commitSession(session)
            host.finish()
        }
    }

    /* ---------------- 工具 ---------------- */

    private fun shot(): Pair<Bitmap, DetectionResult>? {
        val b = host.screenshot() ?: return null
        val r = engine.analyze(b)
        dbgCount++
        if (dbgCount % 5 == 0) {
            host.log("E7SA.Percep", "engine=${r.engine} scene=${r.scene} ${r.diag}")
        }
        return b to r
    }

    private fun recycle(b: Bitmap?) {
        try {
            b?.recycle()
        } catch (e: Exception) {
            // 回收失败只影响内存占用，不影响本帧的识别与决策结果
            android.util.Log.w("E7SA.Mem", "bitmap recycle failed: " + e.message)
        }
    }

    private fun capsReached(s: RecordStore.Session): Boolean {
        val c = host.cfg
        val hit = (c.bookmarkCap > 0 && s.bookmarksGot >= c.bookmarkCap) ||
            (c.medalCap > 0 && s.medalsGot >= c.medalCap)
        if (hit) host.markCompleted()   // 持有量达标 = 正常完成任务（允许自动熄屏）
        return hit
    }

    private fun kindEnabled(kind: String): Boolean =
        if (kind == "bookmark") host.cfg.buyBookmark else host.cfg.buyMedal

    private fun countPurchase(s: RecordStore.Session, kind: String) {
        if (kind == "bookmark") {
            s.bookmarksGot += 5
            s.goldSpent += 184000L
        } else {
            s.medalsGot += 50
            s.goldSpent += 280000L
        }
        host.counters(s)
    }

    /* ---------------- 阶段实现 ---------------- */

    /**
     * SCAN：识别当前画面 -> 决定进入哪个阶段。
     * 弹窗残留 -> RECOVER（关闭）；非商店 -> WAIT；商店 -> SHOP_SCAN。
     */
    private fun doScan(): Phase {
        val p = shot() ?: run { waitReason = "WAIT_FOR_SCREENSHOT"; return Phase.WAIT }
        host.setStage(Stage.CHECKING)
        val scene = p.second.scene
        recycle(p.first)
        host.log("E7SA.Scene", "scene=$scene")
        return when (scene) {
            Scene.SHOP_LIST -> {
                waitStreak = 0
                recoverStreak = 0
                Phase.SHOP_SCAN
            }
            Scene.REFRESH_DLG -> {
                host.setError(host.str(R.string.err_recover_refresh_dlg))
                Phase.RECOVER
            }
            Scene.BUY_DLG -> {
                host.setError(host.str(R.string.err_recover_buy_dlg))
                Phase.RECOVER
            }
            Scene.OTHER -> {
                waitReason = "WAIT_FOR_SCENE"
                Phase.WAIT
            }
        }
    }

    /** WAIT 的细分原因（A5）：日志能回答"到底在等什么"，而不是只看到"6 分钟超时"。 */
    private var waitReason = "unknown"

    /** WAIT：非商店画面（加载/切走）。有超时哨兵，绝不死等。 */
    private fun doWait(): Phase {
        waitStreak++
        if (waitStreak > Tuning.WAIT_MAX_STREAK) {   // ~6 分钟仍不在商店 -> 明确停止并报告
            host.setError(host.str(R.string.err_wait_timeout))
            return Phase.DONE
        }
        host.setStage(Stage.WAITING)
        // 细分等待类型（A5）：WAIT_FOR_SCENE / WAIT_FOR_MODAL / WAIT_FOR_REFRESH...
        host.log("E7SA.Wait", "type=$waitReason elapsed=${waitStreak * 600}ms")
        host.sleepMs(600)
        return Phase.SCAN
    }

    /** RECOVER：残留弹窗恢复 —— 只点"取消"关闭，绝不猜测购买/确认。 */
    private fun doRecover(): Phase {
        recoverStreak++
        if (recoverStreak > Tuning.RECOVER_MAX_STREAK) {
            host.setError(host.str(R.string.err_dialog_stuck))
            return Phase.DONE
        }
        host.setStage(Stage.CHECKING)
        val p = shot() ?: return Phase.SCAN
        val cancelPt = when (p.second.scene) {
            Scene.REFRESH_DLG -> dialogCancel(p.second.lines)
            Scene.BUY_DLG -> dialogCancel(p.second.lines)
            else -> null
        }
        if (cancelPt != null) {
            host.log("E7SA.Tap", "recoverCancel tap=(${cancelPt.first.toInt()},${cancelPt.second.toInt()})")
            guardedTap(cancelPt.first, cancelPt.second, "recoverCancel")
        }
        recycle(p.first)
        host.sleepMs(700)
        // 低频拟人化：恢复流程结束后加一次随机等待（H2：让 delay() 真正被使用）
        host.delayRandom()
        return Phase.SCAN
    }

    /**
     * SHOP_SCAN：找目标 -> 买；无可买 -> 二次确认 -> 揭示 slot6 -> 刷新。
     * 任何购买失败/截图失败都不刷新越过未确认的画面。
     */
    private fun doShopScan(session: RecordStore.Session): Phase {
        if (capsReached(session)) return Phase.DONE
        val p = shot() ?: return Phase.SCAN
        val snap = p.second
        recycle(p.first)
        when (snap.scene) {
            Scene.REFRESH_DLG -> {
                host.setError(host.str(R.string.err_recover_refresh_dlg))
                return Phase.RECOVER
            }
            Scene.BUY_DLG -> {
                host.setError(host.str(R.string.err_recover_buy_dlg))
                return Phase.RECOVER
            }
            Scene.SHOP_LIST -> {}
            else -> return Phase.SCAN
        }
        // A3：记录本轮商店状态（相对上一轮的差异）。纯观测，不改变下面任何决策。
        host.log("E7SA.StateDiff", stateTracker.observe(snap))
        host.setStage(Stage.CHECKING)
        val targets = snap.candidates
            .filter { kindEnabled(it.kind) && !host.handledContains(it.rowY) }
        if (targets.isNotEmpty()) {
            for (t in targets) {
                if (tryBuy(session, t) == BuyResult.FAIL) {
                    // 失败分类 + 重试预算（P3）：暂时性失败允许下次再试，达到上限才标记硬失败。
                    // 关键：**失败也要继续推进到下滑**，不再回 SCAN 反复重试同一行
                    // —— 这正是「购买后不下滑」活锁的根治点。
                    noteRowFailure(t)
                }
                if (capsReached(session)) return Phase.DONE
            }
            // 买完（或处理完）可见目标后：下滑揭示 slot 6，把第 6 格也买掉再刷新
            return slot6Check(session)
        }
        // 漏检保护：等待画面稳定后重查一次同一屏
        host.sleepMs(800)
        val reP = shot()
        if (reP != null) {
            val re = reP.second
            recycle(reP.first)
            if (re.scene == Scene.SHOP_LIST) {
                val reTargets = re.candidates
                    .filter { kindEnabled(it.kind) && !host.handledContains(it.rowY) }
                if (reTargets.isNotEmpty()) {
                    for (t in reTargets) {
                        if (tryBuy(session, t) == BuyResult.FAIL) noteRowFailure(t)
                        if (capsReached(session)) return Phase.DONE
                    }
                    return Phase.REFRESHING
                }
            }
        }
        // 无目标：上滑揭示 slot 6，随后刷新
        return revealSlot6(session)
    }

    /** 买完可见目标后的 slot6 检查（滑到列表不再滚动为止，带滚动验证）。 */
    private fun slot6Check(session: RecordStore.Session): Phase {
        val w = host.screenW
        val h = host.screenH
        val xC = w * host.cfg.swipeCenterX
        val startLow = h * Tuning.SWIPE_LOW_Y
        val endHigh = h * Tuning.SWIPE_HIGH_Y
        val startHigh = h * Tuning.SWIPE_HIGH_Y
        val endLow = h * Tuning.SWIPE_LOW_Y
        var shotFails = 0
        // "滑到底"的判据是**画面不再变化**，而不是"画面动了一下"。
        //
        // 旧逻辑是 `if (moved) return REFRESHING`：一次滑动被判定为"动了"就立刻刷新。
        // 但 didScroll 的 6% 像素阈值卡在临界值上，同一个动作时判"动"时判"没动"，
        // 于是玩家看到的是**同一操作下滑 1 次 / 2 次 / 3 次随机出现**（实测三种都出现过）。
        // 更糟的是：判"动了"就刷新，可能在第 6 格尚未露出时就走了 —— 潜在漏买。
        //
        // 改为连续 2 次"没动"才算到底：行为确定（次数不再随机），且保证第 6 格确实露出。
        // 因为 E7 商店只有 6 格、一次下滑通常就到底，实际也就是多滑一次。
        var stillStreak = 0
        for (attempt in 0 until Tuning.SLOT6_MAX_ATTEMPTS) {
            val before = host.screenshot()
            if (before == null) { shotFails++; continue }
            val dur = if (attempt == 0) Tuning.SWIPE_FIRST_MS else Tuning.SWIPE_REPEAT_MS
            if (attempt % 3 == 1) host.swipe(xC, startHigh, xC, endLow, dur)
            else host.swipe(xC, startLow, xC, endHigh, dur)
            host.sleepMs(host.randInt(Tuning.SWIPE_SETTLE_MIN_MS, Tuning.SWIPE_SETTLE_MAX_MS).toLong())
            val afterBmp = host.screenshot()
            if (afterBmp == null) { shotFails++; recycle(before); continue }
            val moved = didScroll(before, afterBmp)
            recycle(before)
            // 轻量探测先行（见 RecognitionEngine.hasIconFast）：只回答"这一屏有没有书签/奖牌"。
            // 完整 analyze() 的 OCR 约占 390ms，而这个问题 YOLO 一个人就能答；
            // 没图标就跳过完整识别 —— 每次滑动省约 390ms。
            if (!engine.hasIconFast(afterBmp)) {
                recycle(afterBmp)
                if (moved) { stillStreak = 0; continue }
                stillStreak++
                if (stillStreak >= Tuning.SCROLL_STILL_STREAK) return Phase.REFRESHING   // 确认到底
                continue
            }
            val snap = engine.analyze(afterBmp)
            recycle(afterBmp)
            val targets = snap.candidates
                .filter { kindEnabled(it.kind) && !host.handledContains(it.rowY) }
            if (targets.isNotEmpty()) {
                for (t in targets) {
                    if (tryBuy(session, t) == BuyResult.FAIL) noteRowFailure(t)
                    if (capsReached(session)) return Phase.DONE
                }
                return Phase.SHOP_SCAN
            }
            if (moved) { stillStreak = 0; continue }
            stillStreak++
            if (stillStreak >= Tuning.SCROLL_STILL_STREAK) return Phase.REFRESHING
        }
        // 截图持续失败：本屏未知，不刷新（fail-closed）
        return if (shotFails >= Tuning.SHOT_FAIL_LIMIT) Phase.SCAN else Phase.REFRESHING
    }

    /** 无目标时的 slot6 揭示（快速上滑，滑到列表不再滚动为止）。 */
    private fun revealSlot6(session: RecordStore.Session): Phase {
        val w = host.screenW
        val h = host.screenH
        val xC = w * host.cfg.swipeCenterX
        var shotFails = 0
        // 与 slot6Check 同一判据：连续 2 次"没动"才算到底（详见该函数的说明）
        //
        // ⚠ 已知不一致（2026-09-17 代码审查发现，本次**未改动**）：
        //   本函数滑动几何取自 AppConfig：swipeBottomY(0.46) → swipeTopY(0.19)，跨度 0.27h；
        //   slot6Check 取自 Tuning：SWIPE_LOW_Y(0.86) → SWIPE_HIGH_Y(0.14)，跨度 0.72h。
        //   两者目标同为"揭示第 6 格"、判据也相同，跨度却相差约 2.7 倍。
        //   未擅自统一的原因：滑动几何直接决定真机手势幅度，改动必须先在真机回归台上
        //   验证"第 6 格确实露出且不漏买"，否则就是在没有证据的情况下替换一个已验证行为。
        var stillStreak = 0
        for (attempt in 0 until Tuning.SLOT6_MAX_ATTEMPTS) {
            val before = host.screenshot()
            if (before == null) { shotFails++; host.sleepMs(800); continue }
            host.swipe(
                xC, h * host.cfg.swipeBottomY, xC, h * host.cfg.swipeTopY,
                if (attempt == 0) Tuning.SWIPE_FIRST_MS else Tuning.SWIPE_REPEAT_MS
            )
            host.sleepMs(host.randInt(Tuning.SWIPE_SETTLE_MIN_MS, Tuning.SWIPE_SETTLE_MAX_MS).toLong())
            val afterBmp = host.screenshot()
            if (afterBmp == null) { shotFails++; recycle(before); host.sleepMs(800); continue }
            val moved = didScroll(before, afterBmp)
            recycle(before)
            // 轻量探测先行（同 slot6Check）：没图标就不必跑完整识别
            if (!engine.hasIconFast(afterBmp)) {
                recycle(afterBmp)
                if (moved) { stillStreak = 0; continue }
                stillStreak++
                if (stillStreak >= Tuning.SCROLL_STILL_STREAK) break
                continue
            }
            val snap = engine.analyze(afterBmp)
            recycle(afterBmp)
            val targets = snap.candidates
                .filter { kindEnabled(it.kind) && !host.handledContains(it.rowY) }
            if (targets.isNotEmpty()) {
                for (t in targets) {
                    if (tryBuy(session, t) == BuyResult.FAIL) noteRowFailure(t)
                    if (capsReached(session)) return Phase.DONE
                }
            }
            if (moved) { stillStreak = 0; continue }
            stillStreak++
            if (stillStreak >= Tuning.SCROLL_STILL_STREAK) break
        }
        return if (shotFails >= Tuning.SHOT_FAIL_LIMIT) Phase.SCAN else Phase.REFRESHING
    }

    /* ---------------- 最终点击闸门（P1 BeforeClickGate） ---------------- */

    /** 薄包装：统一走共享闸门（ClickGate.kt），传统与 AI 两条管线同一道门。 */
    private fun guardedTap(x: Float, y: Float, tag: String) = host.guardedTap(x, y, tag)

    /* ---------------- 购买流程（S0-S7，fail-closed） ---------------- */

    /**
     * 行级重试预算（P3）：rowY -> 已失败次数。
     * 区分「暂时性失败（可重试）」与「硬失败（放弃该行并报告）」，
     * 避免旧版「FAIL 就回 SCAN 无限重试同一行」的活锁。刷新后随 handledRows 一起清空。
     */
    private val rowAttempts = HashMap<Int, Int>()
    private val maxRowAttempts = Tuning.MAX_ROW_ATTEMPTS

    private fun noteRowFailure(t: Candidate) {
        val n = (rowAttempts[t.rowY] ?: 0) + 1
        rowAttempts[t.rowY] = n
        if (n >= maxRowAttempts) {
            // 硬失败：本会话不再尝试该行，并显式报告（不允许静默重试成活锁）
            host.handledAdd(t.rowY)
            host.setError(host.str(R.string.err_row_buy_failed, n, t.kind, t.rowY))
            host.log("E7SA.Row", "HARD FAIL kind=${t.kind} rowY=${t.rowY} attempts=$n -> give up row")
        } else {
            host.log("E7SA.Row", "TRANSIENT FAIL kind=${t.kind} rowY=${t.rowY} attempts=$n -> retry after next refresh")
        }
    }

    /** 购买一个目标：OK/INERT 都标记该行已处理（避免同一行无限循环点击）。 */
    private fun tryBuy(session: RecordStore.Session, t: Candidate): BuyResult {
        currentTarget = t
        val r = doBuy(session, t)
        if (r == BuyResult.OK || r == BuyResult.INERT) host.handledAdd(t.rowY)
        return r
    }

    private fun doBuy(session: RecordStore.Session, target: Candidate?): BuyResult {
        val t = target ?: return BuyResult.FAIL
        // **购买前必须检查金币/天空石预算**。
        // 此前预算闸门只在 doRefresh（刷新）里，购买路径没有 —— 后果是金币花光后
        // 仍会继续买（失败），然后继续刷新，**每刷新一次白烧 3 颗天空石**。
        if (host.cfg.goldSpendCap > 0 && session.goldSpent >= host.cfg.goldSpendCap) {
            host.setError(host.errGoldCap(session.goldSpent))
            return BuyResult.INERT
        }
        if (host.cfg.maxSkystones > 0 && session.skystonesSpent >= host.cfg.maxSkystones) {
            host.setError(host.errSkyBudget())
            return BuyResult.INERT
        }
        host.setStage(Stage.BUYING)
        // S0: 截图失败 -> 不购买
        val before = host.screenshot() ?: return BuyResult.FAIL
        val snap = engine.analyze(before)
        // S1: 商品行验证（文字 + 图标旁证）；已售空 → 放弃该行（不重试、不循环点击）
        if (rowSoldOut(snap, t.cy, t.tol)) { recycle(before); return BuyResult.INERT }
        var bmpNow = before
        var snapNow = snap
        if (!rowConfirmed(snap, before, t)) {
            // A4 观测优先：单帧可能正落在动画/刷新中间态（实测 sold-out 动画帧会多出候选、
            // 图标颜色也未必稳定）。先用新一帧复核，再决定"这一行买不了"——
            // 宁可多花一帧，也不要用一张坏帧放弃一个本来可买的行（漏买）。
            // 复核通过时后续 S2 一律使用新帧，绝不拿旧帧的文本 bbox 去点。
            recycle(before)
            host.sleepMs(host.randInt(250, 450).toLong())
            val again = shot() ?: return BuyResult.FAIL
            if (!rowConfirmed(again.second, again.first, t)) { recycle(again.first); return BuyResult.FAIL }
            bmpNow = again.first
            snapNow = again.second
            host.log("E7SA.Percept", "A4 re-observe confirmed kind=${t.kind} rowY=${t.rowY}")
        }
        // S2: 行"购买"按钮 = 与该行配对的"购买"文本 bbox 中心；找不到 -> 不购买
        val buyPt = rowBuyPoint(snapNow.lines, t.cy, t.tol, t.cx)
        if (buyPt == null) { recycle(bmpNow); return BuyResult.FAIL }
        host.log("E7SA.Tap", "rowBuy kind=${t.kind} tap=(${buyPt.first.toInt()},${buyPt.second.toInt()})")
        recycle(bmpNow)
        host.hesitate()
        guardedTap(buyPt.first, buyPt.second, "rowBuy")
        // S3: 等待购买弹窗出现（场景判定）
        var dialog: Pair<Bitmap, DetectionResult>? = null
        for (i in 0 until host.framesFor(6000)) {
            host.sleepMs(host.randInt(350, 600).toLong())
            val p = shot()
            if (p != null && p.second.scene == Scene.BUY_DLG) { dialog = p; break }
            if (p != null) recycle(p.first)
        }
        // A4 观测优先：弹窗刚出现时可能还在渐显动画里（内容未完全呈现），拿它做三重验证
        // 会失败 → 取消 → 重买（玩家实测到的"点了取消又重新买"）。这里补一个短等待并重新取帧。
        val stableDlg = if (dialog != null) {
            host.sleepMs(host.randInt(220, 360).toLong())
            val s = shot()
            if (s != null && s.second.scene == Scene.BUY_DLG) {
                recycle(dialog.first)
                s
            } else {
                if (s != null) recycle(s.first)
                dialog
            }
        } else null
        val dlg = stableDlg ?: run {
            // 点击后无弹窗：再看一眼当前行 —— 已售空/灰按钮（点击无效）→ 放弃该行，
            // 不再循环点击；否则视为普通失败，下一轮重查。
            host.sleepMs(600)
            val p2 = host.screenshot()
            var inert = false
            if (p2 != null) {
                val r2 = engine.analyze(p2)
                inert = r2.scene == Scene.SHOP_LIST &&
                    (rowSoldOut(r2, t.cy, t.tol) || rowConfirmed(r2, p2, t))
                recycle(p2)
            }
            return if (inert) BuyResult.INERT else BuyResult.FAIL
        }
        // S4: 弹窗三重验证（商品名 + 图标 + 价格），全 AND
        val confirmed = dialogConfirmed(dlg.second, dlg.first, t.kind)
        if (!confirmed) {
            recycle(dlg.first)
            // 弹窗出现了但三重验证没过。必须区分两种情况，否则会误伤正常购买：
            //  · 弹窗里的商品名**明确是别的东西** → 点错了行（定位错误），重试无意义 → 立即放弃该行，
            //    否则会陷入"点错 → 取消 → 再点错"的活锁（实测：奖牌在第2栏却点第1栏，反复循环）
            //  · 商品名读不到、或读到的与目标一致（只是价格/图标没通过）→ 暂时性失败 → 走重试预算
            val dlgKind = dialogItemKind(dlg.second.lines)
            if (dlgKind != null && dlgKind != t.kind) {
                host.handledAdd(t.rowY)
                host.setError(host.str(R.string.err_row_wrong_target, t.kind, t.rowY))
                host.log("E7SA.Row", "WRONG TARGET kind=${t.kind} rowY=${t.rowY} dialogKind=$dlgKind -> 立即放弃该行")
                return BuyResult.INERT
            }
            host.log("E7SA.Row", "dialog verify FAIL kind=${t.kind} rowY=${t.rowY} dialogKind=${dlgKind ?: "?"} -> 暂时性失败，走重试预算")
            return BuyResult.FAIL
        }
        // S5: 唯一最终购买入口：弹窗"购买"按钮 bbox 中心，全代码库唯一 tap
        val confirmPt = dialogBuy(dlg.second.lines, dlg.first.height)
        if (confirmPt == null) { recycle(dlg.first); return BuyResult.FAIL }
        host.log("E7SA.Tap", "finalPurchase kind=${t.kind} tap=(${confirmPt.first.toInt()},${confirmPt.second.toInt()})")
        recycle(dlg.first)
        host.hesitate()
        guardedTap(confirmPt.first, confirmPt.second, "finalPurchase")
        // S6: 购买成功验证：弹窗关闭（连续两次非 BUY_DLG）。失败不重按、不计数
        if (!waitDialogClosed()) return BuyResult.FAIL
        // S7: 计数
        countPurchase(session, t.kind)
        opCount++
        host.rest(opCount)
        return BuyResult.OK
    }

    private fun waitDialogClosed(): Boolean {
        var closedStreak = 0
        for (i in 0 until host.framesFor(10000)) {
            host.sleepMs(host.randInt(400, 700).toLong())
            val p = shot() ?: continue
            val scene = p.second.scene
            recycle(p.first)
            if (scene != Scene.BUY_DLG) {
                closedStreak++
                if (closedStreak >= Tuning.DIALOG_CLOSED_STREAK) return true
            } else {
                closedStreak = 0
            }
        }
        return false
    }

    /* ---------------- 刷新流程 ---------------- */

    private fun doRefresh(session: RecordStore.Session): Phase {
        // 预算闸门
        // D4 审计：原有「金币下限」闸门（cfg.minGold + session.startGold）已删除——
        // 所有入口都传 startBot(0,0)，startGold 恒为 0，闸门永久短路。金币保护由下面
        // 真正生效的 goldSpendCap 提供。
        if (host.cfg.goldSpendCap > 0 && session.goldSpent >= host.cfg.goldSpendCap) {
            host.setError(host.errGoldCap(session.goldSpent))
            host.markCompleted()   // 正常完成任务 → 允许按设置自动熄屏
            return Phase.DONE
        }
        if (host.cfg.maxSkystones > 0 && session.skystonesSpent >= host.cfg.maxSkystones) {
            host.setError(host.errSkyBudget())
            host.markCompleted()
            return Phase.DONE
        }
        host.setStage(Stage.REFRESHING)
        val p = shot() ?: return Phase.SCAN
        val snap = p.second
        recycle(p.first)
        if (snap.scene != Scene.SHOP_LIST) return Phase.SCAN
        // 刷新前指纹（P4）：只有证明"列表内容真的变了"才算刷新成功
        val beforeFp = frameFingerprint(snap)
        // "立即更新"按钮 = 模型文本 bbox 中心；找不到 -> 不盲点
        val refreshPt = refreshButton(snap.lines) ?: return Phase.SCAN
        host.log("E7SA.Tap", "refresh tap=(${refreshPt.first.toInt()},${refreshPt.second.toInt()})")
        guardedTap(refreshPt.first, refreshPt.second, "refresh")
        // 轮询弹窗出现（超时哨兵：5 轮）
        var dialog: Pair<Bitmap, DetectionResult>? = null
        for (i in 0 until host.framesFor(6000)) {
            host.sleepMs(450)
            val p2 = shot()
            if (p2 != null && p2.second.scene == Scene.REFRESH_DLG) { dialog = p2; break }
            if (p2 != null) recycle(p2.first)
        }
        val dlg = dialog ?: return Phase.SCAN
        // "确认"按钮 = 确认文字最右一条的 bbox 中心；找不到 -> 取消弹窗并恢复
        val confirmPt = refreshConfirm(dlg.second.lines)
        if (confirmPt == null) {
            recycle(dlg.first)
            host.setError(host.str(R.string.err_no_confirm))
            return Phase.RECOVER
        }
        host.log("E7SA.Tap", "refreshConfirm tap=(${confirmPt.first.toInt()},${confirmPt.second.toInt()})")
        recycle(dlg.first)
        host.hesitate()
        guardedTap(confirmPt.first, confirmPt.second, "refreshConfirm")

        // ---- VERIFY → COMMIT（P2 + P4）----
        // 状态驱动等待（国际服网络延迟自适应）：先证明指纹变了，再证明它稳定了。
        // 旧版是固定 sleep 520~950ms，网络慢时第一格还没出现就下滑/刷新。
        if (!waitRefreshed(beforeFp)) {
            // 未验证到刷新完成：不计数、不刷新越过（fail-closed），回到观察重新判断
            return Phase.SCAN
        }
        // 验证通过才提交：清空已处理行 + 重试预算，然后计数
        host.handledClear()
        rowAttempts.clear()
        session.refreshes++
        session.skystonesSpent += 3
        host.counters(session)
        host.daze()
        opCount++
        host.rest(opCount)
        return Phase.SHOP_SCAN
    }

    /**
     * 刷新验证（P4：先证明"变了"，再证明"稳定了"）：
     * 每轮重新识别，要求 fingerprint != 刷新前指纹（内容确实变了），
     * 且连续 2 帧指纹一致（画面已稳定）才判定刷新完成。
     *
     * 为什么不能只看"稳定"：网络慢时旧列表原封不动也会满足"连续两帧相同"，
     * 会被误判为刷新完成 → 提前下滑 → 第一格被跳过（实机已复现）。
     *
     * @return true = 刷新已验证完成；false = 超时未验证（调用方不计数、不下滑）
     */
    private fun waitRefreshed(beforeFp: String): Boolean {
        var lastFp: String? = null
        var stable = 0
        // 帧数 40 → 24：单帧成本约 0.9s（识别本身 0.64s），40 帧最坏要 45 秒，
        // 实测玩家感受就是"点了刷新之后等好久"。24 帧上限约 22 秒，够用且不再拖沓。
        // 时序自适应：按时间预算换算帧数（慢设备自动多给帧、快设备自动收紧）
        for (i in 0 until host.framesFor(20000)) {
            // 轮询间隔 400~700ms → 200~380ms：识别已经占了 0.64s，sleep 再叠 0.5s
            // 就让每帧逼近 1.2s。收紧后每帧约 0.9s。
            host.sleepMs(host.randInt(200, 380).toLong())
            val bmp = host.screenshot() ?: continue
            val r = engine.analyze(bmp)
            recycle(bmp)
            val fp = frameFingerprint(r)
            if (lastFp != null && fp == lastFp) stable++ else stable = 0
            lastFp = fp
            // stable >= 1 = 连续两帧一致即可。原先要求 3 帧（stable>=2）白等一帧约 1.2s；
            // "内容确实变了"由 fp != beforeFp 保证，"不再变化"由连续两帧一致保证，够稳。
            if (fp != beforeFp && stable >= 1) {
                host.log("E7SA.State", "refresh verified after ${i + 1} frames")
                return true
            }
        }
        host.setError(host.str(R.string.err_refresh_unverified))
        host.log("E7SA.State", "refresh NOT verified: fingerprint unchanged after 24 frames")
        return false
    }
}
