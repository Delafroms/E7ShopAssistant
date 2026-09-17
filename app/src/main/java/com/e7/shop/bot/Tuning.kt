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
     * 设置里允许 0~20，但实际一律夹到 4 —— 更大的偏移会点空。
     */
    const val TAP_OFFSET_MAX_PX = 4

    /** 疲劳漂移：在 [FATIGUE_RAMP_MINUTES] 分钟内从 0 线性升到 [FATIGUE_MAX]。 */
    const val FATIGUE_RAMP_MINUTES = 120.0
    const val FATIGUE_MAX = 0.4f

    /* ================= 会话与等待（BotEngine / AiBotEngine） ================= */

    /** 单帧耗时的兜底值（ms）：未测出时用于 framesFor 换算，与改动前行为一致。 */
    const val FRAME_COST_FALLBACK_MS = 900L

    /** framesFor 的夹取区间：下限保证"变化+稳定"判定来得及成立，上限防止极慢设备长时间不返回。 */
    const val FRAMES_MIN = 4
    const val FRAMES_MAX = 40

    /** 行级重试预算：同一行连续失败多少次后判定为硬失败（放弃该行并报告）。 */
    const val MAX_ROW_ATTEMPTS = 2

    /** WAIT 阶段的超时哨兵（轮数，每轮约 600ms）。 */
    const val WAIT_MAX_STREAK = 600

    /** RECOVER 阶段的超时哨兵（轮数）。 */
    const val RECOVER_MAX_STREAK = 6

    /** 滑动揭示 slot6 的最大尝试次数。 */
    const val SLOT6_MAX_ATTEMPTS = 4

    /** 判定"滑到底"所需的连续"画面没动"次数。 */
    const val SCROLL_STILL_STREAK = 2

    /** 判定"弹窗已关闭"所需的连续非弹窗帧数。 */
    const val DIALOG_CLOSED_STREAK = 2

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

    /** 行去重容差（画面短边占比）及其夹取区间（px）。 */
    const val HANDLED_ROW_TOL_RATIO = 0.07f
    const val HANDLED_ROW_TOL_MIN_PX = 24
    const val HANDLED_ROW_TOL_MAX_PX = 220
}
