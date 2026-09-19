package com.e7.shop.data

import android.content.Context
import android.content.SharedPreferences

/**
 * App configuration store (SharedPreferences-backed).
 * All runtime settings: shop coordinates, humanize params, Jianguoyun account, gates.
 *
 * D4 配置能力矩阵审计（UI → Config → Runtime → 测试）已执行：
 * 每个字段都必须「有入口、有读者、可验证」。审计中发现的三个死字段已删除：
 *  - minGold     金币下限：闸门被 `startGold > 0` 短路（所有入口都传 0），永久不生效；
 *                真正的金币下限需要启动时金币输入，列入 V1.1。现有保护 = goldSpendCap（生效中）。
 *  - gpuFps      GPU 识图帧率：GPU 引擎已从架构中移除，无人读取。
 *  - wdAutoSync  WebDAV 自动同步：无 UI、无读者；且无冲突解决（D3）前自动同步会覆盖较新记录，
 *                列入 V1.1 与 D3 一起做。
 */
class AppConfig(context: Context) {

    private val sp: SharedPreferences =
        context.getSharedPreferences("e7_config", Context.MODE_PRIVATE)

    /**
     * 凭据专用存储：**独立文件**，且被备份规则排除
     * （见 res/xml/data_extraction_rules.xml 与 res/xml/backup_rules.xml）。
     *
     * 为什么拆出来：WebDAV 应用密码原先明文存在 e7_config 里，而 AndroidManifest 的
     * allowBackup=true 且没有任何豁免规则 → 密码会随云备份 / 换机迁移（D2D）被带走，
     * 旧设备上还能被 adb backup 直接读走。拆成独立文件后，备份规则只需排除这一个文件，
     * 配置本身（主题、阈值等）仍能正常备份。
     *
     * 为什么不用 EncryptedSharedPreferences：androidx.security:security-crypto 已被
     * Google 标记 deprecated，且其密钥不可导出 —— 换机后密码必然解不开，用户反而要重输；
     * 本方案零依赖、行为可预测，已足以消除"凭据随备份外流"这条真实路径。
     */
    private val secrets: SharedPreferences =
        context.getSharedPreferences("e7_secrets", Context.MODE_PRIVATE)

    init {
        // 一次性迁移：把旧版留在 e7_config 里的明文密码搬到独立凭据文件，并清除原值。
        // 只在新文件尚无该键时搬运，避免覆盖用户升级后新设的密码。
        val legacy = sp.getString("wdPassword", null)
        if (legacy != null) {
            if (!secrets.contains("wdPassword")) {
                secrets.edit().putString("wdPassword", legacy).apply()
            }
            sp.edit().remove("wdPassword").apply()
        }
    }

    companion object {
        /**
         * 拟人化点击偏移的**硬上限**（像素）—— UI 可设范围与运行时夹取范围的唯一来源。
         *
         * 为什么上限是 4：E7 的按钮高度普遍在 80px 以上，4px 的随机偏移绝不会越出
         * 按钮边界；再大就有"点空"风险（点空 = 本次操作无效，最坏情况是碰到相邻行）。
         *
         * 为什么定义在 data 层：`ui`（SettingsSchema 决定滑杆上限）与 `bot`（Humanizer
         * 运行时夹取）都必须与它一致，而 ui / data 两个包都不依赖 bot，
         * 因此不能放在 `bot/Tuning`。
         */
        const val TAP_OFFSET_MAX_PX = 4

        /** 拟人化延迟的硬边界（ms）：防止手滑输入超大值把机器人变成"卡死"。 */
        const val DELAY_MIN_MS = 50
        const val DELAY_MAX_MS = 30_000
    }

    /* ---- bot settings ---- */
    var buyBookmark: Boolean
        get() = sp.getBoolean("buyBookmark", true)
        set(v) = sp.edit().putBoolean("buyBookmark", v).apply()

    var buyMedal: Boolean
        get() = sp.getBoolean("buyMedal", true)
        set(v) = sp.edit().putBoolean("buyMedal", v).apply()

    /** Skystone budget limit (0 = unlimited). 生效：引擎按 skystonesSpent 累计判定。 */
    var maxSkystones: Int
        get() = sp.getInt("maxSkystones", 500)
        set(v) = sp.edit().putInt("maxSkystones", v).apply()

