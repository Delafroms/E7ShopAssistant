package com.e7.shop.bot

/**
 * 识别与点击的**调参常量集中地**。
 *
 * 为什么集中：这些阈值原先散落在 ClickPlanner / Recognition / BotEngine 各处，
 * 以裸字面量形式出现（例如 `0.10f`、`0.45f`、`0.035f`）。后果是：
 *  · 想统一调"按钮判定灵敏度"要翻三个文件、改十几处；
 *  · 同一个语义值在不同地方写法不同，无法判断是"巧合相同"还是"必须一致"；
 *  · 没有名字，读代码时看不出 0.10 到底代表什么。
 *
 * 现在每个常量都有名字和出处说明。**改这里等于改行为**，改完必须跑回归台
 * （长按版本号 → benchmark_report.json）确认召回率与误检没有退化。
 *
 * 命名约定：
 *  · RATIO_*  以画面宽/高为基准的比例（跨分辨率自适应，不写死像素）
 *  · MIN_*    下限夹取（防止低分辨率下比例算出来过小）
 *  · CONF_*   置信度阈值
 */
object Tuning {

    /* ================= 商品价格与数量（游戏内固定值） ================= */

    /**
     * 誓约书签 / 神秘奖牌的单价（金币）与单次购买数量。
     *
     * 为什么集中：这三个值原先散落在 BotEngine.countPurchase、AiBotEngine.countPurchase
     * 与 Recognition 的价格校验里（三处各写一遍字面量）。它们必须**永远一致** ——
     * 统计用 184000 而校验用别的值时，"花了多少钱"与"是否买对商品"会同时判错。
     */
    const val BOOKMARK_PRICE = 184000L
    const val MEDAL_PRICE = 280000L
    const val BOOKMARK_PER_BUY = 5
    const val MEDAL_PER_BUY = 50

    /* ================= 行结构（Recognition） ================= */

    /**
     * 行配对容差 = 行距 × 该系数。
     *
     * **铁律**：必须由「购买」按钮文本的行距推导，且恒小于行距一半。
     * 绝不能用图标框高度×2 —— 那会得到约 300px，超过商店行距 260px，
     * 导致跨行点错、售罄误判、图标误确认。这是实测过的活锁 BUG 根因。
     */
    const val ROW_TOL_FROM_PITCH = 0.45f

    /** 行距推导的兜底值（画面高度占比）：OCR 找不到足够文本行时使用。 */
    const val ROW_PITCH_FALLBACK = 0.20f

    /** 行距的合理区间（画面高度占比）：低于下限说明漏读了行，高于上限说明把两行并成一行。 */
    const val ROW_PITCH_MIN = 0.12f
    const val ROW_PITCH_MAX = 0.28f

    /** 判定"这是两行之间的空隙"的最小间距（画面高度占比）。 */
    const val ROW_GAP_MIN = 0.08f

    /** 行容差的兜底值（画面高度占比，传统引擎用）。 */
    const val ROW_TOL_FALLBACK = 0.045f

    /** 行归属半带宽 = 行距 × 该系数，下限见 [ROW_HALF_BAND_MIN]。 */
    const val ROW_HALF_BAND = 0.45f
    const val ROW_HALF_BAND_MIN = 0.02f

    /* ================= 图标采样窗（Recognition） ================= */

    /**
     * 图标颜色占比的最低门槛（相对采样窗像素数）。
     *
     * 低于此值认为"这一行没有目标图标"。
     */
    const val ICON_RATIO_MIN = 0.004f

    /** 图标采样窗：宽屏（横屏）时的横向范围（画面宽度占比）。 */
    const val ICON_WIN_WIDE_X1 = 0.26f
    const val ICON_WIN_WIDE_X2 = 0.01f
    /** 图标采样窗：窄屏（竖屏）时的横向范围（画面宽度占比）。 */
    const val ICON_WIN_NARROW_X1 = 0.16f
    const val ICON_WIN_NARROW_X2 = 0.02f
    /** 图标采样窗的纵向半径（画面高度占比，横屏/竖屏各一）。 */
    const val ICON_WIN_WIDE_Y = 0.055f
    const val ICON_WIN_NARROW_Y = 0.035f

