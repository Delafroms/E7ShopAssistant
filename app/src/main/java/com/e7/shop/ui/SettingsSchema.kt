package com.e7.shop.ui

import com.e7.shop.R
import com.e7.shop.data.AppConfig

/**
 * 设置系统核心（与主题完全无关）：
 *
 * 主题负责"怎么呈现"，Schema 负责"有什么"——所有主题必须渲染同一份
 * Schema 的全部项目。换主题 = 换 UI，绝不删功能；以后新增第三个、
 * 第四个主题，设置逻辑天然完整继承，不需要重写。
 */
sealed class SettingItem {
    abstract val labelRes: Int

    data class Switch(
        override val labelRes: Int,
        val get: () -> Boolean,
        val set: (Boolean) -> Unit
    ) : SettingItem()

    data class Number(
        override val labelRes: Int,
        val get: () -> Long,
        val set: (Long) -> Unit
    ) : SettingItem()

    data class Text(
        override val labelRes: Int,
        val get: () -> String,
        val set: (String) -> Unit,
        val isPassword: Boolean = false
    ) : SettingItem()
}

data class SettingsSection(val titleRes: Int, val items: List<SettingItem>)

/** 唯一权威定义：全部设置分组与条目（每套主题必须完整渲染）。 */
object SettingsSchema {

    fun sections(cfg: AppConfig): List<SettingsSection> = listOf(
        SettingsSection(
            R.string.bot_settings,
            listOf(
                SettingItem.Switch(R.string.buy_bookmark, { cfg.buyBookmark }, { cfg.buyBookmark = it }),
                SettingItem.Switch(R.string.buy_medal, { cfg.buyMedal }, { cfg.buyMedal = it }),
                SettingItem.Number(R.string.sky_budget, { cfg.maxSkystones.toLong() }, { cfg.maxSkystones = it.toInt() }),
                // 注意：旧版这里有一项「金币保护下限（gold_floor）」，但它从未生效——
                // 所有启动入口都传 startBot(0, 0)，startGold 恒为 0，闸门永久短路。
                // 与其保留一个"看得见改不动"的假开关，不如移除；D4 审计后连
                // AppConfig.minGold 字段与引擎里的死闸门一并删除（不再留潜伏分支）。
                // 金币保护请用下面真正生效的「金币消耗上限」；真正的金币下限保护需要
                // 能读到当前金币（启动时输入 / OCR 读金币数），列入 V1.1 后续增强。
                SettingItem.Number(R.string.cap_bookmark, { cfg.bookmarkCap.toLong() }, { cfg.bookmarkCap = it.toInt() }),
                SettingItem.Number(R.string.cap_medal, { cfg.medalCap.toLong() }, { cfg.medalCap = it.toInt() }),
                SettingItem.Number(R.string.gold_spend_cap, { cfg.goldSpendCap }, { cfg.goldSpendCap = it }),
                // 睡眠模式：挂机过夜时全部走完整识别（不用轻量探测加速），用速度换可靠性。
                // 轻量探测一旦漏报，整屏就被跳过且没有补救机会 → 漏买；睡眠模式下不在乎速度。
                SettingItem.Switch(R.string.sleep_mode, { cfg.sleepMode }, { cfg.sleepMode = it }),
                // 预算用完（金币/天空石/持有量达上限）→ 自动熄屏省电，适合睡前挂机
                SettingItem.Switch(R.string.auto_lock_on_done, { cfg.autoLockOnDone }, { cfg.autoLockOnDone = it }),
                SettingItem.Number(R.string.speed_mult, { cfg.speedMult.toLong() }, { cfg.speedMult = it.toInt().coerceIn(1, 3) })
            )
        ),
        SettingsSection(
            R.string.humanize_settings,
            listOf(
                SettingItem.Number(R.string.delay_min, { cfg.delayMinMs.toLong() }, { cfg.delayMinMs = it.toInt() }),
                SettingItem.Number(R.string.delay_max, { cfg.delayMaxMs.toLong() }, { cfg.delayMaxMs = it.toInt() }),
                // 上限引用 AppConfig.TAP_OFFSET_MAX_PX（单一来源），不再写死字面量 4：
                // 该值同时被 Humanizer 运行时夹取使用，两处必须恒等。
                SettingItem.Number(R.string.tap_offset, { cfg.offsetPx.toLong() }, { cfg.offsetPx = it.toInt().coerceIn(0, AppConfig.TAP_OFFSET_MAX_PX) }),
                SettingItem.Switch(R.string.random_rests, { cfg.randomRest }, { cfg.randomRest = it }),
                // D4 审计补入口：restEvery 一直在 Humanizer 里生效（每 N 次操作休息一次），
                // 却没有任何 UI 能改它 —— 属于"能生效但用户够不着"的半截功能，这里补上。
                SettingItem.Number(R.string.rest_every, { cfg.restEvery.toLong() }, { cfg.restEvery = it.toInt().coerceIn(5, 100) })
            )
        ),
        SettingsSection(
            R.string.wd_sync_title,
            listOf(
                SettingItem.Text(R.string.server_url, { cfg.wdServer }, { cfg.wdServer = it }),
                SettingItem.Text(R.string.account, { cfg.wdAccount }, { cfg.wdAccount = it }),
                SettingItem.Text(R.string.app_password, { cfg.wdPassword }, { cfg.wdPassword = it }, isPassword = true),
                SettingItem.Text(R.string.remote_dir, { cfg.wdRemoteDir }, { cfg.wdRemoteDir = it })
            )
        )
    )
}