    /**
     * Holding caps (learned from the Rem assistant app): stop the run once
     * this many bookmarks / medals have been bought. 0 = unlimited.
     */
    var bookmarkCap: Int
        get() = sp.getInt("bookmarkCap", 0)
        set(v) = sp.edit().putInt("bookmarkCap", v).apply()

    var medalCap: Int
        get() = sp.getInt("medalCap", 0)
        set(v) = sp.edit().putInt("medalCap", v).apply()

    /** Gold spent cap: stop refreshing once total gold spent exceeds this (0 = off). */
    var goldSpendCap: Long
        get() = sp.getLong("goldSpendCap", 0)
        set(v) = sp.edit().putLong("goldSpendCap", v).apply()

    /**
     * 识图引擎（V1 双引擎，地位平等）："yolo"（神经图标检测，推荐）
     * | "traditional"（经典 CV 行结构+颜色签名，独立可用）。
     * 旧值 builtin/gpu/opencv 一律按 traditional 处理（RecognitionEngines）。
     */
    var ocrEngine: String
        get() = sp.getString("ocrEngine", "yolo") ?: "yolo"
        set(v) = sp.edit().putString("ocrEngine", v).apply()

    /**
     * 点击逻辑（与识图引擎正交的第二维度，V1 双模式）：
     *  "ai"（默认，推荐）：完全自主——按钮色块质心 + 文本 bbox 双重定位，
     *     每步点击点由当前画面推导，无固定坐标，跨分辨率/UI 布局自适应；
     *  "traditional"：沿用已验证的稳定点击方案（文本 bbox 锚点）。
     */
    var clickLogic: String
        get() = sp.getString("clickLogic", "ai") ?: "ai"
        set(v) = sp.edit().putString("clickLogic", v).apply()

    /**
     * Script speed multiplier (learned from the Rem assistant's
     * "脚本运行速度倍数" 1-3x). All human-like delays are divided by this
     * value - faster cycles, same rewards per day.
     */
    var speedMult: Int
        get() = sp.getInt("speedMult", 1).coerceIn(1, 3)
        set(v) = sp.edit().putInt("speedMult", v.coerceIn(1, 3)).apply()

    /* ---- humanize params ---- */
    /**
     * 随机延迟下限/上限（ms）。
     *
     * getter 与 setter 都夹取（2026-09-18 加固）：旧版没有任何上下界校验，而设置页的
     * 数字输入框允许 9 位数字 —— 手滑填成 999999999 就是 11.5 天，机器人表现为"卡死"，
     * 玩家只会以为程序挂了。夹取后"显示值 = 生效值"，与 offsetPx 同一原则。
     */
    var delayMinMs: Int
        get() = sp.getInt("delayMin", 350).coerceIn(DELAY_MIN_MS, DELAY_MAX_MS)
        set(v) = sp.edit().putInt("delayMin", v.coerceIn(DELAY_MIN_MS, DELAY_MAX_MS)).apply()

    var delayMaxMs: Int
        get() = sp.getInt("delayMax", 1100).coerceIn(DELAY_MIN_MS, DELAY_MAX_MS)
        set(v) = sp.edit().putInt("delayMax", v.coerceIn(DELAY_MIN_MS, DELAY_MAX_MS)).apply()

    /**
     * 无障碍保活豁免：把本应用加入系统的「无障碍服务自动关闭」豁免名单。
     *
     * 背景（2026-09-18 实测事故）：ColorOS 会在无障碍服务运行几分钟后弹一个**不遮画面
     * 但拦截全部输入**的模态框，导致整夜 567 次点击只有 2 次生效。
     *
     * 为什么默认开：这个弹窗会让挂机彻底失效，且玩家看不到原因。
     * 为什么改成只写豁免名单：旧版还额外把**全设备级**的 accessibility_turn_off_switch
     * 写成 0 —— 那等于对本机所有 App 关掉系统的安全确认，属于超出授权范围的用途，
     * 而且永不恢复。豁免名单只影响本应用，影响面小得多。
     * 需要用户明确知情：本开关就是"允许本应用修改这一项系统设置"的显式授权。
     */
    var accExemptFromAutoOff: Boolean
        get() = sp.getBoolean("accExemptFromAutoOff", true)
        set(v) = sp.edit().putBoolean("accExemptFromAutoOff", v).apply()