    /* ================= 场景判定与候选（Recognition） ================= */

    /** 商店列表的扫描范围（画面占比）：避开顶部状态栏与底部导航。 */
    const val SHOP_SCAN_X1 = 0.34f
    const val SHOP_SCAN_X2 = 0.99f
    const val SHOP_SCAN_Y1 = 0.03f
    const val SHOP_SCAN_Y2 = 0.97f

    /** 判定"画面内容变了"的像素差异比例（滚动检测用）。 */
    const val SCROLL_DIFF_RATIO = 0.06f

    /** 弹窗取消按钮与识别文本的最大允许偏差（画面高度占比）。 */
    const val DIALOG_CANCEL_MAX_OFFSET = 0.06f

    /** OCR 文本被视为"可信商品名"的最低置信度。 */
    const val OCR_TEXT_CONF = 0.4f

    /** 图标候选的最低模型置信度（完整识别）。 */
    const val ICON_CONF_FULL = 0.3f

    /**
     * 快速探测（hasIconFast）的图标置信度门槛。
     *
     * 取 0.25，**比完整分析的 0.3 更宽松** —— 这是刻意的：
     * 快速探测只决定"要不要继续看这一屏"，宁可多看一眼也不能漏报；
     * 一旦漏报整屏就被跳过且没有补救机会（漏买）。
     * （先前误设为 0.4 —— 比完整识别更严，会在 0.3~0.4 区间漏报。）
     */
    const val ICON_CONF_FAST = 0.25f

    /** 图标候选的置信度折算系数（图标证据 + 文本证据的加权）。 */
    const val ICON_CONF_WEIGHT = 0.85f

    /** 颜色签名候选的置信度：占比 / 该基准值，再夹取到 [0.15, 0.8]。 */
    const val COLOR_CONF_BASE = 0.02f
    const val COLOR_CONF_MIN = 0.15f
    const val COLOR_CONF_MAX = 0.8f

    /* ================= 按钮定位（ClickPlanner） ================= */

    /**
     * E1 模型按钮框的采信阈值。
     *
     * 实测（7 类模型，proposed GT）：售罄行被误判成 row_buy_button 的置信度集中在
     * 0.27~0.38，0.5 能干净滤掉；低于阈值一律回退色块法 —— 绝不采信"可能看错"的模型输出。
     */
    const val MODEL_BUTTON_CONF = 0.5f

    /** 按钮采样窗的纵向半径（相对行容差）。 */
    const val BTN_WIN_RY_FROM_TOL = 1.1f
    /** 按钮采样窗的纵向半径下限（画面高度占比）。 */
    const val BTN_WIN_RY_MIN = 0.02f

    /** 以文本锚点为中心的水平采样半径（画面宽度占比）。 */
    const val BTN_WIN_TEXT_HALF_W = 0.10f
    /** 无文本锚点时的兜底扫描带（画面宽度占比）：商品行右侧的按钮区。 */
    const val BTN_WIN_BAND_X1 = 0.55f
    const val BTN_WIN_BAND_X2 = 0.96f

    /** 判定"这是彩色可点按钮"的填充色块占比门槛。 */
    const val BTN_FILL_RATIO = 0.10f
    /** 判定"这是灰色售空按钮"的灰色占比门槛，且要求彩色占比低于 [BTN_FILL_RATIO_LOW]。 */
    const val BTN_GRAY_RATIO = 0.10f
    const val BTN_FILL_RATIO_LOW = 0.04f

    /** 语义按钮（弹窗购买/确认、商店立即更新）的采样窗（画面占比）。 */
    const val GOLD_WIN_HALF_W = 0.14f
    const val GOLD_WIN_RY = 0.035f
    const val GOLD_WIN_RY_MIN_PX = 20
    /** 语义按钮周边彩色填充的最低占比（低于此值认为"视觉上无法确认"，返回 null）。 */
    const val GOLD_FILL_RATIO = 0.06f

