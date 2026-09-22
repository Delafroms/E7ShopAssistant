package com.e7.shop.ui

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * 桌面图标切换（2026-09-20 新增，配合 AndroidManifest 里的三个 activity-alias）。
 *
 * **为什么要用 alias 而不是直接改 `android:icon`**：launcher 图标是清单里的静态声明，
 * 运行时无法修改。Android 的标准做法是声明多个 `activity-alias`（各自一份 icon），
 * 运行时只启用其中一个 —— 这样换图标**不需要重装**，也不会多出多份 Activity 实现。
 *
 * ⚠ 两个必须守住的点：
 *  1. **必须用 [PackageManager.DONT_KILL_APP]** —— 否则切换的瞬间进程会被杀掉，
 *     正在跑的机器人直接中断（用户最不能接受的事故）。
 *  2. **永远保证恰好一个 alias 处于启用态** —— 全禁用会让 App 从桌面消失，
 *     全启用会出多个图标。所以这里对三个 alias 一起遍历设置，不做"只设一个"的偷懒写法。
 *
 * 生效延迟：部分 launcher 会缓存图标，切换后可能需要几秒或一次桌面重绘才刷新；
 * 系统行为，不是这里能控制的。
 */
object IconSwitcher {

    const val VARIANT_DEFAULT = "default"
    const val VARIANT_E7 = "e7"
    const val VARIANT_BA = "ba"

    /** variant 值 → 清单里的 alias 类名（相对包名，必须以点开头）。 */
    private val ALIASES = linkedMapOf(
        VARIANT_DEFAULT to ".IconDefault",
        VARIANT_E7 to ".IconE7",
        VARIANT_BA to ".IconBa"
    )

    /** 供 UI 展示/校验用：全部合法取值。 */
    val VARIANTS: List<String> = ALIASES.keys.toList()

    /**
     * 应用图标选择。未知取值一律回落到默认，**绝不留下"三个都关"的状态**。
     * @return 形如 `icon=e7 enabled=3 disabled=0` 的结果串，便于日志与 UI 回显。
     */
    fun apply(ctx: Context, variant: String): String {
        val want = if (ALIASES.containsKey(variant)) variant else VARIANT_DEFAULT
        val pm = ctx.packageManager
        val pkg = ctx.packageName
        var enabled = 0
        var failed = 0
        for ((id, alias) in ALIASES) {
            val cn = ComponentName(pkg, pkg + alias)
            val state = if (id == want) {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            }
            try {
                pm.setComponentEnabledSetting(cn, state, PackageManager.DONT_KILL_APP)
                if (id == want) enabled++
            } catch (e: Exception) {
                failed++
                android.util.Log.w("E7SA.Icon", "setComponentEnabledSetting failed: $alias ${e.message}")
            }
        }
        val result = "icon=$want enabled=$enabled disabled=${ALIASES.size - enabled} failed=$failed"
        android.util.Log.i("E7SA.Icon", result)
        return result
    }
}
