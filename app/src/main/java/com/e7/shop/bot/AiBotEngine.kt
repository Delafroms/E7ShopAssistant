package com.e7.shop.bot

import android.graphics.Bitmap
import com.e7.shop.R
import com.e7.shop.ShopAccessibilityService.Stage
import com.e7.shop.data.RecordStore

/**
 * AI 点击引擎（V1 独立闭环，与传统 BotEngine 完全解耦、绝不互相接管）：
 *
 *   眼睛 = YoloEngine（YOLO 图标检测 + OCR 语义，每帧实时识别）
 *   决策 = 本类（观察 → 判断 → 点击 → 再观察）
 *   执行 = Host（Accessibility 手势）
 *
 * 硬性约束：
 *  - 不调用任何传统点击函数（rowBuyPoint / dialogBuy / refreshButton /
 *    refreshConfirm / dialogCancel），也不使用固定/录制坐标；
 *  - 每次点击前先判断目标与按钮状态（金色=可点击 / 灰色=售空）；
 *  - 无法确定下一步时明确报告（err_ai_undecided）并结束当前目标，
 *    绝不回退到传统点击逻辑。
 */
class AiBotEngine(
    private val host: BotEngine.Host,
    private val generation: Int,
    /**
     * 视觉引擎：默认仍是 YoloEngine（生产行为不变）。
     *
     * 参数化只为可测性（2026-09-19）：AI 管线原先硬编码 `YoloEngine()`，
     * 于是 FSM 整机测试无法注入假视觉 —— 而"两条管线各自演化"正是本项目的系统性风险，
     * 必须能用同一套假 Host 同时约束两条管线。
     */
    private val vision: RecognitionEngine = YoloEngine()
) {
    private val planner = ClickPlanner()

    private enum class Phase {
        OBSERVE, DECIDE_TARGETS, VERIFY_DIALOG, CONFIRM_PURCHASE, VERIFY_CLOSED,
        REVEAL_SLOT, CLICK_REFRESH, CONFIRM_REFRESH, RECOVER, WAIT,
        /** 网络异常弹窗：点「点击重试」后回到正常流程（2026-09-19 新增）。 */
        RETRY_NET,
        DONE
    }

    private var phase = Phase.OBSERVE
    private var target: Candidate? = null
    private var lastDlg: Pair<Bitmap, DetectionResult>? = null
    /** 刷新前画面指纹（P4）：用于验证"内容真的变了"。 */
    private var refreshBeforeFp: String? = null
    private var waitStreak = 0
    private var recoverStreak = 0
    private var undecidedStreak = 0
    private var opCount = 0
    /** 感知诊断计数（每 3 轮打一次识别细节，用于定位"识别不到候选"类问题）。 */
    private var dbgCount = 0
    private var session: RecordStore.Session? = null

    /* ---- 会话级感知统计：与 BotEngine 同口径，两条管线的日志才能横向对比 ---- */
    private var seenCandidates = 0
    private var droppedByHandledRow = 0

    /** 连续多少轮"进了决策但既没买也没刷新"——死循环兜底，见 decideTargets。 */
    private var idleDecideStreak = 0

    /** 网络异常弹窗连续重试次数（见 retryNet）。 */
    private var netRetryStreak = 0

    /** A3 店铺状态跟踪器（观测层）：只记录状态变迁供诊断，不参与任何决策。 */
    private val stateTracker = ShopStateTracker()

    fun run(startGold: Long, startSkystones: Int) {
        if (!YoloDet.loaded) {
            // AI 点击的"眼睛"不可用：明确报告，绝不降级为传统点击
            host.setError(host.str(R.string.err_ai_yolo_missing))
            host.finish()
            return
        }
        // ⚠ 假开关修复（2026-09-18，玩家实测"开不开睡眠模式都一样"）：
        // 本管线的视觉引擎是硬编码的 YoloEngine()，而 sleepMode 只在
        // ShopAccessibilityService.engineForRun() 里注入 —— 那条路径只有传统引擎走。
        // 结果：AI 点击管线下睡眠模式**从未生效**，开关形同虚设。
        // 现在在会话启动时按配置注入，与 BotEngine 行为对齐。
        vision.sleepMode = host.cfg.sleepMode
        host.log("E7SA.AI", "vision sleepMode=${vision.sleepMode} (engine=${vision.id})")
        session = RecordStore.Session(
            startTime = System.currentTimeMillis(),
            startGold = startGold,
            startSkystones = startSkystones
        )
        stateTracker.reset()
        try {
            while (!host.stopRequested()) {
                val runMin = (System.currentTimeMillis() - session!!.startTime) / 60000.0
                host.setFatigue(
                    (runMin / Tuning.FATIGUE_RAMP_MINUTES * Tuning.FATIGUE_MAX)
                        .toFloat().coerceAtMost(Tuning.FATIGUE_MAX)
                )
                if (host.isPaused()) {
                    host.setStage(Stage.PAUSED)
                    host.sleepMs(Tuning.POLL_PAUSED_MS)
                    continue
                }
                host.log("E7SA.AI", "phase=${phase.name}")
                phase = when (phase) {
                    Phase.OBSERVE -> observe()
                    Phase.DECIDE_TARGETS -> decideTargets()
                    Phase.VERIFY_DIALOG -> verifyDialog()
                    Phase.CONFIRM_PURCHASE -> confirmPurchase()
                    Phase.VERIFY_CLOSED -> verifyClosed()
                    Phase.REVEAL_SLOT -> revealSlot()
                    Phase.CLICK_REFRESH -> clickRefresh()
                    Phase.CONFIRM_REFRESH -> confirmRefresh()
                    Phase.RECOVER -> recover()
                    Phase.WAIT -> waitGame()
                    Phase.RETRY_NET -> retryNet()
                    Phase.DONE -> return
                }
            }
        } catch (e: Exception) {
            // 与 BotEngine 同因修复：`!!` 抛出的 NPE message 为 null，
            // 只记 message 会让日志变成空的「异常：」，无法定位。
            host.log("E7SA.Crash", "ai engine aborted: ${e.javaClass.name}: ${e.message}")
            host.log("E7SA.Crash", e.stackTraceToString().lineSequence().take(6).joinToString(" | "))
            host.setError(host.str(R.string.err_exception, "${e.javaClass.simpleName}: ${e.message ?: "(no message)"}"))
        } finally {
            session!!.endTime = System.currentTimeMillis()
            // 会话级量化汇总：与 BotEngine 同格式（见其 finally 里的判读说明）
            host.log(
                "E7SA.Summary",
                "ai seen=$seenCandidates bought=${session!!.bookmarksGot + session!!.medalsGot} " +
                    "refreshes=${session!!.refreshes} sky=${session!!.skystonesSpent} " +
                    "gold=${session!!.goldSpent} dropHandledRow=$droppedByHandledRow"
            )
            host.commitSession(session!!)
            host.finish()
        }
    }

    /* ---------------- 工具 ---------------- */

    private fun shot(): Pair<Bitmap, DetectionResult>? {
        val b = host.screenshot() ?: return null
        val r = vision.analyze(b)
        // 帧级感知追踪：与 BotEngine 同策略（有候选必记、无候选采样）。
        // 两条管线的日志格式必须一致，否则复盘时无法横向对比。
        dbgCount++
        if (r.candidates.isNotEmpty() || dbgCount % 5 == 0) {
            host.traceFrame("FRAME", r)
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

    private fun s(): RecordStore.Session = session!!

    private fun capsReached(): Boolean {
        val c = host.cfg
        val hit = (c.bookmarkCap > 0 && s().bookmarksGot >= c.bookmarkCap) ||
            (c.medalCap > 0 && s().medalsGot >= c.medalCap)
        if (hit) host.markCompleted()   // 持有量达标也是"正常完成任务"
        return hit
    }

    private fun kindEnabled(kind: String): Boolean =
        if (kind == "bookmark") host.cfg.buyBookmark else host.cfg.buyMedal

    /**
     * 候选是否保留。丢弃时记录原因（与 BotEngine 同口径，便于两条管线横向对比）。
     *
     * `handled-row` 是漏买主因：下滑之后另一件商品会移到同一 y，
     * 却仍被当成"已处理"而永久跳过。
     */
    private fun keepCandidate(c: Candidate): Boolean {
        if (!kindEnabled(c.kind)) {
            host.log("E7SA.Filt", "ai drop ${c.kind}@${c.rowY} reason=kind-disabled")
            return false
        }
        if (host.handledContains(c.rowY)) {
            droppedByHandledRow++
            host.log("E7SA.Filt", "ai drop ${c.kind}@${c.rowY} reason=handled-row(该位置曾被处理)")
            return false
        }
        return true
    }

    private fun countPurchase(kind: String) {
        // 价格与数量取自 Tuning 单一来源（与 BotEngine / Recognition 共用同一组常量）
        if (kind == "bookmark") {
            s().bookmarksGot += Tuning.BOOKMARK_PER_BUY
            s().goldSpent += Tuning.BOOKMARK_PRICE
        } else {
            s().medalsGot += Tuning.MEDAL_PER_BUY
            s().goldSpent += Tuning.MEDAL_PRICE
        }
        host.counters(s())
    }

    private fun budgetBlocked(): Boolean {
        val c = host.cfg
        // D4 审计：这里原有「金币下限」闸门（minGold + startGold），但 startGold 恒为 0
        // （所有入口 startBot(0,0)），闸门永久短路 —— 已删除，避免留下"看起来有保护、
        // 实际永不触发"的假闸门。金币保护由下面真正生效的 goldSpendCap 提供。
        // 共用闸门（BudgetGate）：边界语义只有一份，两条管线不允许各自解释（2026-09-19）
        if (BudgetGate.goldExceeded(s().goldSpent, c.goldSpendCap)) {
            host.setError(host.errGoldCap(s().goldSpent))
            host.markCompleted()   // 正常完成任务 → 允许按设置自动熄屏
            return true
        }
        if (BudgetGate.skyExceeded(s().skystonesSpent, c.maxSkystones)) {
            host.setError(host.errSkyBudget())
            host.markCompleted()
            return true
        }
        return false
    }

    /**
     * AI 无法确定下一步：明确记录，不猜测、不回退传统点击。
     *
     * ⚠ **不再停机**（玩家要求，2026-09-17）：旧版连续 8 次未决就 return DONE，
     * 而深夜挂机时"停一次"= 剩下几小时全部漏买。改为退避继续观察 ——
     * 等待随连续未决次数线性增长，画面一恢复立刻继续。
     */
    private fun undecided(reason: String): Phase {
        undecidedStreak++
        host.setError(host.str(R.string.err_ai_undecided, reason))
        host.log("E7SA.AI", "undecided($undecidedStreak): $reason")
        // ⚠ 自愈（2026-09-18 实测事故驱动）：连续多次"点击后毫无反应"通常**不是识别问题**，
        // 而是有模态窗口在拦截输入 —— ColorOS 的"持续使用无障碍服务"确认框就是典型：
        // 它不遮住画面、OCR 一切正常，却吃掉了整晚 567 次点击中的 565 次，
        // 导致挂机 5 小时 25 分只成功刷新 2 次。
        // 每 3 次未决发一次 BACK：这类系统对话框会被关闭，游戏本身通常不受影响。
        if (undecidedStreak % 3 == 0) {
            host.log("E7SA.Recover", "连续失败 x$undecidedStreak -> 发送 BACK 尝试关闭拦截输入的对话框")
            host.pressBack()
        }
        val backoff = (1200L * undecidedStreak).coerceAtMost(Tuning.UNDECIDED_BACKOFF_MAX_MS)
        host.sleepMs(backoff)
        return Phase.OBSERVE
    }

    /* ---------------- 阶段：观察与决策 ---------------- */

    /** 观察：YOLO+OCR 识别当前画面并路由。 */
    private fun observe(): Phase {
        if (capsReached()) return Phase.DONE
        val p = shot() ?: return Phase.WAIT
        host.setStage(Stage.CHECKING)
        val scene = p.second.scene
        // 会话级感知统计：识别层这一帧"看见"了几个候选（漏买复盘的分子来源）
        seenCandidates += p.second.candidates.size
        recycle(p.first)
        host.log("E7SA.AI", "scene=$scene")
        return when (scene) {
            Scene.SHOP_LIST -> {
                waitStreak = 0
                recoverStreak = 0
                netRetryStreak = 0   // 画面正常了：清零网络重试计数
                Phase.DECIDE_TARGETS
            }
            Scene.BUY_DLG -> {
                host.setError(host.str(R.string.err_recover_buy_dlg))
                Phase.RECOVER
            }
            Scene.REFRESH_DLG -> {
                host.setError(host.str(R.string.err_recover_refresh_dlg))
                Phase.RECOVER
            }
            Scene.NET_ERROR -> {
                // 网络异常弹窗（2026-09-19）：点重试后回到 OBSERVE 继续正常流程
                host.setError(host.str(R.string.err_net_error))
                Phase.RETRY_NET
            }
            Scene.OTHER -> Phase.WAIT
        }
    }

    /**
     * 网络异常弹窗处理（2026-09-19 新增，依据用户实机截图）。
     *
     * 实测场景：网络抖动时游戏弹「网络连接异常，请重新连接。」+「点击重试」。
     * 它是**盖在商店列表上的模态窗** —— 列表文字仍能被 OCR 读到，于是旧逻辑把整屏
     * 当成正常 SHOP_LIST，卡在「「立即更新」按钮无法视觉确认（彩色占比 0.00）」
     * 反复 undecided，整轮无法推进（实测日志 01:32:52）。
     *
     * 处理：优先点 OCR 读到的「点击重试」文字位置；读不到就点弹窗中心区域
     * （用户明确要求"随便在位置上点击" —— 弹窗是模态的，点在它上面即可生效）。
     * 点完等游戏重连，然后回 OBSERVE 继续正常流程。
     */
    private fun retryNet(): Phase {
        // 连续重试上限（2026-09-19）：网络真的不通时狂点没有意义，
        // 而且实测「每 0.75 秒点一次」会点到系统 UI（控制中心/桌面）把游戏推到后台。
        if (++netRetryStreak > Tuning.NET_RETRY_MAX_STREAK) {
            netRetryStreak = 0
            host.log(
                "E7SA.Recover",
                "NET ERROR 连续 " + Tuning.NET_RETRY_MAX_STREAK + " 次未恢复 -> 退避等待"
            )
            host.sleepMs(Tuning.UNDECIDED_BACKOFF_MAX_MS)
            return Phase.WAIT
        }
        host.setStage(Stage.WAITING)
        var x = host.screenW / 2f
        var y = host.screenH * 0.55f
        var hit = "center"
        val p = shot()
        if (p != null) {
            val line = p.second.lines.firstOrNull { hasAny(it.text, RETRY_KW) }
            if (line != null) {
                x = line.cx
                y = line.cy
                hit = "retry-text"
            }
            recycle(p.first)
        }
        host.log(
            "E7SA.Recover",
            "NET ERROR: 点击重试 tap=(" + x.toInt() + "," + y.toInt() + ") by=" + hit
        )
        host.guardedTap(x, y, "netRetry")
        host.sleepMs(Tuning.NET_RETRY_WAIT_MS)
        return Phase.OBSERVE
    }

    /**
     * 决策：逐个评估当前画面目标 ——
     *  金色按钮（可点击）→ 点击购买；
     *  灰色按钮 / 无按钮（售空/不可判断）→ 结束当前目标，找下一个；
     *  全部处理完 → 揭示第 6 格 → 刷新。
     */
    private fun decideTargets(): Phase {
        if (capsReached()) return Phase.DONE
        // **金币/天空石预算闸门必须在这里也检查**。
        // 此前 budgetBlocked() 只在 revealSlot / clickRefresh（下滑与刷新）两处调用，
        // 购买决策路径完全没有预算检查 —— 后果是：金币花光后仍会继续尝试购买，
        // 买不起就失败，然后继续刷新，**每刷新一次白烧 3 颗天空石**。
        // 这是玩家明确担心的损失，必须在"决定买不买"这一步就拦住。
        if (budgetBlocked()) return Phase.DONE
        val p = shot() ?: return Phase.WAIT
        val snap = p.second
        if (snap.scene != Scene.SHOP_LIST) {
            recycle(p.first)
            return Phase.OBSERVE
        }
        host.setStage(Stage.CHECKING)
        // A3：记录本轮商店状态（相对上一轮的差异）。纯观测，不改变下面任何决策。
        host.log("E7SA.StateDiff", stateTracker.observe(snap))
        // 感知诊断：AI 管线此前完全没有输出识别细节，实机出现"0 候选"时无从定位。
        // 每 3 轮打一次，够定位问题又不刷屏。
        dbgCount++
        if (dbgCount % 3 == 0) host.log("E7SA.Percep", "engine=${snap.engine} scene=${snap.scene} ${snap.diag}")
        val bmp = p.first
        val targets = snap.candidates.filter { c -> keepCandidate(c) }
        if (targets.isEmpty()) {
            recycle(bmp)
            return idleExit("no-candidate")
        }
        val skipRows = ArrayList<Pair<Candidate, ClickPlanner.Button>>()
        for (t in targets) {
            val loc = planner.rowButtonState(bmp, snap, t.cy, t.tol)
            when (loc.state) {
                ClickPlanner.Button.CLICKABLE -> {
                    target = t
                    recycle(bmp)
                    host.hesitate()
                    // rowButtonState 契约：state=CLICKABLE 时必然带非空坐标
                    // （E1 路径由 modelButton 非空返回，色块路径由质心成功返回）。
                    // 先取出再使用，避免同一表达式里重复 !! 触发编译器警告。
                    val pt = loc.pt!!
                    idleDecideStreak = 0
                    host.log("E7SA.AI", "buy " + t.kind + " button=CLICKABLE tap=(" + pt.first.toInt() + "," + pt.second.toInt() + ")")
                    host.guardedTap(pt.first, pt.second, "aiRowBuy")
                    return Phase.VERIFY_DIALOG
                }
                // 先记账，等本轮所有行看完后用**同一张新帧**统一复核（见 confirmSkips）
                ClickPlanner.Button.GRAY, ClickPlanner.Button.NONE -> skipRows.add(t to loc.state)
            }
        }
        recycle(bmp)
        if (skipRows.isNotEmpty()) confirmSkips(skipRows)
        return idleExit("all-skipped")
    }

    /**
     * 决策轮"什么都没做成"时的统一出口（死循环兜底，2026-09-19）。
     *
     * 背景：实测事故 —— 买过的商品图标仍留在屏上（按钮已变售空），
     * `confirmSkips` 把它标记为"已跳过"，而 `revealSlot` 又无条件清空位置记忆，
     * 于是它被反复当成目标，机器人整夜在 REVEAL_SLOT ↔ DECIDE_TARGETS 之间打转、
     * **一次都不刷新**（日志：row medal GRAY -> GRAY (x2) -> skip 每 3.5 秒重复）。
     *
     * 根因已修（位置记忆改为"画面真动了才清"），这里再加一道兜底：
     * 连续 [Tuning.IDLE_DECIDE_MAX_STREAK] 轮既没买也没刷新 → 强制刷新一次。
     * 与商品类型无关：书签、奖牌、乃至"按钮读不到"的行都走同一条路径。
     */
    private fun idleExit(reason: String): Phase {
        if (++idleDecideStreak >= Tuning.IDLE_DECIDE_MAX_STREAK) {
            idleDecideStreak = 0
            host.log(
                "E7SA.AI",
                "空转 x" + Tuning.IDLE_DECIDE_MAX_STREAK + " (" + reason + ") -> 强制刷新（打破死循环）"
            )
            return Phase.CLICK_REFRESH
        }
        return Phase.REVEAL_SLOT
    }

    /**
     * A4 观测优先：本轮被判为「灰 / 无按钮」的行，再用同一张新帧复核一次才允许跳过。
     *
     * 单帧可能正落在刷新/售罄动画的中间态（实测 sold-out 动画帧会多出候选、按钮色块
     * 也尚未稳定）。旧版直接 handledAdd，一旦撞上这种帧，**整轮都会跳过这一行**——
     * 那是不可恢复的漏买。这里只多花一帧（不是每行一帧）：复核后仍不可点的才标记跳过，
     * 复核后变回可点的行保持未处理，交给下一轮 observe 重新决策（绝不拿旧帧的坐标去点）。
     */
    private fun confirmSkips(rows: List<Pair<Candidate, ClickPlanner.Button>>) {
        host.sleepMs(host.randInt(Tuning.PRE_TAP_JITTER_MIN_MS, Tuning.PRE_TAP_JITTER_MAX_MS).toLong())
        val p = host.screenshot()
        if (p == null) {
            host.log("E7SA.AI", "A4 re-observe FAILED -> keep ${rows.size} row(s) for next cycle")
            return
        }
        val r = vision.analyze(p)
        if (r.scene != Scene.SHOP_LIST) {
            recycle(p)
            host.log("E7SA.AI", "A4 re-observe scene=${r.scene} -> keep ${rows.size} row(s) for next cycle")
            return
        }
        for ((t, first) in rows) {
            val st = planner.rowButtonState(p, r, t.cy, t.tol).state
            if (st == ClickPlanner.Button.GRAY || st == ClickPlanner.Button.NONE) {
                host.handledAdd(t.rowY)
                // 这条计入"被位置记忆跳过"：机制与 BotEngine 相同，
                // 下滑后另一件商品移到同一 y 时同样会被误跳过
                droppedByHandledRow++
                host.log("E7SA.AI", "row ${t.kind} $first -> $st (x2) -> skip")
            } else {
                host.log("E7SA.AI", "row ${t.kind} $first -> $st -> keep for next cycle")
            }
        }
        recycle(p)
    }

    /* ---------------- 阶段：购买闭环 ---------------- */

    /** 点击后验证：购买弹窗是否出现。 */
    private fun verifyDialog(): Phase {
        var dlg: Pair<Bitmap, DetectionResult>? = null
        // 弹窗等待 6 秒 → 12 秒：网络慢时弹窗可能 8~10 秒才出现，旧预算会让代码
        // 误判"没弹窗"→ 该买未买 → 继续刷新白烧天空石（玩家怀疑的漏买路径）。
        for (i in 0 until host.framesFor(12000)) {
            host.sleepMs(host.randInt(Tuning.DIALOG_POLL_MIN_MS, Tuning.DIALOG_POLL_MAX_MS).toLong())
            val p = shot()
            if (p != null && p.second.scene == Scene.BUY_DLG) { dlg = p; break }
            if (p != null) recycle(p.first)
        }
        if (dlg == null) {
            // 关键证据：弹窗超时。日志里频繁出现即说明"该买未买"，是漏买的直接线索。
            host.log("E7SA.Buy", "DIALOG TIMEOUT kind=${target?.kind} rowY=${target?.rowY} (等待 12s 未见弹窗)")
        }
        if (dlg != null) {
            // A4 观测优先：弹窗刚出现时可能还在渐显动画里（内容未完全呈现），
            // 直接拿它做三重验证会失败 → 取消 → 重买（玩家实测到的"点了取消又重新买"）。
            // 这里补一个短等待并重新取帧，确保验证用的是稳定帧。
            host.sleepMs(host.randInt(Tuning.DIALOG_SETTLE_MIN_MS, Tuning.DIALOG_SETTLE_MAX_MS).toLong())
            val stable = shot()
            if (stable != null && stable.second.scene == Scene.BUY_DLG) {
                recycle(dlg.first)
                lastDlg = stable
            } else {
                if (stable != null) recycle(stable.first)
                lastDlg = dlg
            }
            return Phase.CONFIRM_PURCHASE
        }
        // 弹窗未出现：再看一眼当前行，判断是"售空/灰按钮"还是"无法确认"
        host.sleepMs(Tuning.DIALOG_RECHECK_MS)
        val p2 = host.screenshot()
        if (p2 != null) {
            val r2 = vision.analyze(p2)
            val t = target
            if (t != null && r2.scene == Scene.SHOP_LIST) {
                if (rowSoldOut(r2, t.cy, t.tol)) {
                    host.handledAdd(t.rowY)
                    recycle(p2)
                    host.log("E7SA.AI", "no dialog, row sold -> skip")
                    return Phase.DECIDE_TARGETS
                }
                val loc = planner.rowButtonState(p2, r2, t.cy, t.tol)
                if (loc.state == ClickPlanner.Button.GRAY) {
                    host.handledAdd(t.rowY)
                    recycle(p2)
                    host.log("E7SA.AI", "no dialog, button gray -> skip")
                    return Phase.DECIDE_TARGETS
                }
                if (loc.state == ClickPlanner.Button.CLICKABLE) {
                    // ⚠ 漏买修复（2026-09-18，对齐 BotEngine.kt:670-692）：
                    // "行还在、按钮仍可购买"恰恰说明**这一次点击没有生效**
                    // （坐标偏了 / 被遮挡 / ColorOS 模态框吃掉了点击），这一行依然值得买。
                    // 旧版在这里 handledAdd(t.rowY) → 该行被永久跳过 = 整屏漏买
                    // （玩家实测事故：567 次点击被模态框吃掉 565 次）。
                    // 现在只报告、**不标记已处理**，目标保持未决交给下一轮 observe 重新决策
                    // （绝不拿旧帧的坐标去点，避免点错行）。
                    recycle(p2)
                    host.log("E7SA.AI", "no dialog, button still clickable -> RETRY (不放弃该行)")
                    return undecided("点击后未出现购买弹窗")
                }
            }
            recycle(p2)
        }
        return undecided("点击后无法确认画面状态")
    }

    /** 弹窗确认：三重验证（共享安全语义）+ AI 定位金色确认按钮 + 点击。 */
    private fun confirmPurchase(): Phase {
        val dlg = lastDlg ?: return undecided("购买弹窗状态丢失")
        lastDlg = null
        val t = target
        if (t == null) {
            recycle(dlg.first)
            return Phase.OBSERVE
        }
        if (!dialogConfirmed(dlg.second, dlg.first, t.kind)) {
            // 与 BotEngine 同样的区分逻辑：只有"弹窗里的商品名明确是别的东西"才算点错行。
            // 若一律放弃，会把"价格/图标 OCR 暂时抖动"误判成点错行而漏买。
            val dlgKind = dialogItemKind(dlg.second.lines)
            if (dlgKind != null && dlgKind != t.kind) {
                host.handledAdd(t.rowY)
                host.setError(host.str(R.string.err_row_wrong_target, t.kind, t.rowY))
                host.log("E7SA.AI", "WRONG TARGET kind=${t.kind} rowY=${t.rowY} dialogKind=$dlgKind -> 放弃该行并取消")
            } else {
                host.log("E7SA.AI", "dialog verify FAIL kind=${t.kind} rowY=${t.rowY} dialogKind=${dlgKind ?: "?"} -> 取消后重试")
            }
            recycle(dlg.first)
            return Phase.RECOVER
        }
        // E1：模型有 confirm_button 框就优先用（2 类模型下为 null，自动回退色块法）
        val pt = planner.modelButton(dlg.second, "confirm_button")
            ?: planner.goldButtonForText(dlg.second.lines, dlg.first, BUY_KW)
        if (pt == null) {
            val fr = planner.textButtonFillRatio(dlg.second.lines, dlg.first, BUY_KW)
            recycle(dlg.first)
            return undecided("弹窗确认按钮无法视觉确认（彩色占比 ${"%.2f".format(fr)}）")
        }
        recycle(dlg.first)
        host.hesitate()
        host.log("E7SA.AI", "confirm tap=(${pt.first.toInt()},${pt.second.toInt()})")
        val tapped = host.guardedTap(pt.first, pt.second, "aiConfirm")
        // ⚠ 二次修正（2026-09-19）：**点下「购买」不等于买到了**。
        //
        // 2026-09-18 那次把计数从"验证通过"提前到"点下即记"，解决的是"买到了却不计数"
        // （少计 → 金币上限被超 → 继续烧钱）。但它引出了反方向的问题：
        // 金币不足时游戏会弹一个错误提示窗，而那个窗**既不是商店列表、也不是购买弹窗**，
        // 旧验证逻辑（"连续两帧不是 BUY_DLG 就算成功"）会把它当成"弹窗关掉了" →
        // **误判购买成功**：日志写假的 OK、计数虚高一件（这就是"假阳性"）。
        //
        // 现在改为**按结果提交**：由 verifyClosed() 拿到正向证据（画面回到商店列表）
        // 才计数；超时未确认时仍计数（保守：钱可能已经花了，宁可少花不可烧钱）。
        if (tapped) {
            host.log("E7SA.Buy", "PURCHASE TAP kind=" + target?.kind + " rowY=" + target?.rowY)
        } else {
            host.log("E7SA.Gate", "PURCHASE NOT COMMITTED: 购买点击被闸门拒绝（未花钱，不计数）")
        }
        return Phase.VERIFY_CLOSED
    }

    /**
     * 购买结果验证（2026-09-19 重写为三态）。
     *
     * 旧判据是「连续两帧不是 BUY_DLG 就算成功」—— 它把**任何**非购买弹窗都当成
     * "弹窗关掉了"。金币不足时游戏弹的错误提示窗恰好满足这个条件，于是：
     * 日志写下假的 OK（假阳性）、计数虚高一件，而玩家实际上什么都没买到。
     *
     * 现在要求**正向证据**：
     *  · 回到商店列表（连续 [Tuning.DIALOG_CLOSED_STREAK] 帧）→ 提交计数 + 写 OK
     *  · 出现其它弹窗（连续同样帧数，既非商店列表也非购买弹窗）→ 判**失败**：
     *    不计数、不写 OK，并把该帧 OCR 原文写进日志（据此可加精确关键词）
     *  · 超时仍未确认 → 仍计数（保守），但日志明确标注"未确认"
     */
    private fun verifyClosed(): Phase {
        var shopStreak = 0
        var oddStreak = 0
        var lastOddText = ""
        for (i in 0 until host.framesFor(10000)) {
            host.sleepMs(host.randInt(Tuning.REFRESH_POLL_MIN_MS, Tuning.REFRESH_POLL_MAX_MS).toLong())
            val p = shot() ?: continue
            val scene = p.second.scene
            if (scene == Scene.OTHER) {
                // 留下弹窗上的文字：这是后续加精确识别（比如"金币不足"）的唯一依据
                lastOddText = p.second.lines.take(8)
                    .joinToString("│") { it.text.replace('\n', ' ').trim() }
            }
            recycle(p.first)
            when (scene) {
                Scene.SHOP_LIST -> {
                    shopStreak++
                    oddStreak = 0
                    if (shopStreak >= Tuning.DIALOG_CLOSED_STREAK) {
                        target?.let { countPurchase(it.kind) }
                        target?.let { host.handledAdd(it.rowY) }
                        host.log(
                            "E7SA.Buy",
                            "PURCHASE COMMIT kind=" + target?.kind + " rowY=" + target?.rowY +
                                " bookmarks=" + s().bookmarksGot + " medals=" + s().medalsGot +
                                " goldSpent=" + s().goldSpent + " (正向证据：已回到商店列表)"
                        )
                        target = null
                        undecidedStreak = 0
                        opCount++
                        host.rest(opCount)
                        return Phase.DECIDE_TARGETS
                    }
                }
                Scene.OTHER -> {
                    oddStreak++
                    shopStreak = 0
                    if (oddStreak >= Tuning.DIALOG_CLOSED_STREAK) {
                        // 明确的失败证据：没回到商店列表，出现的是别的弹窗。
                        // 不计数（钱没花）、不写 OK；把弹窗文字留档。
                        target?.let { host.handledAdd(it.rowY) }
                        host.log(
                            "E7SA.Buy",
                            "PURCHASE FAILED kind=" + target?.kind + " rowY=" + target?.rowY +
                                " reason=dialog-not-shop raw=[" + lastOddText + "]"
                        )
                        target = null
                        // 交给 RECOVER 发 BACK 关掉这个弹窗（自愈路径已存在）
                        return Phase.RECOVER
                    }
                }
                else -> {
                    shopStreak = 0
                    oddStreak = 0
                }
            }
        }
        // 超时：无法确认结果。保守起见仍然计数（钱可能已经花了），但日志明确标注未确认。
        target?.let { countPurchase(it.kind) }
        target?.let { host.handledAdd(it.rowY) }
        host.log(
            "E7SA.Buy",
            "PURCHASE UNCONFIRMED kind=" + target?.kind + " rowY=" + target?.rowY +
                " -> 保守计数（可能已买到）；日志里没有 OK 即表示未确认"
        )
        target = null
        return undecided("购买结果未确认")
    }

    /* ---------------- 阶段：滑动与刷新 ---------------- */

    /** 无目标/目标处理完：上滑揭示第 6 格，滑到列表不再滚动为止，随后刷新。 */
    private fun revealSlot(): Phase {
        if (budgetBlocked()) return Phase.DONE
        val w = host.screenW
        val h = host.screenH
        val xC = w * host.cfg.swipeCenterX
        var shotFails = 0
        // 与 BotEngine.slot6Check 同一判据：连续 [Tuning.SCROLL_STILL_STREAK] 次"没动"才算到底。
        // （该值 2026-09-18 按玩家反馈由 2 改为 1 —— "刷新后下滑三次太浪费时间"。
        //   注释此前仍写着"2 次"，与实际值不符，2026-09-19 一并更正。）
        // 旧逻辑 `if (moved) break` 让同一操作的下滑次数在 1~3 次之间随机（didScroll 阈值
        // 卡在临界值），且可能在列表尚未到底时就进入刷新 —— 改为"滑到不动"后行为确定。
        var stillStreak = 0
        var swipes = 0
        // 次数上限收敛到 Tuning（2026-09-19）：这里原先写死 4，与 Tuning.SLOT6_MAX_ATTEMPTS
        // 分家 —— 调"滑动次数"时只改了传统引擎，AI 管线完全不跟随。
        for (attempt in 0 until Tuning.SLOT6_MAX_ATTEMPTS) {
            val before = host.screenshot()
            if (before == null) { shotFails++; host.sleepMs(Tuning.RESHOT_RETRY_MS); continue }
            host.swipe(
                xC, h * Tuning.SWIPE_LOW_Y, xC, h * Tuning.SWIPE_HIGH_Y,
                if (attempt == 0) Tuning.SWIPE_FIRST_MS else Tuning.SWIPE_REPEAT_MS
            )
            swipes++
            // 等画面稳定后再识别（滑动惯性/加载中不急着判断）
            val after = captureStableFrame(6)
            if (after == null) { shotFails++; recycle(before); continue }
            val moved = didScroll(before, after.first)
            recycle(before)
            val snap = after.second
            recycle(after.first)
            // ⚠ 位置记忆只在**画面真的动了**之后才清空（2026-09-19 修正）。
            //
            // 旧版是无条件清空，与 confirmSkips() 的 handledAdd 互相打架，实测造成
            // **死循环、永不刷新**（日志：row medal GRAY -> GRAY (x2) -> skip 每 3.5 秒重复）：
            //   买过的奖牌图标还在屏上 → DECIDE_TARGETS 判出"灰按钮" → confirmSkips 标记已跳过
            //   → 回到 REVEAL_SLOT → 这里无条件清空标记 → 又把它当目标 → 无限下滑。
            //
            // 而清空的本意是"滑动后内容变了、同一 y 已是另一件商品"（漏买修复）——
            // 画面没动时这个前提不成立，位置记忆依然有效，清空反而制造了死循环。
            if (moved) host.handledClear()
            val targets = snap.candidates.filter { c -> keepCandidate(c) }
            // 逐次留痕（2026-09-19）：AI 管线此前**完全不记录滑动次数** ——
            // 日志里只有 phase=REVEAL_SLOT → phase=CLICK_REFRESH，中间滑了几次、
            // 为什么停，一点痕迹都没有。而"滑得不够 = 第 6 格没露出 = 漏买"，
            // 恰恰是最需要举证的一环（传统引擎早就有 E7SA.Scroll）。
            host.log(
                "E7SA.Scroll",
                "ai swipe=" + swipes + " attempt=" + attempt + " moved=" + moved +
                    " still=" + stillStreak + " targets=" + targets.size +
                    " scene=" + snap.scene + " sleep=" + host.cfg.sleepMode
            )
            if (targets.isNotEmpty()) {
                host.log("E7SA.Scroll", "ai slot6 revealed: swipes=" + swipes + " -> decide")
                return Phase.DECIDE_TARGETS
            }
            if (moved) { stillStreak = 0; continue }
            stillStreak++
            if (stillStreak >= Tuning.SCROLL_STILL_STREAK) {
                host.log("E7SA.Scroll", "ai bottom: swipes=" + swipes + " reason=still x" + stillStreak + " -> refresh")
                break
            }
        }
        if (shotFails >= 3) {
            host.log("E7SA.Scroll", "ai abort: swipes=" + swipes + " shotFails=" + shotFails + " -> wait")
            return Phase.WAIT
        }
        if (swipes >= Tuning.SLOT6_MAX_ATTEMPTS) {
            // 滑满上限仍未判"到底"：必须留痕，否则"这轮为什么没揭示第 6 格"无从查起
            host.log("E7SA.Scroll", "ai bottom: swipes=" + swipes + " reason=attempt-limit -> refresh")
        }
        return Phase.CLICK_REFRESH
    }

    /** 刷新：AI 定位「立即更新」金色按钮并点击，等待刷新弹窗。 */
    private fun clickRefresh(): Phase {
        if (budgetBlocked()) return Phase.DONE
        host.setStage(Stage.REFRESHING)
        val p = shot() ?: return Phase.WAIT
        val snap = p.second
        if (snap.scene != Scene.SHOP_LIST) {
            recycle(p.first)
            return Phase.OBSERVE
        }
        val pt = planner.modelButton(snap, "refresh_button")
            ?: planner.goldButtonForText(snap.lines, p.first, REFRESH_BTN_KW)
        if (pt == null) {
            val fr = planner.textButtonFillRatio(snap.lines, p.first, REFRESH_BTN_KW)
            recycle(p.first)
            return undecided("「立即更新」按钮无法视觉确认（彩色占比 ${"%.2f".format(fr)}）")
        }
        // 刷新前指纹（P4）：确认刷新后内容真的变了
        refreshBeforeFp = frameFingerprint(snap)
        recycle(p.first)
        host.hesitate()
        host.log("E7SA.AI", "refresh tap=(${pt.first.toInt()},${pt.second.toInt()})")
        host.guardedTap(pt.first, pt.second, "aiRefresh")
        var dlg: Pair<Bitmap, DetectionResult>? = null
        // 刷新弹窗同样 6 秒 → 12 秒：网络慢时弹窗会晚到
        for (i in 0 until host.framesFor(12000)) {
            host.sleepMs(Tuning.POLL_TICK_MS)
            val p2 = shot()
            if (p2 != null && p2.second.scene == Scene.REFRESH_DLG) { dlg = p2; break }
            if (p2 != null) recycle(p2.first)
        }
        if (dlg == null) {
            host.log("E7SA.Buy", "REFRESH DIALOG TIMEOUT (等待 12s 未见刷新弹窗)")
            return undecided("刷新弹窗未出现")
        }
        lastDlg = dlg
        return Phase.CONFIRM_REFRESH
    }

    /** 刷新弹窗：AI 定位「确认」金色按钮并点击；无法定位 → 取消弹窗恢复。 */
    private fun confirmRefresh(): Phase {
        val dlg = lastDlg ?: return undecided("刷新弹窗状态丢失")
        lastDlg = null
        val pt = planner.modelButton(dlg.second, "confirm_button")
            ?: planner.goldButtonForText(dlg.second.lines, dlg.first, CONFIRM_KW)
        if (pt == null) {
            val fr = planner.textButtonFillRatio(dlg.second.lines, dlg.first, CONFIRM_KW)
            recycle(dlg.first)
            host.setError(host.str(R.string.err_no_confirm))
            host.log("E7SA.AI", "refresh confirm visual fail fill=$fr -> recover")
            return Phase.RECOVER
        }
        recycle(dlg.first)
        host.hesitate()
        host.log("E7SA.AI", "refreshConfirm tap=(${pt.first.toInt()},${pt.second.toInt()})")
        val tapped = host.guardedTap(pt.first, pt.second, "aiRefreshConfirm")
        host.daze()
        opCount++
        host.rest(opCount)
        // ---- ACTION → COMMIT（2026-09-18 高危修复，与 BotEngine.doRefresh 对齐）----
        // 确认按钮已经点下，天空石**已经花掉**：必须立刻记账，与验证结果无关。
        //
        // 旧版是「先验证、验证通过才记账」，而 waitShopLoaded 在三种情况下都不返回
        // DECIDE_TARGETS：① 刷新前后指纹相同 ② 行数 < 5 ③ 18 秒预算耗尽。
        // 其中①在整屏售罄时**恰恰是常态**（刷新前后文本都是"售罄"）→ skystonesSpent
        // 永不增长 → maxSkystones 闸门永远触发不了 → 整夜无限刷新、无限烧天空石
        // （付费货币），而日志里 skySpent 还是 0。
        //
        // 正确语义：动作发生即记账；「验证」只决定要不要按新列表继续决策、要不要清空
        // 已处理行（否则旧列表当新列表会重复购买）。
        if (tapped) {
            s().refreshes++
            s().skystonesSpent += 3
            host.counters(s())
            // 记账时刻留痕（2026-09-19）：这是"钱已经花了"的唯一权威记录点。
            // 旧版把记账挂在验证成功之后，日志里完全看不出记账时机 ——
            // 而 P0（无限烧天空石）正是藏在这个时序里。用 E7SA.Buy 标签 →
            // 同时落进不参与轮转的 e7sa_critical.log，可长期举证。
            host.log(
                "E7SA.Buy",
                "REFRESH COMMIT refreshes=" + s().refreshes + " skySpent=" + s().skystonesSpent +
                    " (确认已点下，记账与验证结果无关)"
            )
            // 刷新 = 有进展：清空空转计数（2026-09-19）。
            // 否则安全网会被**正常**的"这一轮没东西可买"累积触发（实测 01:33:10 误触发过一次），
            // 那样会跳过 revealSlot 的上滑，可能漏掉第 6 格。
            idleDecideStreak = 0
        } else {
            host.log("E7SA.Gate", "REFRESH NOT COMMITTED: 确认点击被闸门拒绝（未花钱，不记账）")
        }
        val next = waitShopLoaded(refreshBeforeFp ?: "", "刷新后商店列表未就绪")
        if (next == Phase.DECIDE_TARGETS) {
            host.handledClear()
            undecidedStreak = 0
        }
        return next
    }

    /* ---------------- 状态驱动等待（动作 → 等待画面变化 → 重新识别 → 稳定后继续） ---------------- */

    /**
     * 等待商店列表真正加载完成（P4：先证明"变了"，再证明"稳定了"）：
     * 每轮重新识别，要求
     *  ① 指纹 != 刷新前指纹（内容确实变了 —— 否则旧列表静止会被误判为刷新完成）
     *  ② 商店场景 + **行数已停止增长**（见下）
     *  ③ 连续 2 帧画面无显著变化（已稳定）
     *
     * 关于 ② 的修正（实机复现的问题）：
     * 旧判据是 `rows >= 3`，门槛太低 —— 网络慢时列表逐行渐显，第 1~3 行刚出来就满足
     * "rows>=3"，于是**在列表只加载了三分之一时就下滑**，漏掉后面的物品栏。
     * 实测正常加载完是 rows=8~10（8~10 个"购买/售罄"文本，含"可购买1次"这类状态文字）。
     * 现改为：行数达到最小门槛 **且连续 3 帧不再增长**才算加载完
     * （不写死行数，兼容不同商店等级；3 帧是为了过滤网络抖动造成的假稳定）。
     */
    private fun waitShopLoaded(beforeFp: String, timeoutReason: String): Phase {
        var prev: Bitmap? = null
        var stable = 0
        var lastRows = -1
        var rowsStable = 0
        // 时序自适应：按时间预算换算帧数（慢设备自动多给帧、快设备自动收紧）
        for (i in 0 until host.framesFor(18000)) {
            host.sleepMs(host.randInt(Tuning.SHOP_LOAD_POLL_MIN_MS, Tuning.SHOP_LOAD_POLL_MAX_MS).toLong())
            val p = host.screenshot() ?: continue
            val r = vision.analyze(p)
            val fp = frameFingerprint(r)
            val rows = r.lines.count { hasAny(it.text, BUY_KW) || hasAny(it.text, SOLD_KW) }
            // 行数是否已停止增长。要求连续 **3 帧** 相同（rowsStable>=2）而不是 2 帧：
            // 网络抖动会让加载中途出现"两帧恰好相同"的假稳定，只等 2 帧就可能被骗过、
            // 在列表尚未加载完时下滑 → 漏掉第一物品栏（玩家实测到的现象）。
            // 多等一帧约 0.9 秒，换的是不漏格。
            if (rows == lastRows) rowsStable++ else rowsStable = 0
            lastRows = rows
            // 最小门槛 5 行：正常一屏 8~10 行；低于 5 行几乎可以确定还在加载中
            val loaded = r.scene == Scene.SHOP_LIST &&
                rows >= 5 && rowsStable >= 2
            val changed = beforeFp.isEmpty() || fp != beforeFp
            var nowStable = false
            if (prev != null) {
                if (didScroll(prev, p)) stable = 0 else stable++
                nowStable = stable >= 1
            }
            recycle(prev)
            prev = p
            if (loaded && changed && nowStable) {
                recycle(prev)
                host.log("E7SA.AI", "shop reloaded rows=$rows changed=$changed stable -> decide")
                return Phase.DECIDE_TARGETS
            }
        }
        recycle(prev)
        return undecided(timeoutReason)
    }

    /**
     * 连续截图直到画面稳定（连续 2 帧无显著变化），返回稳定帧；超时返回最后一帧。
     *
     * 两级探测（省时关键）：稳定判定只需要"画面变没变"，用轻量图标探测即可，
     * **不必每帧都跑完整识别**。旧版每帧都 analyze()（OCR 390ms + YOLO 45ms），
     * 3 帧就是 1.3 秒；现在前几帧只跑 YOLO（约 45ms），**只在最后对稳定帧做一次
     * 完整识别**。省下的正是滑动路径上最大的一块开销。
     */
    private fun captureStableFrame(attempts: Int): Pair<Bitmap, DetectionResult>? {
        var prev: Bitmap? = null
        var stable = 0
        var lastBmp: Bitmap? = null
        for (i in 0 until attempts) {
            host.sleepMs(host.randInt(Tuning.SLOT6_POLL_MIN_MS, Tuning.SLOT6_POLL_MAX_MS).toLong())
            val p = host.screenshot() ?: continue
            // 轻量探测：只为"画面是否还在动"服务，不需要 OCR
            engine_hasIconFast(p)
            if (prev != null) {
                if (didScroll(prev, p)) stable = 0 else stable++
            }
            recycle(prev)
            prev = p
            lastBmp?.let { recycle(it) }
            lastBmp = p
            if (stable >= 2) break
        }
        val bmp = lastBmp ?: return null
        prev?.let { if (it !== bmp) recycle(it) }
        // 只对最终稳定帧做一次完整识别
        val r = vision.analyze(bmp)
        return bmp to r
    }

    /**
     * 轻量探测包装（只为稳定判定服务，结果不参与决策）。
     *
     * 失败不向上抛 —— 探测只决定"画面还在不在动"，它不是决策依据，
     * 抛出去会打断整条稳定判定链路。捕 Throwable 而非 Exception 与项目内
     * 其他 native 调用（YoloDet / PpOcr）保持一致：native 失败可能表现为
     * UnsatisfiedLinkError 这类 Error。
     *
     * **但必须留痕**：这是全项目唯一一处完全静默的 catch。静默会让
     * "探测持续失败"表现为"画面一直不稳定"，最终卡在 WAIT 阶段，
     * 而日志里查不到任何原因。这里每帧都会调用，正常情况下不应失败，
     * 一旦刷屏本身就是最直接的故障信号。
     */
    private fun engine_hasIconFast(bmp: Bitmap) {
        try {
            vision.hasIconFast(bmp)
        } catch (e: Throwable) {
            host.log("E7SA.AI", "hasIconFast failed: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /* ---------------- 阶段：恢复与等待 ---------------- */

    /** 残留弹窗恢复：AI 定位「取消」并关闭；绝不猜测购买/确认。 */
    private fun recover(): Phase {
        recoverStreak++
        if (recoverStreak > 6) {
            // ⚠ 不再停机（同 BotEngine.doRecover）：退避后重新尝试，绝不放弃会话
            host.setError(host.str(R.string.err_dialog_stuck))
            host.log("E7SA.AI", "recover streak=$recoverStreak -> backoff & retry (不停机)")
            host.sleepMs(Tuning.UNDECIDED_BACKOFF_MAX_MS)
            recoverStreak = 0
            return Phase.OBSERVE
        }
        host.setStage(Stage.CHECKING)
        val p = shot() ?: return Phase.OBSERVE
        val scene = p.second.scene
        if (scene != Scene.BUY_DLG && scene != Scene.REFRESH_DLG) {
            recycle(p.first)
            return Phase.OBSERVE
        }
        val pt = planner.modelButton(p.second, "cancel_button")
            ?: planner.cancelButton(p.second.lines, p.first)
        if (pt != null) {
            host.log("E7SA.AI", "cancel tap=(${pt.first.toInt()},${pt.second.toInt()})")
            host.guardedTap(pt.first, pt.second, "aiCancel")
        }
        recycle(p.first)
        host.sleepMs(Tuning.SETTLE_AFTER_RECYCLE_MS)
        return Phase.OBSERVE
    }

    /**
     * WAIT：非商店画面（加载 / 玩家切走 / 过场）。
     *
     * ⚠ **不再超时停机**（玩家要求）：深夜挂机停一次 = 剩下几小时全部漏买。
     * 改为一直等待，只把探测间隔逐步拉长（600ms → 上限），画面恢复即继续。
     */
    private fun waitGame(): Phase {
        waitStreak++
        host.setStage(Stage.WAITING)
        val backoff = (600L * (1 + waitStreak / 20)).coerceAtMost(Tuning.WAIT_BACKOFF_MAX_MS)
        host.sleepMs(backoff)
        return Phase.OBSERVE
    }
}