    /** 取消按钮的采样窗（画面占比）。 */
    const val CANCEL_WIN_HALF_W = 0.10f
    const val CANCEL_WIN_RY = 0.03f

    /** 质心计算所需的最少匹配像素数（太少说明是噪声，不是按钮）。 */
    const val CENTROID_MIN_PIXELS = 40L

    /** 采样步长（像素）：隔点采样以降低 CPU 占用，2 = 每 2 像素取一个。 */
    const val SAMPLE_STEP = 2

    /* ================= 拟人化（Humanizer） ================= */

    /**
     * 点击偏移的硬上限（像素）。
     *
     * E7 按钮高度普遍 80px 以上，4px 偏移绝不会越出按钮边界。
     *
     * **唯一定义处是 [com.e7.shop.data.AppConfig.TAP_OFFSET_MAX_PX]**，此处仅作别名：
     * UI 滑杆上限、配置读写夹取、运行时夹取三者共用同一个值，改一处即全链路生效。
     * （旧注释称"设置里允许 0~20"，与 SettingsSchema 实际夹取的 0~4 相互矛盾，已修正。）
     */
    const val TAP_OFFSET_MAX_PX = com.e7.shop.data.AppConfig.TAP_OFFSET_MAX_PX

    /** 疲劳漂移：在 [FATIGUE_RAMP_MINUTES] 分钟内从 0 线性升到 [FATIGUE_MAX]。 */
    const val FATIGUE_RAMP_MINUTES = 120.0
    const val FATIGUE_MAX = 0.4f

    /* ================= 会话与等待（BotEngine / AiBotEngine） ================= */

    /** 单帧耗时的兜底值（ms）：未测出时用于 framesFor 换算，与改动前行为一致。 */
    const val FRAME_COST_FALLBACK_MS = 900L

    /** framesFor 的夹取区间：下限保证"变化+稳定"判定来得及成立，上限防止极慢设备长时间不返回。 */
    const val FRAMES_MIN = 4
    const val FRAMES_MAX = 40

    /**
     * 行级重试预算：同一行连续失败多少次后判定为硬失败（放弃该行并报告）。
     *
     * ⚠ 已于 2026-09-17 删除（玩家要求"宁可多试，绝不漏买"）：达到次数就永久放弃
     * 会把"点击没生效"这种**可恢复**的失败变成漏买。当前实现只统计失败次数、
     * 不做任何放弃（见 BotEngine.noteRowFailure）。
     */

    /**
     * WAIT 阶段的探测间隔上限（ms）。
     *
     * ⚠ 这里**不再有"等够多久就停机"的哨兵**。玩家要求深夜挂机绝不主动放弃：
     * 停机 = 剩下几个小时全部变成漏买，而神秘奖牌每次刷新只有约 1% 出现率，
     * 错过就是错过。改为「等得越久、探测越稀疏」（600ms → 该上限）：
     * 既省电、不刷屏，又能在画面一恢复时立刻继续买。
     */
    const val WAIT_BACKOFF_MAX_MS = 3000L

    /**
     * 未决退避上限（ms）：连续"无法确定下一步"时逐步拉长等待，但**绝不停止会话**。
     * 与 [WAIT_BACKOFF_MAX_MS] 同理——宁可慢，不可停。
     */
    const val UNDECIDED_BACKOFF_MAX_MS = 8000L

    /** RECOVER 阶段的超时哨兵（轮数）。 */
    const val RECOVER_MAX_STREAK = 6

    /** 滑动揭示 slot6 的最大尝试次数。 */
    const val SLOT6_MAX_ATTEMPTS = 4