    /**
     * 拟人化点击偏移（像素），取值范围 [0, [TAP_OFFSET_MAX_PX]]。
     *
     * getter 与 setter 都夹取：旧版默认值是 8（已超出上限），且只有 UI 的 setter 夹取，
     * 于是老用户的设置页显示 8、实际生效却是 4 —— 典型的"显示与实际不符"。
     * getter 一并夹取后，显示值与生效值恒等。
     */
    var offsetPx: Int
        get() = sp.getInt("offsetPx", TAP_OFFSET_MAX_PX).coerceIn(0, TAP_OFFSET_MAX_PX)
        set(v) = sp.edit().putInt("offsetPx", v.coerceIn(0, TAP_OFFSET_MAX_PX)).apply()

    var randomRest: Boolean
        get() = sp.getBoolean("randomRest", true)
        set(v) = sp.edit().putBoolean("randomRest", v).apply()

    /**
     * 睡眠模式：挂机过夜时**全部走完整识别**，不使用轻量探测加速。
     *
     * 权衡：轻量探测（只跑 YOLO，约 45ms）用来决定"要不要继续看这一屏"，
     * 它一旦漏报，整屏就被跳过、没有补救机会 → 漏买。
     * 睡眠模式下不在乎速度（反正睡一晚上时间很长），所以每帧都做完整识别
     * （YOLO + OCR，约 450ms），把"漏看"的可能性压到最低。
     *
     * 代价：识别耗时约 10 倍、CPU 占用与发热更高。
     */
    var sleepMode: Boolean
        get() = sp.getBoolean("sleepMode", false)
        set(v) = sp.edit().putBoolean("sleepMode", v).apply()

    /**
     * 任务完成后自动熄屏：金币/天空石/持有量达到上限而停止时，自动执行熄屏。
     *
     * 场景：睡前挂机，资源刷完后自动熄屏省电，不必半夜起来手动关屏。
     * 实现用无障碍的 GLOBAL_ACTION_LOCK_SCREEN（Android 9+，**无需额外权限**）。
     *
     * 注意：只在"预算/上限达成"这类**正常完成任务**时触发；
     * 因错误停止（截图失败、等待超时）不熄屏 —— 那些情况玩家需要看屏幕排查。
     */
    var autoLockOnDone: Boolean
        get() = sp.getBoolean("autoLockOnDone", false)
        set(v) = sp.edit().putBoolean("autoLockOnDone", v).apply()

    /** 休息间隔：每 N 次操作休息一次（仅在 randomRest 打开时生效）。 */
    var restEvery: Int
        get() = sp.getInt("restEvery", 15).coerceIn(5, 100)
        set(v) = sp.edit().putInt("restEvery", v.coerceIn(5, 100)).apply()

    /* 新架构：所有点击位置由神经模型 bbox 中心决定，坐标配置已全部删除。
       仅保留滑动手势几何（通用翻页手势，非点击目标）。
       说明：这三项是「配置存在但刻意不设 UI」——滑动几何是跨分辨率自适应参数，
       暴露给用户只会制造误配置；引擎内部读取，随画面尺寸换算。 */
    var swipeCenterX: Float
        get() = sp.getFloat("swipeCenterX", 0.73f)
        set(v) = sp.edit().putFloat("swipeCenterX", v).apply()

    var swipeTopY: Float
        get() = sp.getFloat("swipeTopY", 0.19f)
        set(v) = sp.edit().putFloat("swipeTopY", v).apply()

    var swipeBottomY: Float
        get() = sp.getFloat("swipeBottomY", 0.46f)
        set(v) = sp.edit().putFloat("swipeBottomY", v).apply()

    /* ---- gates ---- */
    var riskAccepted: Boolean
        get() = sp.getBoolean("riskAccepted", false)
        set(v) = sp.edit().putBoolean("riskAccepted", v).apply()

    /** App language: "system" | "zh" | "en" */
    var appLanguage: String
        get() = sp.getString("appLanguage", "system") ?: "system"
        set(v) = sp.edit().putString("appLanguage", v).apply()

    /** Material-Theme appearance: "dark" (Material 3 Steam) | "oled" (pure black). */
    var appearance: String
        get() = sp.getString("appearance", "dark") ?: "dark"
        set(v) = sp.edit().putString("appearance", v).apply()

    /** Show the entry splash animation (heyyo x E7SA). Player can disable it. */
    var showSplash: Boolean
        get() = sp.getBoolean("showSplash", true)
        set(v) = sp.edit().putBoolean("showSplash", v).apply()

