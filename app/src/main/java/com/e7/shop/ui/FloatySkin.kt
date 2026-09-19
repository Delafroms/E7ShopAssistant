package com.e7.shop.ui

import android.widget.FrameLayout

/**
 * 悬浮窗**主题参数**：只描述"长什么样"（颜色 / 圆角 / 装饰开关），
 * 不描述"怎么摆"（那是 [FloatySkin] 的职责）。
 *
 * 从 FloatyController 的私有嵌套类提升为同包 internal 类型，原因：
 * 多套户型（skin）都要读它，而户型实现放在独立文件里，private 嵌套类无法共享。
 */
internal data class FloatySpec(
    val body: Int, val headerTop: Int, val headerBottom: Int, val edge: Int,
    val text: Int, val sub: Int, val run: Int, val warn: Int,
    val statColors: List<Int>,
    val startC1: Int, val startC2: Int, val startText: Int,
    val pauseC1: Int, val pauseC2: Int, val pauseText: Int,
    val stopC1: Int, val stopC2: Int, val stopText: Int,
    val cornerDp: Float,
    val headerGradient: Boolean,
    val headerStripes: Boolean,
    val headerDots: Boolean,
    val tapeCorners: Boolean,
    val flatButtons: Boolean,
    val logPrefix: String,
    val mono: Boolean
)

/**
 * 悬浮窗**户型契约**：功能集合固定，布局自由。
 *
 * ## 为什么要有这层抽象
 *
 * 玩家对悬浮窗的诉求是「更超前、更方便」，而不同主题（Steam Material / Blue Archive
 * 明亮）本身就带着不同的设计语言 —— 按钮该在上面还是下面、是竖排还是横排、
 * 展开后是抽屉还是常驻面板，都不该被一套布局锁死。
 *
 * 但**户型可以变，功能一个都不能少**（玩家原话："供水供电不能碰"）。
 * 因此契约把"必须提供的能力"钉死在这里，任何新户型都必须全部实现：
 *
 * | 能力 | 契约入口 |
 * |---|---|
 * | 开始 / 暂停 / 停止 | [FloatyHost.onStart] / [onTogglePause] / [onStop] |
 * | 阶段 · 运行时长 · 状态点 | [FloatyState.stageText] / [timeText] / [hasError] |
 * | 引擎与模型健康 | [engineShortText] / [modelText] / [modelOk] |
 * | 截图健康 | [shotText] / [shotOk] |
 * | 错误提示 | [errorText] |
 * | 6 项统计 | [stats] |
 * | 事件日志 | [events] |
 * | 展开 / 收起 | [setExpanded] |
 * | 拖拽移动 | [FloatyHost.onDragBy] / [onDragEnd] |
 *
 * ## 等价性验证
 *
 * 新增户型后必须逐项核对上表 —— 缺任何一项都属于「换了皮却少了按钮」，
 * 比不做主题化更糟。
 */
internal interface FloatySkin {
    /** 户型标识（日志与设置项用）。 */
    val id: String

    /** 构建视图树并挂到 [root]。root 由 WindowManager 持有，skin 自行决定内部结构。 */
    fun build(root: FrameLayout, spec: FloatySpec, host: FloatyHost)

    /** 渲染一帧状态（由 ticker / sync / 事件回调驱动，主线程调用）。 */
    fun render(s: FloatyState)

    /**
     * 展开 / 收起。
     *
     * @return true = 本户型支持该概念并已应用；false = 户型常驻（无展开态），
     *         调用方不应因此报错 —— 常驻面板同样是合法户型。
     */
    fun setExpanded(expanded: Boolean): Boolean

    /** 当前是否处于展开态（常驻户型恒为 true）。 */
    val expanded: Boolean

    /** 收起态所需宽度（px）。窗口 LayoutParams 用它定宽，避免宽面板被裁切。 */
    fun collapsedWidthPx(): Int

    /** 移除所有 View 与动画。必须幂等。 */
    fun destroy()
}

/**
 * 交互回调：skin 只负责"画"与"报告用户意图"，所有动作一律交回控制器执行。
 *
 * 这样新户型不需要知道 Service、机器人状态机、配置的任何细节，
 * 也就不会在换布局时误碰功能逻辑。
 */
internal interface FloatyHost {
    fun onStart()
    fun onTogglePause()
    fun onStop()
    fun onToggleExpand()

    /** 拖动位移（相对上一次）。控制器负责夹取到屏幕内并写回窗口参数。 */
    fun onDragBy(dx: Int, dy: Int)

    /** 拖动结束（控制器可在此持久化位置）。 */
    fun onDragEnd()
}

/**
 * 渲染数据：与 View 完全无关的一帧快照。
 *
 * 为什么做成不可变快照而不是让 skin 直接读 Service：
 *  · 多套户型必须渲染**同一份数据**，否则切换主题会出现数字对不上的诡异现象；
 *  · 数据组装只在一处发生（控制器），户型无法各自"顺手改一下"，功能等价性才有保障。
 */
internal data class FloatyState(
    val running: Boolean,
    val paused: Boolean,
    val hasError: Boolean,
    val stageText: String,
    val timeText: String,
    /** 胶囊/窄条用的短引擎名（YOLO / CV）。 */
    val engineShortText: String,
    /** 面板用的长引擎名（YOLO视觉识别 / 传统识图）。 */
    val engineLongText: String,
    val modelVisible: Boolean,
    val modelText: String,
    val modelOk: Boolean,
    val shotText: String,
    val shotOk: Boolean,
    val errorText: String,
    /** 6 项统计：标签 + 值，顺序 书签 / 奖牌 / 刷新 / 天空石 / 金币 / 时长。 */
    val stats: List<Pair<String, String>>,
    /** 事件日志（已按时间正序，末尾最新）。 */
    val events: List<String>,
    val pauseLabel: String,
    val startLabel: String,
    val stopLabel: String,
    val expanded: Boolean
)