    /**
     * 判定"滑到底"所需的连续"画面没动"次数。
     *
     * **2 → 1**（2026-09-18，玩家实测反馈"刷新后下滑三次，太浪费时间"）：
     * 旧值 2 配合滑动循环会让每轮刷新固定滑 **3 次**
     * （第 1 次滑动画面动、第 2 次没动 streak=1、第 3 次没动 streak=2 才判定到底）。
     * 而商店固定 6 格、单次滑动跨度 0.72h 足以露出剩余格位，
     * 因此**一次"没动"即可判定到底**，每轮省下约 0.6~1 秒。
     *
     * 安全兜底（玩家要求"强化单次下滑的安全审查"）：
     *  · 判定到底后若仍识别出新目标，流程会重新处理该屏，不会漏买；
     *  · 确认滑动使用更短的稳定等待（见各滑动循环），不牺牲判据可靠性。
     */
    const val SCROLL_STILL_STREAK = 1

    /** 判定"弹窗已关闭"所需的连续非弹窗帧数。 */
    const val DIALOG_CLOSED_STREAK = 2

    /**
     * 网络异常弹窗的连续重试上限（2026-09-19）。
     *
     * 超过就退避等待而不是继续点 —— 实测事故：关键词误命中导致无限重试，
     * 每 0.75 秒点一次、位置还乱跳，**点到了系统控制中心和桌面**，把游戏推到后台。
     * 网络真的不通时狂点也没有意义。
     */
    const val NET_RETRY_MAX_STREAK = 4

    /**
     * 死循环兜底：连续多少轮"进了决策但既没买也没刷新"就强制刷新一次。
     *
     * 2026-09-19 实测事故：买过的奖牌图标仍在屏上（按钮已售空），
     * 而 revealSlot 无条件清空位置记忆 → 该行被反复当成目标 →
     * 整夜在 REVEAL_SLOT ↔ DECIDE_TARGETS 之间打转、**一次都不刷新**。
     * 根因已修（位置记忆改为"画面真动了才清"），这里留兜底防同类问题复发。
     */
    const val IDLE_DECIDE_MAX_STREAK = 5

    /**
     * 同一行「弹窗验证反复不过」的最大尝试次数（红队测试 2026-09-19 新增）。
     *
     * 为什么需要：验证失败（价格歧义 / 图标不符 / 弹窗商品名读不到）是**确定性**的 ——
     * 同样的画面再点一次还是同样的结果。旧版把它并进「暂时性失败」，于是引擎会一直
     * 点同一个购买键（极端压缩测试里点到 60~180 次）。真机上表现为「机器人一直点
     * 同一个位置」，既暴露异常行为特征，也让玩家无法判断它是在工作还是卡住。
     *
     * 与漏买修复的边界：**「点击没生效（弹窗根本没出现、按钮仍可购买）」不在此列** ——
     * 那种情况继续保留该行（漏买优先），这里只治「弹窗出现了但验证不过」。
     * 计数在刷新/滑动时清零，所以刷新后这一行会重新有机会。
     */
    const val ROW_VERIFY_MAX_ATTEMPTS = 3

    /** 截图连续失败多少次后放弃本屏（fail-closed，不刷新）。 */
    const val SHOT_FAIL_LIMIT = 3

    /** 滑动几何（画面占比）：商店列表的翻页手势。 */
    const val SWIPE_LOW_Y = 0.86f
    const val SWIPE_HIGH_Y = 0.14f

    /** 首次滑动的时长（ms）与后续滑动的时长（ms）。 */
    const val SWIPE_FIRST_MS = 100L
    const val SWIPE_REPEAT_MS = 260L

    /** 滑动后的随机等待区间（ms）。 */
    const val SWIPE_SETTLE_MIN_MS = 380
    const val SWIPE_SETTLE_MAX_MS = 640

    /**
     * **确认滑动**（判定"到底"的那一次）的稳定等待区间（ms）。
     *
     * 为什么比首次短：首次滑动后需要等画面真正稳定才能识别内容；
     * 而确认滑动只回答"画面变没变"这一个问题，短等待足够，
     * 每轮省下约 0.2~0.3 秒（一晚 600+ 轮就是几分钟）。
     */
    const val SWIPE_CONFIRM_SETTLE_MIN_MS = 220
    const val SWIPE_CONFIRM_SETTLE_MAX_MS = 340

