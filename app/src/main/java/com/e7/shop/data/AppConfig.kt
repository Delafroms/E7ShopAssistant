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
    var delayMinMs: Int
        get() = sp.getInt("delayMin", 350)
        set(v) = sp.edit().putInt("delayMin", v).apply()

    var delayMaxMs: Int
        get() = sp.getInt("delayMax", 1100)
        set(v) = sp.edit().putInt("delayMax", v).apply()

    var offsetPx: Int
        get() = sp.getInt("offsetPx", 8)
        set(v) = sp.edit().putInt("offsetPx", v).apply()

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

    /* ---- Jianguoyun WebDAV ---- */
    var wdServer: String
        get() = sp.getString("wdServer", "https://dav.jianguoyun.com/dav/") ?: ""
        set(v) = sp.edit().putString("wdServer", v).apply()

    var wdAccount: String
        get() = sp.getString("wdAccount", "") ?: ""
        set(v) = sp.edit().putString("wdAccount", v).apply()

    var wdPassword: String
        get() = sp.getString("wdPassword", "") ?: ""
        set(v) = sp.edit().putString("wdPassword", v).apply()

    var wdRemoteDir: String
        get() = sp.getString("wdRemoteDir", "E7ShopAssistant") ?: ""
        set(v) = sp.edit().putString("wdRemoteDir", v).apply()
}