    /**
     * 运行日志详细度（写文件，不会被 logcat 刷掉）。
     *
     * 为什么要分级：日志量差别极大 —— 精简约 5MB/晚，调试可到 200MB/晚。
     * 玩家按需选择：平时用精简，排查问题时临时调高。
     *
     *  · "off"      关闭（不写文件）
     *  · "normal"   精简：关键事件（购买结果、超时、异常）—— 默认
     *  · "detail"   详细：+ 每次购买尝试的完整链路、滑动次数、候选列表
     *  · "debug"    调试：+ 每轮识别的候选明细（日志量最大）
     */
    var logLevel: String
        get() = sp.getString("logLevel", "normal") ?: "normal"
        set(v) = sp.edit().putString("logLevel", v).apply()

    /**
     * 页面切换动画：缓动族（见 ui/DfibEasing.kt 的 ANIM_EASINGS）。
     * "beat"(默认·冰火节拍) | "quad" | "cubic" | "quart" | "expo" | "circ"
     * 后五族取自《冰与火之舞》所用的 DOTween Ease 枚举。
     */
    var animEasing: String
        get() = sp.getString("animEasing", "beat") ?: "beat"
        set(v) = sp.edit().putString("animEasing", v).apply()

    /** 缓动方向："in" | "out" | "inout"（默认 inout，最适合整页过渡）。 */
    var animEasingVariant: String
        get() = sp.getString("animEasingVariant", "inout") ?: "inout"
        set(v) = sp.edit().putString("animEasingVariant", v).apply()

    /** 缓动强度 0..100：与线性插值，100 = 完整缓动，0 = 完全线性。 */
    var animEasingStrength: Int
        get() = sp.getInt("animEasingStrength", 100).coerceIn(0, 100)
        set(v) = sp.edit().putInt("animEasingStrength", v.coerceIn(0, 100)).apply()

    /** Top logo mode: "official" (default, EPIC SEVEN image) | "text" | "custom" */
    var logoMode: String
        get() = sp.getString("logoMode", "official") ?: "official"
        set(v) = sp.edit().putString("logoMode", v).apply()

    /** Custom logo image path (mode = custom) */
    var customLogoPath: String
        get() = sp.getString("customLogoPath", "") ?: ""
        set(v) = sp.edit().putString("customLogoPath", v).apply()

    /* ---- appearance ---- */
    /** Custom background image path (background only; top/bottom unchanged). Empty = default gradient. */
    var bgImage: String
        get() = sp.getString("bgImage", "") ?: ""
        set(v) = sp.edit().putString("bgImage", v).apply()

    /* ---- floating window ---- */
    var floatyEnabled: Boolean
        get() = sp.getBoolean("floatyEnabled", true)
        set(v) = sp.edit().putBoolean("floatyEnabled", v).apply()

    /**
     * 悬浮窗**户型**：
     *  · `""`（默认）—— **跟随主题**：Blue Archive 明亮主题用侧栏轨道，
     *    其余主题（Steam / OLED）用经典胶囊；
     *  · `classic` —— 强制经典：胶囊贴顶，展开后按钮在**底部**；
     *  · `rail`    —— 强制侧栏轨道：竖条贴右边，展开后按钮在**顶部**。
     *
     * 户型只改变布局与操作顺序，**功能集合完全一致**
     * （开始/暂停/停止、阶段、时长、引擎、模型与截图健康、错误、6 项统计、
     * 事件日志、展开收起、拖拽移动 —— 见 ui/FloatySkin.kt 的契约表）。
     */
    var floatyLayout: String
        get() = sp.getString("floatyLayout", "") ?: ""
        set(v) = sp.edit().putString("floatyLayout", v).apply()

    /* ---- Jianguoyun WebDAV ---- */
    var wdServer: String
        get() = sp.getString("wdServer", "https://dav.jianguoyun.com/dav/") ?: ""
        set(v) = sp.edit().putString("wdServer", v).apply()

    var wdAccount: String
        get() = sp.getString("wdAccount", "") ?: ""
        set(v) = sp.edit().putString("wdAccount", v).apply()

    /** WebDAV 应用密码：存独立凭据文件（被备份规则排除，不随云备份/换机迁移外流）。 */
    var wdPassword: String
        get() = secrets.getString("wdPassword", "") ?: ""
        set(v) = secrets.edit().putString("wdPassword", v).apply()

    var wdRemoteDir: String
        get() = sp.getString("wdRemoteDir", "E7ShopAssistant") ?: ""
        set(v) = sp.edit().putString("wdRemoteDir", v).apply()
}