    /** 行去重容差（画面短边占比）及其夹取区间（px）。 */
    const val HANDLED_ROW_TOL_RATIO = 0.07f
    const val HANDLED_ROW_TOL_MIN_PX = 24
    const val HANDLED_ROW_TOL_MAX_PX = 220

    /* ================= 时序（等待 / 停顿，ms） =================
     *
     * 为什么集中（2026-09-19）：这 20 个值原先以裸字面量形式散落在 BotEngine 与
     * AiBotEngine 里（同一个 500 在两个文件各写一遍，改一处漏一处），
     * 既看不出语义，也无法判断"两处 800 是巧合还是必须一致"。
     * 现在每个值有名字和出处；**改这里等于改行为**，改完要真机跑一轮看日志。
     *
     * 命名：*_MS 是等待时长；*_MIN_MS/_MAX_MS 是随机区间（拟人化抖动）。
     */

    /** 暂停时轮询"是否已恢复"的间隔（两个引擎同值，必须一致）。 */
    const val POLL_PAUSED_MS = 500L

    /** 网络错误重试前的等待（给网络一次恢复机会，同时避免疯狂重连）。 */
    const val NET_RETRY_WAIT_MS = 1500L

    /** 通用轮询 tick（刷新等待、弹窗轮询等长循环里的单步等待）。 */
    const val POLL_TICK_MS = 450L

    /** "弹窗没出现"之后再确认一眼的间隔（区分"没弹"与"弹得慢"）。 */
    const val DIALOG_RECHECK_MS = 600L

    /** 截图失败后的重试间隔（fail-closed 路径：重试而不推进流程）。 */
    const val RESHOT_RETRY_MS = 800L

    /** 漏检保护：等待画面稳定后重查同一屏的等待（传统引擎）。 */
    const val RESCAN_SETTLE_MS = 800L

    /** 回收上一帧 Bitmap 之后的稳定等待（给系统回收内存的时间）。 */
    const val SETTLE_AFTER_RECYCLE_MS = 700L

    /** 点击「确认/购买」前的随机等待（拟人化：不固定节奏）。 */
    const val PRE_TAP_JITTER_MIN_MS = 220
    const val PRE_TAP_JITTER_MAX_MS = 400

    /** 等待购买弹窗出现时的轮询区间。 */
    const val DIALOG_POLL_MIN_MS = 350
    const val DIALOG_POLL_MAX_MS = 600

    /** 弹窗三重验证前的补等待（等渐显动画结束，避免拿过渡帧判定）。 */
    const val DIALOG_SETTLE_MIN_MS = 220
    const val DIALOG_SETTLE_MAX_MS = 360

    /** 等待刷新完成（画面变化 + 稳定）时的轮询区间。 */
    const val REFRESH_POLL_MIN_MS = 400
    const val REFRESH_POLL_MAX_MS = 700

    /** 等待商店列表加载完成时的轮询区间。 */
    const val SHOP_LOAD_POLL_MIN_MS = 200
    const val SHOP_LOAD_POLL_MAX_MS = 350

    /** 滑动看第 6 格 / 兜底尝试时的轮询区间。 */
    const val SLOT6_POLL_MIN_MS = 350
    const val SLOT6_POLL_MAX_MS = 550

    /** 传统引擎：复核一行并回收旧帧之后的稳定等待（宁可多花一帧，也不拿坏帧放弃可买的行）。 */
    const val RECHECK_SETTLE_MIN_MS = 250
    const val RECHECK_SETTLE_MAX_MS = 450

    /**
     * 传统引擎：**收紧后**的主循环轮询区间（200~380ms）。
     *
     * 为什么比别的轮询短：识别本身已占约 0.64s，sleep 再叠 0.5s 会让每帧逼近 1.2s；
     * 收紧后每帧约 0.9s。这是实测调过的值，不要和 [POLL_TICK_MS] 混用。
     */
    const val POLL_TIGHT_MIN_MS = 200
    const val POLL_TIGHT_MAX_MS = 380
}
